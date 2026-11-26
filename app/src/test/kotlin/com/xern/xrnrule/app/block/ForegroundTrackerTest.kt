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

    @Test
    fun anAppKilledInFrontIsNotInFrontAgainLater() {
        // An app destroyed while resumed sent no pause, and its destroy (24) was read as nothing:
        // it stayed "resumed", so a look between the next app's pause and the one after's resume
        // found the dead app in front, and its block screen came up over the launcher.
        val raw = mutableListOf<Pair<Long, Triple<String, String?, Int>>>()
        var now = 10 * hour
        val tracker = ForegroundTracker({ from, to ->
            raw.filter { it.first in from..to }.mapNotNull { (_, e) -> ForegroundTracker.moveOf(e.first, e.second, e.third) }
        }) { now }
        val game = "com.example.game"
        val notes = "com.example.notes"
        raw += now - 10 to Triple(game, "Main", ForegroundTracker.ACTIVITY_RESUMED)
        assertEquals(game, tracker.poll())
        now += 1_000
        raw += now - 10 to Triple(game, "Main", ForegroundTracker.ACTIVITY_DESTROYED)
        raw += now - 5 to Triple(home, "Launcher", ForegroundTracker.ACTIVITY_RESUMED)
        assertEquals(home, tracker.poll())
        now += 1_000
        raw += now - 10 to Triple(home, "Launcher", ForegroundTracker.ACTIVITY_PAUSED) // on its way to the notes app
        assertEquals("the launcher until the next app resumes", home, tracker.poll())
        now += 1_000
        raw += now - 10 to Triple(notes, "Main", ForegroundTracker.ACTIVITY_RESUMED)
        assertEquals(notes, tracker.poll())
    }

    /** Raw UsageStats events, read as the detector reads them (with their times). */
    private class Raw {
        val events = mutableListOf<Pair<Long, Triple<String, String?, Int>>>()
        var now = 10 * 60 * 60_000L
        val tracker = ForegroundTracker({ from, to ->
            events.filter { it.first in from..to }.mapNotNull { (at, e) -> ForegroundTracker.moveOf(e.first, e.second, e.third, at) }
        }) { now }

        fun at(pkg: String, className: String, type: Int) {
            events += now - 10 to Triple(pkg, className, type)
        }
    }

    @Test
    fun aBlockedAppInPictureInPictureIsShownBesidesTheFront() {
        val video = "com.example.video"
        with(Raw()) {
            at(video, "Watch", ForegroundTracker.ACTIVITY_RESUMED)
            assertEquals(video, tracker.poll())
            // Home pressed during a video: it goes to picture-in-picture, paused and never stopped.
            now += 1_000
            at(video, "Watch", ForegroundTracker.ACTIVITY_PAUSED)
            at(home, "Launcher", ForegroundTracker.ACTIVITY_RESUMED)
            assertEquals(home, tracker.poll())
            assertEquals("not yet: an app on its way out stops within a moment", emptySet<String>(), tracker.shownBesides())
            now += ForegroundTracker.SHOWN_SETTLE_MS
            tracker.poll()
            assertEquals(emptySet<String>(), tracker.shownBesides()) // no stop ever seen yet: an older phone, perhaps
            at("com.example.notes", "Main", ForegroundTracker.ACTIVITY_STOPPED) // this phone says when one stops
            now += 1_000
            assertEquals(home, tracker.poll())
            assertEquals(setOf(video), tracker.shownBesides())
            // Its window closed: stopped.
            at(video, "Watch", ForegroundTracker.ACTIVITY_STOPPED)
            now += 1_000
            tracker.poll()
            assertEquals(emptySet<String>(), tracker.shownBesides())
        }
    }

    @Test
    fun aFloatingWindowIsShownBesidesTheFront() {
        val chat = "com.example.chat"
        with(Raw()) {
            at("com.example.notes", "Main", ForegroundTracker.ACTIVITY_STOPPED)
            at(chat, "Main", ForegroundTracker.ACTIVITY_RESUMED)
            now += 1_000
            at(home, "Launcher", ForegroundTracker.ACTIVITY_RESUMED) // both resumed: the chat floats over it
            assertEquals(home, tracker.poll())
            assertEquals(setOf(chat), tracker.shownBesides())
            // An ordinary switch: paused, then stopped a moment later, never shown besides.
            now += 1_000
            at(chat, "Main", ForegroundTracker.ACTIVITY_PAUSED)
            at(chat, "Main", ForegroundTracker.ACTIVITY_STOPPED)
            tracker.poll()
            now += ForegroundTracker.SHOWN_SETTLE_MS
            tracker.poll()
            assertEquals(emptySet<String>(), tracker.shownBesides())
        }
    }

    @Test
    fun onlyResumesPausesStopsAndDestroysAreMoves() {
        assertEquals(true, ForegroundTracker.moveOf("a", null, ForegroundTracker.ACTIVITY_RESUMED)?.front)
        assertEquals(false, ForegroundTracker.moveOf("a", null, ForegroundTracker.ACTIVITY_PAUSED)?.front)
        assertEquals(false, ForegroundTracker.moveOf("a", null, ForegroundTracker.ACTIVITY_STOPPED)?.front)
        assertEquals(false, ForegroundTracker.moveOf("a", null, ForegroundTracker.ACTIVITY_DESTROYED)?.front)
        assertNull(ForegroundTracker.moveOf("a", null, 5)) // CONFIGURATION_CHANGE
    }
}
