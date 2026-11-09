package com.xern.xrnrule.desktop

import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockEvent
import com.xern.xrnrule.domain.ShieldPlan
import com.xern.xrnrule.domain.ShieldPort
import com.xern.xrnrule.domain.Sites
import com.xern.xrnrule.domain.World
import com.xern.xrnrule.domain.appliesHere
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** One window as KWin (or Hyprland) reports it: the `.desktop` id when it has one, else its WM class. */
data class AppWindow(val appId: String, val resourceClass: String, val pid: Long)

/**
 * What the KWin script (`desktop/kwin`) calls over D-Bus, and [HyprlandWindows] calls directly. Arguments are strings: a JS number
 * reaches D-Bus as a double, which wouldn't match an `i` argument.
 */
@DBusInterfaceName("com.xern.xrnrule.Desktop")
interface DesktopBus : DBusInterface {
    /** A window came to the front. Answers "block" when KWin should close it. */
    fun Activated(appId: String, resourceClass: String, pid: String): String

    /** A window opened (or the script started and is listing what's open). Answers like [Activated]. */
    fun Added(appId: String, resourceClass: String, pid: String): String

    fun Removed(pid: String)
}

/**
 * Which app and site are "in use" right now, for [BlockEvent.ForegroundChanged]. The active
 * window comes from KWin; the site from the browser extension, which reports the focused tab's
 * host a few times a second while that browser window has focus. The daemon's own block page
 * reports the app or site it is standing in for, so a TIMER that counts "waiting on the block
 * screen" (I4) runs while you wait there. A locked screen is "nothing in use", as on the phone.
 */
class Foreground(private val store: Store, private val browsersFile: File? = null) {
    /** I49. */
    val browsers = BrowserWatch(
        known = browsersFile?.takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }?.toSet().orEmpty(),
        startedAtMs = store.clock.nowMonotonicMs(),
    )

    @Serializable
    /** [standIn]: the extension's own block page, standing in for the site it blocks (as our block page does). */
    data class Heartbeat(
        val host: String? = null,
        val app: String? = null,
        val focused: Boolean = false,
        val fromBlockPage: Boolean = false,
        val standIn: Boolean = false,
        /** The extension's version (versions up to 2.1.1 don't say). */
        val version: String? = null,
        /** I47: playing sound without being in front. */
        val playing: Boolean = false,
        /** Whether the extension may run in incognito windows, if the browser says. */
        val incognito: Boolean? = null,
    )

    @Volatile var active: AppWindow? = null
        set(value) {
            field = value
            // Focus went to an app that isn't a browser: whatever tab was in front no longer is.
            if (value != null && !isBrowser(value.appId) && heartbeat?.fromBlockPage != true) heartbeat = null
            browsers.front(value?.appId, value != null && isBrowser(value.appId), store.clock.nowMonotonicMs())
        }
    @Volatile var locked = false
    @Volatile private var heartbeat: Heartbeat? = null
    @Volatile private var heartbeatAtMono = 0L
    /** I47: pages playing sound out of sight, by when each last said so. */
    private val playing = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var playingSent: Set<String> = emptySet()

    /** Whether the extension may run in incognito; null until a version that says has spoken. */
    @Volatile var incognitoAllowed: Boolean? = null
        private set

    /** The version the extension last said it is. */
    @Volatile var extensionVersion: String? = null
        private set
    /** The (app, site) last sent to the engine. */
    var sent: Pair<String?, String?>? = null
        private set

    /**
     * Only a focused page speaks for the foreground. A visible but unfocused one (a second browser
     * window) mustn't overwrite it; when the focused one loses focus, it says so and is dropped.
     */
    fun onHeartbeat(beat: Heartbeat) {
        val now = store.clock.nowMonotonicMs()
        browsers.heard(now)
        if (!beat.fromBlockPage && beat.focused && browsers.spoke(now)) {
            runCatching { browsersFile?.writeText(browsers.known.joinToString("\n")) }
        }
        if (!beat.fromBlockPage && beat.version != null && beat.version.length <= 20) extensionVersion = beat.version
        if (!beat.fromBlockPage && beat.incognito != null) incognitoAllowed = beat.incognito
        if (beat.playing && !beat.focused) beat.host?.let(Sites::normalizeTarget)?.let { playing[it] = now }
        val current = heartbeat
        if (beat.focused) {
            heartbeat = beat
            heartbeatAtMono = now
        } else if (current != null && current.host == beat.host && current.app == beat.app) {
            heartbeat = null
        }
        update()
    }

    /** The extension asked for its rules: alive, even on a page that doesn't beat. */
    fun heardExtension() = browsers.heard(store.clock.nowMonotonicMs())

    @Synchronized
    fun update() {
        val now = store.clock.nowMonotonicMs()
        playing.values.removeIf { now - it > FRESH_MS }
        if (playing.keys != playingSent) {
            playingSent = playing.keys.toSet()
            store.dispatch(BlockEvent.BackgroundChanged(playingSent)) // I47
        }
        val beat = heartbeat?.takeIf { it.focused && now - heartbeatAtMono <= FRESH_MS }
        val next: Pair<String?, String?> = when {
            locked -> null to null
            beat?.app != null -> beat.app to null
            beat != null -> (active?.appId ?: "browser") to beat.host?.let(Sites::normalizeTarget)
            else -> active?.appId to null
        }
        if (next != sent) {
            sent = next
            store.dispatch(BlockEvent.ForegroundChanged(next.first, next.second))
        }
    }

    /** A browser is in front but the extension has gone quiet: website blocking isn't working. */
    fun extensionMissing(): Boolean {
        val app = active?.appId ?: return false
        return isBrowser(app) && browsers.silentFor(store.clock.nowMonotonicMs()) > EXTENSION_SILENCE_MS
    }

    companion object {
        const val FRESH_MS = 6_000L

        /** The extension asks for its rules once a minute, even on a page where it can't run. */
        const val EXTENSION_SILENCE_MS = 75_000L

        /** Browsers, as a word of their app id. */
        val BROWSERS = setOf(
            "helium", "chrome", "chromium", "brave", "vivaldi", "edge", "opera", "firefox", "firefoxdeveloperedition",
            "zen", "librewolf", "floorp", "thorium",
        )

        /**
         * A word of [appId] (split at `.`, `-`, `_`) is a browser's name: `app.zen_browser.zen`,
         * `org.chromium.Chromium`. Never part of a word, which would take zenity (Steam's prompts)
         * for Zen and close it after 10 s as a browser without the extension (I49).
         */
        fun isBrowser(appId: String): Boolean = appId.lowercase().split(NOT_A_WORD).any { it in BROWSERS }

        private val NOT_A_WORD = Regex("[^a-z0-9]+")
    }
}

/**
 * The laptop's shield: blocked apps are closed through the compositor's [WindowSystem] (and their process stopped if the
 * window doesn't go), blocked sites are the extension's job (it asks [isSiteBlocked]).
 */
class Enforcer(private val store: Store, private val notifier: Notifier, private val config: Config) : ShieldPort, DesktopBus {
    private val windows = ConcurrentHashMap<Long, AppWindow>()
    lateinit var foreground: Foreground

    /** Who reports windows and closes them: KWin's script, or Hyprland's sockets. */
    var windowSystem: WindowSystem = KWin

    /** When the window system last reported in (the name is from when it could only be KWin). */
    @Volatile var kwinSeenAtMs = 0L
        private set

    /** The window system is there and watching, even with no window open yet. */
    fun reported() {
        kwinSeenAtMs = System.currentTimeMillis()
    }

    private fun plan(): ShieldPlan = store.world.value.appliedShield

    fun isAppBlocked(appId: String): Boolean = appId in plan().blockedPackages

    fun isSiteBlocked(host: String): Boolean = plan().siteBlocked(Sites.normalizeTarget(host) ?: host)

