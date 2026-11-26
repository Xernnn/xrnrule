package com.xern.xrnrule.app.data

import androidx.core.util.AtomicFile
import com.xern.xrnrule.domain.Telemetry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.time.LocalDate

/** One line of the local event log, as read back for Insights. */
sealed interface TelemetryEvent {
    val atWallMs: Long

    /** A blocked app was opened and the shield met it. [label] names it when it was on another device. */
    data class Blocked(val pkg: String, override val atWallMs: Long, val label: String? = null) : TelemetryEvent

    /** A blocked website was opened and the shield met it. */
    data class SiteBlocked(val host: String, override val atWallMs: Long) : TelemetryEvent

    /** Minutes were bought past [blockIds] (comma-separated: one purchase can cover several blocks). */
    data class MinutesBought(val ms: Long, override val atWallMs: Long, val blockIds: String = "") : TelemetryEvent

    /** The day's break was taken. */
    data class BreakTaken(val ms: Long, override val atWallMs: Long) : TelemetryEvent

    /** A habit block's habit was done (ARMED → EARNED). */
    data class HabitDone(val blockId: String, override val atWallMs: Long) : TelemetryEvent

    /** An unlock ended after [ms] of use; [startedAtWall] names the session (0 in old lines). */
    data class UnlockUsed(val blockId: String, val ms: Long, override val atWallMs: Long, val startedAtWall: Long = 0) : TelemetryEvent
}

/**
 * What other devices logged, out of a `GET /events` answer, this device's own lines left out
 * (its own log has them, and lines not sent yet). A habit done or an unlock used is shared, so
 * every device logs it as it hears of it: Insights counts those once ([com.xern.xrnrule.app.ui.InsightsModel.merge]).
 * Anything malformed is left out, since this only adds to a screen.
 */
fun eventsFromElsewhere(body: String, thisDevice: String): List<TelemetryEvent> {
    fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
    val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
    val events = root?.get("events") as? JsonArray ?: return emptyList()
    return events.mapNotNull { element ->
        val e = element as? JsonObject ?: return@mapNotNull null
        val at = e.number("at") ?: return@mapNotNull null
        val ms = e.number("ms")
        if (e.text("d") == thisDevice) return@mapNotNull null
        when (e.text("t")) {
            "blocked" -> e.text("target")?.let { TelemetryEvent.Blocked(it, at, e.text("name") ?: it) }
            "bought" -> ms?.let { TelemetryEvent.MinutesBought(it, at, e.text("block").orEmpty()) }
            "break" -> ms?.let { TelemetryEvent.BreakTaken(it, at) }
            "habit" -> e.text("block")?.let { TelemetryEvent.HabitDone(it, at) }
            "unlock" -> e.text("block")?.let { block -> ms?.let { TelemetryEvent.UnlockUsed(block, it, at, e.number("start") ?: 0) } }
            else -> null
        }
    }
}

/**
 * The server's record of daily limit use (`GET /limits`: {"limits": {block: {device id: {day
 * start ms: ms}}}}), as block -> device id -> day start -> ms. Anything malformed is left out,
 * since this only adds to a screen.
 */
fun limitsFrom(body: String): Map<String, Map<String, Map<Long, Long>>> {
    val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
    val limits = root?.get("limits") as? JsonObject ?: return emptyMap()
    return limits.mapNotNull { (block, byDevice) ->
        val devices = (byDevice as? JsonObject)?.mapNotNull { (device, days) ->
            val kept = (days as? JsonObject)?.mapNotNull { (day, ms) ->
                val start = day.toLongOrNull() ?: return@mapNotNull null
                val used = (ms as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 } ?: return@mapNotNull null
                start to used
            }?.toMap().orEmpty()
            if (kept.isEmpty()) null else device to kept
        }?.toMap().orEmpty()
        if (devices.isEmpty()) null else block to devices
    }.toMap()
}

/** A day of a device's screen time: app id -> ms, host -> ms (docs/laptop-screen-time.md). */
data class UsageDay(val apps: Map<String, Long> = emptyMap(), val sites: Map<String, Long> = emptyMap()) {
    /** A day's time is its apps': a site's is part of its browser's. */
    val totalMs: Long get() = apps.values.sum()
}

/** The other devices' screen time by day, and their apps' names. */
data class UsageElsewhere(val days: Map<LocalDate, UsageDay> = emptyMap(), val names: Map<String, String> = emptyMap())

/**
 * What other devices counted, out of a `GET /usage` answer ({"usage": {device: {"YYYY-MM-DD":
 * {"apps", "sites"}}}, "names": {...}}), the rest added up by day: in practice the laptop's. This
 * device's own copy there ([UsageUpload]) is left out: Insights has it from the phone itself, and
 * it would count twice. Anything malformed is left out, since this only adds to a screen.
 */
