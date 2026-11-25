package com.xern.xrnrule.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The trace a refused restart leaves, and what the main screen warns of (S1). */
class RestartLogTest {

    @Test
    fun aRefusedRestartLeavesATrace() {
        // A watchdog run that Android refused used to be swallowed: nothing showed it had happened.
        val log = RestartLog(MemoryPrefs())
        assertNull(log.last())
        log.refused(1_000)
        log.refused(901_000)
        assertEquals(RestartLog.Refusal(901_000, inARow = 2), log.last())

        // The service came back (the app was opened): the trace stays, the next run counts afresh.
        log.started()
        assertEquals(RestartLog.Refusal(901_000, inARow = 2), log.last())
        log.refused(2_000_000)
        assertEquals(RestartLog.Refusal(2_000_000, inARow = 1), log.last())
    }

    @Test
    fun theMainScreenOnlyMentionsARecentOne() {
        val log = RestartLog(MemoryPrefs())
        log.refused(1_000)
        assertEquals(1_000L, log.recent(1_000 + RestartLog.RECENT_MS)?.atWall)
        assertNull(log.recent(1_001 + RestartLog.RECENT_MS))
    }

    @Test
    fun whatKeepsItFromComingBack() {
        assertEquals(listOf(RestartIssue.AUTOSTART_OFF), RestartIssue.of(hasBlocks = true, autostart = false, batteryExempt = true))
        assertEquals(listOf(RestartIssue.AUTOSTART_OFF, RestartIssue.BATTERY_OPTIMISED), RestartIssue.of(true, false, false))
        assertEquals("a phone that won't say isn't warned of", emptyList<RestartIssue>(), RestartIssue.of(true, null, true))
        assertEquals("nothing to block, nothing to warn of", emptyList<RestartIssue>(), RestartIssue.of(false, false, false))
        assertEquals("Autostart off", RestartIssue.AUTOSTART_OFF.label)
    }
}
