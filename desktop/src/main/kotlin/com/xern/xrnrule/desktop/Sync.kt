package com.xern.xrnrule.desktop

import com.xern.xrnrule.domain.BlockEvent
import com.xern.xrnrule.domain.HabitChanges
import com.xern.xrnrule.domain.HabitRecords
import com.xern.xrnrule.domain.HabitWrite
import com.xern.xrnrule.domain.HabitWriteResult
import com.xern.xrnrule.domain.SharedState
import com.xern.xrnrule.domain.World
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/** How the last attempt went, for the local page. */
@Serializable
data class SyncStatus(val atWallMs: Long, val ok: Boolean, val message: String)

class AuthException : IOException("the server refused the user name or password")

/** Basic-auth JSON over HTTPS to the two sync services. Blocking: call on [Dispatchers.IO]. */
class Http(user: String, password: String) {
    // HTTP/1.1, not the client's default HTTP/2, which shares one connection and keeps reusing it
    // after a request times out: a VPN dropping or a new Wi-Fi left sync dead until the daemon
    // restarted. Here a request that times out takes its connection with it.
    private val client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(15))
        .build()
    private val auth ="Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    fun send(url: String, method: String = "GET", body: String? = null, headers: Map<String, String> = emptyMap(), timeoutS: Long = 20): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(timeoutS))
            .header("Authorization", auth)
            .header("X-Device", "laptop")
        headers.forEach { (k, v) -> builder.header(k, v) }
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        if (body != null) builder.header("Content-Type", "application/json; charset=utf-8")
        val response = client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 401) throw AuthException()
        return response
    }
}

/**
 * The laptop's side of the shared state (contract §2.4), the same rounds as the phone's
 * `SharedSync`: read, [BlockEvent.SharedStateMerged], write back with If-Match. The server is
 * long-polled, so an unlock on the phone shows up here within a second or two.
 */
