package com.xern.xrnrule.desktop

import com.xern.xrnrule.domain.Clock
import com.xern.xrnrule.domain.Sites
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.Serializable

/**
 * Screen time on the laptop (docs/laptop-screen-time.md): how long each app was in front, and
 * each site in the browser in front, per local day. Statistics only: the engine never reads it,
 * so nothing here can loosen a limit.
 *
 * [show] says what is in front now (from [Foreground]), [idle] and [active] when input stopped
 * and came back ([IdleWatch]); the daemon's tick calls [tick] every second, and a longer gap is
 * the laptop asleep. Time runs on the boot-time clock, which counts through sleep, and goes to
 * days by the wall clock, split at local midnight.
 *
 * Idle is only known [holdBackMs] after input stopped, and then that time is taken back. So the
 * last [holdBackMs] of use stay pending: shown ([snapshot]) but not stored or sent, until they
 * are that old. A stored figure only ever grows, which lets the server keep the largest it saw.
 */
class UsageLog(private val file: File, private val clock: Clock, private val holdBackMs: Long = IdleWatch.IDLE_MS) {

    /** A day's time: app id -> ms, host -> ms. A site's time is part of its browser's. */
    @Serializable
    data class Day(val apps: Map<String, Long> = emptyMap(), val sites: Map<String, Long> = emptyMap()) {
        val totalMs: Long get() = apps.values.sum()
    }

    @Serializable
    private data class Stored(val days: Map<Long, Day> = emptyMap())

    /** An app in front, and the site in it when it is a browser on one. */
    data class Target(val app: String, val site: String?)

    private class Span(val target: Target, val fromMono: Long, val toMono: Long)

    /** Stored: epoch day -> its time. */
    private var booked: Map<Long, Day> = load()

    /** Closed but not stored yet: the last [holdBackMs] of use, which an idle notice may take back. */
    private val pending = ArrayList<Span>()

    private var target: Target? = null
    private var idle = false

    /** What is counting now, since when (boot-time ms); null while nothing is in front or input stopped. */
    private var openSince: Long? = null

    /** What was last sent to the server, by day. */
    private var sent: Map<Long, Day> = emptyMap()

    /** The last tick: the laptop was awake then. */
    private var lastSeen = clock.nowMonotonicMs()

    /** The app [app] (null: none) is in front, on [site] when it is a browser on one. */
    @Synchronized
    fun show(app: String?, site: String?) {
        val next = app?.takeIf { it.isNotBlank() }?.let { Target(it, site?.takeIf(String::isNotBlank)?.let(Sites::usageHost)) }
        if (next == target) return
        val now = clock.nowMonotonicMs()
        close(now)
        target = next
        if (!idle && next != null) openSince = now
    }

    /** Input stopped at [sinceMono]: nothing counts from then until [active]. */
    @Synchronized
    fun idle(sinceMono: Long) {
        val now = clock.nowMonotonicMs()
        close(now)
        idle = true
        val cut = minOf(sinceMono, now)
        // What ran past the moment input stopped wasn't use.
        val kept = pending.mapNotNull { s -> if (s.fromMono >= cut) null else if (s.toMono <= cut) s else Span(s.target, s.fromMono, cut) }
        pending.clear()
        pending += kept
    }

    /** Input came back: what's in front counts again. */
    @Synchronized
    fun active() {
        if (!idle) return
        idle = false
        if (target != null) openSince = clock.nowMonotonicMs()
    }

    /**
     * The daemon's tick, every second. A gap longer than [GAP_MS] since the last one was the laptop
     * asleep: what was counting ends at the last tick, and counts again from now.
     */
    @Synchronized
    fun tick() {
        val now = clock.nowMonotonicMs()
        if (now - lastSeen > GAP_MS) {
            close(now)
            if (!idle && target != null) openSince = now
        }
        lastSeen = now
    }

    /** Stores what is older than [holdBackMs]; writes the file if anything changed. */
    @Synchronized
    fun flush() = book(clock.nowMonotonicMs() - holdBackMs)

    /** The daemon is stopping: nothing can be taken back any more, so all of it is stored. */
    @Synchronized
    fun stop() {
        val now = clock.nowMonotonicMs()
        close(now)
        book(now)
    }

