package com.xern.xrnrule.desktop

import com.xern.xrnrule.domain.BlockEdits
import com.xern.xrnrule.domain.BlockEngine
import com.xern.xrnrule.domain.BlockEvent
import com.xern.xrnrule.domain.Clock
import com.xern.xrnrule.domain.Effect
import com.xern.xrnrule.domain.Outcome
import com.xern.xrnrule.domain.HabitEntry
import com.xern.xrnrule.domain.PersistPort
import com.xern.xrnrule.domain.ShieldPlan
import com.xern.xrnrule.domain.ShieldPort
import com.xern.xrnrule.domain.Telemetry
import com.xern.xrnrule.domain.World
import com.xern.xrnrule.domain.applyOutcome
import com.xern.xrnrule.domain.withoutBookkeeping
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.ZoneId
import java.util.Properties

/**
 * `~/.config/xrnrule/desktop.properties`:
 *
 *     server=https://your-server         # both sync services hang off it: /xrnrule, /xrnloops
 *     user=you
 *     passwordFile=~/.config/xrnrule/password   # a file holding just the password; or password=...
 *     port=47321                         # the local page and the browser extension's door
 *     # ruleUrl=… / loopUrl=…           # only if the two services don't live at server/xrnrule, server/xrnloops
 *     # openBlockPage=false              # don't open the block page when a blocked app is closed
 *
 * Without a server the daemon still enforces, just on its own.
 */
data class Config(
    val server: String,
    val user: String,
    val password: String,
    val port: Int,
    val dataDir: File,
    val ruleUrl: String = "$server/xrnrule",
    val loopUrl: String = "$server/xrnloops",
    val openBlockPage: Boolean = true,
) {
    val syncConfigured: Boolean get() = ruleUrl.startsWith("http") && user.isNotEmpty() && password.isNotEmpty()
    val localUrl: String get() = "http://127.0.0.1:$port"

    companion object {
        const val DEFAULT_PORT = 47321
        private val home: String = System.getProperty("user.home")

        fun file(): File = System.getenv("XRNRULE_CONFIG")?.let(::File)
            ?: File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "xrnrule/desktop.properties")

        fun load(from: File = file()): Config {
            val props = Properties()
            from.takeIf { it.isFile }?.reader()?.use(props::load)
            fun path(p: String) = File(p.replaceFirst(Regex("^~(?=/|$)"), home))
            val password = props.getProperty("password")
                ?: props.getProperty("passwordFile")?.let { f -> path(f).takeIf { it.isFile }?.readText()?.trim() }
                ?: ""
            val dataHome = System.getenv("XDG_DATA_HOME") ?: "$home/.local/share"
            val server = props.getProperty("server", "").trim().trimEnd('/')
            return Config(
                server = server,
                user = props.getProperty("user", "").trim(),
                password = password,
                port = props.getProperty("port")?.toIntOrNull() ?: DEFAULT_PORT,
                dataDir = path(props.getProperty("dataDir") ?: "$dataHome/xrnrule"),
                ruleUrl = props.getProperty("ruleUrl")?.trim()?.trimEnd('/') ?: "$server/xrnrule",
                loopUrl = props.getProperty("loopUrl")?.trim()?.trimEnd('/') ?: "$server/xrnloops",
                openBlockPage = props.getProperty("openBlockPage")?.trim() != "false",
            )
        }
    }
}

/**
 * Wall time is the system clock; "monotonic" is CLOCK_BOOTTIME from `/proc/uptime`, which,
 * like Android's `elapsedRealtime`, keeps counting while the laptop sleeps, so a lid closed on
 * an unlock doesn't stretch it.
 */
class DesktopClock : Clock {
    private val uptime = File("/proc/uptime")

    override fun nowMonotonicMs(): Long = try {
        (uptime.readText().substringBefore(' ').toDouble() * 1000).toLong()
    } catch (_: Exception) {
        System.nanoTime() / 1_000_000
    }

