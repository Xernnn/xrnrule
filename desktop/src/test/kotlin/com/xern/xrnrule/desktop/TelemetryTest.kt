package com.xern.xrnrule.desktop

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class TelemetryTest {
    private fun log() = FileTelemetry(File.createTempFile("telemetry", ".log").also { it.deleteOnExit() })

    private fun FileTelemetry.types(since: Long = 0) = eventsAfter(since).map {
        listOf("t", "target", "block", "ms", "at").mapNotNull { key -> it[key]?.jsonPrimitive?.content }
    }

    @Test
    fun everyLineGoesUpAsTheServerTakesIt() {
        val log = log()
        log.onSiteBlocked("reddit.com", 1_000)
        log.onMinutesBought(setOf("b1", "b2"), 240_000, 2_000)
        log.onBreakTaken(900_000, 3_000)
        assertEquals(
            listOf(
                listOf("blocked", "reddit.com", "1000"),
                listOf("bought", "b1,b2", "240000", "2000"),
                listOf("break", "900000", "3000"),
            ),
            log.types(),
        )
    }

    @Test
    fun onlyNewLinesGoUp() {
        val log = log()
        log.onBreakTaken(900_000, 3_000)
        log.onMinutesBought(setOf("b1"), 240_000, 4_000)
        assertEquals(1, log.eventsAfter(3_000).size)
    }

    /** A line read at the wrong place would look undated, and stay for ever. */
    @Test
    fun oldLinesOfEveryKindArePruned() {
        val log = log()
        val day = 86_400_000L
        val now = 200 * day
        log.onSiteBlocked("reddit.com", day)
        log.onMinutesBought(setOf("b1"), 240_000, day)
        log.onBreakTaken(900_000, day)
        log.onSiteBlocked("x.com", now)
        log.prune(now)
        assertEquals(listOf(listOf("blocked", "x.com", "$now")), log.types())
    }
}
