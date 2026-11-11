package com.xern.xrnrule.app.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** I49: which reports say what browser the extension lives in. */
class ExtensionVoiceTest {

    private val settle = 3_000L
    private val voice = ExtensionVoice()

    @Test
    fun aWebsiteReportedWellAfterTheBrowserCameInFrontNamesIt() {
        val since = 10_000L
        voice.report(site = true, standIn = false, atElapsed = since + 500) // the last browser's, still
        assertFalse(voice.namedSiteSince(since + settle))
        voice.report(site = true, standIn = false, atElapsed = since + 3_000)
        assertTrue(voice.namedSiteSince(since + settle))

        voice.report(site = false, standIn = false, atElapsed = since + 5_500) // a page that is no website
        assertFalse(voice.namedSiteSince(since + settle))
    }

    @Test
    fun theBlockPageOpenedInABrowserWithoutTheExtensionDoesNotNameIt() {
        // The extension's browser reports a site. Then another browser comes in front and opens
        // the app's block page (its address is in every blocked tab), which reports every second
        // by itself, extension or not.
        voice.report(site = true, standIn = false, atElapsed = 1_000)
        val since = 10_000L
        (1..10).forEach { voice.report(site = true, standIn = true, atElapsed = since + it * 1_000L) }
        assertFalse("or that browser would count as the extension's and never be blocked", voice.namedSiteSince(since + settle))

        // Back in the extension's browser, on a website: that one is named, as before.
        val back = 30_000L
        voice.report(site = true, standIn = false, atElapsed = back + 4_000)
        assertTrue(voice.namedSiteSince(back + settle))
    }
}