    override fun nowWallMs(): Long = System.currentTimeMillis()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * The whole [World] as one JSON file, replaced atomically (written, synced, then moved over).
 * Saves that only move timer bookkeeping forward are held back for up to 30 s, as on the phone:
 * the 1 s tick would otherwise write the disk every second. An unreadable file is kept aside
 * rather than overwritten.
 *
 * [sharedBase] is the base of the shared state ([SharedSyncClient]), which means something only
 * beside the world that agreed on it: when the world is gone or unreadable, it goes too (§2.4).
 */
class FilePersist(private val file: File, private val clock: Clock, private val sharedBase: File? = null) : PersistPort {
    private var saved: World? = null
    private var savedAtMono = 0L
    private var pending: World? = null

    /** The habit history (contract §2.5) has its own file, written only when it changed. */
    private val entriesFile = File(file.parentFile, "habit-entries.json")
    private val entriesSerializer = MapSerializer(String.serializer(), HabitEntry.serializer())
    private var savedEntries: Map<String, HabitEntry>? = null

    @Synchronized
    override fun loadWorld(): World {
        var world = if (!file.exists()) {
            forgetSharedBase()
            World()
        } else {
            try {
                json.decodeFromString(World.serializer(), file.readText())
            } catch (e: Exception) {
                forgetSharedBase()
                keepUnreadable(file, e)
                World()
            }
        }
        val entries = if (!entriesFile.exists()) null else try {
            json.decodeFromString(entriesSerializer, entriesFile.readText())
        } catch (e: Exception) {
            keepUnreadable(entriesFile, e) // the habit server has them; unsent ones are in the copy
            null
        }
        if (entries != null) {
            savedEntries = entries
            world = world.copy(habitEntries = world.habitEntries + entries)
        } else if (world.habitEntries.isEmpty() && world.habitSyncSeq > 0) {
            world = world.copy(habitSyncSeq = 0) // read the history from the habit server's start again (§2.5)
        }
        // A number below zero saved by an older build, or written into the file by hand, is read as
        // what it means (BlockEdits.sane), as what comes by sync is: no limit, an unlock of no
        // length. The engine never meets one: a negative unlock, once redeemed, threw on every event.
        world = BlockEdits.sane(world)
        saved = world
        savedAtMono = clock.nowMonotonicMs()
        return world
    }

    /**
     * Starting without the world, this laptop deleted nothing. Against the old base, the first
     * round would take every block missing here for deleted, the locked ones too, and send that
     * to every device; without one, it merges as a first sync and the server's blocks come back.
     */
    private fun forgetSharedBase() {
        val base = sharedBase?.takeIf { it.exists() } ?: return
        if (base.delete()) System.err.println("Starting without the world, so ${base.name} went too: the next sync takes the server's blocks")
        else System.err.println("Couldn't delete ${base.name}: the next sync may take the lost world's blocks for deleted")
    }

    private fun keepUnreadable(f: File, e: Exception) {
        val copy = File(f.parentFile, "${f.nameWithoutExtension}.unreadable-${System.currentTimeMillis()}.json")
        f.copyTo(copy, overwrite = true)
        System.err.println("${f.name} unreadable ($e); kept as ${copy.name}, starting without it")
    }

    @Synchronized
    override fun save(world: World) {
        val last = saved
        val bookkeepingOnly = last != null && world.withoutBookkeeping() == last.withoutBookkeeping()
        if (bookkeepingOnly && clock.nowMonotonicMs() - savedAtMono < 30_000) {
            pending = world
            return
        }
        write(world)
    }

    @Synchronized
    fun flush() {
        pending?.let(::write)
    }

    private fun write(world: World) {
        file.parentFile.mkdirs()
        // The entries first: a crash in between leaves them newer, which only means work again.
        if (world.habitEntries !== savedEntries) {
            writeAtomically(entriesFile, json.encodeToString(entriesSerializer, world.habitEntries))
            savedEntries = world.habitEntries
        }
        writeAtomically(file, json.encodeToString(World.serializer(), world.copy(habitEntries = emptyMap())))
        saved = world
        savedAtMono = clock.nowMonotonicMs()
        pending = null
    }

    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.path + ".tmp")
        FileChannel.open(tmp.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { ch ->
            ch.write(java.nio.ByteBuffer.wrap(text.encodeToByteArray()))
            ch.force(true)
        }
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            throw IOException("can't replace $target", e)
        }
    }
}

