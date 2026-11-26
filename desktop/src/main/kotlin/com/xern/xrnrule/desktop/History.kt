package com.xern.xrnrule.desktop

import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.Clock
import com.xern.xrnrule.domain.SharedState
import com.xern.xrnrule.domain.World
import java.util.concurrent.Executors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** What the sync server keeps of both devices' history (xrnrule-sync.py: /events, /limits). */
interface RemoteHistory {
    /** Every device's events at or after [sinceMs], or null when the server can't be asked. */
    fun events(sinceMs: Long): List<JsonObject>?

    /** Each block's daily limit use, {block: {device id: {day start ms: ms}}}, or null. */
    fun limits(): JsonObject?

    /** Every device's screen time from [sinceDay], {"usage": {device: {day: …}}, "names": …}, or null. */
    fun usage(sinceDay: String): JsonObject? = null
}

/**
 * The Insights page's history (`GET /api/history`): this laptop's own event log and, with sync,
 * the server's log of both devices, each event once; the limit use the server has kept per day,
 * with today's as this laptop has it now; the emergency unlocks (shared, so known here); and the
 * blocks, in list order, to tell which one a blocked try was for; and screen time, every device's
 * from the server with this laptop's own as it stands now ([UsageLog]). The page works the figures
 * out (insights.js).
 *
 * The server is asked at most once a minute, and not while the page waits: the first time it is,
 * later the copy kept here is answered and the next one fetched behind it. The log after the last
 * two days rarely changes (only a device that was offline sends older events), so it is read
 * whole every half hour and only those two days in between.
 */
