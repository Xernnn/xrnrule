package com.xern.xrnrule.app.data

import java.io.File

/**
 * When the block screen covered which app (wall-clock spans), so screen time counts that time as
 * the block screen's rather than the app's: Android counts the app under the overlay as in front.
 * Kept in a small file of its own, a span per line, for [KEEP_MS]. The laptop leaves its block
 * page out the same way (docs/laptop-screen-time.md).
 */
class ShieldSpans(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    private var open: Pair<String, Long>? = null

    /** The block screen is over [pkg] now, or over nothing (null). */
    @Synchronized
    fun shown(pkg: String?) {
        val at = now()
        val was = open
        if (was?.first == pkg) return
        if (was != null && at > was.second) append(was.first, was.second, at)
        open = pkg?.let { it to at }
    }

    /** The spans by package, the one still open running to now. Off the main thread: it drops what's past [KEEP_MS]. */
    @Synchronized
    fun read(): Map<String, List<LongRange>> {
        val lines = runCatching { if (file.isFile) file.readLines() else emptyList() }.getOrDefault(emptyList())
        val cutoff = now() - KEEP_MS
        val spans = lines.mapNotNull(::parse).filter { it.second.last >= cutoff }.toMutableList()
        if (spans.size < lines.size) runCatching { file.writeText(spans.joinToString("") { (pkg, r) -> "$pkg\t${r.first}\t${r.last + 1}\n" }) }
        open?.let { (pkg, from) -> spans += pkg to (from until now()) }
        return spans.groupBy({ it.first }, { it.second })
    }

    private fun append(pkg: String, from: Long, to: Long) {
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText("$pkg\t$from\t$to\n")
        }
    }

    companion object {
        /** As far back as Insights and the server's screen time look. */
        const val KEEP_MS = 31L * 24 * 60 * 60 * 1000

        private fun parse(line: String): Pair<String, LongRange>? {
            val parts = line.split('\t')
            if (parts.size != 3) return null
            val from = parts[1].toLongOrNull() ?: return null
            val to = parts[2].toLongOrNull() ?: return null
            return if (to > from) parts[0] to (from until to) else null
        }

        /**
         * [from]..[to] of an app's time split by [spans] of the block screen over it: the pieces that
         * were the app's, and those that were the block screen's.
         */
        fun split(from: Long, to: Long, spans: List<LongRange>): Pair<List<Pair<Long, Long>>, List<Pair<Long, Long>>> {
            val covered = spans.map { maxOf(from, it.first) to minOf(to, it.last + 1) }.filter { it.second > it.first }.sortedBy { it.first }
            val mine = mutableListOf<Pair<Long, Long>>()
            val shield = mutableListOf<Pair<Long, Long>>()
            var t = from
            for ((a, b) in covered) {
                if (b <= t) continue
                if (a > t) mine += t to a
                shield += maxOf(a, t) to b
                t = b
            }
            if (t < to) mine += t to to
            return mine to shield
        }
    }
}