object NoTelemetry : Telemetry {
    override fun onBlockedImpression(pkg: String, atWallMs: Long) = Unit
    override fun onSiteBlocked(host: String, atWallMs: Long) = Unit
    override fun onMinutesBought(blockIds: Set<String>, ms: Long, atWallMs: Long) = Unit
    override fun onBreakTaken(ms: Long, atWallMs: Long) = Unit
    override fun onHabitCompleted(blockId: String, atWallMs: Long) = Unit
    override fun onUnlockConsumed(blockId: String, ms: Long, atWallMs: Long, startedAtWall: Long) = Unit
}

/**
 * The laptop's event log, in the phone's format (`LocalTelemetry`): a blocked app or site opened,
 * a habit done, an unlock used, minutes bought, a break taken, one `;`-separated line each. [SharedSyncClient] sends new lines
 * to the server for the weekly review in the habits web app.
 */
class FileTelemetry(private val file: File) : Telemetry {
    override fun onBlockedImpression(pkg: String, atWallMs: Long) = append("blocked;$pkg;$atWallMs")
    override fun onSiteBlocked(host: String, atWallMs: Long) = append("site;$host;$atWallMs")
    override fun onMinutesBought(blockIds: Set<String>, ms: Long, atWallMs: Long) =
        append("bought;${blockIds.joinToString(",")};$ms;$atWallMs")
    override fun onBreakTaken(ms: Long, atWallMs: Long) = append("break;$ms;$atWallMs")
    override fun onHabitCompleted(blockId: String, atWallMs: Long) = append("habit;$blockId;$atWallMs")
    override fun onUnlockConsumed(blockId: String, ms: Long, atWallMs: Long, startedAtWall: Long) =
        append("unlock;$blockId;$ms;$atWallMs;$startedAtWall")

    @Synchronized
    private fun append(line: String) {
        runCatching {
            file.parentFile.mkdirs()
            file.appendText(line + "\n")
        }
    }

    /** The log's size in bytes, a cheap "anything new?" check. */
    fun size(): Long = file.length()

    /** Events after [sinceMs] as the server takes them (`POST /events`), oldest first. */
    @Synchronized
    fun eventsAfter(sinceMs: Long): List<JsonObject> = lines().mapNotNull { line ->
        val f = line.split(';')
        when (f.firstOrNull()) {
            "blocked" -> f.getOrNull(2)?.toLongOrNull()?.let { at ->
                buildJsonObject {
                    put("t", "blocked")
                    put("at", at)
                    put("target", f[1])
                    put("name", DesktopApps.name(f[1]))
                }
            }
            "site" -> f.getOrNull(2)?.toLongOrNull()?.let { at ->
                buildJsonObject {
                    put("t", "blocked")
                    put("at", at)
                    put("target", f[1])
                    put("name", f[1])
                }
            }
            "bought" -> {
                val ms = f.getOrNull(2)?.toLongOrNull()
                val at = f.getOrNull(3)?.toLongOrNull()
                if (ms == null || at == null) {
                    null
                } else {
                    buildJsonObject {
                        put("t", "bought")
                        put("at", at)
                        put("block", f[1])
                        put("ms", ms)
                    }
                }
            }
            "break" -> {
                val ms = f.getOrNull(1)?.toLongOrNull()
                val at = f.getOrNull(2)?.toLongOrNull()
                if (ms == null || at == null) {
                    null
                } else {
                    buildJsonObject {
                        put("t", "break")
                        put("at", at)
                        put("ms", ms)
                    }
                }
            }
            "habit" -> f.getOrNull(2)?.toLongOrNull()?.let { at ->
                buildJsonObject {
                    put("t", "habit")
                    put("at", at)
                    put("block", f[1])
                }
            }
            "unlock" -> {
                val ms = f.getOrNull(2)?.toLongOrNull()
                val at = f.getOrNull(3)?.toLongOrNull()
                val start = f.getOrNull(4)?.toLongOrNull() ?: 0
                if (ms == null || at == null) {
                    null
                } else {
                    buildJsonObject {
                        put("t", "unlock")
                        put("at", at)
                        put("block", f[1])
                        put("ms", ms)
                        if (start > 0) put("start", start)
                    }
                }
            }
            else -> null
        }
    }.filter { it["at"]!!.jsonPrimitive.long > sinceMs }