class History(
    private val local: FileTelemetry,
    private val remote: RemoteHistory?,
    private val clock: Clock,
    private val usageLog: UsageLog? = null,
    /** A laptop app's name. */
    private val appName: (String) -> String = DesktopApps::name,
    /** Runs the fetches behind the answer; the tests run them in place. */
    private val background: (() -> Unit) -> Unit = { job -> WORKER.execute(job) },
) {
    private var events: List<JsonObject>? = null
    private var eventsSince = Long.MAX_VALUE
    private var fullAt = Long.MIN_VALUE / 2
    private var askedAt = Long.MIN_VALUE / 2
    private var limits: JsonObject? = null
    private var usage: JsonObject? = null
    private var ok: Boolean? = null
    private var fetching = false

    /** The history of the last [days] days, as JSON. */
    fun json(world: World, days: Int): String {
        val now = clock.nowWallMs()
        val since = now - days.coerceIn(1, KEEP_DAYS) * DAY_MS
        remote?.let { fetch(since) }
        val (theirs, theirLimits, reached) = synchronized(this) { Triple(events.orEmpty(), limits, ok) }
        val theirUsage = synchronized(this) { usage }
        return buildJsonObject {
            put("now", now)
            put("zone", clock.zone().id)
            put("device", "laptop")
            put("synced", remote != null)
            reached?.let { put("reached", it) }
            put("events", JsonArray(merge(local.eventsAfter(since - 1).map { it.with("d", "laptop") }, theirs.filter { it.at() >= since })))
            put("limits", limitsWith(theirLimits, world))
            // I40: shared, every one ever, "blockId@wall" -> wall; the page shows them on every device.
            put("emergencies", buildJsonObject { for ((key, at) in world.emergencyLog) if (at >= since) put(key, at) })
            put("blocks", blocksOf(world))
            usageLog?.let { log ->
                val mine = log.snapshot()
                val firstDay = java.time.Instant.ofEpochMilli(since).atZone(clock.zone()).toLocalDate().toEpochDay()
                put("usage", usageWith(theirUsage?.get("usage") as? JsonObject, mine, firstDay))
                put("usageNames", namesWith(theirUsage?.get("names") as? JsonObject, mine, appName))
            }
        }.toString()
    }

    /** Fetches the server's copy, now if there is none yet, else behind the answer once it is a minute old. */
    private fun fetch(since: Long) {
        val mono = clock.nowMonotonicMs()
        val first = synchronized(this) {
            val wider = events != null && since < eventsSince
            if (fetching || (!wider && mono - askedAt < REFRESH_MS)) return
            fetching = true
            askedAt = mono
            ok == null || wider // a server that failed before is asked behind the answer, not in its way
        }
        val job = {
            try {
                refresh(since, mono)
            } finally {
                synchronized(this) { fetching = false }
            }
        }
        if (first) job() else background(job)
    }

    private fun refresh(since: Long, mono: Long) {
        val remote = remote ?: return
        val whole = synchronized(this) { events == null || since < eventsSince || mono - fullAt >= FULL_MS }
        val from = if (whole) since else clock.nowWallMs() - RECENT_MS
        val got = remote.events(from)
        val record = remote.limits()
        val use = usageLog?.let { remote.usage(UsageLog.dayName(java.time.Instant.ofEpochMilli(since).atZone(clock.zone()).toLocalDate().toEpochDay())) }
        synchronized(this) {
            ok = got != null
            if (record != null) limits = record
            if (use != null) usage = use
            if (got == null) return
            if (whole) {
                events = got
                eventsSince = since
                fullAt = mono
            } else {
                events = events.orEmpty().filter { it.at() < from } + got
            }
        }
    }

    companion object {
        const val KEEP_DAYS = 120
        private const val DAY_MS = 86_400_000L
        private const val REFRESH_MS = 60_000L
        private const val FULL_MS = 30 * 60_000L
        private const val RECENT_MS = 2 * DAY_MS
        private val WORKER = Executors.newSingleThreadExecutor { r -> Thread(r, "history").apply { isDaemon = true } }

        private fun JsonObject.at(): Long = this["at"]?.jsonPrimitive?.longOrNull ?: 0

        private fun JsonObject.with(key: String, value: String) = JsonObject(this + (key to JsonPrimitive(value)))

        /** The server's dedup key (xrnrule-sync.py, event_key): device, kind, time and what it names. */
        private fun JsonObject.key() = listOf("d", "t", "at", "block", "target", "ms").map { this[it]?.toString() }

        /**
         * This laptop's events and the server's, each once, oldest first: the server holds copies
         * of those this laptop sent, and the laptop's own log has those it hasn't sent yet.
         */
        fun merge(mine: List<JsonObject>, theirs: List<JsonObject>): List<JsonObject> {
            val seen = HashSet<List<String?>>()
            return (mine + theirs).filter { seen.add(it.key()) }.sortedBy { it.at() }
        }

        /** The server's record of limit use, with each device's use today as this laptop has it now. */
        fun limitsWith(record: JsonObject?, world: World): JsonObject {
            val out = LinkedHashMap<String, MutableMap<String, MutableMap<String, Long>>>()
            record?.forEach { (block, devices) ->
                (devices as? JsonObject)?.forEach { (device, days) ->
                    (days as? JsonObject)?.forEach { (day, ms) ->
                        (ms as? JsonPrimitive)?.longOrNull?.let { out.getOrPut(block) { LinkedHashMap() }.getOrPut(device) { LinkedHashMap() }[day] = it }
                    }
                }
            }
            for ((block, devices) in SharedState.of(world).allowance) {
                for ((device, use) in devices) {
                    val days = out.getOrPut(block) { LinkedHashMap() }.getOrPut(device) { LinkedHashMap() }
                    val day = use.dayStartWall.toString()
                    days[day] = maxOf(days[day] ?: 0, use.ms)
                }
            }
            return buildJsonObject {
                for ((block, devices) in out) {
                    put(block, buildJsonObject { for ((device, days) in devices) put(device, buildJsonObject { for ((d, ms) in days) put(d, ms) }) })
                }
            }
        }

        /**
         * Every device's screen time from [firstDay], {device: {day: {"apps", "sites"}}}: the server's
         * ([server]), with this laptop's own ([mine], what it counts now included) in place of its
         * copy there, each figure the larger of the two (a lost usage.json shows what the server kept).
         */
        fun usageWith(server: JsonObject?, mine: Map<Long, UsageLog.Day>, firstDay: Long, device: String = "laptop"): JsonObject {
            val out = LinkedHashMap<String, MutableMap<String, UsageLog.Day>>()
            server?.forEach { (dev, days) ->
                (days as? JsonObject)?.forEach { (day, use) ->
                    val d = runCatching { java.time.LocalDate.parse(day).toEpochDay() }.getOrNull()
                    if (d != null && d >= firstDay && use is JsonObject) out.getOrPut(dev) { java.util.TreeMap() }[day] = dayOf(use)
                }
            }
            for ((d, use) in mine) {
                if (d < firstDay) continue
                val days = out.getOrPut(device) { java.util.TreeMap() }
                val name = UsageLog.dayName(d)
                val had = days[name]
                days[name] = if (had == null) use else UsageLog.Day(larger(had.apps, use.apps), larger(had.sites, use.sites))
            }
            return buildJsonObject {
                for ((dev, days) in out) put(dev, buildJsonObject { for ((day, use) in days) put(day, UsageLog.dayJson(use)) })
            }
        }

        /** The apps' names: the server's, and this laptop's own apps by [name]. */
        fun namesWith(server: JsonObject?, mine: Map<Long, UsageLog.Day>, name: (String) -> String): JsonObject {
            val out = LinkedHashMap<String, String>()
            server?.forEach { (app, n) -> (n as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { out[app] = it } }
            for (app in mine.values.flatMap { it.apps.keys }.toSortedSet()) name(app).takeIf { it.isNotBlank() && it != app }?.let { out[app] = it }
            return JsonObject(out.mapValues { JsonPrimitive(it.value) })
        }

        private fun dayOf(use: JsonObject): UsageLog.Day {
            fun figures(key: String) = (use[key] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.longOrNull?.let { k to it } }?.toMap().orEmpty()
            return UsageLog.Day(figures("apps"), figures("sites"))
        }

        private fun larger(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> =
            (a.keys + b.keys).associateWith { maxOf(a[it] ?: 0, b[it] ?: 0) }

        /** The blocks in list order, with what they block: a try names what was tried, not its block. */
        fun blocksOf(world: World): JsonElement = buildJsonArray {
            for (b in world.blocks) {
                add(
                    buildJsonObject {
                        put("id", b.id)
                        put("name", b.name)
                        put("apps", JsonArray((b.targetPackages.sorted() + b.targetDesktopApps.sorted()).map(::JsonPrimitive)))
                        put("sites", JsonArray(b.targetSites.sorted().map(::JsonPrimitive)))
                        put("except", JsonArray(b.exceptSites.sorted().map(::JsonPrimitive)))
                        put("dailyMs", if (b.mode == BlockMode.ALLOWANCE || b.mode == BlockMode.HABIT) b.dailyAllowanceMs else 0)
                        put("perDevice", b.perDevice)
                    },
                )
            }
        }
    }
}

/** A JSON object's array under [key], as objects. */
internal fun JsonObject.objects(key: String): List<JsonObject>? = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }
