package com.xern.xrnrule.desktop

import com.xern.xrnrule.domain.BlockEvent
import com.xern.xrnrule.domain.ShieldPlan
import com.xern.xrnrule.domain.ShieldPort
import com.xern.xrnrule.domain.Sites
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** One window as KWin reports it: the `.desktop` id when it has one, else its WM class. */
data class AppWindow(val appId: String, val resourceClass: String, val pid: Long)

/**
 * What the KWin script (`desktop/kwin`) calls over D-Bus. Arguments are strings: a JS number
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

    private fun isBrowser(appId: String): Boolean = appId.lowercase().let { id -> BROWSERS.any { id.contains(it) } }

    companion object {
        const val FRESH_MS = 6_000L

        /** The extension asks for its rules once a minute, even on a page where it can't run. */
        const val EXTENSION_SILENCE_MS = 75_000L
        val BROWSERS = listOf("helium", "chrom", "brave", "vivaldi", "edge", "opera", "firefox", "zen", "librewolf", "floorp", "thorium")
    }
}

/**
 * The laptop's shield: blocked apps are closed through KWin (and their process stopped if the
 * window doesn't go), blocked sites are the extension's job (it asks [isSiteBlocked]).
 */
class Enforcer(private val store: Store, private val notifier: Notifier, private val config: Config) : ShieldPort, DesktopBus {
    private val windows = ConcurrentHashMap<Long, AppWindow>()
    lateinit var foreground: Foreground

    @Volatile var kwinSeenAtMs = 0L
        private set

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
            KWin.closeWindowsOf(ids)
            ids.forEach { notifier.blocked(it) }
            Thread.sleep(4_000)
            // Closing a window may only hide it to the tray (Steam, Discord): then stop the process.
            for (w in doomed) ProcessHandle.of(w.pid).ifPresent { if (it.isAlive && w.appId in plan().blockedPackages) it.destroy() }
        }
    }

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "enforcer").apply { isDaemon = true } }

    private fun seen(appId: String, resourceClass: String, pid: String): AppWindow? {
        kwinSeenAtMs = System.currentTimeMillis()
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
object KWin {
    private var connection: DBusConnection? = null

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

    fun closeWindowsOf(appIds: Set<String>) {
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
    fun screenLocked(): Boolean =
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
    )

    @Volatile private var cache: Pair<Long, List<App>>? = null

    fun all(): List<App> {
        cache?.let { (at, apps) -> if (System.currentTimeMillis() - at < 60_000) return apps }
        val found = LinkedHashMap<String, App>()
        for (dir in dirs) {
            File(dir).listFiles { f -> f.name.endsWith(".desktop") }?.forEach { f ->
                parse(f)?.let { found[it.id] = it } // later dirs (the user's own) win
            }
        }
        val apps = found.values.sortedBy { it.name.lowercase() }
        cache = System.currentTimeMillis() to apps
        return apps
    }

    private fun parse(f: File): App? {
        var inEntry = false
        var name: String? = null
        var wmClass: String? = null
        var hidden = false
        var type: String? = null
        for (line in f.readLines()) {
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

/** Desktop notifications (`notify-send`), and the block page in the browser. */
class Notifier(private val config: Config) {
    private val lastShown = ConcurrentHashMap<String, Long>()

    fun notify(title: String, body: String, urgent: Boolean = false) {
        run("notify-send", "-a", "xrnrule", "-i", "changes-prevent", "-u", if (urgent) "critical" else "normal", title, body)
    }

    /** A blocked app was closed: say why, and open the block page (at most once a minute per app). */
    fun blocked(appId: String) {
        val now = System.currentTimeMillis()
        val last = lastShown.put(appId, now)
        if (last != null && now - last < 60_000) return
        notify("${DesktopApps.name(appId)} is blocked", "Open the block page to see what unlocks it.")
        if (config.openBlockPage) run("xdg-open", "${config.localUrl}/block?app=" + URLEncoder.encode(appId, Charsets.UTF_8))
    }

    private fun run(vararg cmd: String) {
        try {
            ProcessBuilder(*cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        } catch (_: Exception) {
            // no notification daemon / no browser: the block still holds
        }
    }
}
