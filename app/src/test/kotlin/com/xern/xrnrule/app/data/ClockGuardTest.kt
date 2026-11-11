package com.xern.xrnrule.app.data

import com.xern.xrnrule.domain.Clock
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ClockGuardTest {
    private val start = Instant.parse("2026-01-15T12:00:00Z").toEpochMilli()

    /** The phone: its raw clocks, automatic time off, and which boot it's in. */
    private class Phone(var wall: Long) : Clock, TimeSettings {
        var mono = 0L
        var boot: Int? = 1
        override fun nowMonotonicMs() = mono
        override fun nowWallMs() = wall
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun automaticTime() = false
        override fun automaticTimeZone() = true
        override fun bootCount() = boot

        fun run(ms: Long) {
            mono += ms
            wall += ms
        }

        /** Off for [offMs], then up again for [upMs] of the new boot. */
        fun reboot(offMs: Long, upMs: Long) {
            boot = boot?.plus(1)
            mono = upMs
            wall += offMs + upMs
        }
    }

    @Test
    fun aClockSetByHandIsTakenBackOut() {
        val phone = Phone(start).apply { mono = 60_000 }
        val guard = ClockGuard(MemoryPrefs(), phone, phone)
        phone.run(60_000)
        phone.wall += 2 * HOUR
        guard.check()
        assertEquals(2 * HOUR, guard.skewMs)
        assertEquals(start + 60_000, guard.nowWallMs())
    }

    @Test
    fun aRebootIsNeverAClockSet() {
        // The baseline is written at a boot's first look, a minute in. The next boot's first look
        // came two minutes in: later than that, so it passed for the same boot, and the hour the
        // phone was off (and more) was booked as the clock set back by hand: the wrong day.
        val prefs = MemoryPrefs()
        val phone = Phone(start).apply { mono = 60_000 }
        ClockGuard(prefs, phone, phone)
        phone.run(10 * 60_000)
        phone.reboot(offMs = HOUR, upMs = 2 * 60_000)

        val after = ClockGuard(prefs, phone, phone)
        assertEquals(0L, after.skewMs)
        assertEquals(phone.wall, after.nowWallMs())

        // And a clock set by hand in the new boot still counts.
        phone.run(60_000)
        phone.wall -= 3 * HOUR
        after.check()
        assertEquals(-3 * HOUR, after.skewMs)
    }

    @Test
    fun withoutABootCountItGoesByTheMonotonicClock() {
        val prefs = MemoryPrefs()
        val phone = Phone(start).apply { mono = 5 * 60_000; boot = null }
        ClockGuard(prefs, phone, phone)
        phone.reboot(offMs = HOUR, upMs = 60_000)
        assertEquals("a boot younger than the last look is a new one", 0L, ClockGuard(prefs, phone, phone).skewMs)
    }

    private companion object {
        const val HOUR = 60L * 60_000
    }
}
