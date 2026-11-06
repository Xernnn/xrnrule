package com.xern.xrnrule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlockTextsTest {
    private val engine = BlockEngine()
    private val all = (1..7).toSet()
    private fun window(startHour: Int, endHour: Int) = TimeWindow(startHour * 60, endHour * 60)

    private fun strict(id: String = "s", schedule: Schedule = Schedule.ALWAYS) =
        modeBlock(id, BlockMode.SCHEDULE, schedule = schedule)

    @Test
    fun daysReadAsNamesAndRanges() {
        assertEquals("Every day", BlockTexts.days(all))
        assertEquals("Weekdays", BlockTexts.days((1..5).toSet()))
        assertEquals("Weekends", BlockTexts.days(setOf(6, 7)))
        assertEquals("Mon–Thu", BlockTexts.days((1..4).toSet()))
        assertEquals("Mon, Tue", BlockTexts.days(setOf(1, 2)))
        assertEquals("Mon–Wed, Sat", BlockTexts.days(setOf(1, 2, 3, 6)))
    }

    @Test
    fun scheduleJoinsDaysWithTheSameTimes() {
        val week = (2..7).map { DayWindow(setOf(it), window(21, 22)) }
        assertEquals("Every day 21:00–22:00", BlockTexts.schedule(Schedule(setOf(1), window(21, 22), week)))
    }

    @Test
    fun scheduleJoinsTimesOnTheSameDays() {
        val s = Schedule(all, window(21, 23), listOf(DayWindow(all, window(8, 9))))
        assertEquals("Every day 08:00–09:00, 21:00–23:00", BlockTexts.schedule(s))
    }

    @Test
    fun scheduleKeepsDifferentDaysApart() {
        val s = Schedule((1..5).toSet(), window(9, 17), listOf(DayWindow(setOf(6), window(10, 12))))
        assertEquals("Weekdays 09:00–17:00 · Sat 10:00–12:00", BlockTexts.schedule(s))
    }

    @Test
    fun scheduleAllDay() {
        assertEquals("All the time", BlockTexts.schedule(Schedule.ALWAYS))
        assertEquals("Weekends", BlockTexts.schedule(Schedule(setOf(6, 7), null)))
    }

    @Test
    fun durations() {
        assertEquals("< 1 min", BlockTexts.duration(30_000))
        assertEquals("45 min", BlockTexts.duration(45 * 60_000L))
        assertEquals("2 h", BlockTexts.duration(120 * 60_000L))
        assertEquals("3 h 12 min", BlockTexts.duration(192 * 60_000L))
    }

    @Test
    fun headlineOfBlockedHoursSaysUntilWhen() {
        val clock = FakeClock() // 12:00 UTC
        val block = strict(schedule = Schedule(all, window(9, 17)))
        val world = buildWorld(listOf(block), clock = clock)
        assertEquals(BlockTexts.Headline("Blocked until 17:00", BlockTexts.Tone.BLOCKED), BlockTexts.headline(engine, world, block, clock))
    }

    @Test
    fun headlineOutsideItsHoursSaysWhenTheyStart() {
        val clock = FakeClock()
        val block = strict(schedule = Schedule(all, window(21, 23)))
        val world = buildWorld(listOf(block), clock = clock)
        assertEquals(BlockTexts.Headline("Open · blocks at 21:00", BlockTexts.Tone.OPEN), BlockTexts.headline(engine, world, block, clock))
    }

    @Test
    fun headlineOfALimit() {
        val clock = FakeClock()
        val block = modeBlock("a", BlockMode.ALLOWANCE, dailyAllowanceMs = 30 * 60_000L)
        var world = buildWorld(listOf(block), clock = clock, foregroundPkg = "com.target")
        assertEquals("Open · 30 min left today", BlockTexts.headline(engine, world, block, clock).text)
        clock.advance(31 * 60_000L)
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        val now = BlockTexts.headline(engine, world, world.block("a")!!, clock)
        assertEquals(BlockTexts.Headline("Limit reached · back tomorrow", BlockTexts.Tone.BLOCKED), now)
    }

    @Test
    fun aBreakIsntWhyALockedBlockIsOpen() {
        val clock = FakeClock()
        val block = modeBlock("a", BlockMode.ALLOWANCE, dailyAllowanceMs = 30L * 60_000)
        val resting = engine.reduce(buildWorld(listOf(block), clock = clock), BlockEvent.StartPause(), clock).world
        assertEquals("Open · break ends in 15 min", BlockTexts.headline(engine, resting, block, clock).text)

        val at = clock.nowWallMs()
        val locked = block.copy(lock = BlockLock(untilWall = at + 60_000, setAtWall = at))
        val world = resting.copy(blocks = listOf(locked))
        assertEquals("Open · 30 min left today", BlockTexts.headline(engine, world, locked, clock).text)
    }

    @Test
    fun headlineOfAHabitBlock() {
        val clock = FakeClock()
        val armed = checklistBlock("c")
        assertEquals("Blocked until you do the habit", BlockTexts.headline(engine, buildWorld(listOf(armed), clock = clock), armed, clock).text)
        val earned = armed.copy(state = BlockState.EARNED)
        assertEquals(
            BlockTexts.Headline("Ready to open", BlockTexts.Tone.READY),
            BlockTexts.headline(engine, buildWorld(listOf(earned), clock = clock), earned, clock),
        )
    }

    /** A limit used up, its hours as given: the state a block screen most often has to explain. */
    private fun spentLimit(clock: FakeClock, hours: Schedule? = null): Pair<World, Block> {
        val block = modeBlock("a", BlockMode.ALLOWANCE, dailyAllowanceMs = 30 * 60_000L).copy(blockedHours = hours)
        var world = buildWorld(listOf(block), clock = clock, foregroundPkg = "com.target")
        clock.advance(31 * 60_000L)
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        return world to world.block("a")!!
    }

    @Test
    fun aLimitOverAtMidnightStillWaitsOutHoursThatStartThen() {
        val clock = FakeClock() // 12:31 by then, a Thursday, outside its hours: they'd have stopped the limit counting
        val (world, block) = spentLimit(clock, Schedule(all, window(0, 6)))
        assertEquals("Fri 06:00", BlockTexts.clockTime(BlockTexts.freeAtWall(engine, world, block, clock)!!, clock))
    }

    @Test
    fun blockedHoursSayWhenTheyAreBack() {
        val clock = FakeClock() // 12:00 UTC
        val block = strict(schedule = Schedule(all, window(9, 17)))
        val world = buildWorld(listOf(block), clock = clock)
        assertEquals(BlockTexts.Back("Back at 17:00", "Blocked hours · 5 h to go"), BlockTexts.back(engine, world, block, clock))
    }

    @Test
    fun aSpentLimitIsBackAtMidnight() {
        val clock = FakeClock()
        val (world, block) = spentLimit(clock)
        assertEquals(BlockTexts.Back("Back at midnight", "Today's 30 min is used up · 11 h 29 min to go"), BlockTexts.back(engine, world, block, clock))
    }

    @Test
    fun aSpentLimitBeforeHoursIsBackWhenBothAreOver() {
        val clock = FakeClock()
        val (world, block) = spentLimit(clock, Schedule(all, window(0, 6)))
        assertEquals("Back Fri 06:00", BlockTexts.back(engine, world, block, clock)!!.at)
    }

    @Test
    fun aHabitHasNoTimeToBeBackBy() {
        val clock = FakeClock()
        val block = timerBlock("h")
        assertNull(BlockTexts.back(engine, buildWorld(listOf(block), clock = clock), block, clock))
    }

    @Test
    fun aHabitBlockSaysWhatItWants() {
        val block = timerBlock("h")
        assertEquals("Do its habits to open it.", BlockTexts.why(null, block))
        assertEquals("Its habits are done: it's ready to open.", BlockTexts.why(null, block.copy(state = BlockState.EARNED)))
        assertNull(BlockTexts.why(null, null))
    }

    @Test
    fun anOpenBlockHasNothingToSay() {
        val clock = FakeClock()
        val block = strict(schedule = Schedule(all, window(21, 23)))
        assertNull(BlockTexts.back(engine, buildWorld(listOf(block), clock = clock), block, clock))
    }

    @Test
    fun theScreenSpeaksForWhatHoldsLongest() {
        val clock = FakeClock() // 12:00 UTC
        val soon = strict("soon", Schedule(all, window(9, 13)))
        val late = strict("late", Schedule(all, window(9, 20)))
        val habit = timerBlock("habit")
        val world = buildWorld(listOf(habit, soon, late), clock = clock)
        assertEquals("late", BlockTexts.leading(engine, world, listOf(habit, soon, late), clock)?.id)
        assertEquals("habit", BlockTexts.leading(engine, world, listOf(habit), clock)?.id)
        assertNull(BlockTexts.leading(engine, world, emptyList(), clock))
    }

    @Test
    fun anOffBlockIsOff() {
        val clock = FakeClock()
        val off = strict().copy(enabled = false)
        assertEquals(BlockTexts.Headline("Off", BlockTexts.Tone.OFF), BlockTexts.headline(engine, buildWorld(listOf(off), clock = clock), off, clock))
    }

    @Test
    fun nextHoursLookAtEveryWindow() {
        val clock = FakeClock() // 12:00 UTC, a Thursday
        val block = strict(schedule = Schedule(setOf(1), window(9, 10), listOf(DayWindow(setOf(5), window(18, 19)))))
        assertEquals("Fri 18:00", BlockTexts.clockTime(BlockTexts.nextHoursAtWall(block, clock)!!, clock))
        assertNull(BlockTexts.nextHoursAtWall(modeBlock("a", BlockMode.ALLOWANCE), clock))
    }

    @Test
    fun summaryCountsOnlyWhatHoldsHere() {
        val clock = FakeClock()
        val blocked = strict("1")
        val open = strict("2", schedule = Schedule(all, window(21, 23)))
        val off = strict("3").copy(enabled = false)
        val laptop = strict("4").copy(devices = Devices.LAPTOP)
        val world = buildWorld(listOf(blocked, open, off, laptop), clock = clock).copy(deviceKind = DeviceKind.PHONE)
        assertEquals("1 blocked · 1 open", BlockTexts.summary(engine, world, clock))
        assertNull(BlockTexts.summary(engine, buildWorld(listOf(off), clock = clock), clock))
    }
}
