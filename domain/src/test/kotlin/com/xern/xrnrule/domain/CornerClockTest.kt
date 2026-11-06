package com.xern.xrnrule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CornerClockTest {
    private val second = 1_000L
    private val minute = 60_000L

    @Test
    fun standsOutForSixSecondsAtEachMilestone() {
        for (min in CornerClock.MILESTONES_MIN) {
            assertFalse("just before $min min", CornerClock.strong(min * minute + 1))
            assertTrue("at $min min", CornerClock.strong(min * minute))
            assertTrue("still at $min min", CornerClock.strong(min * minute - 5 * second))
            assertFalse("past $min min", CornerClock.strong(min * minute - 6 * second))
        }
    }

    @Test
    fun staysFaintBetweenMilestones() {
        assertFalse(CornerClock.strong(15 * minute))
        assertFalse(CornerClock.strong(7 * minute))
        assertFalse(CornerClock.strong(2 * minute))
        assertFalse(CornerClock.strong(31 * second))
    }

    @Test
    fun theLastThirtySecondsStayStrong() {
        assertTrue(CornerClock.strong(30 * second))
        assertTrue(CornerClock.strong(1 * second))
        assertTrue(CornerClock.strong(0))
    }

    @Test
    fun readsAsMinutesAndSecondsRoundedUp() {
        assertEquals("12:34", CornerClock.text(12 * minute + 34 * second))
        assertEquals("0:01", CornerClock.text(1))
        assertEquals("0:00", CornerClock.text(0))
        assertEquals("0:00", CornerClock.text(-5))
        assertEquals("1:05", CornerClock.text(65 * minute))
    }
}
