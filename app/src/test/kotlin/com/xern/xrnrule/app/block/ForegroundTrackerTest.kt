package com.xern.xrnrule.app.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundTrackerTest {

    private val hour = 60 * 60_000L

    /** UsageStats as the tracker reads it: resumes and pauses at given times, and which spans were asked for. */
    private class Events {
        val moves = mutableListOf<Pair<Long, ForegroundTracker.Move>>()
        val asked = mutableListOf<LongRange>()

        fun resume(at: Long, pkg: String, className: String? = null) {
            moves += at to ForegroundTracker.Move(pkg, className, front = true)
        }

        fun pause(at: Long, pkg: String, className: String? = null) {
            moves += at to ForegroundTracker.Move(pkg, className, front = false)
        }

        fun between(from: Long, to: Long): List<ForegroundTracker.Move> {
            asked += from..to
            return moves.filter { (at, _) -> at in from..to }.sortedBy { it.first }.map { it.second }
        }
    }

    private val browser = "net.quetta.browser"
    private val browserMain = "com.google.android.apps.chrome.Main"
    private val home = "com.miui.home"

    @Test
    fun aSwipeHomeAndBackThatNeverPausedTheAppLeavesItInFront() {
        // 2026-11-24, 23:00, as the phone logged it: the browser resumed; a swipe brought the
        // launcher up for a second, without pausing the browser; the launcher paused again as the
        // browser came back on top, with no new resume for it. The browser was in front for 36
        // minutes after that; the last resume said the launcher.
        val events = Events()
        var now = 10 * hour
        val tracker = ForegroundTracker(events::between) { now }
        events.resume(now - 100, browser, browserMain)
        assertEquals(browser, tracker.poll())
        now += 5_800
        events.resume(now - 50, home, "com.miui.home.launcher.Launcher")
        assertEquals("the launcher is up for a moment", home, tracker.poll())
        now += 1_000
        events.pause(now - 20, home, "com.miui.home.launcher.Launcher")
        assertEquals(browser, tracker.poll())
        now += 36 * 60_000L
        assertEquals("and stays so, with nothing new", browser, tracker.poll())
    }

    @Test
    fun anAppPausedForTheNextIsStillInFrontUntilItResumes() {
        val events = Events()
        var now = 10 * hour
        val tracker = ForegroundTracker(events::between) { now }
        events.resume(now - 100, "com.whatsapp")
        assertEquals("com.whatsapp", tracker.poll())
        now += 300
        events.pause(now - 50, "com.whatsapp")
        assertEquals("between one pause and the next resume, nothing is cleared", "com.whatsapp", tracker.poll())
        now += 300
        events.resume(now - 250, "com.instagram.android")
        assertEquals("com.instagram.android", tracker.poll())
        now += 300
        assertEquals("a pause read again in the overlap changes nothing", "com.instagram.android", tracker.poll())
    }

    @Test
    fun theFirstLookLooksFurtherBackWhenTheLastMinuteOnlyShowsAPauseAfterItsResume() {
        // xrnrule starts again 20 minutes into the browser; a minute ago the launcher came up and went.
        val events = Events()
        val now = 10 * hour
        events.resume(now - 20 * 60_000L, browser, browserMain)
        events.resume(now - 30_000, home)
        events.pause(now - 29_000, home)
        val tracker = ForegroundTracker(events::between) { now }
        assertEquals(browser, tracker.poll())
    }

    @Test
    fun theAppInFrontSinceBeforeAKillIsInFrontAtOnce() {
        val events = Events()
        var now = 10 * hour
        // TikTok came to the front two hours ago; MIUI killed xrnrule since, and it starts again now.
        events.resume(now - 2 * hour - 5_000, "com.android.launcher")
        events.resume(now - 2 * hour, "com.zhiliaoapp.musically")
        val tracker = ForegroundTracker(events::between) { now }
        assertEquals("com.zhiliaoapp.musically", tracker.poll())

        // From then on, only what came since.
        events.asked.clear()
        now += 300
        events.resume(now - 100, "com.whatsapp")
        assertEquals("com.whatsapp", tracker.poll())
        assertEquals("one span, from just before the last poll", 1, events.asked.size)
    }

    @Test
    fun aUsualStartReadsOnlyAMinute() {
        val events = Events()
        val now = 10 * hour
        events.resume(now - 3 * hour, "com.old")
        events.resume(now - 20_000, "com.recent")
        val tracker = ForegroundTracker(events::between) { now }
        assertEquals("com.recent", tracker.poll())
        assertEquals(listOf((now - 60_000)..now), events.asked)
    }

    @Test
    fun nothingEverResumedIsNothingInFront_andACustomTabIsSaidSo() {
        val events = Events()
        var now = 10 * hour
        val tracker = ForegroundTracker(events::between) { now }
        assertNull(tracker.poll())
        now += 300
        events.resume(now - 50, "com.android.chrome", "org.chromium.chrome.browser.customtabs.CustomTabActivity")
        assertEquals("com.android.chrome", tracker.poll())
        assertTrue(tracker.customTab)
        now += 300
        events.resume(now - 50, "com.android.chrome", "com.google.android.apps.chrome.Main")
        tracker.poll()
        assertFalse(tracker.customTab)
    }
}
