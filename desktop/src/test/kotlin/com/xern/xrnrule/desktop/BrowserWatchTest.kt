package com.xern.xrnrule.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserWatchTest {
    @Test
    fun i49_aBrowserWithoutTheExtensionGoesOnceOneIsKnown() {
        val watch = BrowserWatch(known = setOf("helium"))
        watch.front("firefox", browser = true, nowMs = 1_000)
        assertNull(watch.toClose(1_000 + BrowserWatch.OTHER_GRACE_MS - 1))
        assertEquals("firefox", watch.toClose(1_000 + BrowserWatch.OTHER_GRACE_MS))
    }

    @Test
    fun i49_nothingGoesWhileNoBrowserIsKnown() {
        val watch = BrowserWatch()
        watch.front("firefox", browser = true, nowMs = 0)
        assertNull(watch.toClose(10L * 60_000))
    }

    @Test
    fun i49_otherAppsAndAnEmptyDesktopAreLeftAlone() {
        val watch = BrowserWatch(known = setOf("helium"))
        watch.front("konsole", browser = false, nowMs = 0)
        assertNull(watch.toClose(10L * 60_000))
        watch.front(null, browser = false, nowMs = 0)
        assertNull(watch.toClose(10L * 60_000))
    }

    @Test
    fun i49_theHomeBrowserGoesOnlyAfterLongSilence() {
        val watch = BrowserWatch(known = setOf("helium"))
        watch.front("helium", browser = true, nowMs = 0)
        assertNull(watch.toClose(BrowserWatch.SILENT_GRACE_MS - 1))
        watch.heard(BrowserWatch.SILENT_GRACE_MS - 1)
        assertNull(watch.toClose(BrowserWatch.SILENT_GRACE_MS + 5_000))
        assertEquals("helium", watch.toClose(2 * BrowserWatch.SILENT_GRACE_MS))
    }

    @Test
    fun i49_timeWithTheBrowserBehindIsntSilence() {
        val watch = BrowserWatch(known = setOf("helium"))
        watch.front("konsole", browser = false, nowMs = 0)
        watch.front("helium", browser = true, nowMs = 10L * 60_000)
        assertNull(watch.toClose(10L * 60_000 + 1_000))
    }

    @Test
    fun i49_theSilenceAddsUpAcrossStaysInFront() {
        val watch = BrowserWatch(known = setOf("helium"))
        // The extension turned off, and a terminal in front every 110 s or so.
        watch.front("helium", browser = true, nowMs = 0)
        watch.front("konsole", browser = false, nowMs = 110_000)
        watch.front("helium", browser = true, nowMs = 200_000)
        assertNull(watch.toClose(200_000 + BrowserWatch.OTHER_GRACE_MS - 1))
        assertEquals("helium", watch.toClose(200_000 + BrowserWatch.OTHER_GRACE_MS))

        // Closed and opened again: ten seconds each time from now on.
        watch.front(null, browser = false, nowMs = 300_000)
        watch.front("helium", browser = true, nowMs = 400_000)
        assertNull(watch.toClose(400_000 + BrowserWatch.OTHER_GRACE_MS - 1))
        assertEquals("helium", watch.toClose(400_000 + BrowserWatch.OTHER_GRACE_MS))

        // The extension speaking for the front again: two minutes again.
        watch.heard(415_000)
        assertNull(watch.toClose(415_000 + BrowserWatch.SILENT_GRACE_MS - 1))
        assertEquals("helium", watch.toClose(415_000 + BrowserWatch.SILENT_GRACE_MS))
    }

    @Test
    fun i49_aSleepNeitherCountsNorStartsTheSilenceAgain() {
        val watch = BrowserWatch(known = setOf("helium"))
        watch.front("helium", browser = true, nowMs = 0)
        watch.woke(sleptAtMs = 100_000, nowMs = 3_700_000)
        assertNull(watch.toClose(3_700_000 + 19_999))
        assertEquals("helium", watch.toClose(3_700_000 + 20_000))
    }

    @Test
    fun i49_aBeatRightAfterSwitchingIsntBelieved() {
        val watch = BrowserWatch(known = setOf("helium"))
        watch.front("firefox", browser = true, nowMs = 1_000)
        assertFalse(watch.spoke(1_500))
        assertEquals(setOf("helium"), watch.known)
        assertTrue(watch.spoke(1_000 + BrowserWatch.SETTLE_MS))
        assertEquals(setOf("helium", "firefox"), watch.known)
    }

    @Test
    fun i49_aSpokenBrowserIsntNewAgain() {
        val watch = BrowserWatch(known = setOf("helium"))
        watch.front("helium", browser = true, nowMs = 0)
        assertFalse(watch.spoke(60_000))
    }
}
