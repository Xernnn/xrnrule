package com.xern.xrnrule.app.data

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import com.xern.xrnrule.domain.Clock
import java.time.ZoneId
import kotlin.math.abs

class AndroidClock : Clock {
    override fun nowMonotonicMs(): Long = SystemClock.elapsedRealtime()
    override fun nowWallMs(): Long = System.currentTimeMillis()

    /** Read each time: Android resets the process default when the time zone changes. */
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

/**
 * The engine's clock: wall time with manual clock changes taken back out, so pushing the date
 * forward doesn't reset a daily limit and moving the time doesn't step around a schedule. The
 * same goes for the time zone: one picked by hand is left out ([ZoneGuard]).
 *
 * Wall minus monotonic time only moves when the clock is set. While Android's automatic time is
 * off, every such jump is a manual change and is added to [skewMs], which [nowWallMs] takes back
 * off; once automatic time is on again the clock is trusted and the skew cleared. Jumps made while
 * xrnrule wasn't running are caught at the next [check] too, unless the phone rebooted in
 * between (Android's boot count says so, [TimeSettings.bootCount]): the monotonic clock restarts
 * then, so there is nothing to compare against.
 */
class ClockGuard(private val prefs: SharedPreferences, private val raw: Clock, private val phone: TimeSettings) : Clock {

    constructor(context: Context, raw: Clock) :
        this(context.getSharedPreferences("clock_guard", Context.MODE_PRIVATE), raw, AndroidTimeSettings(context.contentResolver))

    /** How far the phone's clock has been moved by hand, in ms (positive = set ahead). */
    @Volatile
    var skewMs: Long = prefs.getLong(KEY_SKEW, 0)
        private set

    private val zones = ZoneGuard(prefs.getString(KEY_ZONE, null), prefs.getBoolean(KEY_ZONE_SET_ITSELF, false)) { zone, setItself ->
        prefs.edit().putString(KEY_ZONE, zone).putBoolean(KEY_ZONE_SET_ITSELF, setItself).apply()
    }

    /** I54: the zone the phone set itself, shared so the laptop goes by it ([ZoneGuard.ownZone]). */
    val ownZone: ZoneId? get() = zones.ownZone

    init {
        check()
    }

    override fun nowMonotonicMs(): Long = raw.nowMonotonicMs()
    override fun nowWallMs(): Long = raw.nowWallMs() - skewMs
    override fun zone(): ZoneId = zones.zone(raw.zone())

    /** The phone's own time zone, when it was picked by hand and blocks keep going by [zone] instead. */
    val handPickedZone: ZoneId? get() = raw.zone().takeIf { it != zone() }

    /** Call on `ACTION_TIME_CHANGED`, `ACTION_TIMEZONE_CHANGED` and now and then: books any clock jump since the last look. */
    @Synchronized
    fun check() {
        zones.check(raw.zone(), automaticTimeZone())
        val mono = raw.nowMonotonicMs()
        val offset = raw.nowWallMs() - mono
        val lastMono = prefs.getLong(KEY_MONO, Long.MAX_VALUE)
        val lastOffset = prefs.getLong(KEY_OFFSET, offset)
        val boot = phone.bootCount()
        val lastBoot = if (prefs.contains(KEY_BOOT)) prefs.getInt(KEY_BOOT, 0) else null
        // Otherwise the two offsets aren't comparable. The monotonic clock alone can't tell: a boot
        // looked at later into it than the last look was into its own passes for the same one.
        val sameBoot = mono >= lastMono && (boot == null || lastBoot == null || boot == lastBoot)
        val jumped = sameBoot && abs(offset - lastOffset) > TOLERANCE_MS
        var skew = skewMs
        if (automaticTime()) {
            skew = 0
        } else if (jumped) {
            skew += offset - lastOffset
        }
        // The baseline only needs writing when it moved: offsets don't change between clock sets.
        val write = skew != skewMs || jumped || !sameBoot || !prefs.contains(KEY_MONO) || boot != lastBoot
        skewMs = skew
        if (write) {
            val edit = prefs.edit()
                .putLong(KEY_SKEW, skew)
                .putLong(KEY_OFFSET, offset)
                .putLong(KEY_MONO, mono)
            if (boot != null) edit.putInt(KEY_BOOT, boot) else edit.remove(KEY_BOOT)
            edit.apply()
        }
    }

    fun automaticTime(): Boolean = phone.automaticTime()

    fun automaticTimeZone(): Boolean = phone.automaticTimeZone()

    private companion object {
        const val KEY_ZONE = "zone"
        const val KEY_ZONE_SET_ITSELF = "zone_set_itself"
        const val KEY_SKEW = "skew"
        const val KEY_OFFSET = "offset"
        const val KEY_MONO = "mono"
        const val KEY_BOOT = "boot"

        /** Clock drift and network corrections stay well under this; a hand-set change doesn't. */
        const val TOLERANCE_MS = 5_000L
    }
}

/** What [ClockGuard] reads of the phone's settings. */
interface TimeSettings {
    /** Whether the phone sets its clock itself (from the network). */
    fun automaticTime(): Boolean

    /** Whether the phone sets its time zone itself. */
    fun automaticTimeZone(): Boolean

    /** Which boot this is: a new number at every boot, null where the phone keeps none. */
    fun bootCount(): Int?
}

class AndroidTimeSettings(private val resolver: ContentResolver) : TimeSettings {
    override fun automaticTime(): Boolean = Settings.Global.getInt(resolver, Settings.Global.AUTO_TIME, 1) == 1

    override fun automaticTimeZone(): Boolean = Settings.Global.getInt(resolver, Settings.Global.AUTO_TIME_ZONE, 1) == 1

    override fun bootCount(): Int? = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 }
}
