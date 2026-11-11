package com.xern.xrnrule.desktop

import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockEngine
import com.xern.xrnrule.domain.BlockEvent
import com.xern.xrnrule.domain.Clock
import com.xern.xrnrule.domain.DeviceKind
import com.xern.xrnrule.domain.PersistPort
import com.xern.xrnrule.domain.World
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.time.ZoneId

/**
 * The daemon's door on `127.0.0.1`, over HTTP as the extension and any web page reach it: what
 * a request may change, and whom it counts as. The server runs on a free port with a clock the
 * test moves; nothing here syncs, notifies or fetches from the network.
 */
class LocalServerTest {

    private class TestClock(var mono: Long = 1_000_000) : Clock {
        override fun nowMonotonicMs() = mono
        override fun nowWallMs() = 1_800_000_000_000 + mono
        override fun zone(): ZoneId = ZoneId.of("UTC")
    }

    private val dir = Files.createTempDirectory("xrnrule-local").toFile()

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    /** A daemon holding [blocks], with [known] the browsers that have had the extension (I49). */
    private inner class Daemon(blocks: List<Block> = emptyList(), known: String? = null) {
        val clock = TestClock()
        val port = ServerSocket(0).use { it.localPort }
        private val config = Config(server = "", user = "", password = "", port = port, dataDir = dir)
        private val scope = CoroutineScope(Dispatchers.Unconfined)
        private val world = World(blocks = blocks, deviceId = "laptop-1", deviceKind = DeviceKind.LAPTOP)
        val store = Store(
            BlockEngine(),
            object : PersistPort {
                override fun loadWorld() = world
                override fun save(world: World) = Unit
            },
            clock,
            scope,
        ).also { runBlocking { it.dispatchAndGet(BlockEvent.BootOrColdStart) } }
        val foreground = Foreground(store, File(dir, "browsers.txt").apply { known?.let(::writeText) })
        val icons = File(dir, "site-icons")

        init {
            val telemetry = FileTelemetry(File(dir, "telemetry.log"))
            val quiet = object : Notifier(config) {
                override fun notify(title: String, body: String, urgent: Boolean) = Unit
                override fun blocked(appId: String) = Unit
            }
            val enforcer = Enforcer(store, quiet, config).also { it.foreground = foreground }
            val shared = SharedSyncClient(config, store, telemetry, scope)
            LocalServer(config, store, foreground, enforcer, shared, HabitClient(config, store, scope), SiteIconCache(icons, scope), telemetry).start()
        }

        /** The answer's status code. [xrnrule]: with the header that only our pages and the extension can send. */
        fun get(path: String, xrnrule: Boolean = true): Int = request("GET", path, null, xrnrule)

        fun post(path: String, body: String): Int = request("POST", path, body, xrnrule = true)

        private fun request(method: String, path: String, body: String?, xrnrule: Boolean): Int {
            val conn = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
            conn.requestMethod = method
            if (xrnrule) conn.setRequestProperty("X-Xrnrule", "1")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes() }
            return code
        }
    }

    @Test
    fun i49_aWindowInFrontTheExtensionCantSeeIsTheExtensionOff() {
        val d = Daemon(known = "zen\n")
        // A private window the extension may not run in is in front, or another profile's. Behind
        // it a window of the extension's stays in sight, its page beating unfocused, and the
        // extension's minute poll says that no window it can see has focus.
        d.foreground.active = AppWindow("zen", "zen", 10)
        var waited = 0L
        while (waited < BrowserWatch.SILENT_GRACE_MS) {
            d.clock.mono += 2_500
            waited += 2_500
            assertEquals(200, d.post("/ext/heartbeat", """{"host":"docs.example.com","focused":false}"""))
            if (waited % 60_000 == 0L) assertEquals(200, d.get("/ext/rules?focused=0"))
        }
        assertTrue(d.foreground.extensionMissing())
        assertEquals("closed as with the extension off", "zen", d.foreground.browsers.toClose(d.clock.mono))
    }

    @Test
    fun i49_aWindowTheExtensionSeesStaysWithoutAPageBeating() {
        val d = Daemon(known = "zen\n")
        // about:addons, the new tab page, our block page: no content script beats there, but the
        // extension sees its own window has focus.
        d.foreground.active = AppWindow("zen", "zen", 10)
        repeat(10) {
            d.clock.mono += 60_000
            assertEquals(200, d.get("/ext/rules?focused=1"))
            assertFalse(d.foreground.extensionMissing())
            assertNull(d.foreground.browsers.toClose(d.clock.mono))
        }
        // An extension up to 2.3.5 doesn't say: its poll counts as before, until it is updated.
        repeat(10) {
            d.clock.mono += 60_000
            assertEquals(200, d.get("/ext/rules"))
            assertNull(d.foreground.browsers.toClose(d.clock.mono))
        }
    }
}