    /** Drops lines older than [keepDays] (the server keeps the same 120). */
    @Synchronized
    fun prune(nowWallMs: Long, keepDays: Int = 120) {
        val cutoff = nowWallMs - keepDays * 86_400_000L
        val lines = lines()
        val kept = lines.filter { line -> eventTime(line)?.let { it >= cutoff } != false }
        if (kept.size == lines.size) return
        val tmp = File(file.path + ".tmp")
        tmp.writeText(kept.joinToString("") { it + "\n" })
        tmp.renameTo(file)
    }

    private fun lines(): List<String> = runCatching { file.readLines() }.getOrDefault(emptyList())

    /** When a line's event happened: the third field, or the fourth for an unlock. */
    private fun eventTime(line: String): Long? {
        val f = line.split(';')
        return (if (f.firstOrNull() in setOf("unlock", "bought")) f.getOrNull(3) else f.getOrNull(2))?.toLongOrNull()
    }
}

/**
 * Serializes every change to the [World] through one mutex, as the phone's `WorldStore` does,
 * so persist-before-shield (I7) holds whoever calls: the tick, D-Bus, the local page, sync.
 */
class Store(
    val engine: BlockEngine,
    private val persist: PersistPort,
    val clock: Clock,
    private val scope: CoroutineScope,
    private val telemetry: Telemetry = NoTelemetry,
) {
    @Volatile
    var shield: ShieldPort = object : ShieldPort {
        override fun apply(plan: ShieldPlan) = Unit
    }

    private val _world = MutableStateFlow(persist.loadWorld())
    val world: StateFlow<World> = _world.asStateFlow()
    private val mutex = Mutex()
    private val _effects = MutableSharedFlow<Effect>(extraBufferCapacity = 32)
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    fun dispatch(event: BlockEvent) {
        scope.launch { dispatchAndGet(event) }
    }

    suspend fun dispatchAndGet(event: BlockEvent): World = dispatchOutcome(event).world

    /**
     * I8 at a start, done before [feed] starts what reports to the engine (the window system, the
     * extension's door). The world as loaded holds the monotonic stamps of before the daemon
     * stopped: the first window or page reported would otherwise charge all the time it was
     * stopped to whatever was in front then, to a limit or to a habit app's timer.
     */
    suspend fun coldStart(feed: () -> Unit) {
        dispatchAndGet(BlockEvent.BootOrColdStart) // and the first shield plan
        feed()
    }

    /** As [dispatchAndGet], with the effects too: the caller wants to know how it went. */
    suspend fun dispatchOutcome(event: BlockEvent): Outcome = mutex.withLock {
        val outcome = engine.reduce(_world.value, event, clock)
        applyOutcome(outcome, persist, shield, telemetry, clock.nowWallMs())
        _world.value = outcome.world
        outcome.effects.forEach { _effects.tryEmit(it) }
        outcome
    }

    /**
     * A structural edit; a block whose settings or checklist change is stamped, so the later edit
     * wins and a lock can refuse one made without it (contract §2.4, [BlockEdits.stamped]).
     */
    suspend fun edit(transform: (World) -> World): World = mutex.withLock {
        val before = _world.value
        val edited = BlockEdits.stamped(before, transform(before), clock.nowWallMs())
        val plan = engine.shieldPlan(edited, clock)
        val next = edited.copy(appliedShield = plan)
        persist.save(next)
        shield.apply(plan)
        _world.value = next
        next
    }
}
