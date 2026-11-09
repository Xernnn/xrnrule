package com.xern.xrnrule.desktop

import com.xern.xrnrule.desktop.DesktopApps.App
import com.xern.xrnrule.desktop.Hyprland.Client
import com.xern.xrnrule.desktop.Hyprland.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class HyprlandTest {

    @Test
    fun eventLines() {
        assertEquals(Event.Active("0x559dc0c9f680"), Hyprland.parseEvent("activewindowv2>>559dc0c9f680"))
        // Nothing focused (an empty workspace).
        assertEquals(Event.Active(null), Hyprland.parseEvent("activewindowv2>>"))
        assertEquals(Event.Active(null), Hyprland.parseEvent("activewindowv2>>,"))
        assertEquals(Event.Opened("0x559dc0c9f680", "xrnruletest"), Hyprland.parseEvent("openwindow>>559dc0c9f680,1,xrnruletest,foot, a title, with commas"))
        assertEquals(Event.Closed("0x559dc0c9f680"), Hyprland.parseEvent("closewindow>>559dc0c9f680"))
        // The v1 event and the rest aren't needed: v2 names the window exactly.
        assertNull(Hyprland.parseEvent("activewindow>>zen,YouTube"))
        assertNull(Hyprland.parseEvent("windowtitlev2>>559dc0c9f680,foot"))
        assertNull(Hyprland.parseEvent("garbage"))
        assertNull(Hyprland.parseEvent("closewindow>>not-hex"))
    }

    @Test
    fun addressesAreAlways0xLowerCase() {
        assertEquals("0xabc123", Hyprland.address("ABC123"))
        assertEquals("0xabc123", Hyprland.address("0xabc123"))
        assertNull(Hyprland.address("0x0"))
        assertNull(Hyprland.address(""))
    }

    @Test
    fun clientsFromJson() {
        val text = """
            [{"address": "0x559dc07c1c50", "mapped": true, "hidden": false, "workspace": {"id": 3, "name": "3"},
              "class": "proton.vpn.app.gtk", "title": "Proton VPN", "initialClass": "proton.vpn.app.gtk",
              "initialTitle": "Proton VPN", "pid": 4856, "xwayland": false, "focusHistoryID": 2, "grouped": []},
             {"address": "0x559dbecd8ac0", "mapped": false, "class": "zen", "initialClass": "zen", "pid": 12635},
             {"address": "0x1", "mapped": true, "class": "", "initialClass": "", "pid": 1}]
        """.trimIndent()
        val clients = Hyprland.parseClients(text)
        assertEquals(3, clients.size)
        assertEquals(Client("0x559dc07c1c50", true, "proton.vpn.app.gtk", "proton.vpn.app.gtk", "Proton VPN", 4856), clients[0])
        assertEquals(listOf("0x559dc07c1c50"), clients.filter { it.relevant }.map { it.address })
        assertEquals(emptyList<Client>(), Hyprland.parseClients("unknown request"))
    }

    @Test
    fun activeWindow() {
        assertEquals("0xabc", Hyprland.parseActive("""{"address": "0xabc", "class": "zen", "pid": 5}""")?.address)
        assertNull(Hyprland.parseActive("{}"))
        assertNull(Hyprland.parseActive("Invalid"))
    }

    @Test
    fun aClassBecomesTheDesktopIdKWinWouldHaveGiven() {
        val apps = listOf(
            App("steam", "Steam", "steam"),
            App("com.discordapp.Discord", "Discord", "discord"),
            App("zen", "Zen Browser", "zen"),
            App("helium-browser", "Helium", "helium"),
            App("org.kde.dolphin", "Dolphin"),
            App("com.mitchellh.ghostty", "Ghostty"),
        )
        fun id(cls: String, initial: String = cls) = Hyprland.appId(Client("0x1", true, cls, initial, "", 1), apps)
        assertEquals("steam", id("steam"))           // XWayland: WM class, which is also the id
        assertEquals("com.discordapp.Discord", id("discord")) // a Flatpak: by StartupWMClass
        assertEquals("zen", id("zen"))               // Wayland: the app id is the .desktop name
        assertEquals("helium-browser", id("helium"))
        assertEquals("org.kde.dolphin", id("org.kde.dolphin"))
        assertEquals("org.kde.dolphin", id("dolphin")) // a short class for a reverse-DNS id
        assertEquals("com.mitchellh.ghostty", id("COM.MITCHELLH.GHOSTTY"))
        // The class changed since it opened: the one an installed app claims wins.
        assertEquals("steam", id("steamwebhelper", "steam"))
        // No app claims it: the class itself, lower case, as the KWin path does.
        assertEquals("somegame", id("SomeGame"))
        assertNull(id("", ""))
    }

    @Test
    fun closingTriesLuaFirst() {
        val (lua, legacy) = Hyprland.closeCommands("0xabc")
        assertEquals("dispatch hl.dsp.window.close({ window = [====[address:0xabc]====] })", lua)
        assertEquals("dispatch closewindow address:0xabc", legacy)
    }

    @Test
    fun lockFromMonitors() {
        assertTrue(Hyprland.lockedFromMonitors("""[{"id": 0, "solitaryBlockedBy": ["LOCK", "WINDOWED"]}]"""))
        assertFalse(Hyprland.lockedFromMonitors("""[{"id": 0, "solitaryBlockedBy": ["WINDOWED", "CANDIDATE"]}]"""))
        assertFalse(Hyprland.lockedFromMonitors("""[{"id": 0}]"""))
        assertFalse(Hyprland.lockedFromMonitors("nope"))
    }

    @Test
    fun detection() {
        assertTrue(Hyprland.detected(mapOf("HYPRLAND_INSTANCE_SIGNATURE" to "abc")))
        assertTrue(Hyprland.detected(mapOf("XDG_CURRENT_DESKTOP" to "Hyprland")))
        assertFalse(Hyprland.detected(mapOf("XDG_CURRENT_DESKTOP" to "KDE")))
        assertFalse(Hyprland.detected(emptyMap()))
        assertEquals("kwin", WindowSystem.detect(mapOf("XDG_CURRENT_DESKTOP" to "KDE")).name)
    }

    @Test
    fun socketsPreferOurInstanceThenTheNewest() {
        val runtime = Files.createTempDirectory("xrnrule-hypr").toFile()
        try {
            fun instance(sig: String, at: Long) = java.io.File(runtime, "hypr/$sig").apply {
                mkdirs()
                java.io.File(this, ".socket2.sock").writeText("")
                setLastModified(at)
            }
            val old = instance("old", 1_000_000)
            val new = instance("new", 2_000_000)
            java.io.File(runtime, "hypr/empty").mkdirs()
            val env = mapOf("XDG_RUNTIME_DIR" to runtime.path)
            assertEquals(listOf(old, new), Hyprland.Sockets(env + ("HYPRLAND_INSTANCE_SIGNATURE" to "old")).candidates())
            // Restarted: the signature we were given is gone, so the newest live one.
            assertEquals(listOf(new, old), Hyprland.Sockets(env + ("HYPRLAND_INSTANCE_SIGNATURE" to "gone")).candidates())
        } finally {
            runtime.deleteRecursively()
        }
    }

    /** A stand-in Hyprland: `.socket.sock` answers from [clients], `.socket2.sock` sends what [emit] is given. */
    private class FakeHyprland(runtime: File, sig: String) {
        val dir = File(runtime, "hypr/$sig").apply { mkdirs() }
        @Volatile var clients = "[]"
        @Volatile var active = "{}"
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val listeners = CopyOnWriteArrayList<SocketChannel>()
        private val servers = listOf(".socket.sock", ".socket2.sock").map { name ->
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(UnixDomainSocketAddress.of(File(dir, name).toPath())) }
        }

        init {
            thread(isDaemon = true) {
                while (true) {
                    val c = runCatching { servers[0].accept() }.getOrNull() ?: break
                    val buf = ByteBuffer.allocate(4096)
                    c.read(buf)
                    val request = String(buf.array(), 0, buf.position())
                    requests += request
                    val answer = when {
                        request == "j/clients" -> clients
                        request == "j/activewindow" -> active
                        request == "j/monitors" -> "[]"
                        request.startsWith("dispatch hl.dsp.window.close") -> "ok"
                        else -> "unknown request"
                    }
                    c.write(ByteBuffer.wrap(answer.toByteArray()))
                    c.close()
                }
            }
            thread(isDaemon = true) {
                while (true) listeners += runCatching { servers[1].accept() }.getOrNull() ?: break
            }
        }

        val listening: Int get() = listeners.count { it.isOpen }

        fun emit(line: String) = listeners.filter { it.isOpen }.forEach { it.write(ByteBuffer.wrap("$line\n".toByteArray())) }

        /** Hyprland going away (and its event socket with it), as on a restart. */
        fun dropListeners() = listeners.forEach { it.close() }

        fun stop() = servers.forEach { it.close() }
    }

    private class Calls : DesktopBus {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun Activated(appId: String, resourceClass: String, pid: String) = "ok".also { calls += "Activated $appId $pid" }
        // A blocked app: the daemon answers "block", and Hyprland is asked to close it.
        override fun Added(appId: String, resourceClass: String, pid: String) =
            (if (appId == "xrnruletest") "block" else "ok").also { calls += "Added $appId $pid" }
        override fun Removed(pid: String) { calls += "Removed $pid" }
        override fun getObjectPath() = "/Desktop"
    }

    private fun waitFor(what: String, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!ok()) {
            check(System.currentTimeMillis() < until) { "timed out waiting for: $what" }
            Thread.sleep(20)
        }
    }

    @Test
    fun reportsWindowsLikeTheKWinScript_closesBlockedOnes_andReconnects() {
        val runtime = Files.createTempDirectory("xrnrule-hypr").toFile()
        val hypr = FakeHyprland(runtime, "sig")
        try {
            hypr.clients = """[{"address": "0xaaa", "class": "zen", "initialClass": "zen", "pid": 10}]"""
            hypr.active = """{"address": "0xaaa", "class": "zen", "initialClass": "zen", "pid": 10}"""
            val bus = Calls()
            var connections = 0
            val env = mapOf("XDG_RUNTIME_DIR" to runtime.path, "HYPRLAND_INSTANCE_SIGNATURE" to "sig")
            HyprlandWindows(Hyprland.Sockets(env)).start(bus) { connections++ }

            // On connecting: every window, then the one in front.
            waitFor("the first listing") { bus.calls.size >= 2 && hypr.listening == 1 }
            assertEquals(listOf("Added zen 10", "Activated zen 10"), bus.calls.toList())
            assertEquals(1, connections)

            // A blocked app opens: reported, and closed by address.
            hypr.clients = """[{"address": "0xaaa", "class": "zen", "pid": 10}, {"address": "0xbbb", "class": "xrnruletest", "pid": 20}]"""
            hypr.emit("openwindow>>bbb,1,xrnruletest,foot")
            waitFor("the close") { hypr.requests.any { it.startsWith("dispatch") } }
            assertTrue("Added xrnruletest 20" in bus.calls)
            assertEquals("dispatch hl.dsp.window.close({ window = [====[address:0xbbb]====] })", hypr.requests.last { it.startsWith("dispatch") })

            // Focus moves on, and to nothing; the same window again is no news.
            bus.calls.clear()
            hypr.emit("activewindowv2>>bbb")
            hypr.emit("activewindowv2>>bbb")
            hypr.emit("closewindow>>bbb")
            hypr.emit("activewindowv2>>")
            waitFor("focus to nothing") { "Activated  0" in bus.calls }
            assertEquals(listOf("Activated xrnruletest 20", "Removed 20", "Activated  0"), bus.calls.toList())

            // Hyprland restarts: nothing in front meanwhile, then everything is listed again.
            bus.calls.clear()
            hypr.clients = """[{"address": "0xaaa", "class": "zen", "pid": 10}]"""
            hypr.dropListeners()
            waitFor("the second listing") { connections == 2 && bus.calls.size >= 3 }
            assertEquals(listOf("Activated  0", "Added zen 10", "Activated zen 10"), bus.calls.toList())
        } finally {
            hypr.stop()
            runtime.deleteRecursively()
        }
    }
}
