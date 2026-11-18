package com.xern.xrnrule.desktop

import com.xern.xrnrule.domain.Clock
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The engine's clock on the laptop: the phone's ClockGuard and ZoneGuard, over Linux.
 *
 * **Time.** Wall time minus CLOCK_BOOTTIME only moves when the clock is set. A jump made while NTP
 * doesn't keep the clock ([synced]: timedatectl's NTPSynchronized, which setting the clock by
 * hand clears) is a change by hand and goes into [skewMs], which [nowWallMs] takes back off; once
 * NTP keeps the clock again it is trusted and the skew cleared. Without this, `date -s` a day ahead
 * started a new day of limits, and a week ahead ended a week's lock (I39), which the laptop checks
 * by its own clock. A jump is caught at the next reading of the time, before anything reads the
 * moved clock, and one made while the daemon was stopped at its next start, unless the laptop
 * rebooted in between: the boot clock restarts then, so there is nothing to compare against. A boot
 * keeps the skew (the hardware clock may hold the change), but the time never goes back past the
 * last it saw: `date -s` leaves the hardware clock right, and the skew kept would put the laptop a
 * day behind, back in yesterday, until NTP answered. A clock set ahead across a reboot (NTP off,
 * the hardware clock set too) is caught by the sync server's time instead ([serverTime]), at the
 * first answer from it; until then, offline, nothing can tell.
 *
 * **Zone** (I54). Linux sets no time zone by itself: `timedatectl set-timezone` (Omarchy's menu
 * too) is a zone picked by hand, and one where it's already tomorrow would start a new day of
 * limits and another daily break, as on the phone. So the laptop goes by the zone the phone set
 * itself ([sharedZone], from the shared state), and with none known by the zone it last went by,
 * at first the one it first ran in. The system's zone is read from /etc/localtime, not from TZ,
 * which the daemon's own environment could set.
 *
 * [file] keeps all of it across restarts.
 */
