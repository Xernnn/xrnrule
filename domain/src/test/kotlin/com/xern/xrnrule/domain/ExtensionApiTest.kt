package com.xern.xrnrule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionApiTest {

    @Test
    fun versionsCompareByNumberPartByPart() {
        assertTrue(ExtensionApi.isOlder("2.1.0", "2.1.1"))
        assertTrue(ExtensionApi.isOlder("2.9", "2.10"))
        assertTrue(ExtensionApi.isOlder("2.1", "2.1.1"))
        assertFalse(ExtensionApi.isOlder("2.1.1", "2.1.1"))
        assertFalse(ExtensionApi.isOlder("2.1.0", "2.1"))
        assertFalse("newer than the app's copy", ExtensionApi.isOlder("2.2.0", "2.1.1"))
        assertFalse("unreadable", ExtensionApi.isOlder("dev", "2.1.1"))
        assertFalse("unreadable", ExtensionApi.isOlder("2.1.1", ""))
    }

    @Test
    fun aBlockedSiteSaysWhenItIsBack() {
        val clock = FakeClock() // 12:00 UTC
        val block = modeBlock("s", BlockMode.SCHEDULE, targetPackages = emptySet(), targetSites = setOf("example.com"),
            schedule = Schedule((1..7).toSet(), TimeWindow(9 * 60, 17 * 60)))
        val world = run { val w = buildWorld(listOf(block), clock = clock); w.copy(appliedShield = BlockEngine().shieldPlan(w, clock)) }
        val verdict = ExtensionApi.verdict(BlockEngine(), world, "example.com", clock, null)
        assertTrue(verdict.blocked)
        assertEquals(block.name, verdict.block)
        assertEquals("Back at 17:00", verdict.back)
        assertEquals("Blocked hours · 5 h to go", verdict.why)
    }

    @Test
    fun aSiteBehindAHabitSaysSo() {
        val clock = FakeClock()
        val block = timerBlock("h").copy(targetPackages = emptySet(), targetSites = setOf("example.com"))
        val world = run { val w = buildWorld(listOf(block), clock = clock); w.copy(appliedShield = BlockEngine().shieldPlan(w, clock)) }
        val verdict = ExtensionApi.verdict(BlockEngine(), world, "example.com", clock, null)
        assertTrue(verdict.blocked)
        assertEquals(null, verdict.back)
        assertEquals("Do its habits to open it.", verdict.why)
    }
}
