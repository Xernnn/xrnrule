package com.xern.xrnrule.app.data

import androidx.core.util.AtomicFile
import com.xern.xrnrule.domain.Clock
import com.xern.xrnrule.domain.HabitEntry
import com.xern.xrnrule.domain.PersistPort
import com.xern.xrnrule.domain.World
import com.xern.xrnrule.domain.withoutBookkeeping
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException

/**
 * Persists the [World] as JSON: everything in [file], except the habit history
 * ([World.habitEntries], years of days) in `habit-entries.json` next to it, written only when it
 * changed. The data is always read/written as a unit, so files beat a relational schema here.
 * [AtomicFile] syncs the new copy to disk before swapping it in, so a crash or power cut
 * mid-save leaves the previous copy intact. The entries go first: a crash in between leaves them
 * newer than the world, which only means a habit item is worked out again.
 *
 * [sharedBase] is the base of the shared state ([SharedSync]), which means something only beside
 * the world that agreed on it: when the world is gone or unreadable, it goes too (§2.4).
 */
class FilePersistPort(private val file: File, private val sharedBase: File? = null) : PersistPort {

    private val atomic = AtomicFile(file)
    private val entriesFile = File(file.parentFile, "habit-entries.json")
    private val entriesAtomic = AtomicFile(entriesFile)
    private val entriesSerializer = MapSerializer(String.serializer(), HabitEntry.serializer())

    /** The entries as last read or written: the same map again needs no write. */
    private var savedEntries: Map<String, HabitEntry>? = null
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Set when the file existed but couldn't be read at start: a copy of it, kept because the
     * next save would otherwise replace the user's blocks with an empty world for good.
     */
    @Volatile
    var unreadableCopy: File? = null
        private set

    override fun loadWorld(): World {
        val bytes = try {
            atomic.readFully()
        } catch (_: FileNotFoundException) {
            forgetSharedBase() // a first run has none; a world that went missing leaves one behind
            return World()
        }
        val world = runCatching { json.decodeFromString(World.serializer(), bytes.decodeToString()) }
            .getOrElse {
                forgetSharedBase()
                val copy = File(file.parentFile, "world.unreadable-${System.currentTimeMillis()}.json")
                if (runCatching { copy.writeBytes(bytes) }.isSuccess) unreadableCopy = copy
                return World()
            }
        val entries = loadEntries()
        savedEntries = entries ?: emptyMap()
        // Without its entries this phone reads the history from the habit server's start again (§2.5).
        val lost = entries == null && world.habitEntries.isEmpty() && world.habitSyncSeq > 0
        // Entries still inside the world file (written before they had their own) count too.
        return world.copy(
            habitEntries = world.habitEntries + (entries ?: emptyMap()),
            habitSyncSeq = if (lost) 0 else world.habitSyncSeq,
        )
    }

    /**
     * Starting without the world, this phone deleted nothing. Against the old base, the first
     * round would take every block missing here for deleted, the locked ones too, and send that
     * to every device; without one, it merges as a first sync and the server's blocks come back.
     */
    private fun forgetSharedBase() {
        sharedBase?.let { AtomicFile(it).delete() } // with any copy AtomicFile would restore it from
    }

    /**
     * An unreadable entries file is kept aside like the world's: the habit server has the history,
     * so the next sync reads it back in, but anything not yet sent would be lost without the copy.
     * Null when there are none to read.
     */
    private fun loadEntries(): Map<String, HabitEntry>? {
        val bytes = try {
            entriesAtomic.readFully()
        } catch (_: FileNotFoundException) {
            return null
        }
        return runCatching { json.decodeFromString(entriesSerializer, bytes.decodeToString()) }.getOrElse {
            val copy = File(file.parentFile, "habit-entries.unreadable-${System.currentTimeMillis()}.json")
            if (runCatching { copy.writeBytes(bytes) }.isSuccess) unreadableCopy = copy
            null
        }
    }

    @Synchronized
    override fun save(world: World) {
        if (world.habitEntries !== savedEntries) {
            write(entriesAtomic, json.encodeToString(entriesSerializer, world.habitEntries))
            savedEntries = world.habitEntries
        }
        write(atomic, json.encodeToString(World.serializer(), world.copy(habitEntries = emptyMap())))
    }

    private fun write(file: AtomicFile, text: String) {
        val out = file.startWrite()
        try {
            out.write(text.encodeToByteArray())
            file.finishWrite(out)
        } catch (e: Throwable) {
            file.failWrite(out)
            throw e
        }
    }
}

/**
 * Skips saves that only move timer bookkeeping forward. Every ~1 s tick advances
 * [com.xern.xrnrule.domain.HabitProgress] markers, so saving each one rewrote the file every
 * second, all day. Anything else — a state change, a credit, the applied shield (so I7 still
 * holds: a new shield plan is always saved first) — is written at once; timer-only changes at
 * most every [maxDelayMs], and on [flush]. A process death loses at most that much accrual.
 */
class ThrottledPersist(
    private val inner: PersistPort,
    private val clock: Clock,
    private val maxDelayMs: Long = 30_000,
) : PersistPort {

    private var saved: World? = null
    private var savedAtMono = 0L
    private var pending: World? = null

    @Synchronized
    override fun loadWorld(): World = inner.loadWorld().also {
        saved = it
        savedAtMono = clock.nowMonotonicMs()
    }

    @Synchronized
    override fun save(world: World) {
        val last = saved
        val bookkeepingOnly = last != null && world.withoutBookkeeping() == last.withoutBookkeeping()
        if (bookkeepingOnly && clock.nowMonotonicMs() - savedAtMono < maxDelayMs) {
            pending = world
            return
        }
        write(world)
    }

    /** Writes a held-back save now (screen off, service stopping). */
    @Synchronized
    fun flush() {
        pending?.let(::write)
    }

    private fun write(world: World) {
        inner.save(world)
        saved = world
        savedAtMono = clock.nowMonotonicMs()
        pending = null
    }
}
