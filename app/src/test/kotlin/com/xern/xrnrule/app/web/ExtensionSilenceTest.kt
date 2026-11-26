package com.xern.xrnrule.app.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** I49: the extension silent in its own browser, counted across returns. */
class ExtensionSilenceTest {

    private fun looks(silence: ExtensionSilence, from: Long, until: Long, silentInFront: Boolean = true) {
        var t = from
        while (t <= until) {
            silence.look(silentInFront, t)
            t += 5_000
        }
    }

    @Test
    fun silenceInFrontAddsUpAcrossReturns() {
        val silence = ExtensionSilence()
        // The extension turned off in the browser, and home every 100 s or so.
        looks(silence, 0, 100_000)
        silence.look(false, 105_000)
        assertFalse(silence.spent)
        looks(silence, 200_000, 220_000)
        assertTrue("100 s and then 20 s: two minutes in all", silence.spent)
    }

    @Test
    fun theExtensionNamingASiteThereStartsItAgain() {
        val silence = ExtensionSilence()
        looks(silence, 0, 120_000)
        assertTrue(silence.spent)
        silence.spoke()
        assertFalse(silence.spent)
    }

    @Test
    fun theScreenOffInBetweenIsntSilence() {
        val silence = ExtensionSilence()
        silence.look(true, 0)
        silence.look(true, 60L * 60_000) // an hour later, the screen off in between
        assertFalse(silence.spent)
    }
}