class ClockGuard(
    private val raw: Clock,
    private val file: File,
    private val synced: () -> Boolean = ::ntpSynchronized,
    private val systemZone: () -> ZoneId = ::systemZone,
) : Clock {

    /** I54: the zone the phone set itself, when one is known; wired to the store once there is one. */
    @Volatile
    var sharedZone: () -> ZoneId? = { null }

    @Serializable
    private data class Saved(
        val skewMs: Long = 0,
        val offset: Long,
        val mono: Long,
        val zone: String,
        /** The latest time seen ([nowWallMs]), now and then: the time doesn't go back past it after a boot. */
        val seenWall: Long = 0,
    )

    @Volatile
    private var saved: Saved = load()

    /** How far the laptop's clock has been moved by hand, in ms (positive = set ahead). */
    val skewMs: Long get() = saved.skewMs

    override fun nowMonotonicMs(): Long = raw.nowMonotonicMs()

    override fun nowWallMs(): Long {
        val wall = raw.nowWallMs()
        return wall - book(wall, raw.nowMonotonicMs()).skewMs
    }

    override fun zone(): ZoneId = sharedZone() ?: kept()

    /** The laptop's own zone, when blocks go by another ([zone]) because it was picked by hand. */
    val handPickedZone: ZoneId? get() = systemZone().takeIf { it != zone() }

    /**
     * Now and then, and on waking: books a jump nothing has read yet, trusts the clock again once
     * NTP keeps it, and keeps the phone's zone as the one to go by should it stop arriving.
     */
    @Synchronized
    fun check() {
        val wall = raw.nowWallMs()
        var s = book(wall, raw.nowMonotonicMs())
        if (s.skewMs != 0L && synced()) s = s.copy(skewMs = 0)
        if (wall - s.skewMs - s.seenWall > SEEN_EVERY_MS) s = s.copy(seenWall = wall - s.skewMs)
        sharedZone()?.let { if (it.id != s.zone) s = s.copy(zone = it.id) }
        if (s != saved) save(s)
    }

    /**
     * An answer from the sync server, sent at [dateMs] by its clock (whole seconds) and [tookMs]
     * after it was asked: the time now is at most [dateMs] + 1 s + [tookMs]. Ahead of that by more
     * than [SERVER_TOLERANCE_MS] while NTP doesn't keep the clock, the laptop's time is taken back
     * to it: a clock set ahead across a reboot, where there was nothing to compare against. Only
     * ever back: the server's time never takes the laptop's forward, so an answer from anywhere
     * else can't end a lock or start a day. NTP keeping the clock again trusts it ([check]).
     */
    @Synchronized
    fun serverTime(dateMs: Long, tookMs: Long) {
        val wall = raw.nowWallMs()
        val s = book(wall, raw.nowMonotonicMs())
        val latest = dateMs + 1_000 + tookMs.coerceAtLeast(0)
        if (wall - s.skewMs - latest <= SERVER_TOLERANCE_MS || synced()) return
        // The times seen since the boot were the hand-set ones: not a floor for the next boot.
        save(s.copy(skewMs = wall - latest, seenWall = minOf(s.seenWall, latest)))
    }

    /** What a person should know about the clock on the local page, or null. */
    fun note(): String? {
        val skew = skewMs
        val zone = zone()
        val picked = handPickedZone
        return when {
            skew != 0L -> "Set ${duration(abs(skew))} ${if (skew > 0) "ahead" else "back"} by hand. To trust it again: timedatectl set-ntp true"
            picked != null -> "Set to ${picked.id} by hand: blocks go by ${zone.id}"
            else -> null
        }
    }

    private fun kept(): ZoneId = runCatching { ZoneId.of(saved.zone) }.getOrElse { systemZone() }

    /** Books any jump of the clock since the last look; writes only when something moved. */
    @Synchronized
    private fun book(wall: Long, mono: Long): Saved {
        val s = saved
        val offset = wall - mono
        val sameBoot = mono >= s.mono // otherwise the two offsets aren't comparable
        val jumped = sameBoot && abs(offset - s.offset) > TOLERANCE_MS
        if (sameBoot && !jumped) return s
        val skew = when {
            synced() -> 0
            // A new boot: nothing to compare against, and never back past the time last seen.
            !sameBoot -> minOf(s.skewMs, wall - s.seenWall)
            else -> s.skewMs + (offset - s.offset)
        }
        return save(s.copy(skewMs = skew, offset = offset, mono = mono, seenWall = maxOf(s.seenWall, wall - skew)))
    }

    private fun load(): Saved {
        val read = runCatching { json.decodeFromString(Saved.serializer(), file.readText()) }.getOrNull()
        val wall = raw.nowWallMs()
        return read ?: save(Saved(offset = wall - raw.nowMonotonicMs(), mono = raw.nowMonotonicMs(), zone = systemZone().id, seenWall = wall))
    }

    private fun save(s: Saved): Saved {
        saved = s
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(Saved.serializer(), s))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        return s
    }

    private fun duration(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes >= 48 * 60 -> "${minutes / (24 * 60)} days"
            minutes >= 120 -> "${minutes / 60} hours"
            minutes >= 2 -> "$minutes minutes"
            else -> "${ms / 1000} seconds"
        }
    }

    companion object {
        /** Clock drift and NTP's small corrections stay well under this; a change by hand doesn't. */
        const val TOLERANCE_MS = 5_000L

        /** How far ahead of the sync server's time the laptop's may be ([serverTime]): its seconds, slow answers, its own clock. */
        const val SERVER_TOLERANCE_MS = 2 * 60_000L

        /** How often [check] notes the time seen; staler only makes the floor after a boot lower. */
        const val SEEN_EVERY_MS = 10 * 60_000L

        /** Whether NTP keeps the clock now. Setting it by hand clears this until NTP sets it again. */
        fun ntpSynchronized(): Boolean = timedatectl("NTPSynchronized") == "yes"

        /** The zone the system is set to: /etc/localtime, else timedated, else the JVM's. */
        fun systemZone(): ZoneId {
            val link = runCatching { Files.readSymbolicLink(Path.of("/etc/localtime")).toString() }.getOrNull()
            val id = link?.substringAfter("zoneinfo/", "")?.takeIf { it.isNotEmpty() } ?: timedatectl("Timezone")
            return id?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
        }

        private fun timedatectl(property: String): String? = runCatching {
            val process = ProcessBuilder("timedatectl", "show", "-p", property, "--value").redirectErrorStream(true).start()
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            process.inputStream.bufferedReader().readText().trim().takeIf { process.exitValue() == 0 && it.isNotEmpty() }
        }.getOrNull()
    }
}
