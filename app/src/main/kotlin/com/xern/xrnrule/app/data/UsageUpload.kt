package com.xern.xrnrule.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.xern.xrnrule.domain.Clock
import com.xern.xrnrule.domain.localDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Base64

/**
 * Sends the sync server this phone's screen time (`POST /usage`, docs/laptop-screen-time.md), in
 * the laptop's format, as device "phone": each day's time per app as Insights counts it
 * ([ScreenTime]) and per site ([SiteUsageLog]), with the apps' names. Only to the sync server set
 * in Settings, with its login ([server] null: sync is off, and nothing goes).
 *
 * A pass runs after a sync round that worked, at most every [PASS_MS]. It sends the days from the
 * last one a pass got through (yesterday at the latest, for what came after that pass) to today.
 * A figure only grows and the server keeps the largest, so a day sent again changes nothing, and a
 * phone that was offline catches up, [BACK_DAYS] at most. The first pass, and the first to another
 * server or user, sends [BACK_DAYS]. Nothing here throws.
 */
class UsageUpload(
    private val server: () -> IconUpload.Server?,
    private val source: Source,
    private val prefs: SharedPreferences,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    /** This phone's screen time. Blocking. */
    interface Source {
        /** Each day's time per app (by package) and per site (by host), from [first] to [last]. */
        fun days(first: LocalDate, last: LocalDate): Map<LocalDate, UsageDay>

        /** An app's name as the phone shows it; its package when it has none. */
        fun name(pkg: String): String
    }

    private val passes = Channel<Unit>(Channel.CONFLATED)

    /** When the last pass began (boot-time ms), whether it got through or not. */
    @Volatile
    private var lastPassMono: Long? = null

    /** Passes as the class says, [syncStatus] saying when a sync round has worked. */
    fun start(syncStatus: StateFlow<SharedSyncStatus?>) {
        scope.launch(Dispatchers.IO) {
            for (p in passes) {
                val last = lastPassMono
                if (last != null && clock.nowMonotonicMs() - last in 0 until PASS_MS) continue
                lastPassMono = clock.nowMonotonicMs()
                try {
                    pass()
                } catch (_: Exception) {
                    // Offline, a server from before /usage, a strange answer: the next pass sends the same days and more.
                }
            }
        }
        scope.launch { syncStatus.collect { if (it?.ok == true) passes.trySend(Unit) } }
    }

    /** One send: whether it got through (false with sync off). Blocking; throws when the server can't be reached or refuses. */
    fun pass(): Boolean {
        val to = server() ?: return false
        val today = clock.localDate()
        val first = firstDay(to, today)
        val days = source.days(first, today).filterKeys { !it.isBefore(first) && !it.isAfter(today) }
        body(days, source::name)?.let { post(to, it) }
        prefs.edit {
            putLong(KEY_DAY, today.toEpochDay())
            putString(KEY_TO, account(to))
        }
        return true
    }

    /** From the last day a pass got through, yesterday at the latest; [BACK_DAYS] back at most, and the first time. */
    private fun firstDay(to: IconUpload.Server, today: LocalDate): LocalDate {
        val oldest = today.minusDays(BACK_DAYS - 1L)
        if (prefs.getString(KEY_TO, null) != account(to) || !prefs.contains(KEY_DAY)) return oldest
        val last = LocalDate.ofEpochDay(prefs.getLong(KEY_DAY, 0))
        return maxOf(oldest, minOf(last, today.minusDays(1)))
    }

    private fun account(to: IconUpload.Server) = "${to.url}\n${to.user}"

    private fun post(to: IconUpload.Server, body: String) {
        val bytes = body.encodeToByteArray()
        val conn = (URL("${to.url}/usage").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setFixedLengthStreamingMode(bytes.size)
            setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("${to.user}:${to.password}".toByteArray()))
            setRequestProperty("X-Device", DEVICE)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("server answered $code")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** How often a pass runs while sync rounds work. */
        const val PASS_MS = 5 * 60_000L

        /** How far back a pass goes: as far as Insights does, and the site log keeps. */
        const val BACK_DAYS = 30

        /** Who this is to the server (`X-Device`), as in [SharedSync]. */
        const val DEVICE = "phone"
        private const val TIMEOUT_MS = 15_000
        private const val KEY_DAY = "sent_day"
        private const val KEY_TO = "sent_to"

        /**
         * A `POST /usage` body, as the laptop's (desktop `UsageLog.body`): the days with any time,
         * oldest first, and the names of their apps that have one. Null when no day has any.
         */
        fun body(days: Map<LocalDate, UsageDay>, name: (String) -> String): String? {
            val kept = days.toSortedMap()
                .mapValues { (_, use) -> UsageDay(use.apps.filterValues { it > 0 }.toSortedMap(), use.sites.filterValues { it > 0 }.toSortedMap()) }
                .filterValues { it.apps.isNotEmpty() || it.sites.isNotEmpty() }
            if (kept.isEmpty()) return null
            return buildJsonObject {
                put(
                    "days",
                    buildJsonObject {
                        for ((day, use) in kept) {
                            put(
                                day.toString(),
                                buildJsonObject {
                                    put("apps", buildJsonObject { for ((pkg, ms) in use.apps) put(pkg, ms) })
                                    put("sites", buildJsonObject { for ((host, ms) in use.sites) put(host, ms) })
                                },
                            )
                        }
                    },
                )
                put(
                    "names",
                    buildJsonObject {
                        for (pkg in kept.values.flatMap { it.apps.keys }.toSortedSet()) name(pkg).takeIf { it.isNotBlank() && it != pkg }?.let { put(pkg, it) }
                    },
                )
            }.toString()
        }
    }
}

/**
 * This phone's screen time as its Insights shows it: Android's usage events for the apps
 * ([ScreenTime], with usage access), [sites] for the sites.
 */
class PhoneUsage(
    private val context: Context,
    private val sites: SiteUsageLog,
    private val clock: Clock,
    private val shieldSpans: ShieldSpans? = null,
) : UsageUpload.Source {
    override fun days(first: LocalDate, last: LocalDate): Map<LocalDate, UsageDay> {
        val count = (last.toEpochDay() - first.toEpochDay() + 1).toInt().coerceAtLeast(1)
        val apps = if (AppPermission.USAGE_ACCESS.isGranted(context)) {
            ScreenTime.lastDays(context, count, clock.zone(), shieldSpans).associate { d -> d.date to d.apps.associate { it.packageName to it.foregroundMs } }
        } else {
            emptyMap()
        }
        val siteDays = sites.days.value.mapKeys { LocalDate.ofEpochDay(it.key) }
        return (apps.keys + siteDays.keys).filter { !it.isBefore(first) && !it.isAfter(last) }
            .associateWith { UsageDay(apps[it].orEmpty(), siteDays[it].orEmpty()) }
    }

    override fun name(pkg: String): String = InstalledApps.labelFor(context, pkg)
}