    /** Every day's time, what's pending and in front now included: for the pages. */
    @Synchronized
    fun snapshot(): Map<Long, Day> {
        val days = booked.toMutableMap()
        val now = clock.nowMonotonicMs()
        val spans = pending + listOfNotNull(target?.takeIf { openSince != null }?.let { Span(it, openSince!!, now) })
        for (s in spans) add(days, s.target, s.fromMono, s.toMono, now)
        return days
    }

    /**
     * The stored days to send the server: today and yesterday every time (so a server that lost
     * its last write gets them again), and any other that changed since the last [sentOk].
     */
    @Synchronized
    fun toSend(): Map<Long, Day> {
        val today = today()
        return booked.filter { (day, use) -> day >= today - 1 || sent[day] != use }
    }

    /** The server took [days]. */
    @Synchronized
    fun sentOk(days: Map<Long, Day>) {
        sent = sent + days
    }

    private fun today(): Long = Instant.ofEpochMilli(clock.nowWallMs()).atZone(clock.zone()).toLocalDate().toEpochDay()

    /**
     * Ends what's counting at [atMono], into [pending]. When there has been no tick for [GAP_MS]
     * the laptop was asleep (the tick may not have seen it yet): what began before that ends at the
     * last tick, and only what began since waking counts up to [atMono].
     */
    private fun close(atMono: Long) {
        val since = openSince ?: return
        openSince = null
        val t = target ?: return
        val asleep = atMono - lastSeen > GAP_MS && since - lastSeen <= GAP_MS
        val end = if (asleep) lastSeen else atMono
        if (end > since) pending += Span(t, since, end)
    }

    /** Stores everything that ran before [untilMono]; what's counting now too, up to there. */
    private fun book(untilMono: Long) {
        val now = clock.nowMonotonicMs()
        val since = openSince
        if (since != null && untilMono > since) {
            pending += Span(target!!, since, untilMono)
            openSince = untilMono
        }
        val ready = pending.filter { it.fromMono < untilMono }
        if (ready.isEmpty()) return
        val later = pending.filter { it.fromMono >= untilMono }
        val days = booked.toMutableMap()
        pending.clear()
        for (s in ready) {
            val end = minOf(s.toMono, untilMono)
            add(days, s.target, s.fromMono, end, now)
            if (s.toMono > end) pending += Span(s.target, end, s.toMono)
        }
        pending += later
        val oldest = today() - KEEP_DAYS
        booked = days.filterKeys { it > oldest }
        save()
    }

    /** Adds [fromMono]..[toMono] of [t] to [days], each part to the local day it fell on. */
    private fun add(days: MutableMap<Long, Day>, t: Target, fromMono: Long, toMono: Long, nowMono: Long) {
        if (toMono <= fromMono) return
        val offset = clock.nowWallMs() - nowMono
        var cursor = fromMono + offset
        val end = toMono + offset
        val zone = clock.zone()
        while (cursor < end) {
            val date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
            val nextMidnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val slice = minOf(end, nextMidnight) - cursor
            val day = days[date.toEpochDay()] ?: Day()
            days[date.toEpochDay()] = Day(
                apps = day.apps + (t.app to (day.apps[t.app] ?: 0) + slice),
                sites = if (t.site == null) day.sites else day.sites + (t.site to (day.sites[t.site] ?: 0) + slice),
            )
            cursor += slice
        }
    }

    private fun load(): Map<Long, Day> {
        if (!file.exists()) return emptyMap()
        return try {
            json.decodeFromString(Stored.serializer(), file.readText()).days
        } catch (e: Exception) {
            // Kept aside rather than written over: the server has most of it anyway.
            runCatching { file.copyTo(File(file.parentFile, "${file.nameWithoutExtension}.unreadable-${System.currentTimeMillis()}.json"), overwrite = true) }
            System.err.println("${file.name} unreadable ($e); counting screen time afresh")
            emptyMap()
        }
    }

    private fun save() {
        runCatching {
            file.parentFile.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(Stored.serializer(), Stored(booked)))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { System.err.println("Couldn't write ${file.name}: $it") }
    }

    companion object {
        /** As long as the server and the event log keep. */
        const val KEEP_DAYS = 120L

        /** Longer between two ticks is a sleep, as the daemon's tick takes it. */
        const val GAP_MS = 10_000L

        /** An epoch day as the server and the pages name it, "2026-11-26". */
        fun dayName(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).toString()
    }
}
