package com.xern.xrnrule.app.data

import java.time.ZoneId

/**
 * The time zone days and schedules go by: the phone's own while it sets the zone itself (from the
 * network or its location), else the last one it set. A zone picked by hand is the clock moved
 * by hand, and is left out like one ([ClockGuard]): one where it's already tomorrow would start a
 * new day of limits and another daily break, which switching back would keep, and one hours away
 * would step around a schedule. Pure, so it can be tested without a phone; [saved],
 * [savedSetItself] and [save] keep the zone across restarts.
 */
class ZoneGuard(saved: String?, savedSetItself: Boolean = false, private val save: (zone: String, setItself: Boolean) -> Unit) {

    @Volatile
    private var kept: ZoneId? = saved?.let { runCatching { ZoneId.of(it) }.getOrNull() }

    /** Whether [kept] is a zone the phone set itself, rather than the one it had when first asked. */
    @Volatile
    private var setItself: Boolean = savedSetItself && kept != null

    /** The zone to go by, with the phone set to [system]. */
    fun zone(system: ZoneId): ZoneId = kept ?: system

    /**
     * I54: the zone kept, when the phone set it itself; null while it's only the one the phone had
     * when first asked, which may have been picked by hand. Shared, so the laptop goes by it.
     */
    val ownZone: ZoneId? get() = kept?.takeIf { setItself }

    /** Takes the phone's zone ([system]) if it set it itself ([automatic]), or if none is kept yet. */
    @Synchronized
    fun check(system: ZoneId, automatic: Boolean) {
        if (!automatic && kept != null) return
        if (kept == system && setItself == automatic) return
        kept = system
        setItself = automatic
        save(system.id, automatic)
    }
}
