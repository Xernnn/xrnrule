package com.xern.xrnrule.app.data

import com.xern.xrnrule.domain.Clock
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ClockGuardTest {
    private val start = Instant.parse("2026-01-15T12:00:00Z").toEpochMilli()

    /**
     * The phone: its raw clocks, automatic time (off unless a test turns it on), which boot it's in,
     * and the real time, which Android knows only once it has fetched it from the network ([online]).
     */
    private class Phone(var wall: Long) : Clock, TimeSettings {
        var mono = 0L
        var boot: Int? = 1
        var automatic = false
        var real = wall
        var online = false
        override fun nowMonotonicMs() = mono
        override fun nowWallMs() = wall
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun automaticTime() = automatic
        override fun automaticTimeZone() = true
        override fun bootCount() = boot
        override fun networkTimeMs() = real.takeIf { online }

        fun run(ms: Long) {
            mono += ms
            wall += ms
            real += ms
        }

        /** What Android does with automatic time on once it has the network's time. */
        fun setByNetwork() {
            online = true
            if (automatic) wall = real
        }

        /** Off for [offMs], then up again for [upMs] of the new boot. */
        fun reboot(offMs: Long, upMs: Long) {
            boot = boot?.plus(1)
            mono = upMs
            wall += offMs + upMs
            real += offMs + upMs
            online = false
        }
    }

    @Test
    fun automaticTimeBackOnWithNoNetworkKeepsTheSkew() {
        // Airplane mode, automatic time off, the date a day ahead, then automatic time on again:
        // with no network Android leaves the clock where it was set. The skew went with the
        // setting, and the hand-set day became the engine's: a new day of limits, a lock run out.
        val prefs = MemoryPrefs()
        val phone = Phone(start).apply { mono = 60_000 }
        val guard = ClockGuard(prefs, phone, phone)
        phone.run(60_000)
        phone.wall += DAY
        guard.check()
        assertEquals(DAY, guard.skewMs)

        phone.automatic = true
        phone.run(60_000)
        guard.check()
        assertEquals("the clock is still the hand-set one", DAY, guard.skewMs)
        assertEquals(phone.real, guard.nowWallMs())
        assertEquals("and after a restart", DAY, ClockGuard(prefs, phone, phone).skewMs)

        // Off airplane mode: Android sets the clock back, and the clock is the time again.
        phone.run(60_000)
        phone.setByNetwork()
        guard.check()
        assertEquals(0L, guard.skewMs)
        assertEquals(phone.real, guard.nowWallMs())
    }

    @Test
    fun theClockComingBackToTheTimeKeptEndsTheSkew() {
        val phone = Phone(start).apply { mono = 60_000 }
        val guard = ClockGuard(MemoryPrefs(), phone, phone)
        phone.run(60_000)
        phone.wall += 2 * HOUR
        guard.check()
        phone.run(60_000)
        phone.wall -= 2 * HOUR - 3_000 // set back by hand, three seconds out
        guard.check()
        assertEquals(0L, guard.skewMs)
    }

    @Test
    fun aClockSetWhileXrnruleWasStoppedIsCaughtByTheNetworkTime() {
        // Force-stopped; automatic time off, a day ahead, automatic time on; xrnrule opened again.
        // The jump is seen with automatic time on, but the network's time says it isn't the time.
        val prefs = MemoryPrefs()
        val phone = Phone(start).apply { mono = 60_000; online = true }
        ClockGuard(prefs, phone, phone)
        phone.run(60_000)
        phone.wall += DAY
        phone.automatic = true
        phone.run(60_000)
        val guard = ClockGuard(prefs, phone, phone)
        assertEquals(DAY, guard.skewMs)
        assertEquals(phone.real, guard.nowWallMs())
    }

    @Test
    fun aClockSetAheadAcrossARebootIsCaughtOnceTheNetworkTimeIsKnown() {
        val prefs = MemoryPrefs()
        val phone = Phone(start).apply { mono = 60_000 }
        ClockGuard(prefs, phone, phone)
        // Force-stopped, automatic time off, a day ahead, rebooted in airplane mode: nothing to
        // compare against, so the hand-set day stands for now.
        phone.run(60_000)
        phone.wall += DAY
        phone.reboot(offMs = 60_000, upMs = 60_000)
        val guard = ClockGuard(prefs, phone, phone)
        assertEquals(0L, guard.skewMs)

        // Off airplane mode, automatic time still off: Android fetches the time and leaves the clock.
        phone.run(60_000)
        phone.setByNetwork()
        guard.check()
        assertEquals(DAY, guard.skewMs)
        assertEquals(phone.real, guard.nowWallMs())
    }

    @Test
    fun theNetworkTimeAgreeingIsTheTime() {
        // The phone took its time from the mobile network, some seconds off the NTP time.
        val phone = Phone(start).apply { mono = 60_000; automatic = true; online = true }
        phone.wall += 8_000
        val guard = ClockGuard(MemoryPrefs(), phone, phone)
        assertEquals(0L, guard.skewMs)
        assertEquals(phone.wall, guard.nowWallMs())
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
        const val DAY = 24 * HOUR
    }
}
