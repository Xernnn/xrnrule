package com.xern.xrnrule.domain

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When blocked hours let go ([BlockTexts.freeAtWall]): the same moment the old minute-by-minute
 * walk found, without the walk. It ran on every redraw of a block screen, once a second, and
 * tried up to 10,080 minutes each time.
 */
class FreeAtTest {
    private val engine = BlockEngine()

    private fun w(startH: Int, startM: Int, endH: Int, endM: Int) = TimeWindow(startH * 60 + startM, endH * 60 + endM)

    private val schedules = listOf(
        // Mornings but on Sundays, evenings, and late on Sundays.
        Schedule((1..6).toSet(), w(0, 0, 14, 0), listOf(DayWindow((1..7).toSet(), w(18, 0, 22, 0)), DayWindow(setOf(7), w(23, 0, 2, 0)))),
        Schedule((1..5).toSet(), null), // whole weekdays
        Schedule((1..7).toSet(), w(22, 0, 7, 0)), // every night
        Schedule((1..7).toSet(), w(18, 0, 24, 0), listOf(DayWindow((1..7).toSet(), w(0, 0, 14, 0)))), // evening into morning
        Schedule(setOf(6, 7), w(1, 30, 2, 30), listOf(DayWindow(setOf(7), w(2, 45, 3, 15)))), // around the clock changes
    )

    private val zones = listOf("UTC", "Australia/Sydney", "Europe/Berlin", "America/New_York").map(ZoneId::of)

    /** The walk it replaced: a minute at a time from the minute it holds at, up to a week on. */
    private fun walked(block: Block, after: Long, zone: ZoneId): Long? {
        val start = Instant.ofEpochMilli(after).atZone(zone).withSecond(0).withNano(0)
        return (1..7 * 24 * 60).asSequence()
            .map { start.plusMinutes(it.toLong()) }
            .firstOrNull { t -> !engine.inBlockedHours(block, FakeClock(wall = t.toInstant().toEpochMilli(), zoneId = zone)) }
            ?.toInstant()?.toEpochMilli()
    }

    @Test
    fun blockedHoursEndWhenTheWalkSaidTheyDid() {
        // Three weeks either side of the clock changes in both hemispheres, every 37 minutes.
        val starts = listOf("2026-03-20T00:00:00Z", "2026-09-25T00:00:00Z", "2026-10-20T00:00:00Z").map { Instant.parse(it).toEpochMilli() }
        var compared = 0
        for (zone in zones) for (schedule in schedules) for (from in starts) {
            val block = modeBlock("s", BlockMode.SCHEDULE, schedule = schedule)
            var at = from + 13_000 // not on a minute
            while (at < from + 21 * DAY_MS) {
                val clock = FakeClock(wall = at, zoneId = zone)
                if (engine.inBlockedHours(block, clock)) {
                    val world = buildWorld(listOf(block), clock = clock)
                    assertEquals("$zone ${Instant.ofEpochMilli(at)} $schedule", walked(block, at, zone), BlockTexts.freeAtWall(engine, world, block, clock))
                    compared++
                }
                at += 37 * 60_000L
            }
        }
        assertTrue(compared > 5_000)
    }

    @Test
    fun aWeekOfHoursIsWorkedOutQuickly() {
        // Monday 00:01 of whole days off Monday to Saturday: free on Sunday, 8,639 minutes on.
        val zone = ZoneId.of("Europe/Berlin")
        val monday = Instant.parse("2026-01-11T23:01:00Z").toEpochMilli()
        val block = modeBlock("s", BlockMode.SCHEDULE, schedule = Schedule((1..6).toSet(), null))
        val clock = FakeClock(wall = monday, zoneId = zone)
        val world = buildWorld(listOf(block), clock = clock)
        assertEquals(Instant.parse("2026-01-17T23:00:00Z").toEpochMilli(), BlockTexts.freeAtWall(engine, world, block, clock))
        val began = System.nanoTime()
        repeat(5_000) { BlockTexts.freeAtWall(engine, world, block, clock) }
        val ms = (System.nanoTime() - began) / 1_000_000
        assertTrue("5,000 block-screen redraws took $ms ms", ms < 1_500)
    }
}
