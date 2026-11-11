package com.xern.xrnrule.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * What the daemon needs from the compositor: being told which window is in front (it calls
 * [Enforcer]'s [DesktopBus] methods, as the KWin script does over D-Bus), closing an app's
 * windows, and whether the screen is locked. [KWin] on Plasma, [HyprlandWindows] on Hyprland.
 */
interface WindowSystem {
    /** For the local page: "kwin" or "hyprland". */
    val name: String

    /**
     * Starts reporting windows to [bus], and [connected] each time the compositor is there to
     * watch (even with no window open). Returns false when it can't (no D-Bus).
     */
    fun start(bus: DesktopBus, connected: () -> Unit = {}): Boolean

    fun closeWindowsOf(appIds: Set<String>)

    fun screenLocked(): Boolean

    companion object {
        /**
         * Hyprland when this session is one (its instance signature is set), or when a service
         * started without that in its environment finds Hyprland's sockets and no KDE; else KWin.
         */
        fun detect(env: Map<String, String> = System.getenv()): WindowSystem {
            val kde = env["XDG_CURRENT_DESKTOP"].orEmpty().contains("KDE", ignoreCase = true)
            val hyprland = Hyprland.detected(env) || (!kde && Hyprland.Sockets(env).candidates().isNotEmpty())
            return if (hyprland) HyprlandWindows(Hyprland.Sockets(env)) else KWin
        }
    }
}

/** Hyprland's IPC, the pure parts: event lines, `j/clients` and `j/monitors`, and app ids. */
object Hyprland {
    fun detected(env: Map<String, String>): Boolean =
        !env["HYPRLAND_INSTANCE_SIGNATURE"].isNullOrBlank() ||
            env["XDG_CURRENT_DESKTOP"].orEmpty().split(':').any { it.equals("Hyprland", ignoreCase = true) }

    /** One window, from `j/clients`. */
    @Serializable
    data class Client(
        val address: String = "",
        val mapped: Boolean = true,
        val `class`: String = "",
        val initialClass: String = "",
        val title: String = "",
        val pid: Long = 0,
    ) {
        /** Like the KWin script's `relevant`: a mapped window that says what app it is. */
        val relevant: Boolean get() = mapped && address.isNotEmpty() && (`class`.isNotBlank() || initialClass.isNotBlank())

        /** The WM class we report and match by: the current one, else the one it opened with. */
        val resourceClass: String get() = `class`.ifBlank { initialClass }
    }

    sealed interface Event {
        /** Focus moved; [address] is null when nothing has it (an empty workspace). */
        data class Active(val address: String?) : Event
        data class Opened(val address: String, val resourceClass: String) : Event
        data class Closed(val address: String) : Event
        data class Titled(val address: String, val title: String) : Event
    }

    /** Event addresses come without `0x`, `j/clients` ones with it: always `0x…`, lower case. */
    fun address(raw: String): String? {
        val hex = raw.trim().lowercase().removePrefix("0x")
        if (hex.isEmpty() || hex.any { it !in '0'..'9' && it !in 'a'..'f' } || hex.all { it == '0' }) return null
        return "0x$hex"
    }

    /** One line of `.socket2.sock` (`EVENT>>DATA`), or null for the events we don't need. */
    fun parseEvent(line: String): Event? {
        val name = line.substringBefore(">>", "")
        val data = line.substringAfter(">>", "")
        return when (name) {
            "activewindowv2" -> Event.Active(address(data.substringBefore(',')))
            "openwindow" -> {
                // ADDRESS,WORKSPACE,CLASS,TITLE: the title may hold commas, the rest don't.
                val parts = data.split(',', limit = 4)
                address(parts[0])?.let { Event.Opened(it, parts.getOrElse(2) { "" }) }
            }
            "closewindow" -> address(data)?.let { Event.Closed(it) }
            // ADDRESS,TITLE: the title may hold commas.
            "windowtitlev2" -> address(data.substringBefore(',', ""))?.let { Event.Titled(it, data.substringAfter(',')) }
            else -> null
        }
    }

    fun parseClients(text: String): List<Client> =
        runCatching { json.decodeFromString<List<Client>>(text) }.getOrDefault(emptyList())
            .map { c -> c.copy(address = address(c.address) ?: "") }

    /** `j/activewindow`: the focused window, or null (Hyprland answers `{}` when there's none). */
    fun parseActive(text: String): Client? =
        runCatching { json.decodeFromString<Client>(text) }.getOrNull()
            ?.let { c -> c.copy(address = address(c.address) ?: "") }
            ?.takeIf { it.relevant }

    /** A session lock up on any monitor shows as `LOCK` among the reasons it can't go solitary. */
    fun lockedFromMonitors(text: String): Boolean = runCatching {
        (json.parseToJsonElement(text) as JsonArray).any { m ->
            ((m as JsonObject)["solitaryBlockedBy"] as? JsonArray)?.any { it.jsonPrimitive.content == "LOCK" } == true
        }
    }.getOrDefault(false)

    /**
     * The `.desktop` id a window's class stands for, as KWin's `desktopFileName` would say: a
     * Wayland app's class is its app id, which is usually its `.desktop` name (`zen`,
     * `org.kde.dolphin`); an X11 one's is its WM class (`steam`, `discord`), which the `.desktop`
     * file names as `StartupWMClass`. Tries the class, then the one the window opened with;
     * a class no installed app claims is blocked by itself, in lower case, as on KDE.
     */
    fun appId(client: Client, apps: List<DesktopApps.App>): String? {
        val classes = listOf(client.`class`, client.initialClass).filter { it.isNotBlank() }.distinct()
        if (classes.isEmpty()) return null
        for (cls in classes) known(cls, apps)?.let { return it }
        return classes.first().lowercase()
    }

    private fun known(cls: String, apps: List<DesktopApps.App>): String? {
        val bare = cls.removeSuffix(".desktop")
        apps.firstOrNull { it.id == bare }?.let { return it.id }
        apps.firstOrNull { it.id.equals(bare, ignoreCase = true) }?.let { return it.id }
        apps.firstOrNull { it.wmClass.equals(bare, ignoreCase = true) }?.let { return it.id }
        // A short class for a reverse-DNS id (`dolphin` for org.kde.dolphin), when only one app fits.
        return apps.filter { it.id.substringAfterLast('.').equals(bare, ignoreCase = true) }.singleOrNull()?.id
    }

    /**
     * Closing one window. Hyprland 0.56 with a Lua config takes Lua dispatches; older ones (and
     * hyprlang configs) take `closewindow`. A Lua long string can't be broken out of by the address,
     * which is only ever `0x` and hex digits here anyway.
     */
    fun closeCommands(address: String): List<String> = listOf(
        "dispatch hl.dsp.window.close({ window = [====[address:$address]====] })",
        "dispatch closewindow address:$address",
    )

    /** Where this Hyprland's sockets are: `$XDG_RUNTIME_DIR/hypr/$HYPRLAND_INSTANCE_SIGNATURE/`. */
    class Sockets(private val env: Map<String, String>) {
        private val base: File
            get() = File(env["XDG_RUNTIME_DIR"]?.takeIf { it.isNotBlank() } ?: "/run/user/${uid()}", "hypr")

        /**
         * The instance to talk to: ours, or, if Hyprland was restarted under a daemon that kept
         * the old signature, the newest one with live sockets.
         */
        fun candidates(): List<File> {
            val own = env["HYPRLAND_INSTANCE_SIGNATURE"]?.takeIf { it.isNotBlank() }?.let { File(base, it) }
            val others = base.listFiles()?.filter { it.isDirectory && it != own }?.sortedByDescending { it.lastModified() }.orEmpty()
            return (listOfNotNull(own) + others).filter { File(it, ".socket2.sock").exists() }
        }

        private fun uid(): Int = runCatching { Files.getAttribute(Path.of("/proc/self"), "unix:uid") as Int }.getOrDefault(1000)
    }
}

/**
 * The Hyprland stand-in for the KWin script: follows `.socket2.sock` and reports each window
 * to [Enforcer] exactly as the script does (Added for each window, Activated for the one in
 * front, Removed when one closes), with the title of the one in front too, which the script
 * doesn't send ([DesktopBus.Titled]); and closes those it answers "block" for through `.socket.sock`.
 * Reconnects whenever Hyprland goes away, and lists what's open again when it's back.
 */
class HyprlandWindows(
    private val sockets: Hyprland.Sockets,
    /** The installed apps, to name windows by (tests give their own). */
    private val apps: () -> List<DesktopApps.App> = DesktopApps::all,
) : WindowSystem {
    override val name = "hyprland"

    @Volatile private var dir: File? = null
    private val clients = ConcurrentHashMap<String, Hyprland.Client>()
    @Volatile private var activeAddress: String? = null
    private lateinit var bus: DesktopBus
    private var connected: () -> Unit = {}
    private val requests = Executors.newCachedThreadPool { r -> Thread(r, "hyprland-request").apply { isDaemon = true } }

    /** XRNRULE_DEBUG=1: say each window reported, on stderr (the journal). */
    private val debug = !System.getenv("XRNRULE_DEBUG").isNullOrEmpty()

    /** Which close command this Hyprland takes, once one has worked. */
    @Volatile private var closeStyle = -1

    override fun start(bus: DesktopBus, connected: () -> Unit): Boolean {
        this.bus = bus
        this.connected = connected
        thread(isDaemon = true, name = "hyprland-events") { follow() }
        return true
    }

    private fun follow() {
        var told = false
        while (true) {
            val connected = sockets.candidates().firstNotNullOfOrNull { d ->
                runCatching { d to open(File(d, ".socket2.sock")) }.getOrNull()
            }
            if (connected == null) {
                if (!told) System.err.println("Hyprland's sockets aren't there yet; desktop apps aren't watched until they are")
                told = true
                Thread.sleep(2_000)
                continue
            }
            val (d, channel) = connected
            dir = d
            told = false
            System.err.println("Watching windows through Hyprland (${d.name})")
            try {
                channel.use {
                    connected()
                    // Like an event's, a listing's failure is logged and the watch goes on: going
                    // round again would fail the same way, every second, with nothing blocked.
                    runCatching { relist() }.onFailure { System.err.println("Listing Hyprland's windows: $it") }
                    val reader = Channels.newReader(channel, Charsets.UTF_8).buffered()
                    while (true) {
                        val line = reader.readLine() ?: break
                        runCatching { handle(line) }.onFailure { System.err.println("Hyprland event $line: $it") }
                    }
                }
            } catch (e: Exception) {
                System.err.println("Lost Hyprland's events: $e")
            }
            // Hyprland went away: nothing is in front until it's back.
            dir = null
            clients.clear()
            activeAddress = null
            bus.Activated("", "", "0")
            Thread.sleep(1_000)
        }
    }

    /** On (re)connecting, as the KWin script does on load: report every open window, then the one in front. */
    private fun relist() {
        refresh()
        for (c in clients.values) report("Added", c)
        val active = request("j/activewindow")?.let(Hyprland::parseActive)
        activate(active?.address)
    }

    private fun handle(line: String) {
        when (val e = Hyprland.parseEvent(line) ?: return) {
            is Hyprland.Event.Opened -> {
                refresh()
                clients[e.address]?.let { report("Added", it) }
            }
            is Hyprland.Event.Active -> if (e.address != activeAddress) activate(e.address)
            is Hyprland.Event.Closed -> {
                val gone = clients.remove(e.address) ?: return
                // [activeAddress] stays: the activewindowv2 after it (to another window or none) is news.
                // The daemon keeps windows by process: only forget it once its last window is gone.
                if (gone.pid > 0 && clients.values.none { it.pid == gone.pid }) bus.Removed(gone.pid.toString())
            }
            is Hyprland.Event.Titled -> {
                clients.computeIfPresent(e.address) { _, c -> c.copy(title = e.title) }
                if (e.address == activeAddress) bus.Titled(e.title)
            }
        }
    }

    private fun activate(address: String?) {
        activeAddress = address
        val client = address?.let { clients[it] ?: refresh().let { _ -> clients[it] } }
        if (client != null) return report("Activated", client)
        if (debug) System.err.println("Activated: nothing")
        bus.Activated("", "", "0")
    }

    private fun report(method: String, c: Hyprland.Client) {
        val appId = Hyprland.appId(c, apps()) ?: return
        val answer = when (method) {
            "Activated" -> bus.Activated(appId, c.resourceClass, c.pid.toString()).also { bus.Titled(c.title) }
            else -> bus.Added(appId, c.resourceClass, c.pid.toString())
        }
        if (debug) System.err.println("$method ${c.address} $appId (class ${c.resourceClass}, pid ${c.pid}): $answer")
        if (answer == "block") close(c.address)
    }

    /** Re-reads every window. */
    private fun refresh() {
        val text = request("j/clients") ?: return
        val now = Hyprland.parseClients(text).filter { it.relevant }.associateBy { it.address }
        clients.keys.retainAll(now.keys)
        clients.putAll(now)
    }

    override fun closeWindowsOf(appIds: Set<String>) {
        if (appIds.isEmpty()) return
        val lower = appIds.map { it.lowercase() }.toSet()
        val installed = apps()
        val all = request("j/clients")?.let(Hyprland::parseClients).orEmpty().filter { it.relevant }
        for (c in all) {
            // As the KWin close script: by `.desktop` id, or by WM class in any case.
            if (Hyprland.appId(c, installed) in appIds || c.resourceClass.lowercase() in lower) close(c.address)
        }
    }

    private fun close(address: String) {
        val commands = Hyprland.closeCommands(address)
        val order = if (closeStyle >= 0) listOf(closeStyle) + commands.indices.filter { it != closeStyle } else commands.indices.toList()
        for (i in order) {
            if (request(commands[i])?.trim() == "ok") {
                closeStyle = i
                return
            }
        }
        System.err.println("Hyprland wouldn't close $address")
    }

    override fun screenLocked(): Boolean = request("j/monitors")?.let(Hyprland::lockedFromMonitors) == true

    /** One request on `.socket.sock`, as hyprctl makes it; null when Hyprland doesn't answer within 3 s. */
    private fun request(command: String): String? {
        val socket = File(dir ?: sockets.candidates().firstOrNull() ?: return null, ".socket.sock")
        val channel = runCatching { open(socket) }.getOrNull() ?: return null
        val answer = requests.submit<String> {
            channel.write(ByteBuffer.wrap(command.toByteArray(Charsets.UTF_8)))
            Channels.newInputStream(channel).readBytes().toString(Charsets.UTF_8)
        }
        return try {
            answer.get(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
            answer.cancel(true)
            null
        } finally {
            runCatching { channel.close() }
        }
    }

    private fun open(socket: File): SocketChannel =
        SocketChannel.open(StandardProtocolFamily.UNIX).apply {
            try {
                connect(UnixDomainSocketAddress.of(socket.toPath()))
            } catch (e: Exception) {
                close()
                throw e
            }
        }
}