    /** Runs inside the store's lock (I7), so the slow part goes to [worker]. */
    override fun apply(plan: ShieldPlan) {
        // Unlocks ending, lockdowns and schedules starting: close what just became blocked.
        val doomed = windows.values.filter { it.appId in plan.blockedPackages }
        if (doomed.isEmpty()) return
        worker.execute {
            val ids = doomed.map { it.appId }.toSet()
            windowSystem.closeWindowsOf(ids)
            ids.forEach { notifier.blocked(it) }
            Thread.sleep(4_000)
            // Closing a window may only hide it to the tray (Steam, Discord): then stop the process.
            for (w in doomed) ProcessHandle.of(w.pid).ifPresent { if (it.isAlive && w.appId in plan().blockedPackages) it.destroy() }
        }
    }

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "enforcer").apply { isDaemon = true } }

    private fun seen(appId: String, resourceClass: String, pid: String): AppWindow? {
        reported()
        val id = DesktopApps.resolve(appId, resourceClass) ?: return null
        val w = AppWindow(id, resourceClass, pid.toLongOrNull() ?: 0)
        if (w.pid > 0) windows[w.pid] = w
        return w
    }

    override fun Activated(appId: String, resourceClass: String, pid: String): String {
        val w = seen(appId, resourceClass, pid)
        foreground.active = w
        foreground.update()
        return verdict(w)
    }

    override fun Added(appId: String, resourceClass: String, pid: String): String = verdict(seen(appId, resourceClass, pid))

    override fun Removed(pid: String) {
        pid.toLongOrNull()?.let { windows.remove(it) }
    }

    private fun verdict(w: AppWindow?): String {
        if (w == null || !isAppBlocked(w.appId)) return "ok"
        notifier.blocked(w.appId)
        return "block"
    }

    override fun getObjectPath(): String = OBJECT_PATH

    companion object {
        const val BUS_NAME = "com.xern.xrnrule"
        const val OBJECT_PATH = "/Desktop"
    }
}

/** Talking to KWin: export [DesktopBus] for our script, and run one-off scripts to close windows. */
object KWin : WindowSystem {
    private var connection: DBusConnection? = null

    override val name = "kwin"

    /** Exports [DesktopBus] and restarts our script, so it lists the open windows again. */
    override fun start(bus: DesktopBus, connected: () -> Unit): Boolean = export(bus).also { if (it) reloadScript() }

    fun export(bus: DesktopBus): Boolean = try {
        val conn = DBusConnectionBuilder.forSessionBus().build()
        conn.requestBusName(Enforcer.BUS_NAME)
        conn.exportObject(Enforcer.OBJECT_PATH, bus)
        connection = conn
        true
    } catch (e: Exception) {
        System.err.println("D-Bus unavailable, desktop apps won't be blocked: $e")
        false
    }

    /** Restarts our KWin script so it lists the open windows again (we may be newer than it). */
    fun reloadScript() {
        qdbus("org.kde.KWin", "/Scripting", "org.kde.kwin.Scripting.unloadScript", "xrnrule")
        qdbus("org.kde.KWin", "/KWin", "org.kde.KWin.reconfigure")
    }

    override fun closeWindowsOf(appIds: Set<String>) {
        if (appIds.isEmpty()) return
        // As JSON strings, which are JS strings: quotes, backslashes and control characters all escaped.
        val list = appIds.joinToString(",") { json.encodeToString(String.serializer(), it) }
        val script = File.createTempFile("xrnrule-close", ".js")
        script.writeText(
            """
            const ids = [$list];
            for (const w of workspace.windowList()) {
                const id = (w.desktopFileName || "").replace(/\.desktop${'$'}/, "");
                const cls = (w.resourceClass || "").toString().toLowerCase();
                if (ids.includes(id) || ids.some(x => x.toLowerCase() === cls)) w.closeWindow();
            }
            """.trimIndent(),
        )
        val name = "xrnrule-close-${System.nanoTime()}"
        val id = qdbus("org.kde.KWin", "/Scripting", "org.kde.kwin.Scripting.loadScript", script.path, name)?.trim()
        if (id != null && id.toIntOrNull() != null && id.toInt() >= 0) {
            qdbus("org.kde.KWin", "/Scripting/Script$id", "org.kde.kwin.Script.run")
            Thread.sleep(300)
        }
        qdbus("org.kde.KWin", "/Scripting", "org.kde.kwin.Scripting.unloadScript", name)
        script.delete()
    }

