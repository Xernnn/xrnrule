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
