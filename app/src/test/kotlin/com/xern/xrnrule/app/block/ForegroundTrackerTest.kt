package com.xern.xrnrule.app.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundTrackerTest {

    private val hour = 60 * 60_000L

    /** UsageStats as the tracker reads it: resumes at given times, and which spans were asked for. */
    private class Events {
        val resumes = mutableListOf<Pair<Long, ForegroundTracker.Resume>>()
        val asked = mutableListOf<LongRange>()

        fun resume(at: Long, pkg: String, className: String? = null) {
            resumes += at to ForegroundTracker.Resume(pkg, className)
        }

        fun between(from: Long, to: Long): List<ForegroundTracker.Resume> {
            asked += from..to
            return resumes.filter { (at, _) -> at in from..to }.sortedBy { it.first }.map { it.second }
        }
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
