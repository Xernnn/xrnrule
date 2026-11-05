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
    fun i49_comingToTheFrontStartsTheSilenceAgain() {
        val watch = BrowserWatch(known = setOf("helium"))
        watch.front("konsole", browser = false, nowMs = 0)
        watch.front("helium", browser = true, nowMs = 10L * 60_000)
        assertNull(watch.toClose(10L * 60_000 + 1_000))
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
