package com.xern.xrnrule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the corner clocks count down to on an open app or site ([BlockEngine.timeLeft]), the same
 * on the phone and the laptop: whichever comes first of what runs out with use and what comes by
 * the clock, blocked hours beginning among them.
 */
class TimeLeftTest {
    private val engine = BlockEngine()
    private val minute = 60_000L

    private fun hours(startH: Int, endH: Int) = Schedule((1..7).toSet(), TimeWindow(startH * 60, endH * 60))

    /** [hour]:[minuteOfHour] UTC on the test day (BASE_WALL is 12:00). */
    private fun at(hour: Int, minuteOfHour: Int = 0) =
        FakeClock(wall = BASE_WALL + ((hour - 12) * 60L + minuteOfHour) * minute)

    private fun limited(daily: Long = 30 * minute, blockedHours: Schedule? = hours(18, 22)) =
        modeBlock("y", BlockMode.ALLOWANCE, targetSites = setOf("videos.example"), dailyAllowanceMs = daily).copy(blockedHours = blockedHours)

    @Test
    fun blockedHoursComingBeforeTheLimitAreWhatIsLeft() {
        // 30 min a day, blocked 18:00-22:00, asked at 17:50: the clock said 30:00 and never warned.
        val clock = at(17, 50)
        val world = buildWorld(listOf(limited()), clock = clock)
        assertEquals(TimeLeft(10 * minute, ClockKind.HOURS, "y"), engine.timeLeft(world, "browser", "videos.example", clock))
    }

    @Test
    fun aLimitRunningOutFirstStillLeads() {
        val clock = at(17, 0)
        val world = buildWorld(listOf(limited(daily = 20 * minute)), clock = clock)
        assertEquals(TimeLeft(20 * minute, ClockKind.LIMIT, "y"), engine.timeLeft(world, "browser", "videos.example", clock))
    }

    @Test
    fun aRowRuleRunningOutFirstStillLeads() {
        val clock = at(17, 50)
        val block = limited().copy(sessionMs = 5 * minute, sessionBreakMs = 15 * minute)
        val world = buildWorld(listOf(block), clock = clock)
        assertEquals(ClockKind.ROW, engine.timeLeft(world, "browser", "videos.example", clock)?.kind)
    }

    @Test
    fun aScheduleStartingCountsDown() {
        val clock = at(17, 55)
        val block = modeBlock("s", BlockMode.SCHEDULE, targetPackages = setOf("com.game"), schedule = hours(18, 22))
        val world = buildWorld(listOf(block), clock = clock)
        assertEquals(TimeLeft(5 * minute, ClockKind.HOURS, "s"), engine.timeLeft(world, "com.game", null, clock))
    }

    @Test
    fun aHabitUnlockEndsAtTheHoursIfTheyComeFirst() {
        val clock = at(17, 58)
        val block = checklistBlock("h", unlockMs = 10 * minute).copy(blockedHours = hours(18, 22))
        var world = buildWorld(listOf(block), mapOf("h" to items("h", 1)), clock)
        world = engine.reduce(world, BlockEvent.ToggleChecklistItem("h-i0", true), clock).world
        world = engine.reduce(world, BlockEvent.Redeem("h"), clock).world
        assertEquals(TimeLeft(2 * minute, ClockKind.HOURS, "h"), engine.timeLeft(world, "com.target", null, clock))
    }

    @Test
    fun aBreakHoldsTheHoursOffUntilItEnds() {
        val clock = at(17, 55)
        val world = engine.reduce(buildWorld(listOf(limited()), clock = clock), BlockEvent.StartPause(), clock).world
        // The break lifts the block until 18:10, so that's when it closes.
        assertEquals(TimeLeft(15 * minute, ClockKind.HOURS, "y"), engine.timeLeft(world, "browser", "videos.example", clock))
    }

    @Test
    fun hoursADayOrMoreAwayAreNoClock() {
        val clock = at(12, 0)
        val twoDaysOn = Schedule(setOf(clock.localDate().plusDays(2).dayOfWeek.value), TimeWindow(9 * 60, 10 * 60))
        val block = modeBlock("s", BlockMode.SCHEDULE, targetPackages = setOf("com.game"), schedule = twoDaysOn)
        assertNull(engine.timeLeft(buildWorld(listOf(block), clock = clock), "com.game", null, clock))
    }

    @Test
    fun aWindowThatNeverHoldsBeginsNothing() {
        val clock = at(17, 0)
        val block = modeBlock("s", BlockMode.SCHEDULE, targetPackages = setOf("com.game"), schedule = Schedule((1..7).toSet(), TimeWindow(18 * 60, 18 * 60)))
        assertNull(engine.timeLeft(buildWorld(listOf(block), clock = clock), "com.game", null, clock))
    }

    @Test
    fun theExtensionIsToldTheSame() {
        val clock = at(17, 50)
        val world = buildWorld(listOf(limited()), clock = clock)
        val verdict = ExtensionApi.verdict(engine, world, "www.videos.example", clock, page = null)
        assertEquals(ExtensionApi.Left(10 * minute, ClockKind.HOURS), verdict.left)
    }

    @Test
    fun theCardSaysWhenTheHoursBeginBeforeTheLimitRunsOut() {
        val soon = at(17, 50)
        val block = limited()
        assertEquals("Open · blocks at 18:00", BlockTexts.headline(engine, buildWorld(listOf(block), clock = soon), block, soon).text)
        val early = at(12, 0)
        assertEquals("Open · 30 min left today", BlockTexts.headline(engine, buildWorld(listOf(block), clock = early), block, early).text)
    }
}
