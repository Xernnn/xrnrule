package com.xern.xrnrule.app.data

import java.time.ZoneId

/**
 * The time zone days and schedules go by: the phone's own while it sets the zone itself (from the
 * network or its location), else the last one it set. A zone picked by hand is the clock moved
 * by hand, and is left out like one ([ClockGuard]): one where it's already tomorrow would start a
 * new day of limits and another daily break, which switching back would keep, and one hours away
 * would step around a schedule. Pure, so it can be tested without a phone; [saved] and [save]
 * keep the zone across restarts.
 */
class ZoneGuard(saved: String?, private val save: (String) -> Unit) {

    @Volatile
    private var kept: ZoneId? = saved?.let { runCatching { ZoneId.of(it) }.getOrNull() }

    /** The zone to go by, with the phone set to [system]. */
    fun zone(system: ZoneId): ZoneId = kept ?: system

    /** Takes the phone's zone ([system]) if it set it itself ([automatic]), or if none is kept yet. */
    @Synchronized
    fun check(system: ZoneId, automatic: Boolean) {
        if (kept == system || (!automatic && kept != null)) return
        kept = system
        save(system.id)
    }
}
