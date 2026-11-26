package com.xern.xrnrule.app.ui

import com.xern.xrnrule.app.data.TelemetryEvent
import com.xern.xrnrule.app.data.UsageDay
import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.Sites
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What Insights shows, worked out from the phone's own event log and the other devices' (the
 * sync server's `GET /events`), with no Android in it so it can be tested. The laptop's page
 * builds the same sections from the same log.
 */

/** Where something happened. */
enum class Device { PHONE, LAPTOP }

enum class InsightRange(val label: String, val days: Int) { TODAY("Today", 1), WEEK("7 days", 7), MONTH("30 days", 30) }

enum class DeviceFilter(val label: String) {
    ALL("All"), PHONE("Phone"), LAPTOP("Laptop");

    /** Whether an event on [device] shows; one that belongs to every device (null) always does. */
    fun shows(device: Device?): Boolean = when (this) {
        ALL -> true
        PHONE -> device != Device.LAPTOP
        LAPTOP -> device != Device.PHONE
    }
}

enum class EventKind { BLOCKED, HABIT, UNLOCK, BOUGHT, BREAK, EMERGENCY }

/**
 * One thing that happened. [device] is null for what belongs to every device at once (an
 * emergency unlock, which the shared state keeps without saying where it was used). [blockId]
 * is the block it's about as far as can be told: a blocked app or site goes to the first block,
 * in the list's order, that covers it.
 */
data class InsightEvent(
    val atWallMs: Long,
    val device: Device?,
    val kind: EventKind,
    val blockId: String? = null,
    /** BLOCKED: the app (a package or a laptop app) or the site. */
    val target: String? = null,
    /** BLOCKED: its name, when the device that logged it said. */
    val label: String? = null,
    val isSite: Boolean = false,
    /** UNLOCK: time used; BOUGHT, BREAK: time given. */
    val ms: Long = 0,
    /** UNLOCK: when its session started, the same on every device that logs it (I28); 0 in old lines. */
    val session: Long = 0,
)

object InsightsModel {
    /** Two devices logging the same habit (each logs a shared one as it hears of it) this close together logged one. */
    const val SAME_HABIT_MS = 10 * 60_000L

    /** An unlock from before sessions were named (no start) is the same one on both devices this close together. */
    const val SAME_UNLOCK_MS = 2 * 60_000L

    /**
     * Every event, oldest first: this phone's own [local] log, what the other devices sent the
     * server ([elsewhere], without this phone's own lines), and the shared [emergencies]
     * ("blockId@wall" -> wall).
     *
     * A habit done and an unlock used are shared (§2.4), so every device logs them as it hears
     * of them: two devices' lines for the same one are one event, on the device that logged it
     * first (where it happened; the other heard of it later). An unlock goes by its session
     * (its start), the same on every device; a habit by its block and the time.
     */
    fun merge(
        local: List<TelemetryEvent>,
        elsewhere: List<TelemetryEvent>,
        emergencies: Map<String, Long>,
        blocks: List<Block>,
    ): List<InsightEvent> {
        val all = local.map { from(it, Device.PHONE, blocks) } +
            elsewhere.map { from(it, Device.LAPTOP, blocks) } +
            emergencies.map { (key, at) ->
                InsightEvent(at, null, EventKind.EMERGENCY, key.substringBeforeLast('@').takeIf { id -> blocks.any { it.id == id } })
            }
        val sorted = all.sortedWith(compareBy<InsightEvent> { it.atWallMs }.thenBy { it.device?.ordinal ?: -1 })
        val kept = ArrayList<InsightEvent>(sorted.size)
        val sessions = HashMap<Pair<String?, Long>, Device?>()
        val lastShared = HashMap<Pair<EventKind, String?>, MutableList<InsightEvent>>()
        for (e in sorted) {
            if (e.kind == EventKind.UNLOCK && e.session > 0) {
                val first = sessions.getOrPut(e.blockId to e.session) { e.device }
                if (first != e.device) continue
            } else if (e.kind == EventKind.HABIT || e.kind == EventKind.UNLOCK) {
                val window = if (e.kind == EventKind.HABIT) SAME_HABIT_MS else SAME_UNLOCK_MS
                val earlier = lastShared.getOrPut(e.kind to e.blockId) { ArrayList() }
                earlier.removeAll { e.atWallMs - it.atWallMs > window }
                if (earlier.any { it.device != e.device }) continue
                earlier += e
            }
            kept += e
        }
        return kept
    }

    private fun from(e: TelemetryEvent, device: Device, blocks: List<Block>): InsightEvent = when (e) {
        is TelemetryEvent.Blocked -> {
            // From the laptop a blocked site comes as "blocked" too, named by its host.
            val site = device == Device.LAPTOP && isSite(e.pkg, e.label, blocks)
            InsightEvent(e.atWallMs, device, EventKind.BLOCKED, blockFor(blocks, e.pkg, site), e.pkg, e.label, site)
        }
        is TelemetryEvent.SiteBlocked -> InsightEvent(e.atWallMs, device, EventKind.BLOCKED, blockFor(blocks, e.host, true), e.host, e.host, true)
        is TelemetryEvent.HabitDone -> InsightEvent(e.atWallMs, device, EventKind.HABIT, known(blocks, e.blockId))
        is TelemetryEvent.UnlockUsed ->
            InsightEvent(e.atWallMs, device, EventKind.UNLOCK, known(blocks, e.blockId), ms = e.ms, session = e.startedAtWall)
        is TelemetryEvent.MinutesBought ->
            InsightEvent(e.atWallMs, device, EventKind.BOUGHT, e.blockIds.split(',').firstNotNullOfOrNull { known(blocks, it.trim()) }, ms = e.ms)
        is TelemetryEvent.BreakTaken -> InsightEvent(e.atWallMs, device, EventKind.BREAK, ms = e.ms)
    }

    private fun known(blocks: List<Block>, id: String): String? = id.takeIf { blocks.any { it.id == id } }

    /** The first block, in the list's order, with [target] among its apps or sites. */
    fun blockFor(blocks: List<Block>, target: String, isSite: Boolean): String? =
        blocks.firstOrNull { b -> if (isSite) b.coversSite(target) else target in b.targetApps }?.id

    /**
     * Whether a laptop's "blocked" [target] is a site: a block has it as one, or (named by itself,
     * as the laptop names a site) it reads as a host and no block has it as a laptop app.
     */
    fun isSite(target: String, label: String?, blocks: List<Block>): Boolean {
        if (blocks.any { b -> b.targetSites.any { Sites.matches(target, it) } }) return true
        if (blocks.any { target in it.targetApps }) return false
        return (label == null || label == target) && '.' in target && Sites.normalize(target) == target
    }

    /** The first moment of the range: midnight [InsightRange.days] - 1 days before today. */
    fun rangeStart(range: InsightRange, nowWallMs: Long, zone: ZoneId): Long =
        today(nowWallMs, zone).minusDays(range.days - 1L).atStartOfDay(zone).toInstant().toEpochMilli()

    fun today(nowWallMs: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(nowWallMs).atZone(zone).toLocalDate()

    /** [events] on the devices [device] shows, about [blockId] when one is picked. */
    fun select(events: List<InsightEvent>, device: DeviceFilter, blockId: String?): List<InsightEvent> =
        events.filter { device.shows(it.device) && (blockId == null || it.blockId == blockId) }

    /** The first moment of the stretch before [range]: yesterday for today, the 7 or 30 days before. */
    fun previousStart(range: InsightRange, nowWallMs: Long, zone: ZoneId): Long =
        today(nowWallMs, zone).minusDays(2L * range.days - 1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** The tiles' figures for [events]. */
    fun figures(events: List<InsightEvent>): Figures {
        val unlocks = events.filter { it.kind == EventKind.UNLOCK }
        val bought = events.filter { it.kind == EventKind.BOUGHT }
        return Figures(
            tries = events.count { it.kind == EventKind.BLOCKED },
            unlocks = unlocks.size,
            unlockedMs = unlocks.sumOf { it.ms },
            bought = bought.size,
            boughtMs = bought.sumOf { it.ms },
            breaks = events.count { it.kind == EventKind.BREAK },
            habits = events.count { it.kind == EventKind.HABIT },
        )
    }

    /**
     * Everything the page shows for [range] ending now, from [events] already narrowed by
     * [select]. Streaks look at all of [events], not just the range. [logStartMs] is when the
     * whole log (not just what [select] kept) begins: the stretch before the range has figures
     * only when the log reaches back to that stretch's first day, or a log begun since would say
     * "0 before" about days it never saw.
     */
    fun summarize(
        events: List<InsightEvent>,
        blocks: List<Block>,
        range: InsightRange,
        nowWallMs: Long,
        zone: ZoneId,
        logStartMs: Long? = events.minOfOrNull { it.atWallMs },
    ): InsightsSummary {
        val start = rangeStart(range, nowWallMs, zone)
        val inRange = events.filter { it.atWallMs in start..nowWallMs }
        val previousStart = previousStart(range, nowWallMs, zone)
        val covered = logStartMs != null && Instant.ofEpochMilli(logStartMs).atZone(zone).toLocalDate() <= Instant.ofEpochMilli(previousStart).atZone(zone).toLocalDate()
        val previous = if (covered) figures(events.filter { it.atWallMs in previousStart until start }) else null
        val today = today(nowWallMs, zone)
        fun dateOf(e: InsightEvent): LocalDate = Instant.ofEpochMilli(e.atWallMs).atZone(zone).toLocalDate()
        val tries = inRange.filter { it.kind == EventKind.BLOCKED }
        val order: (String?) -> Int = { id -> blocks.indexOfFirst { it.id == id }.let { if (it < 0) Int.MAX_VALUE else it } }

        fun segments(of: List<InsightEvent>): List<Segment> =
            of.groupingBy { it.blockId }.eachCount().entries.sortedBy { order(it.key) }.map { Segment(it.key, it.value) }

        val bars = if (range == InsightRange.TODAY) {
            val byHour = tries.groupBy { Instant.ofEpochMilli(it.atWallMs).atZone(zone).hour }
            (0 until 24).map { h -> Bar(today, h, segments(byHour[h].orEmpty())) }
        } else {
            val byDay = tries.groupBy(::dateOf)
            (range.days - 1 downTo 0).map { back -> today.minusDays(back.toLong()).let { d -> Bar(d, null, segments(byDay[d].orEmpty())) } }
        }

        val heat = List(7) { IntArray(24) }
        tries.forEach {
            val at = Instant.ofEpochMilli(it.atWallMs).atZone(zone)
            heat[at.dayOfWeek.value - 1][at.hour]++
        }

        // A clean day for a block: nothing of it was tried, bought past or unlocked in an emergency.
        // Only days the log covers count: an empty log has no clean days to show.
        val firstDay = events.minOfOrNull { it.atWallMs }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: today.plusDays(1)
        val dirty = HashMap<String, MutableSet<LocalDate>>()
        events.filter { it.kind == EventKind.BLOCKED || it.kind == EventKind.BOUGHT || it.kind == EventKind.EMERGENCY }
            .forEach { e -> e.blockId?.let { dirty.getOrPut(it) { HashSet() } += dateOf(e) } }
        fun streak(id: String): Int {
            val days = dirty[id].orEmpty()
            var d = today
            var n = 0
            while (!d.isBefore(firstDay) && d !in days) {
                n++
                d = d.minusDays(1)
            }
            return n
        }

        val perBlock = blocks.map { b ->
            val mine = inRange.filter { it.blockId == b.id }
            val unlocks = mine.filter { it.kind == EventKind.UNLOCK }
            val bought = mine.filter { it.kind == EventKind.BOUGHT }
            BlockSummary(
                blockId = b.id,
                tries = mine.count { it.kind == EventKind.BLOCKED },
                unlocks = unlocks.size,
                unlockedMs = unlocks.sumOf { it.ms },
                bought = bought.size,
                boughtMs = bought.sumOf { it.ms },
                habits = mine.count { it.kind == EventKind.HABIT },
                cleanDays = streak(b.id),
                trend = bars.map { bar -> bar.segments.firstOrNull { it.blockId == b.id }?.count ?: 0 },
            )
        }

        val targets = tries.filter { it.target != null }
            .groupBy { targetKey(it.target!!, it.isSite) }
            .map { (key, list) ->
                TargetTries(
                    key.first, key.second, list.firstNotNullOfOrNull { it.label?.takeIf { l -> l != it.target } }, list.size,
                    list.mapNotNullTo(HashSet()) { it.device }, list.first().blockId,
                    phone = list.count { it.device == Device.PHONE }, laptop = list.count { it.device == Device.LAPTOP },
                )
            }
            .sortedByDescending { it.tries }

        val history = inRange.sortedByDescending { it.atWallMs }.groupBy(::dateOf).map { (d, list) -> historyDay(d, list, ::segments) }

        return InsightsSummary(
            range = range,
            today = today,
            nowHour = Instant.ofEpochMilli(nowWallMs).atZone(zone).hour,
            figures = figures(inRange),
            previous = previous,
            split = segments(tries),
            bars = bars,
            heat = heat,
            blocks = perBlock,
            targets = targets,
            history = history,
        )
    }

    /** Tries at one thing on one device this close together, the same day, are one row of the history. */
    const val RUN_MS = 30 * 60_000L

    /** A day of the history from its [events], newest first: tries at one thing in a run are one row. */
    private fun historyDay(date: LocalDate, events: List<InsightEvent>, segments: (List<InsightEvent>) -> List<Segment>): HistoryDay {
        val rows = ArrayList<HistoryRow>()
        for (e in events) {
            val last = rows.lastOrNull()
            if (e.kind == EventKind.BLOCKED && last != null && last.event.kind == EventKind.BLOCKED && last.event.device == e.device &&
                last.event.target != null && e.target != null && targetKey(last.event.target, last.event.isSite) == targetKey(e.target, e.isSite) &&
                last.fromMs - e.atWallMs <= RUN_MS
            ) {
                rows[rows.lastIndex] = last.copy(n = last.n + 1, fromMs = e.atWallMs)
                continue
            }
            rows += HistoryRow(e)
        }
        val unlocks = events.filter { it.kind == EventKind.UNLOCK }
        val tries = events.filter { it.kind == EventKind.BLOCKED }
        return HistoryDay(date, rows, tries.size, unlocks.size, unlocks.sumOf { it.ms }, segments(tries))
    }

    /** Which device a limit record's device id is ("phone-…", "laptop-…"), as the web pages tell. */
    fun deviceOfId(id: String): Device? = when {
        id.startsWith("phone", ignoreCase = true) -> Device.PHONE
        id.startsWith("laptop", ignoreCase = true) -> Device.LAPTOP
        else -> null
    }

    /**
     * A block's daily limit use over [range]: the past days from the server's record ([record]:
     * device id -> day start -> ms, `GET /limits`), today from [today] (the engine's own figure,
     * newer than the record's). A block shared by the devices adds their use up; one with a limit
     * on each (I33) goes by the device that used the most, as the engine does. Only days some
     * device reported count, so a day before sync was set up isn't a day of nothing.
     */
    fun limitUse(
        block: Block,
        record: Map<String, Map<Long, Long>>,
        today: BlockToday?,
        range: InsightRange,
        device: DeviceFilter,
        nowWallMs: Long,
        zone: ZoneId,
    ): LimitUse {
        val todayDate = today(nowWallMs, zone)
        val first = todayDate.minusDays(range.days - 1L)
        val byDay = HashMap<LocalDate, Long>()
        for ((id, days) in record) {
            val dev = deviceOfId(id)
            if (device != DeviceFilter.ALL && (dev == null || !device.shows(dev))) continue
            for ((start, ms) in days) {
                // Midday of the day it starts: a day 23 or 25 hours long is still that day.
                val day = Instant.ofEpochMilli(start + 12 * 3_600_000L).atZone(zone).toLocalDate()
                if (day.isBefore(first) || day.isAfter(todayDate)) continue
                val had = byDay[day] ?: 0L
                byDay[day] = if (block.perDevice) maxOf(had, ms) else had + ms
            }
        }
        if (today != null) {
            val phone = if (device.shows(Device.PHONE)) today.phoneMs else 0L
            val laptop = if (device.shows(Device.LAPTOP)) today.laptopMs else 0L
            byDay[todayDate] = maxOf(byDay[todayDate] ?: 0L, if (block.perDevice) maxOf(phone, laptop) else phone + laptop)
        }
        return LimitUse(
            dailyMs = block.dailyAllowanceMs,
            usedMs = byDay.values.sum(),
            days = byDay.size,
            over = byDay.count { it.value >= block.dailyAllowanceMs },
            todayOnly = byDay.keys == setOf(todayDate),
        )
    }

    /** A site goes by its host without "www.", as the phone's time per site does; an app by its id. */
    fun targetKey(target: String, isSite: Boolean): Pair<String, Boolean> =
        if (isSite) Sites.hostOf(target).removePrefix("www.") to true else target to false

    /**
     * The apps' and the sites' rows: time on the phone ([appTime], [siteTime]) and on the laptop
     * ([laptopApps], [laptopSites], from the server: docs/laptop-screen-time.md), each only when the
     * caller's filter shows that device, and tries on the devices shown, by time and then by tries.
     * Only what [blockId] covers when one is picked. Under a minute and never tried is left out.
     */
    fun topTargets(
        appTime: Map<String, Pair<String, Long>>,
        siteTime: Map<String, Long>,
        tries: List<TargetTries>,
        blocks: List<Block>,
        blockId: String?,
        laptopApps: Map<String, Pair<String, Long>> = emptyMap(),
        laptopSites: Map<String, Long> = emptyMap(),
    ): Pair<List<TopTarget>, List<TopTarget>> {
        val picked = blocks.firstOrNull { it.id == blockId }
        fun covered(target: String, site: Boolean) = picked == null || (if (site) picked.coversSite(target) else target in picked.targetApps)
        val triesOf = tries.associateBy { it.target to it.isSite }
        fun rows(phone: Map<String, Long>, laptop: Map<String, Long>, labels: Map<String, String>, site: Boolean): List<TopTarget> {
            val keys = phone.keys + laptop.keys + tries.filter { it.isSite == site }.map { it.target }
            return keys.distinct()
                .filter { covered(it, site) }
                .map { t ->
                    val n = triesOf[t to site]
                    val p = phone[t] ?: 0L
                    val l = laptop[t] ?: 0L
                    TopTarget(
                        t, labels[t] ?: n?.label, site, p + l, n?.tries ?: 0, blockFor(blocks, t, site),
                        laptopOnly = p == 0L && (l > 0L || n?.devices == setOf(Device.LAPTOP)), phoneMs = p, laptopMs = l,
                    )
                }
                .filter { it.ms >= 60_000 || it.tries > 0 }
                .sortedWith(compareByDescending<TopTarget> { it.ms }.thenByDescending { it.tries })
        }
        fun bySite(time: Map<String, Long>) = time.entries.groupBy({ targetKey(it.key, true).first }, { it.value }).mapValues { it.value.sum() }
        val apps = rows(
            appTime.mapValues { it.value.second }, laptopApps.mapValues { it.value.second },
            laptopApps.mapValues { it.value.first } + appTime.mapValues { it.value.first }, site = false,
        )
        val sites = rows(bySite(siteTime), bySite(laptopSites), emptyMap(), site = true)
        return apps to sites
    }

    /**
     * Screen time a day over [range] on the devices [device] shows: the phone's ([phoneApps],
     * [phoneSites]: date -> app or host -> ms) and the laptop's ([laptop], from the server). A day's
     * time on a device is its apps' (a site's is part of its browser's); with a block picked
     * ([blockId]), the time of the apps and the sites it covers.
     */
    fun screenTime(
        phoneApps: Map<LocalDate, Map<String, Long>>,
        phoneSites: Map<LocalDate, Map<String, Long>>,
        laptop: Map<LocalDate, UsageDay>,
        blocks: List<Block>,
        blockId: String?,
        range: InsightRange,
        device: DeviceFilter,
        nowWallMs: Long,
        zone: ZoneId,
    ): ScreenTimeSummary {
        val picked = blocks.firstOrNull { it.id == blockId }
        fun of(apps: Map<String, Long>?, sites: Map<String, Long>?): Long =
            if (picked == null) apps.orEmpty().values.sum()
            else apps.orEmpty().filterKeys { it in picked.targetApps }.values.sum() + sites.orEmpty().filterKeys { picked.coversSite(it) }.values.sum()
        val today = today(nowWallMs, zone)
        val days = (range.days - 1 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            ScreenDay(
                d,
                phoneMs = if (device.shows(Device.PHONE)) of(phoneApps[d], phoneSites[d]) else 0L,
                laptopMs = if (device.shows(Device.LAPTOP)) laptop[d]?.let { of(it.apps, it.sites) } ?: 0L else 0L,
            )
        }
        return ScreenTimeSummary(days)
    }

    /** Whole days of [range] as epoch days, oldest first: what to add up of per-day logs. */
    fun epochDays(range: InsightRange, nowWallMs: Long, zone: ZoneId): List<Long> {
        val today = today(nowWallMs, zone).toEpochDay()
        return (today - range.days + 1..today).toList()
    }
}

/** A part of a bar: one block's tries (null: no block covers what was tried any more). */
data class Segment(val blockId: String?, val count: Int)

/** A day's bar ([hour] null), or an hour's on the Today range. */
data class Bar(val date: LocalDate, val hour: Int?, val segments: List<Segment>) {
    val total: Int get() = segments.sumOf { it.count }
}

/** The tiles: what happened in a stretch of time. */
data class Figures(
    val tries: Int = 0,
    val unlocks: Int = 0,
    val unlockedMs: Long = 0,
    val bought: Int = 0,
    val boughtMs: Long = 0,
    val breaks: Int = 0,
    val habits: Int = 0,
)

data class BlockSummary(
    val blockId: String,
    val tries: Int,
    val unlocks: Int,
    val unlockedMs: Long,
    val bought: Int,
    val boughtMs: Long,
    val habits: Int,
    /** Days in a row up to today with nothing of it tried, bought past or unlocked in an emergency. */
    val cleanDays: Int,
    /** Its tries in each of the chart's bars: a day each, or an hour each on Today. */
    val trend: List<Int> = emptyList(),
)

/** A daily limit's use over a range: [usedMs] over the [days] some device reported, [over] of them at or past the limit. */
data class LimitUse(val dailyMs: Long, val usedMs: Long, val days: Int, val over: Int = 0, val todayOnly: Boolean = false) {
    val perDayMs: Long get() = if (days == 0) 0L else usedMs / days
    val fraction: Float get() = if (dailyMs <= 0L) 0f else perDayMs.toFloat() / dailyMs
}

/** How a meter of a limit is drawn: in the data colour, amber from [NEAR_LIMIT], red once used up. */
enum class MeterTone {
    DATA, NEAR, OVER;

    companion object {
        const val NEAR_LIMIT = 0.8f
        fun of(fraction: Float): MeterTone = when {
            fraction >= 1f -> OVER
            fraction >= NEAR_LIMIT -> NEAR
            else -> DATA
        }
    }
}

/** Today's use of a block's daily limit, here and on the laptop (I19, §2.4). */
data class BlockToday(val phoneMs: Long, val laptopMs: Long, val allowanceMs: Long)

data class TargetTries(
    val target: String,
    val isSite: Boolean,
    val label: String?,
    val tries: Int,
    val devices: Set<Device>,
    val blockId: String?,
    val phone: Int = 0,
    val laptop: Int = 0,
)

/**
 * An app or a site: time on the devices shown ([ms], the phone's and the laptop's parts beside
 * it), tries on them. [laptopOnly]: only ever met on the laptop.
 */
data class TopTarget(
    val target: String,
    val label: String?,
    val isSite: Boolean,
    val ms: Long,
    val tries: Int,
    val blockId: String?,
    val laptopOnly: Boolean = false,
    val phoneMs: Long = ms,
    val laptopMs: Long = 0,
)

/** A day's screen time on each device. */
data class ScreenDay(val date: LocalDate, val phoneMs: Long, val laptopMs: Long) {
    val totalMs: Long get() = phoneMs + laptopMs
}

/** Screen time over a range: a day each, oldest first. */
data class ScreenTimeSummary(val days: List<ScreenDay>) {
    val phoneMs: Long get() = days.sumOf { it.phoneMs }
    val laptopMs: Long get() = days.sumOf { it.laptopMs }
    val totalMs: Long get() = phoneMs + laptopMs
}

/** One row of the history: an event, or [n] tries at one thing in a run, the first at [fromMs]. */
data class HistoryRow(val event: InsightEvent, val n: Int = 1, val fromMs: Long = event.atWallMs)

/** A day of the history, newest row first, with its tries, unlocks and the tries' split by block. */
data class HistoryDay(
    val date: LocalDate,
    val rows: List<HistoryRow>,
    val tries: Int,
    val unlocks: Int,
    val unlockedMs: Long,
    val split: List<Segment>,
) {
    val events: Int get() = rows.sumOf { it.n }
}

data class InsightsSummary(
    val range: InsightRange,
    val today: LocalDate,
    /** The hour it is now: today's bar on Today. */
    val nowHour: Int,
    val figures: Figures,
    /** The stretch before the range (yesterday, the 7 or 30 days before); null when the log doesn't reach back. */
    val previous: Figures?,
    /** The range's tries by block, in the list's order. */
    val split: List<Segment>,
    val bars: List<Bar>,
    /** Blocked tries by weekday (Monday first) and hour. */
    val heat: List<IntArray>,
    val blocks: List<BlockSummary>,
    val targets: List<TargetTries>,
    /** Newest day first, each newest row first. */
    val history: List<HistoryDay>,
) {
    val tries: Int get() = figures.tries
    val unlocks: Int get() = figures.unlocks
    val unlockedMs: Long get() = figures.unlockedMs
    val bought: Int get() = figures.bought
    val boughtMs: Long get() = figures.boughtMs
    val breaks: Int get() = figures.breaks
    val habits: Int get() = figures.habits
}
