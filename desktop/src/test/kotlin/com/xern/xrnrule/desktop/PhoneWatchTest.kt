package com.xern.xrnrule.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneWatchTest {
    private val now = 10_000_000_000L
    private val hour = 3_600_000L

    @Test
    fun readsThePhoneOutOfTheServersAnswer() {
        assertEquals(1234L, PhoneWatch.seen("""{"laptop": 9, "phone": 1234}"""))
        assertNull(PhoneWatch.seen("""{"laptop": 9}"""))
        assertNull(PhoneWatch.seen("not json"))
        assertNull(PhoneWatch.seen("[]"))
        assertNull(PhoneWatch.seen("""{"phone": {"at": 1}}"""))
    }

    @Test
    fun aNightAsleepIsNotQuiet() {
        assertNull(PhoneWatch.quietMs(now - PhoneWatch.QUIET_MS + 1, now, now))
    }

    @Test
    fun halfADayIsQuiet() {
        assertEquals(PhoneWatch.QUIET_MS, PhoneWatch.quietMs(now - PhoneWatch.QUIET_MS, now, now))
    }

    @Test
    fun aLaptopThatJustWokeUpDoesntBlameThePhone() {
        val asked = now - 2 * PhoneWatch.CHECK_MS - 1
        assertNull(PhoneWatch.quietMs(now - 20 * hour, asked, now))
    }

    @Test
    fun aPhoneNeverSeenIsNotQuiet() {
        assertNull(PhoneWatch.quietMs(null, now, now))
    }

    @Test
    fun theTextSaysHowLong() {
        assertTrue(PhoneWatch.text(14 * hour + 5).contains("14 hours"))
    }
}
