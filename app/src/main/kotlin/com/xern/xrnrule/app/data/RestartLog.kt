package com.xern.xrnrule.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * When the blocking service last couldn't come back: Android refused to start it from the
 * background (a watchdog run, a boot, a broadcast waking the app). That used to be swallowed and
 * leave no trace; now the app shows the last one (S1). Kept on this phone only.
 */
class RestartLog(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    /** The last refused start, and how many there were in a row before the service came up again. */
    data class Refusal(val atWall: Long, val inARow: Int)

    fun refused(atWall: Long) {
        prefs.edit().putLong(KEY_AT, atWall).putInt(KEY_IN_A_ROW, prefs.getInt(KEY_IN_A_ROW, 0) + 1).apply()
    }

    /** The service is up: the next refusal starts a new run of them; the last one stays to be seen. */
    fun started() {
        if (prefs.getInt(KEY_IN_A_ROW, 0) != 0) prefs.edit().putInt(KEY_IN_A_ROW, 0).putInt(KEY_LAST_RUN, prefs.getInt(KEY_IN_A_ROW, 0)).apply()
    }

    fun last(): Refusal? {
        val at = prefs.getLong(KEY_AT, 0).takeIf { it > 0 } ?: return null
        val inARow = prefs.getInt(KEY_IN_A_ROW, 0).takeIf { it > 0 } ?: prefs.getInt(KEY_LAST_RUN, 1)
        return Refusal(at, inARow)
    }

    /** The last refusal if it came within [withinMs] of [nowWall]: what the main screen still mentions. */
    fun recent(nowWall: Long, withinMs: Long = RECENT_MS): Refusal? = last()?.takeIf { nowWall - it.atWall in 0..withinMs }

    companion object {
        private const val PREFS = "restarts"
        private const val KEY_AT = "refused_at"
        private const val KEY_IN_A_ROW = "in_a_row"
        private const val KEY_LAST_RUN = "last_run"
        const val RECENT_MS = 24 * 60 * 60_000L
    }
}

/** What can keep xrnrule from coming back after a kill or a reboot, for the main screen's banner (S1). */
enum class RestartIssue(val label: String, val action: String) {
    AUTOSTART_OFF("Autostart off", "Turn on"),
    BATTERY_OPTIMISED("Battery optimised", "Allow"),
    ;

    companion object {
        /** [autostart]: HyperOS's switch, null where the phone won't say. Nothing to warn of with no blocks. */
        fun of(hasBlocks: Boolean, autostart: Boolean?, batteryExempt: Boolean): List<RestartIssue> =
            if (!hasBlocks) emptyList()
            else listOfNotNull(AUTOSTART_OFF.takeIf { autostart == false }, BATTERY_OPTIMISED.takeIf { !batteryExempt })
    }
}
