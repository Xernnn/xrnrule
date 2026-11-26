package com.xern.xrnrule.desktop

import com.xern.xrnrule.domain.Clock
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageLogTest {

    private class TestClock(private val start: ZonedDateTime) : Clock {
        var mono = 5_000_000L
        private val offset = start.toInstant().toEpochMilli() - mono
        override fun nowMonotonicMs() = mono
        override fun nowWallMs() = mono + offset
        override fun zone(): ZoneId = start.zone
    }

    private val zone = ZoneId.of("Australia/Sydney")
    private val day = LocalDate.of(2026, 11, 20)
    private val noon = day.atTime(12, 0).atZone(zone)

    private fun dir(): File = Files.createTempDirectory("usage").toFile()

    /** Moves the clock on [minutes], ticking every second as the daemon does. */
    private fun TestClock.pass(log: UsageLog, minutes: Double) {
        val until = mono + (minutes * 60_000).toLong()
        while (mono < until) {
            mono = minOf(until, mono + 1_000)
            log.tick()
        }
    }

    private fun Map<Long, UsageLog.Day>.on(date: LocalDate) = this[date.toEpochDay()] ?: UsageLog.Day()

    private fun min(m: Double) = (m * 60_000).toLong()

    @Test
    fun theAppInFrontAndTheSiteInItCount() {
        val clock = TestClock(noon)
        val log = UsageLog(File(dir(), "usage.json"), clock)
        log.show("zen", "www.youtube.com/watch")
        clock.pass(log, 10.0)
        log.show("steam", null)
        clock.pass(log, 5.0)
        log.show(null, null) // nothing in front
        clock.pass(log, 30.0)
        val today = log.snapshot().on(day)
        assertEquals(mapOf("zen" to min(10.0), "steam" to min(5.0)), today.apps)
        // The site is the browser's time, without "www." or its path.
        assertEquals(mapOf("youtube.com" to min(10.0)), today.sites)
        assertEquals(min(15.0), today.totalMs)
    }

    @Test
    fun theMinutesBeforeAnIdleNoticeAreTakenBack() {
        val clock = TestClock(noon)
        val log = UsageLog(File(dir(), "usage.json"), clock)
        log.show("zen", "example.org")
        clock.pass(log, 10.0)
        // The notice comes 3 minutes after the last input.
        log.idle(clock.mono - IdleWatch.IDLE_MS)
        clock.pass(log, 20.0)
        assertEquals(min(7.0), log.snapshot().on(day).apps["zen"])
        // Back at the keyboard: it counts again.
        log.active()
        clock.pass(log, 2.0)
        assertEquals(min(9.0), log.snapshot().on(day).apps["zen"])
        assertEquals(min(9.0), log.snapshot().on(day).sites["example.org"])
    }

    @Test
    fun anIdleNoticeAlsoTakesBackWhatFocusMovedToMeanwhile() {
        val clock = TestClock(noon)
        val log = UsageLog(File(dir(), "usage.json"), clock)
        log.show("zen", null)
        clock.pass(log, 5.0) // last input at 5
        clock.pass(log, 1.0)
        log.show("org.gnome.Calendar", null) // a reminder took focus by itself at 6
        clock.pass(log, 2.0)
        log.idle(clock.mono - IdleWatch.IDLE_MS) // at 8: idle since 5
        clock.pass(log, 10.0)
        val today = log.snapshot().on(day)
        assertEquals(min(5.0), today.apps["zen"])
        assertFalse("org.gnome.Calendar" in today.apps)
    }

    @Test
    fun onlyWhatIsSureIsStored_andAStoredFigureOnlyGrows() {
        val clock = TestClock(noon)
        val file = File(dir(), "usage.json")
        val log = UsageLog(file, clock)
        log.show("zen", null)
        clock.pass(log, 10.0)
        log.flush()
        // The last 3 minutes are shown, not stored: an idle notice may still take them back.
        assertEquals(min(10.0), log.snapshot().on(day).apps["zen"])
        assertEquals(min(7.0), UsageLog(file, clock).snapshot().on(day).apps["zen"])
        assertEquals(min(7.0), log.toSend().on(day).apps["zen"])

        log.idle(clock.mono - min(2.0)) // idle since 8, after what was stored
        log.flush()
        assertEquals(min(8.0), log.snapshot().on(day).apps["zen"])
        clock.pass(log, 5.0)
        log.flush()
        assertEquals(min(8.0), UsageLog(file, clock).snapshot().on(day).apps["zen"])
    }

    @Test
    fun stoppingStoresEverything() {
        val clock = TestClock(noon)
        val file = File(dir(), "usage.json")
        val log = UsageLog(file, clock)
        log.show("zen", "example.org")
        clock.pass(log, 4.0)
        log.stop()
        val again = UsageLog(file, clock).snapshot().on(day)
        assertEquals(min(4.0), again.apps["zen"])
        assertEquals(min(4.0), again.sites["example.org"])
    }

    @Test
    fun aSleepIsNoUse() {
        val clock = TestClock(noon)
        val log = UsageLog(File(dir(), "usage.json"), clock)
        log.show("zen", null)
        clock.pass(log, 5.0)
        clock.mono += min(60.0) // asleep an hour: the boot-time clock ran on
        // A window event comes in before the first tick after waking.
        log.show("steam", null)
        clock.pass(log, 1.0)
        val today = log.snapshot().on(day)
        assertEquals(min(5.0), today.apps["zen"])
        assertEquals(min(1.0), today.apps["steam"])
    }

    @Test
    fun aSleepWithTheSameAppInFrontIsNoUseEither() {
        val clock = TestClock(noon)
        val log = UsageLog(File(dir(), "usage.json"), clock)
        log.show("zen", null)
        clock.pass(log, 5.0)
        clock.mono += min(60.0)
        log.tick() // the first tick awake
        clock.pass(log, 2.0)
        assertEquals(min(7.0), log.snapshot().on(day).apps["zen"])
    }

    @Test
    fun timeGoesToTheDayItFellOn() {
        val clock = TestClock(day.atTime(23, 50).atZone(zone))
        val log = UsageLog(File(dir(), "usage.json"), clock)
        log.show("zen", null)
        clock.pass(log, 20.0)
        val days = log.snapshot()
        assertEquals(min(10.0), days.on(day).apps["zen"])
        assertEquals(min(10.0), days.on(day.plusDays(1)).apps["zen"])
    }

    @Test
    fun theServerGetsTodayYesterdayAndWhatChanged() {
        val clock = TestClock(day.minusDays(5).atTime(12, 0).atZone(zone))
        val log = UsageLog(File(dir(), "usage.json"), clock)
        log.show("zen", null)
        clock.pass(log, 10.0)
        log.show(null, null)
        clock.mono += 5 * 86_400_000L // five days on, the laptop off
        log.tick()
        log.flush()
        val first = log.toSend()
        assertEquals(setOf(day.minusDays(5).toEpochDay()), first.keys)
        log.sentOk(first)
        assertTrue(log.toSend().isEmpty()) // nothing new, and nothing today or yesterday
        log.show("steam", null)
        clock.pass(log, 10.0)
        log.flush()
        assertEquals(setOf(day.toEpochDay()), log.toSend().keys)
    }

    @Test
    fun daysPastWhatTheServerKeepsGo() {
        val clock = TestClock(noon)
        val file = File(dir(), "usage.json")
        val log = UsageLog(file, clock)
        log.show("zen", null)
        clock.pass(log, 5.0)
        log.stop()
        clock.mono += (UsageLog.KEEP_DAYS + 1) * 86_400_000L
        val later = UsageLog(file, clock)
        later.show("steam", null)
        clock.pass(later, 5.0)
        later.stop()
        assertEquals(setOf(day.plusDays(UsageLog.KEEP_DAYS + 1).toEpochDay()), UsageLog(file, clock).snapshot().keys)
    }

    @Test
    fun anUnreadableFileIsKeptAsideAndCountingStartsAfresh() {
        val d = dir()
        val file = File(d, "usage.json")
        file.writeText("{not json")
        val clock = TestClock(noon)
        val log = UsageLog(file, clock)
        assertTrue(log.snapshot().isEmpty())
        assertTrue(d.listFiles()!!.any { it.name.startsWith("usage.unreadable-") })
        log.show("zen", null)
        clock.pass(log, 4.0)
        log.stop()
        assertEquals(min(4.0), UsageLog(file, clock).snapshot().on(day).apps["zen"])
    }
}
