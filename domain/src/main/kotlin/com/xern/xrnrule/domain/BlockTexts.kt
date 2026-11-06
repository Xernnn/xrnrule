package com.xern.xrnrule.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * A block in words, the same on the phone's card and the laptop's page: how it stands right now,
 * and when its hours are. Pure, so it's tested here rather than on a screen.
 */
object BlockTexts {
    enum class Tone { BLOCKED, READY, OPEN, OFF }

    data class Headline(val text: String, val tone: Tone)

    private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** "Every day", "Weekdays", "Mon–Thu", "Mon, Wed, Sat". */
    fun days(days: Set<Int>): String = when (days) {
        (1..7).toSet() -> "Every day"
        (1..5).toSet() -> "Weekdays"
        setOf(6, 7) -> "Weekends"
        else -> runs(days.sorted()).joinToString(", ") { run ->
            // Two days in a row read better as two names; three or more as a range.
            if (run.size >= 3) "${DAY_NAMES[run.first() - 1]}–${DAY_NAMES[run.last() - 1]}"
            else run.joinToString(", ") { DAY_NAMES[it - 1] }
        }
    }

    private fun runs(sorted: List<Int>): List<List<Int>> = sorted.fold(mutableListOf<MutableList<Int>>()) { all, day ->
        if (all.lastOrNull()?.last() == day - 1) all.last() += day else all += mutableListOf(day)
        all
    }