class SharedSyncClient(
    private val config: Config,
    private val store: Store,
    private val telemetry: FileTelemetry,
    private val scope: CoroutineScope,
) {
    private val http = Http(config.user, config.password)
    private val baseFile = File(config.dataDir, "shared-base.json")
    private val eventsSentFile = File(config.dataDir, "events-sent")
    private var telemetrySizeSent = -1L
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val _status = MutableStateFlow<SyncStatus?>(null)
    val status: StateFlow<SyncStatus?> = _status.asStateFlow()

    /** When the server last answered, a 304 to a long poll included; "synced" alone would stand still. */
    @Volatile
    var lastContactWall: Long? = null
        private set

    /** The phone's last check-in as the server saw it, and when the server was asked. */
    @Volatile
    private var phoneSeen: Pair<Long?, Long>? = null

    /** How long the phone has been quiet, or null while it's fine or nobody can tell. */
    fun phoneQuietMs(nowWall: Long): Long? = phoneSeen?.let { (seen, asked) -> PhoneWatch.quietMs(seen, asked, nowWall) }

    @Serializable
    private data class Base(val etag: String, val state: SharedState)

    fun start() {
        if (!config.syncConfigured) return
        scope.launch(Dispatchers.IO) {
            while (true) {
                runCatching {
                    val r = http.send("${config.ruleUrl}/devices")
                    if (r.statusCode() == 200) phoneSeen = PhoneWatch.seen(r.body()) to System.currentTimeMillis()
                }
                delay(PhoneWatch.CHECK_MS)
            }
        }
        scope.launch(Dispatchers.IO) {
            for (r in requests) {
                try {
                    _status.value = SyncStatus(System.currentTimeMillis(), true, round())
                    runCatching { sendEvents() } // best effort: what doesn't go now goes next round
                } catch (e: Exception) {
                    _status.value = SyncStatus(System.currentTimeMillis(), false, describe(e))
                    delay(5_000)
                    requests.trySend(Unit)
                }
            }
        }
        // Local changes: everything but allowance use goes out at once; that goes with the minute.
        scope.launch {
            store.world
                .map { SharedState.of(it).withoutUse() }
                .distinctUntilChanged()
                .collect { shared -> if (loadBase()?.state?.withoutUse() != shared) request() }
        }
        scope.launch {
            while (true) {
                delay(60_000)
                if (loadBase()?.state != SharedState.of(store.world.value)) request()
            }
        }
        // Remote changes: a long-poll that answers as soon as another device writes.
        scope.launch(Dispatchers.IO) {
            request()
            while (true) {
                try {
                    val etag = loadBase()?.etag
                    if (etag == null) {
                        delay(2_000)
                        continue
                    }
                    val r = http.send("${config.ruleUrl}/state?wait=50", headers = mapOf("If-None-Match" to etag), timeoutS = 65)
                    lastContactWall = System.currentTimeMillis()
                    if (r.statusCode() == 200) {
                        request()
                        delay(1_000) // let the round store the new base before asking again
                    }
                } catch (_: Exception) {
                    // A dropped long-poll isn't a failure by itself, and a later 304 wouldn't
                    // clear one: a round says whether the server is really out of reach.
                    delay(10_000)
                    request()
                }
            }
        }
    }

    fun request() {
        requests.trySend(Unit)
    }

    private suspend fun round(): String {
        repeat(4) {
            val base = loadBase()
            val local = SharedState.of(store.world.value)
            val cheap = base != null && base.state == local
            val headers = if (cheap) mapOf("If-None-Match" to base!!.etag) else emptyMap()
            val got = withContext(Dispatchers.IO) { http.send("${config.ruleUrl}/state", headers = headers) }
            lastContactWall = System.currentTimeMillis()
            if (got.statusCode() == 304) return "Up to date"
            check(got.statusCode() == 200) { "server answered ${got.statusCode()}" }
            val etag = got.headers().firstValue("ETag").orElse("")
            val remote = json.decodeFromString(SharedState.serializer(), got.body())
            val against = SharedState.baseFor(base?.state, base?.etag, etag) // I45
            val world = store.dispatchAndGet(BlockEvent.SharedStateMerged(against, remote))
            saveBase(Base(etag, remote)) // it's all in here now; a retry mustn't undo the other side
            val merged = SharedState.of(world)
            if (merged == remote) return "Up to date"
            val put = withContext(Dispatchers.IO) {
                http.send("${config.ruleUrl}/state", "PUT", json.encodeToString(SharedState.serializer(), merged), mapOf("If-Match" to etag))
            }
            if (put.statusCode() == 412) return@repeat
            check(put.statusCode() == 200) { "server answered ${put.statusCode()}" }
            saveBase(Base(put.headers().firstValue("ETag").orElse(""), merged))
            return "Sent"
        }
        throw IOException("the server kept changing; trying again")
    }

    /** The laptop's event log newer than what the server has, for the weekly review. */
    private fun sendEvents() {
        val size = telemetry.size()
        if (size == telemetrySizeSent) return
        val since = eventsSentFile.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: 0
        val fresh = telemetry.eventsAfter(since)
        if (fresh.isNotEmpty()) {
            val body = JsonObject(mapOf("events" to JsonArray(fresh))).toString()
            val r = http.send("${config.ruleUrl}/events", "POST", body)
            check(r.statusCode() == 200) { "server answered ${r.statusCode()}" }
            eventsSentFile.writeText(fresh.maxOf { it["at"]!!.jsonPrimitive.long }.toString())
        }
        telemetrySizeSent = size
    }

    private fun loadBase(): Base? = try {
        json.decodeFromString(Base.serializer(), baseFile.readText())
    } catch (_: Exception) {
        null
    }

    private fun saveBase(base: Base) {
        baseFile.parentFile.mkdirs()
        val tmp = File(baseFile.path + ".tmp")
        tmp.writeText(json.encodeToString(Base.serializer(), base))
        tmp.renameTo(baseFile)
    }
}

/**
 * The laptop's side of the habit server (contract §2.5), the same rounds as the phone's
 * `HabitSync`: read every record after [World.habitSyncSeq] → [BlockEvent.HabitServerPulled],
 * send what changed here naming the version it was made from → [BlockEvent.HabitServerPushed].
 * The server is long-polled, so a tick on the phone or in the web app shows up here within a
 * second or two.
 */
