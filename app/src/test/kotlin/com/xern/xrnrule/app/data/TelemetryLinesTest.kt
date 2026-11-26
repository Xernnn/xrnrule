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

    /** Insights sets a limit's use a day against the limit from the server's record of each day. */
    @Test
    fun theServersLimitRecordReadsBackByBlockDeviceAndDay() {
        val body = """{"limits": {
            "b1": {"phone-1a2b": {"1763470800000": 1200000, "1763557200000": 600000}, "laptop-9f8e": {"1763470800000": 300000}},
            "b2": {"phone-1a2b": {"bad": 5, "1763470800000": -1, "1763557200000": "60000", "1763643600000": 60000.5}},
            "b3": [],
            "b4": {"phone-1a2b": []}
        }}"""
        assertEquals(
            mapOf(
                "b1" to mapOf(
                    "phone-1a2b" to mapOf(1763470800000L to 1_200_000L, 1763557200000L to 600_000L),
                    "laptop-9f8e" to mapOf(1763470800000L to 300_000L),
                ),
            ),
            limitsFrom(body),
        )
        assertTrue(limitsFrom("not json").isEmpty())
        assertTrue(limitsFrom("""{"limits": 3}""").isEmpty())
    }

    @Test
    fun theLaptopsScreenTimeReadsBackByDayItsOwnDevicesAddedUp() {
        val body = """{"usage": {
            "laptop": {"2026-11-25": {"apps": {"zen": 600000, "steam": 60000}, "sites": {"example.org": 300000}},
                       "2026-11-24": {"apps": {"zen": 1000, "bad": -5, "odd": "60000"}, "sites": []},
                       "not-a-day": {"apps": {"zen": 1}}},
            "laptop-2": {"2026-11-25": {"apps": {"zen": 5000}}},
            "phone": {"2026-11-25": {"apps": {"com.example.reader": 99}}}
          }, "names": {"zen": "Zen Browser", "steam": 3}}"""
        val got = usageFrom(body, "phone")
        assertEquals(
            mapOf(
                java.time.LocalDate.of(2026, 11, 25) to UsageDay(mapOf("zen" to 605_000L, "steam" to 60_000L), mapOf("example.org" to 300_000L)),
                java.time.LocalDate.of(2026, 11, 24) to UsageDay(mapOf("zen" to 1_000L)),
            ),
            got.days,
        )
        assertEquals(mapOf("zen" to "Zen Browser"), got.names)
        assertEquals(665_000L, got.days.getValue(java.time.LocalDate.of(2026, 11, 25)).totalMs)
        assertTrue(usageFrom("not json", "phone").days.isEmpty())
        assertTrue(usageFrom("""{"usage": []}""", "phone").days.isEmpty())
    }
}
