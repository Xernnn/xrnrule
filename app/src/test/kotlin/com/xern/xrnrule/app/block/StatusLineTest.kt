package com.xern.xrnrule.app.block

import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockEngine
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.BlockTexts
import com.xern.xrnrule.domain.Clock
import com.xern.xrnrule.domain.Schedule
import com.xern.xrnrule.domain.TimeWindow
import com.xern.xrnrule.domain.Trigger
import com.xern.xrnrule.domain.TriggerType
import com.xern.xrnrule.domain.TriggerUnit
import com.xern.xrnrule.domain.World
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** The ongoing notification's line ([Notifications.statusOf]), made up blocks only. */
class StatusLineTest {
    private val engine = BlockEngine()
    private val minute = 60_000L

    private class At(private val wall: Long) : Clock {
        override fun nowMonotonicMs() = 0L
        override fun nowWallMs() = wall
        override fun zone(): ZoneId = ZoneId.of("UTC")
    }

    private fun at(hour: Int, minuteOfHour: Int) = At(Instant.parse("2026-01-15T00:00:00Z").toEpochMilli() + (hour * 60L + minuteOfHour) * minute)

    private val videos = Block(
        id = "v",
        name = "Videos",
        targetPackages = setOf("com.example.videos"),
        trigger = Trigger(TriggerType.TIMER, 0, TriggerUnit.MS),
        mode = BlockMode.ALLOWANCE,
        dailyAllowanceMs = 30 * minute,
        blockedHours = Schedule((1..7).toSet(), TimeWindow(18 * 60, 22 * 60)),
    )

    private fun line(clock: Clock, front: String?) =
        Notifications.statusOf(World(blocks = listOf(videos)), engine, clock, "xrnrule", front) { BlockTexts.clockTime(clock.nowWallMs() + it, clock) }

    @Test
    fun theAppInFrontCountsDownToItsBlockedHours() {
        // At 17:50 it said "Nothing is blocked right now", and at 18:00 the block screen came mid-video.
        val status = line(at(17, 50), front = "com.example.videos")
        assertEquals(Notifications.Status("Videos", "Blocks at 18:00", endsInMs = 10 * minute), status)
    }

    @Test
    fun theAppInFrontShowsItsLimitWhenThatRunsOutFirst() {
        val status = line(at(12, 0), front = "com.example.videos")
        assertEquals(Notifications.Status("Videos", "30 min left today", endsInMs = 30 * minute), status)
    }

    @Test
    fun withNothingInFrontItSaysWhatHolds() {
        assertEquals("Nothing is blocked right now", line(at(17, 50), front = null).text)
        assertEquals("1 block active", line(at(19, 0), front = null).text)
    }
}