    private fun qdbus(vararg args: String): String? = try {
        val p = ProcessBuilder(listOf("qdbus6") + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) out else null
    } catch (_: Exception) {
        null
    }

    /** Whether the screen locker is up (KDE's, through the freedesktop ScreenSaver interface). */
    override fun screenLocked(): Boolean =
        qdbus("org.freedesktop.ScreenSaver", "/ScreenSaver", "org.freedesktop.ScreenSaver.GetActive")?.trim() == "true"
}

/** Installed desktop apps, from `.desktop` files, for the local page and to name windows. */
object DesktopApps {
    @Serializable
    data class App(val id: String, val name: String, val wmClass: String? = null)

    private val home = System.getProperty("user.home")
    private val dirs = listOf(
        "/usr/share/applications",
        "/usr/local/share/applications",
        "/var/lib/flatpak/exports/share/applications",
        "$home/.local/share/flatpak/exports/share/applications",
        "$home/.local/share/applications",
    ).map(::File)

    @Volatile private var cache: Pair<Long, List<App>>? = null

    fun all(): List<App> {
        cache?.let { (at, apps) -> if (System.currentTimeMillis() - at < 60_000) return apps }
        val apps = scan(dirs)
        cache = System.currentTimeMillis() to apps
        return apps
    }

    /** The apps whose `.desktop` files are in [dirs], by name; a later dir's file wins over an earlier one's. */
    fun scan(dirs: List<File>): List<App> {
        val found = LinkedHashMap<String, App>()
        for (dir in dirs) {
            dir.listFiles { f -> f.name.endsWith(".desktop") }?.forEach { f ->
                parse(f)?.let { found[it.id] = it } // later dirs (the user's own) win
            }
        }
        return found.values.sortedBy { it.name.lowercase() }
    }

    private fun parse(f: File): App? {
        // A link left behind by an uninstalled app, a directory, a file we may not read: no app,
        // and not the end of the list, which names every window the daemon is told about.
        if (!f.isFile) return null
        val lines = try {
            f.readLines()
        } catch (_: IOException) {
            return null
        }
        var inEntry = false
        var name: String? = null
        var wmClass: String? = null
        var hidden = false
        var type: String? = null
        for (line in lines) {
            if (line.startsWith("[")) {
                inEntry = line.trim() == "[Desktop Entry]"
                continue
            }
            if (!inEntry) continue
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=', "").trim()
            when (key) {
                "Name" -> name = value
                "StartupWMClass" -> wmClass = value
                "NoDisplay", "Hidden" -> if (value == "true") hidden = true
                "Type" -> type = value
            }
        }
        if (hidden || type != "Application" || name == null) return null
        return App(f.name.removeSuffix(".desktop"), name, wmClass)
    }

    /** The id we block by: the window's `.desktop` id, else the app whose WM class it has, else the class. */
    fun resolve(desktopFileName: String, resourceClass: String): String? {
        val id = desktopFileName.removeSuffix(".desktop")
        if (id.isNotEmpty()) return id
        if (resourceClass.isEmpty()) return null
        return all().firstOrNull { it.wmClass.equals(resourceClass, ignoreCase = true) }?.id ?: resourceClass.lowercase()
    }

    fun name(id: String): String = all().firstOrNull { it.id == id }?.name ?: id
}

/** Whether [block] has anything to lock on this laptop: the end of a phone game's unlock isn't news here. */
fun World.locksOnLaptop(block: Block): Boolean =
    appliesHere(block) && (block.targetSites.isNotEmpty() || block.targetDesktopApps.isNotEmpty())