    private fun time(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

    /**
     * "Every day 21:00–22:30", "Weekdays 09:00–17:00 · Sat 10:00–12:00", "All the time". Times on
     * the same days share their days, and days with the same times join up, so a block set up one
     * day at a time doesn't read like a list of seven.
     */
    fun schedule(s: Schedule): String {
        val windows = listOf(s.activeDays to s.activeWindow) + s.moreWindows.map { it.days to it.window }
        if (windows.all { it.second == null } && windows.flatMap { it.first }.toSet().size == 7) return "All the time"
        val byTimes = windows.groupBy({ it.second }, { it.first }).map { (window, days) -> days.flatten().toSet() to window }
        val byDays = byTimes.groupBy({ it.first }, { it.second })
        return byDays.entries.joinToString(" · ") { (days, times) ->
            val hours = times.filterNotNull().sortedBy { it.startMinuteOfDay }
                .joinToString(", ") { "${time(it.startMinuteOfDay)}–${time(it.endMinuteOfDay)}" }
            if (hours.isEmpty()) days(days) else "${days(days)} $hours"
        }
    }

    /** "45 min", "3 h 12 min", "< 1 min". */
    fun duration(ms: Long): String {
        val minutes = (ms + 59_999) / 60_000
        return when {
            ms < 60_000 -> "< 1 min"
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0L -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }

    /** "22:00" later today, "Mon 09:00" on another day. */
    fun clockTime(wallMs: Long, clock: Clock): String {
        val zone = clock.zone()
        val at = Instant.ofEpochMilli(wallMs).atZone(zone)
        val today = Instant.ofEpochMilli(clock.nowWallMs()).atZone(zone).toLocalDate()
        val text = "%02d:%02d".format(at.hour, at.minute)
        return if (at.toLocalDate() == today) text else "${DAY_NAMES[at.dayOfWeek.value - 1]} $text"
    }

    /** For a block held by time alone: when the last of what holds it lets go. Null if a habit could, or it isn't held. */
    fun freeAtWall(engine: BlockEngine, world: World, block: Block, clock: Clock): Long? {
        val zone = clock.zone()
        val now = Instant.ofEpochMilli(clock.nowWallMs()).atZone(zone)
        when (engine.blockReason(world, block, clock)) {
            null, BlockReason.HABIT -> return null
            BlockReason.LOCKDOWN -> return clock.nowWallMs() + engine.lockdownRemainingMs(world, block.id, clock)
            else -> Unit
        }
        val breakEnd = engine.rowBreakRemainingMs(world, block, clock).takeIf { it > 0 }?.let { clock.nowWallMs() + it }
        val limitEnd = now.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            .takeIf { engine.limitHolds(world, block, clock) && engine.overrideRemainingMs(world, block.id, clock) == 0L }
        // Hours are asked about from when the rest lets go: a limit over at midnight frees nothing if hours start then.
        val after = listOfNotNull(breakEnd, limitEnd).maxOrNull() ?: clock.nowWallMs()
        val hoursEnd = if (engine.inBlockedHours(block, AtClock(after, zone))) {
            // Windows may follow one another (I30): walk forward a minute at a time, up to a week.
            val start = Instant.ofEpochMilli(after).atZone(zone).withSecond(0).withNano(0)
            (1..7 * 24 * 60).asSequence()
                .map { start.plusMinutes(it.toLong()) }
                .firstOrNull { t -> !engine.inBlockedHours(block, AtClock(t.toInstant().toEpochMilli(), zone)) }
                ?.toInstant()?.toEpochMilli()
        } else {
            null
        }
        return listOfNotNull(hoursEnd, breakEnd, limitEnd).maxOrNull()
    }

    /** What a block screen leads with for a block held by time: when it's back, and why it isn't now. */
    data class Back(val at: String, val why: String)

    /** Null while a habit could open [block] or nothing holds it: the screen shows the habits then. */
    fun back(engine: BlockEngine, world: World, block: Block, clock: Clock): Back? {
        val why = when (engine.blockReason(world, block, clock)) {
            null, BlockReason.HABIT -> return null
            BlockReason.LOCKDOWN -> "Locked down"
            BlockReason.HOURS -> "Blocked hours"
            BlockReason.BREAK -> "A break after ${duration(block.sessionMs)} in a row"
            BlockReason.LIMIT -> if (block.dailyAllowanceMs > 0) "Today's ${duration(block.dailyAllowanceMs)} is used up" else "Today's limit is used up"
        }
        val freeAt = freeAtWall(engine, world, block, clock) ?: return Back("Blocked for now", why)
        return Back(backAt(freeAt, clock), "$why · ${duration(freeAt - clock.nowWallMs())} to go")
    }

    /** The line under a block screen's headline: why it's held, or what a habit block asks for. */
    fun why(back: Back?, lead: Block?): String? = back?.why ?: lead?.let {
        if (it.state == BlockState.EARNED) "Its habits are done: it's ready to open." else "Do its habits to open it."
    }

    /** "Back at 14:00", "Back at midnight", "Back Fri 14:00". */
    private fun backAt(wallMs: Long, clock: Clock): String {
        val at = Instant.ofEpochMilli(wallMs).atZone(clock.zone())
        val today = Instant.ofEpochMilli(clock.nowWallMs()).atZone(clock.zone()).toLocalDate()
        return when {
            at.toLocalDate() == today -> "Back at ${clockTime(wallMs, clock)}"
            at.toLocalDate() == today.plusDays(1) && at.hour == 0 && at.minute == 0 -> "Back at midnight"
            else -> "Back ${clockTime(wallMs, clock)}"
        }
    }

    /** Which of [blockers] a block screen speaks for: what no habit lifts (I22, I44), the longest first. */
    fun leading(engine: BlockEngine, world: World, blockers: List<Block>, clock: Clock): Block? =
        blockers.filter { engine.blockReason(world, it, clock).let { why -> why != null && why != BlockReason.HABIT } }
            .maxByOrNull { freeAtWall(engine, world, it, clock) ?: Long.MAX_VALUE }
            ?: blockers.firstOrNull { it.mode == BlockMode.HABIT && it.state != BlockState.UNLOCKED }

    /** A clock stopped at [wall], to ask the engine about another moment. */
    private class AtClock(private val wall: Long, private val zone: ZoneId) : Clock {
        override fun nowMonotonicMs() = 0L
        override fun nowWallMs() = wall
        override fun zone() = zone
    }

    /** When a block's hours next begin, from the windows themselves: this runs on every redraw of a card. */
    fun nextHoursAtWall(block: Block, clock: Clock): Long? {
        val hours = block.blockedHours ?: block.schedule.takeIf { block.mode == BlockMode.SCHEDULE } ?: return null
        val zone = clock.zone()
        val nowMs = clock.nowWallMs()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val windows = listOf(hours.activeDays to hours.activeWindow) + hours.moreWindows.map { it.days to it.window }
        return (0L..7L).flatMap { ahead ->
            val date = today.plusDays(ahead)
            windows.filter { (days, _) -> date.dayOfWeek.value in days }.map { (_, window) ->
                date.atTime(LocalTime.ofSecondOfDay((window?.startMinuteOfDay ?: 0) * 60L)).atZone(zone).toInstant().toEpochMilli()
            }
        }.filter { it > nowMs }.minOrNull()
    }

    /** How [block] stands right now, the first thing its card says. */
    fun headline(engine: BlockEngine, world: World, block: Block, clock: Clock): Headline {
        if (!block.enabled) return Headline("Off", Tone.OFF)
        val blocked = { text: String -> Headline(text, Tone.BLOCKED) }
        val open = { text: String -> Headline(text, Tone.OPEN) }
        val until = { freeAtWall(engine, world, block, clock)?.let { clockTime(it, clock) } }
        return when (engine.blockReason(world, block, clock)) {
            BlockReason.LOCKDOWN -> blocked("Locked for ${duration(engine.lockdownRemainingMs(world, block.id, clock))}")
            BlockReason.HOURS -> blocked(until()?.let { "Blocked until $it" } ?: "Blocked")
            BlockReason.BREAK -> blocked("Resting · open in ${duration(engine.rowBreakRemainingMs(world, block, clock))}")
            BlockReason.LIMIT -> blocked("Limit reached · back tomorrow")
            BlockReason.HABIT -> when {
                block.state == BlockState.EARNED -> Headline("Ready to open", Tone.READY)
                block.hasLimit && block.limitComesFirst -> blocked("Time's up · do the habit to open it")
                else -> blocked("Blocked until you do the habit")
            }
            null -> openHeadline(engine, world, block, clock, open)
        }
    }

    private fun openHeadline(engine: BlockEngine, world: World, block: Block, clock: Clock, open: (String) -> Headline): Headline {
        val bought = engine.overrideRemainingMs(world, block.id, clock)
        val session = world.sessions[block.id]
        val pause = engine.pauseRemainingMs(world, clock)
        return when {
            block.mode == BlockMode.LOCKDOWN -> Headline("Lockdown over", Tone.OFF)
            pause > 0 -> open("Open · break ends in ${duration(pause)}")
            bought > 0 -> open("Open · ${duration(bought)} bought")
            session != null && session.selfPaced -> open("Open · ${duration(session.leftMs(clock))} of use left")
            session != null -> open("Open · ${duration(session.leftMs(clock))} left")
            block.hasLimit && block.dailyAllowanceMs > 0 ->
                open("Open · ${duration((block.dailyAllowanceMs - engine.allowanceUsedMs(world, block, clock)).coerceAtLeast(0))} left today")
            block.hasRowRule -> open("Open · ${duration((block.sessionMs - engine.rowUsedMs(world, block, clock)).coerceAtLeast(0))} left in a row")
            else -> nextHoursAtWall(block, clock)?.let { open("Open · blocks at ${clockTime(it, clock)}") } ?: open("Open")
        }
    }

    /** "5 blocked · 1 ready · 2 open", across the blocks that hold here; null with none. */
    fun summary(engine: BlockEngine, world: World, clock: Clock): String? {
        val tones = world.blocks.filter { it.enabled && world.appliesHere(it) }.map { headline(engine, world, it, clock).tone }
        val parts = listOf(Tone.BLOCKED to "blocked", Tone.READY to "ready", Tone.OPEN to "open").mapNotNull { (tone, word) ->
            tones.count { it == tone }.takeIf { it > 0 }?.let { "$it $word" }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}
