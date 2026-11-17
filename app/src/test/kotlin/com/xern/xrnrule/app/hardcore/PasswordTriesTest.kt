package com.xern.xrnrule.app.hardcore

import com.xern.xrnrule.app.data.MemoryPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordTriesTest {
    private var elapsed = 5_000_000L
    private val prefs = MemoryPrefs()
    private val tries = PasswordTries(prefs, { elapsed })

    @Test
    fun wrongPasswordsCostTimeAfterTheThird() {
        // Every try used to be free, in the loosen dialog and on the Settings gate: a short
        // password could be guessed by hand.
        repeat(3) { assertFalse(tries.attempt { false }) }
        assertEquals(PasswordTries.FIRST_WAIT_MS, tries.waitMs())
        var asked = false
        assertFalse("not tried during the wait, even if right", tries.attempt { asked = true; true })
        assertFalse(asked)

        elapsed += PasswordTries.FIRST_WAIT_MS
        assertFalse(tries.attempt { false })
        assertEquals("twice as long", 2 * PasswordTries.FIRST_WAIT_MS, tries.waitMs())
        assertEquals("kept across a restart", 2 * PasswordTries.FIRST_WAIT_MS, PasswordTries(prefs, { elapsed }).waitMs())

        elapsed += 2 * PasswordTries.FIRST_WAIT_MS
        assertTrue(tries.attempt { true })
        assertEquals("a right one starts the count again", 0L, tries.waitMs())
        assertFalse(tries.attempt { false })
        assertEquals(0L, tries.waitMs())
    }

    @Test
    fun aRebootDoesNotShortenTheWait() {
        repeat(3) { tries.attempt { false } }
        elapsed += PasswordTries.FIRST_WAIT_MS
        tries.attempt { false }
        // Rebooted: the monotonic clock starts again, below the last wrong try.
        elapsed = 10_000L
        assertEquals(2 * PasswordTries.FIRST_WAIT_MS, tries.waitMs())
        elapsed += 2 * PasswordTries.FIRST_WAIT_MS
        assertEquals(0L, tries.waitMs())
    }

    @Test
    fun theWaitStopsGrowingAtAnHour() {
        assertEquals(PasswordTries.MAX_WAIT_MS, PasswordTries.waitAfter(40))
        assertEquals("Wait 30 s.", PasswordTries.waitText(30_000))
        assertEquals("Wait 60 min.", PasswordTries.waitText(PasswordTries.MAX_WAIT_MS))
    }
}
