package com.xern.xrnrule.app.data

import android.content.Context
import android.os.PowerManager
import android.util.Base64
import com.xern.xrnrule.domain.BlockEvent
import com.xern.xrnrule.domain.HabitChanges
import com.xern.xrnrule.domain.HabitRecords
import com.xern.xrnrule.domain.HabitWrite
import com.xern.xrnrule.domain.HabitWriteResult
import com.xern.xrnrule.domain.World
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps this phone's habits and their entries in step with the habit server (contract §2.5):
 * xrnloops-sync, next to xrnrule-sync on the home server, which xrnloops and the web app use too.
 *
 * A round: read every record after [World.habitSyncSeq] (a page at a time) →
 * [BlockEvent.HabitServerPulled], which merges them in (I38) → send what changed here, each
 * record naming the version it was made from → [BlockEvent.HabitServerPushed]. A record the server
 * refused comes back newer and is rebased, then sent again.
 *
 * Rounds run when a habit or an entry changes here (after a second, so a run of taps goes as one),
 * on service start and unlock, when the shield appears over a habit item, when the app opens, and
 * every minute while the screen is on. While a shield shows a habit item, [watch] long-polls the
 * server, so a tick in the web app or on the laptop lifts it within a second or two.
 */
class HabitSync(
    private val context: Context,
    private val store: WorldStore,
    private val settings: SyncSettings,
    private val scope: CoroutineScope,
) {
    private val json = HabitRecords.json
    private val mutex = Mutex()
    private var pending: Job? = null

    private val _status = MutableStateFlow<SharedSyncStatus?>(null)
    val status: StateFlow<SharedSyncStatus?> = _status.asStateFlow()

    /** When rounds started failing, for the "not syncing" alert; null while they work. */
    private val _failingSince = MutableStateFlow<Long?>(null)
    val failingSince: StateFlow<Long?> = _failingSince.asStateFlow()

    /** The habit server: xrnrule-sync's URL with `/xrnloops` for its last part (§2.5). */
    val url: String get() = habitServerUrl(settings.url)

    fun start() {
        scope.launch {
            store.world
                .map { w -> w.habits.count { it.dirty } + w.habitTombstones.size + w.habitEntries.values.count { it.dirty } }
                .distinctUntilChanged()
                .collect { dirty -> if (dirty > 0) request(CHANGE_DEBOUNCE_MS) }
        }
        scope.launch {
            val power = context.getSystemService(PowerManager::class.java)
            while (true) {
                delay(PERIOD_MS)
                if (power.isInteractive) request(0)
            }
        }
    }

    /** A round after [delayMs], replacing one still waiting; one under way finishes. */
    @Synchronized
    fun request(delayMs: Long = 0): Job? {
        if (!settings.isConfigured) return null
        pending?.cancel()
        return scope.launch {
            delay(delayMs)
            withContext(NonCancellable) { syncNow() }
        }.also { pending = it }
    }

    /** Runs a round now; true if it worked. */
    suspend fun syncNow(): Boolean {
        if (!settings.isConfigured) return false
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                try {
                    val message = round(api())
                    _status.value = SharedSyncStatus(now, true, message)
                    _failingSince.value = null
                    true
                } catch (e: Exception) {
                    _status.value = SharedSyncStatus(now, false, describe(e))
                    if (_failingSince.value == null) _failingSince.value = now
                    false
                }
            }
        }
    }

    /**
     * While [wanted] (a block screen shows a habit item): waits on the server for anything newer
     * than what this phone has read, then runs a round. Outside the round's lock, so a tick on the
     * block screen goes out at once. Runs until cancelled.
     */
    suspend fun watch(wanted: () -> Boolean) {
        while (currentCoroutineContext().isActive) {
            if (!wanted() || !settings.isConfigured) {
                delay(WATCH_IDLE_MS)
                continue
            }
            val seq = store.world.value.habitSyncSeq
            val changed = try {
                withContext(Dispatchers.IO) {
                    val page = api().changes(seq, waitS = WATCH_WAIT_S)
                    page.seq > seq || HabitRecords.wentBack(store.world.value, page)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                delay(WATCH_RETRY_MS) // the next round says what's wrong
                false
            }
            if (changed) {
                request(0)?.join()
                if (_status.value?.ok == false) delay(WATCH_RETRY_MS)
            }
        }
    }

    private suspend fun round(api: Api): String {
        var world = pull(api)
        var sent = 0
        repeat(MAX_TRIES) {
            val (habits, entries, push) = HabitRecords.pending(world)
            if (push.isEmpty) return if (sent == 0) "Up to date" else "Sent $sent"
            val result = api.write(HabitWrite(habits, entries))
            sent += result.accepted.habits.size + result.accepted.entries.size
            world = store.dispatchAndGet(
                BlockEvent.HabitServerPushed(push, result.acceptance(), result.rejected.habits, result.rejected.entries),
            )
        }
        throw IOException("the server kept changing; will try again")
    }

    /** Every record after what this phone has read, a page at a time. */
    private suspend fun pull(api: Api): World {
        var world = store.world.value
        repeat(MAX_PAGES) {
            val page = api.changes(world.habitSyncSeq)
            if (HabitRecords.wentBack(world, page)) {
                // §2.5: the server went back (restored from a backup). Read all of it again and
                // send back whatever it lost; otherwise nothing it gets until it passes our seq shows here.
                val all = HabitRecords.readAll(MAX_PAGES) { api.changes(it) }
                return store.dispatchAndGet(
                    BlockEvent.HabitServerPulled(all.habits, all.entries, all.seq, all.dayStartHour, full = true, epoch = all.epoch),
                )
            }
            if (HabitRecords.nothingNew(world, page)) return world
            world = store.dispatchAndGet(BlockEvent.HabitServerPulled(page.habits, page.entries, page.seq, page.dayStartHour, epoch = page.epoch))
            if (!page.more) return world
        }
        return world
    }

    private fun api() = Api(url, settings.user, settings.password)

    private fun describe(e: Exception): String = when (e) {
        is AuthException -> "Wrong user name or password"
        is IOException -> "Can't reach the habit server (${e.message})"
        else -> "Failed: $e"
    }

    private class AuthException : IOException("login refused")

    /** xrnloops-sync's `/api/changes`. Blocking; called on [Dispatchers.IO]. */
    private inner class Api(private val baseUrl: String, user: String, password: String) {
        private val auth = "Basic " + Base64.encodeToString("$user:$password".toByteArray(), Base64.NO_WRAP)

        fun changes(since: Long, waitS: Int = 0): HabitChanges {
            // The epoch this phone knows: one that has gone back gets everyone a new one (§2.5).
            val epoch = store.world.value.habitServerEpoch?.let { "&epoch=$it" } ?: ""
            val conn = open("GET", "?since=$since$epoch" + if (waitS > 0) "&wait=$waitS" else "")
            if (waitS > 0) conn.readTimeout = (waitS + 15) * 1000
            try {
                check(conn, conn.responseCode)
                return json.decodeFromString(HabitChanges.serializer(), conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
        }

        fun write(body: HabitWrite): HabitWriteResult {
            val conn = open("POST")
            try {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(json.encodeToString(HabitWrite.serializer(), body).encodeToByteArray()) }
                check(conn, conn.responseCode)
                return json.decodeFromString(HabitWriteResult.serializer(), conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
        }

        private fun open(method: String, query: String = ""): HttpURLConnection =
            (URL("$baseUrl/api/changes$query").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Authorization", auth)
                setRequestProperty("X-Device", "phone")
            }

        private fun check(conn: HttpURLConnection, code: Int) {
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) throw AuthException()
            if (code !in 200..299) {
                val text = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("server answered $code ${text.take(120)}")
            }
        }
    }

    companion object {
        private const val CHANGE_DEBOUNCE_MS = 1_000L
        private const val PERIOD_MS = 60_000L
        private const val MAX_TRIES = 4
        private const val MAX_PAGES = 200
        private const val TIMEOUT_MS = 15_000
        private const val WATCH_WAIT_S = 25
        private const val WATCH_IDLE_MS = 1_000L
        private const val WATCH_RETRY_MS = 5_000L

        /** `https://host/xrnrule` → `https://host/xrnloops`; any other URL gets `/xrnloops` on its host. */
        fun habitServerUrl(syncUrl: String): String {
            val url = syncUrl.trim().trimEnd('/')
            if (url.endsWith("/xrnrule")) return url.removeSuffix("/xrnrule") + "/xrnloops"
            return runCatching { URL(url).let { u -> "${u.protocol}://${u.authority}/xrnloops" } }.getOrDefault("$url/xrnloops")
        }
    }
}
