package com.xern.xrnrule.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

/** The block screen's time over an app is the block screen's, not the app's. */
class ShieldSpansTest {

    @Test
    fun timeUnderTheBlockScreenIsSplitOff() {
        // YouTube in front 10:00-10:30; the block screen over it 10:05-10:20.
        val (its, shield) = ShieldSpans.split(1_000, 4_000, listOf(1_500L until 3_000L))
        assertEquals(listOf(1_000L to 1_500L, 3_000L to 4_000L), its)
        assertEquals(listOf(1_500L to 3_000L), shield)
        // Spans outside or overlapping each other.
        val (all, none) = ShieldSpans.split(1_000, 2_000, listOf(0L until 500L, 2_500L until 3_000L))
        assertEquals(listOf(1_000L to 2_000L), all)
        assertEquals(emptyList<Pair<Long, Long>>(), none)
        val (left, covered) = ShieldSpans.split(0, 10, listOf(2L until 6L, 4L until 8L))
        assertEquals(listOf(0L to 2L, 8L to 10L), left)
        assertEquals(listOf(2L to 6L, 6L to 8L), covered)
    }

    @Test
    fun spansAreKeptAcrossARestartAndTheOldOnesGo() {
        val dir = Files.createTempDirectory("xrnrule-spans").toFile()
        var now = 100 * 24 * 60 * 60_000L
        val file = java.io.File(dir, "shield_spans.tsv")
        val spans = ShieldSpans(file) { now }
        spans.shown("com.example.video")
        now += 60_000
        spans.shown("com.example.video") // still the same showing
        now += 60_000
        spans.shown(null)
        spans.shown("com.example.game")
        now += 30_000
        assertEquals(mapOf("com.example.video" to listOf((now - 150_000) until (now - 30_000)), "com.example.game" to listOf((now - 30_000) until now)), spans.read())

        val later = ShieldSpans(file) { now + ShieldSpans.KEEP_MS + 1 }
        assertEquals("a month on, gone", emptyMap<String, List<LongRange>>(), later.read())
        dir.deleteRecursively()
    }
}
