package com.xern.xrnrule.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TelemetryLinesTest {
    private fun log() = LocalTelemetry(File.createTempFile("telemetry", ".jsonl").also { it.deleteOnExit() })

    @Test
    fun everyEventReadsBackAsWritten() {
        val log = log()
        log.onBlockedImpression("com.app", 1)
        log.onSiteBlocked("reddit.com", 2)
        log.onHabitCompleted("b1", 3)
        log.onUnlockConsumed("b1", 60_000, 4, 3)
        log.onMinutesBought(setOf("b1", "b2"), 240_000, 5)
        log.onBreakTaken(900_000, 6)
        assertEquals(
            listOf(
                TelemetryEvent.Blocked("com.app", 1),
                TelemetryEvent.SiteBlocked("reddit.com", 2),
                TelemetryEvent.HabitDone("b1", 3),
                TelemetryEvent.UnlockUsed("b1", 60_000, 4, 3),
                TelemetryEvent.MinutesBought(240_000, 5, "b1,b2"),
                TelemetryEvent.BreakTaken(900_000, 6),
            ),
            log.events(),
        )
    }

    @Test
    fun theLaptopsBlocksAndShortcutsComeIn() {
        val body = """{"events": [
            {"d": "laptop", "t": "blocked", "at": 1, "target": "reddit.com", "name": "reddit.com"},
            {"d": "laptop", "t": "bought", "at": 2, "block": "b1", "ms": 240000},
            {"d": "laptop", "t": "break", "at": 3, "ms": 900000}
        ]}"""
        assertEquals(
            listOf(
                TelemetryEvent.Blocked("reddit.com", 1, "reddit.com"),
                TelemetryEvent.MinutesBought(240_000, 2, "b1"),
                TelemetryEvent.BreakTaken(900_000, 3),
            ),
            eventsFromElsewhere(body, "phone"),
        )
    }

    @Test
    fun thisDevicesOwnLinesStayOut() {
        val body = """{"events": [
            {"d": "phone", "t": "blocked", "at": 1, "target": "com.app"},
            {"d": "phone", "t": "habit", "at": 2, "block": "b1"}
        ]}"""
        assertTrue(eventsFromElsewhere(body, "phone").isEmpty())
    }

    /** Insights shows where a habit was done and an unlock used; it counts a shared one once (InsightsModel.merge). */
    @Test
    fun theLaptopsHabitsAndUnlocksComeInWithTheirSession() {
        val body = """{"events": [
            {"d": "laptop", "t": "habit", "at": 2, "block": "b1"},
            {"d": "laptop", "t": "unlock", "at": 3, "block": "b1", "ms": 60000, "start": 2},
            {"d": "laptop", "t": "unlock", "at": 4, "block": "b1", "ms": 60000},
            {"d": "laptop", "t": "habit", "at": 5}
        ]}"""
        assertEquals(
            listOf(
                TelemetryEvent.HabitDone("b1", 2),
                TelemetryEvent.UnlockUsed("b1", 60_000, 3, 2),
                TelemetryEvent.UnlockUsed("b1", 60_000, 4, 0),
            ),
            eventsFromElsewhere(body, "phone"),
        )
    }

    /** It runs on the app's process, where an uncaught exception would take every block down. */
    @Test
    fun aBadAnswerIsNoEventsNotACrash() {
        for (body in listOf("", "not json", "[]", "{}", """{"events": 3}""", """{"events": [3, null]}""")) {
            assertTrue(body, eventsFromElsewhere(body, "phone").isEmpty())
        }
        val odd = """{"events": [
            {"d": {"x": 1}, "t": "blocked", "at": 1, "target": ["a"]},
            {"d": "laptop", "t": ["blocked"], "at": 2},
            {"d": "laptop", "t": "bought", "at": {"x": 1}, "ms": 1},
            {"d": "laptop", "t": "bought", "at": 3, "ms": "soon"}
        ]}"""
        assertTrue(eventsFromElsewhere(odd, "phone").isEmpty())
    }
}
