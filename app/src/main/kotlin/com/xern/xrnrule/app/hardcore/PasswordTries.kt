package com.xern.xrnrule.app.hardcore

import android.content.SharedPreferences

/**
 * Wrong hardcore passwords cost time, so a short one can't be guessed by hand: after [FREE_TRIES]
 * wrong ones in a row, each further try waits, twice as long as the one before, up to [MAX_WAIT_MS].
 * A right one starts the count again. Kept in [prefs], so restarting the app doesn't clear it.
 *
 * [elapsed] is the monotonic clock (`elapsedRealtime`): setting the clock doesn't shorten a wait.
 * It starts again at a boot; a wait then counts from the boot's first look, never from less.
 */
class PasswordTries(private val prefs: SharedPreferences, private val elapsed: () -> Long) {

    /** How long until the next try is taken, in ms; 0 if now. */
    @Synchronized
    fun waitMs(): Long {
        val failures = prefs.getInt(KEY_FAILURES, 0)
        if (failures < FREE_TRIES) return 0
        val now = elapsed()
        var last = prefs.getLong(KEY_LAST, now)
        if (now < last) {
            // A new boot: the wait counts from here.
            last = now
            prefs.edit().putLong(KEY_LAST, now).apply()
        }
        return (last + waitAfter(failures) - now).coerceAtLeast(0)
    }

    /** Checks [input] with [right], unless a wait is due: then it isn't tried, and false. */
    @Synchronized
    fun attempt(right: () -> Boolean): Boolean {
        if (waitMs() > 0) return false
        if (right()) {
            prefs.edit().remove(KEY_FAILURES).remove(KEY_LAST).apply()
            return true
        }
        prefs.edit().putInt(KEY_FAILURES, prefs.getInt(KEY_FAILURES, 0) + 1).putLong(KEY_LAST, elapsed()).apply()
        return false
    }

    companion object {
        const val FREE_TRIES = 3
        const val FIRST_WAIT_MS = 30_000L
        const val MAX_WAIT_MS = 60 * 60_000L
        private const val KEY_FAILURES = "wrong_tries"
        private const val KEY_LAST = "wrong_at_elapsed"

        /** The wait after [failures] wrong tries in a row. */
        fun waitAfter(failures: Int): Long {
            if (failures < FREE_TRIES) return 0
            val doublings = (failures - FREE_TRIES).coerceAtMost(20)
            return (FIRST_WAIT_MS shl doublings).coerceAtMost(MAX_WAIT_MS)
        }

        /** "Wait 30 s." / "Wait 4 min.", for the password fields. */
        fun waitText(ms: Long): String {
            val s = (ms + 999) / 1000
            return if (s < 120) "Wait $s s." else "Wait ${(s + 59) / 60} min."
        }
    }
}