fun usageFrom(body: String, thisDevice: String): UsageElsewhere {
    val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return UsageElsewhere()
    fun figures(o: JsonElement?): Map<String, Long> = (o as? JsonObject)?.mapNotNull { (k, v) ->
        (v as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it > 0 }?.let { k to it }
    }?.toMap().orEmpty()
    val days = HashMap<LocalDate, UsageDay>()
    (root["usage"] as? JsonObject)?.forEach { (device, byDay) ->
        if (device == thisDevice) return@forEach
        (byDay as? JsonObject)?.forEach { (day, use) ->
            val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return@forEach
            val o = use as? JsonObject ?: return@forEach
            val had = days[date] ?: UsageDay()
            days[date] = UsageDay(sum(had.apps, figures(o["apps"])), sum(had.sites, figures(o["sites"])))
        }
    }
    val names = (root["names"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it } }?.toMap().orEmpty()
    return UsageElsewhere(days, names)
}

private fun sum(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> = (a.keys + b.keys).associateWith { (a[it] ?: 0) + (b[it] ?: 0) }

/**
 * Append-only local event log behind the Insights history. One `;`-separated line per event (the
 * `.jsonl` name is historical). It stays on the phone (brainstorm §1) unless sync with the
 * user's own server is set up: then [SharedSync] also sends it there, for the weekly review.
 */
class LocalTelemetry(private val file: File) : Telemetry {

    override fun onBlockedImpression(pkg: String, atWallMs: Long) = append("blocked;$pkg;$atWallMs")
    override fun onSiteBlocked(host: String, atWallMs: Long) = append("site;$host;$atWallMs")
    override fun onMinutesBought(blockIds: Set<String>, ms: Long, atWallMs: Long) =
        append("bought;${blockIds.joinToString(",")};$ms;$atWallMs")
    override fun onBreakTaken(ms: Long, atWallMs: Long) = append("break;$ms;$atWallMs")
    override fun onHabitCompleted(blockId: String, atWallMs: Long) = append("habit;$blockId;$atWallMs")
    override fun onUnlockConsumed(blockId: String, ms: Long, atWallMs: Long, startedAtWall: Long) =
        append("unlock;$blockId;$ms;$atWallMs;$startedAtWall")

    @Synchronized
    private fun append(line: String) {
        runCatching { file.appendText(line + "\n") }
    }

    /** The log's size in bytes, a cheap "anything new?" check. */
    fun size(): Long = file.length()

    /** Everything logged, oldest first. Blocking — call off the main thread. */
    @Synchronized
    fun events(): List<TelemetryEvent> = lines().mapNotNull(::parse)

    /** Drops events older than [keepDays], so the log can't grow without end. */
    @Synchronized
    fun prune(nowWallMs: Long, keepDays: Int = 120) {
        val cutoff = nowWallMs - keepDays * 24L * 60 * 60 * 1000
        val lines = lines()
        val kept = lines.filter { line -> parse(line)?.let { it.atWallMs >= cutoff } == true }
        if (kept.size == lines.size) return
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        runCatching {
            out.write(kept.joinToString("") { it + "\n" }.encodeToByteArray())
            atomic.finishWrite(out)
        }.onFailure { atomic.failWrite(out) }
    }

    private fun lines(): List<String> = runCatching { file.readLines() }.getOrDefault(emptyList())

    private fun parse(line: String): TelemetryEvent? {
        val f = line.split(';')
        return when (f.firstOrNull()) {
            "blocked" -> f.getOrNull(2)?.toLongOrNull()?.let { TelemetryEvent.Blocked(f[1], it) }
            "site" -> f.getOrNull(2)?.toLongOrNull()?.let { TelemetryEvent.SiteBlocked(f[1], it) }
            "habit" -> f.getOrNull(2)?.toLongOrNull()?.let { TelemetryEvent.HabitDone(f[1], it) }
            "bought" -> {
                val ms = f.getOrNull(2)?.toLongOrNull()
                val at = f.getOrNull(3)?.toLongOrNull()
                if (ms != null && at != null) TelemetryEvent.MinutesBought(ms, at, f[1]) else null
            }
            "break" -> {
                val ms = f.getOrNull(1)?.toLongOrNull()
                val at = f.getOrNull(2)?.toLongOrNull()
                if (ms != null && at != null) TelemetryEvent.BreakTaken(ms, at) else null
            }
            "unlock" -> {
                val ms = f.getOrNull(2)?.toLongOrNull()
                val at = f.getOrNull(3)?.toLongOrNull()
                if (ms != null && at != null) TelemetryEvent.UnlockUsed(f[1], ms, at, f.getOrNull(4)?.toLongOrNull() ?: 0) else null
            }
            else -> null
        }
    }
}