class HabitClient(
    private val config: Config,
    private val store: Store,
    private val scope: CoroutineScope,
) {
    private val http = Http(config.user, config.password)
    private val json = HabitRecords.json
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val _status = MutableStateFlow<SyncStatus?>(null)
    val status: StateFlow<SyncStatus?> = _status.asStateFlow()

    fun start() {
        if (!config.syncConfigured) return
        scope.launch(Dispatchers.IO) {
            for (r in requests) {
                try {
                    _status.value = SyncStatus(System.currentTimeMillis(), true, round())
                } catch (e: Exception) {
                    _status.value = SyncStatus(System.currentTimeMillis(), false, describe(e))
                    delay(5_000)
                    requests.trySend(Unit)
                }
            }
        }
        // Local changes: a log, a tick, an edit (after a second, so a run of clicks goes as one).
        scope.launch {
            store.world
                .map { w -> w.habits.count { it.dirty } + w.habitTombstones.size + w.habitEntries.values.count { it.dirty } }
                .distinctUntilChanged()
                .collect { dirty ->
                    if (dirty > 0) {
                        delay(1_000)
                        request()
                    }
                }
        }
        // Remote changes: a long-poll that answers as soon as anything is written.
        scope.launch(Dispatchers.IO) {
            request()
            while (true) {
                try {
                    val seq = store.world.value.habitSyncSeq
                    val page = changes(seq, waitS = 50)
                    if (page.seq > seq || HabitRecords.wentBack(store.world.value, page) || (page.dayStartHour != null && page.dayStartHour != store.world.value.habitDayStartHour)) {
                        request()
                        delay(1_000) // let the round move the seq on before asking again
                    }
                } catch (_: Exception) {
                    delay(10_000) // a round says whether the server is really out of reach
                    request()
                }
            }
        }
    }

    fun request() {
        requests.trySend(Unit)
    }

    private suspend fun round(): String {
        var world = pull()
        var sent = 0
        repeat(4) {
            val (habits, entries, push) = HabitRecords.pending(world)
            if (push.isEmpty) return if (sent == 0) "Up to date" else "Sent $sent"
            val r = http.send("${config.loopUrl}/api/changes", "POST", json.encodeToString(HabitWrite.serializer(), HabitWrite(habits, entries)))
            check(r.statusCode() == 200) { "server answered ${r.statusCode()}" }
            val result = json.decodeFromString(HabitWriteResult.serializer(), r.body())
            sent += result.accepted.habits.size + result.accepted.entries.size
            world = store.dispatchAndGet(
                BlockEvent.HabitServerPushed(push, result.acceptance(), result.rejected.habits, result.rejected.entries),
            )
        }
        throw IOException("the server kept changing; will try again")
    }

    /** Every record after what the laptop has read, a page at a time. */
    private suspend fun pull(): World {
        var world = store.world.value
        repeat(200) {
            val page = changes(world.habitSyncSeq)
            if (HabitRecords.wentBack(world, page)) {
                // §2.5: the server went back (restored from a backup). Read all of it again and
                // send back whatever it lost; otherwise nothing it gets until it passes our seq shows here.
                val all = HabitRecords.readAll(200) { changes(it) }
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

    private fun changes(since: Long, waitS: Int = 0): HabitChanges {
        val r = http.send(
            // The epoch the laptop knows: one that has gone back gets everyone a new one (§2.5).
            "${config.loopUrl}/api/changes?since=$since" + (store.world.value.habitServerEpoch?.let { "&epoch=$it" } ?: "") +
                if (waitS > 0) "&wait=$waitS" else "",
            timeoutS = if (waitS > 0) waitS + 15L else 20,
        )
        check(r.statusCode() == 200) { "server answered ${r.statusCode()}" }
        return json.decodeFromString(HabitChanges.serializer(), r.body())
    }
}

fun describe(e: Exception): String = when (e) {
    is AuthException -> "Wrong user name or password"
    is java.net.ConnectException, is java.net.http.HttpTimeoutException -> "Can't reach the server"
    is IOException -> "Can't reach the server (${e.message ?: e.javaClass.simpleName})"
    else -> "Failed: ${e.message ?: e}"
}
