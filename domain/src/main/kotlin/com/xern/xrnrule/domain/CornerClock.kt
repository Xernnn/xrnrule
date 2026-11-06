package com.xern.xrnrule.domain

/**
 * The faint clock in a corner of an app or site that's running out of time: how it reads, and when
 * it stands out. `extension/shared.js` has the same rules for the browser's clock.
 */
object CornerClock {
    /** Minutes left at which it stands out for a moment. */
    val MILESTONES_MIN = listOf(10, 5, 3, 1)

    /** How long it stands out at each of them. */
    const val STRONG_MS = 6_000L

    /** The end is the one that stays. */
    const val LAST_MS = 30_000L

    fun strong(leftMs: Long): Boolean = leftMs <= LAST_MS ||
        MILESTONES_MIN.any { min -> (min * 60_000L).let { leftMs in (it - STRONG_MS + 1)..it } }

    /** "12:34", or "1:05" for an hour and five minutes. */
    fun text(leftMs: Long): String {
        val seconds = (leftMs.coerceAtLeast(0) + 999) / 1000
        val hours = seconds / 3600
        val minutes = seconds % 3600 / 60
        return if (hours > 0) "%d:%02d".format(hours, minutes) else "%d:%02d".format(minutes, seconds % 60)
    }
}