/**
 * The phone can't say it has stopped, so the laptop watches for it: the server notes every device
 * that reaches it, and the phone's service checks in whenever the phone is used.
 */
object PhoneWatch {
    /** Longer than a night with the phone asleep. */
    const val QUIET_MS = 12 * 60 * 60_000L
    const val CHECK_MS = 10 * 60_000L

    /** The phone's last check-in out of a `GET /devices` answer, or null if there's none. */
    fun seen(body: String): Long? = runCatching {
        (Json.parseToJsonElement(body) as? JsonObject)?.get("phone")?.jsonPrimitive?.longOrNull
    }.getOrNull()

    /**
     * How long the phone has been quiet, or null while it's fine. An old answer says nothing: a
     * laptop that just woke up mustn't blame the phone for what it missed while asleep.
     */
    fun quietMs(seenWall: Long?, askedWall: Long, nowWall: Long): Long? {
        if (seenWall == null || nowWall - askedWall > 2 * CHECK_MS) return null
        return (nowWall - seenWall).takeIf { it >= QUIET_MS }
    }

    fun text(quietMs: Long): String =
        "It hasn't reached your home server for ${quietMs / 3_600_000} hours, so xrnrule may have stopped there. Open it on the phone."
}

/**
 * At most one popup of a kind in [everyMs]. The engine's warnings fire on every tick, so anything
 * reaching the desktop without this would stack up a popup a second.
 */
class PopupLimit(private val everyMs: Long = 10 * 60_000L) {
    private val last = ConcurrentHashMap<String, Long>()

    fun allows(kind: String, now: Long): Boolean {
        var allowed = false
        last.compute(kind) { _, shown ->
            if (shown != null && now - shown < everyMs) shown else now.also { allowed = true }
        }
        return allowed
    }
}

/** Desktop notifications (`notify-send`), and the block page in the browser. */
class Notifier(private val config: Config, private val limit: PopupLimit = PopupLimit()) {
    private val lastShown = ConcurrentHashMap<String, Long>()

    /** The popup on screen: the next one takes its place rather than stacking on it. */
    @Volatile private var onScreen: String? = null

    /**
     * Gone by itself after 10 s, at normal urgency because Plasma keeps a critical one up until it's
     * dismissed. [urgent] only leaves it in the notification history, for what shouldn't be missed.
     */
    fun notify(title: String, body: String, urgent: Boolean = false) {
        if (!limit.allows(title, System.currentTimeMillis())) return
        val command = mutableListOf("notify-send", "-a", "xrnrule", "-i", "changes-prevent", "-t", "$POPUP_MS", "-p")
        if (!urgent) command += "-e"
        onScreen?.let { command += listOf("-r", it) }
        try {
            val process = ProcessBuilder(command + listOf("--", title, body)).redirectErrorStream(true).start()
            thread(isDaemon = true) {
                val id = process.inputStream.bufferedReader().use { it.readText() }.trim()
                if (id.toIntOrNull() != null) onScreen = id
            }
        } catch (_: Exception) {
            // no notification daemon: the block still holds
        }
    }

    /** A blocked app was closed: the block page says why (at most once a minute per app), else a popup does. */
    fun blocked(appId: String) {
        val now = System.currentTimeMillis()
        val last = lastShown.put(appId, now)
        if (last != null && now - last < 60_000) return
        if (config.openBlockPage) {
            run("xdg-open", "${config.localUrl}/block?app=" + URLEncoder.encode(appId, Charsets.UTF_8))
        } else {
            notify("${DesktopApps.name(appId)} is blocked", "Open the block page to see what unlocks it.")
        }
    }

    private fun run(vararg cmd: String) {
        try {
            ProcessBuilder(*cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        } catch (_: Exception) {
            // no browser: the block still holds
        }
    }

    private companion object {
        const val POPUP_MS = 10_000
    }
}
