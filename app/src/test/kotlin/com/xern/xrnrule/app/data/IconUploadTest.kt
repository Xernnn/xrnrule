package com.xern.xrnrule.app.data

import com.sun.net.httpserver.HttpServer
import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.Icons
import com.xern.xrnrule.domain.Trigger
import com.xern.xrnrule.domain.TriggerType
import com.xern.xrnrule.domain.TriggerUnit
import com.xern.xrnrule.domain.World
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The phone sending its logos ([IconUpload]) to a made-up sync server on a free port, which answers
 * `GET /icons` and `PUT /icons/<key>` as `server/xrnrule-sync.py` does.
 */
class IconUploadTest {
    private val dir = Files.createTempDirectory("xrnrule-icon-upload").toFile()
    private val stored = ConcurrentHashMap<String, ByteArray>()
    private val puts = CopyOnWriteArrayList<String>()
    private val lists = java.util.concurrent.atomic.AtomicInteger()
    private val refused = ConcurrentHashMap.newKeySet<String>()
    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 8).apply {
        createContext("/xrnrule/icons") { ex ->
            val login = "Basic " + Base64.getEncoder().encodeToString("you:pw".toByteArray())
            val key = ex.requestURI.rawPath.removePrefix("/xrnrule/icons").removePrefix("/").let { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
            val (code, body) = when {
                ex.requestHeaders.getFirst("Authorization") != login -> 401 to ""
                ex.requestHeaders.getFirst("X-Device") != "phone" -> 400 to "X-Device"
                ex.requestMethod == "GET" && key.isEmpty() -> {
                    lists.incrementAndGet()
                    200 to stored.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"${Icons.hash(v)}\"" }
                }
                ex.requestMethod == "PUT" && Icons.valid(key) -> {
                    puts += key
                    val bytes = ex.requestBody.readBytes()
                    if (key in refused || bytes.size > Icons.MAX_BYTES) 400 to "no" else 204.also { stored[key] = bytes } to ""
                }
                else -> 400 to "bad"
            }
            val out = body.toByteArray()
            ex.sendResponseHeaders(code, if (code == 204 || out.isEmpty()) -1 else out.size.toLong())
            if (out.isNotEmpty() && code != 204) ex.responseBody.use { it.write(out) }
            ex.close()
        }
        start()
    }
    private val server = IconUpload.Server("http://127.0.0.1:${http.address.port}/xrnrule", "you", "pw")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 1_800_000_000_000

    @After
    fun stop() {
        scope.cancel()
        http.stop(0)
        dir.deleteRecursively()
    }

    private val timer = Trigger(TriggerType.TIMER, 0, TriggerUnit.MS)

    private fun png(tag: String) = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10) + tag.toByteArray()

    /** Draws each app and site as a PNG of its name (and [version]); counts how often. */
    private inner class FakeRender(val sites: Set<String> = emptySet()) : IconUpload.Render {
        var version = 1
        val drawn = CopyOnWriteArrayList<String>()
        override fun app(packageName: String): ByteArray? = png("$packageName v$version").also { drawn += packageName }
        override fun site(host: String): ByteArray? = if (host in sites) png(host).also { drawn += host } else null
    }

    private fun upload(world: World, render: IconUpload.Render, to: IconUpload.Server? = server) =
        IconUpload({ to }, MutableStateFlow(world), render, java.io.File(dir, "icons-sent.json"), scope) { now }

    private fun world(vararg blocks: Block) = World(blocks = blocks.toList())

    @Test
    fun theNamedAppsGoAndOnlyTheSitesTheServerLacks() {
        stored["site:laptop.example"] = png("the laptop's")
        val render = FakeRender(sites = setOf("laptop.example", "phone.example"))
        val w = world(
            Block("a", "A", targetPackages = setOf("com.example.chat", "com.example.game"), targetSites = setOf("laptop.example", "phone.example/shorts"), trigger = timer),
            Block("b", "B", targetSites = setOf("not-kept.example"), trigger = timer),
        )
        assertEquals(3, upload(w, render).pass())
        assertEquals(setOf("app:com.example.chat", "app:com.example.game", "site:phone.example"), puts.toSet())
        assertTrue("the laptop's copy stays", stored["site:laptop.example"]!!.contentEquals(png("the laptop's")))
        assertTrue(stored["app:com.example.chat"]!!.contentEquals(png("com.example.chat v1")))
    }

    @Test
    fun nothingGoesAgainWhileNothingChangedNorIsItDrawnAgain() {
        val render = FakeRender(sites = setOf("phone.example"))
        val w = world(Block("a", "A", targetPackages = setOf("com.example.chat"), targetSites = setOf("phone.example"), trigger = timer))
        upload(w, render).pass()
        val drawn = render.drawn.size
        // Again, and after the app was started afresh (what was sent is on disk).
        assertEquals(0, upload(w, render).pass())
        assertEquals(0, upload(w, render).pass())
        assertEquals(2, puts.size)
        assertEquals(drawn, render.drawn.size)
    }

    @Test
    fun anAppWhoseIconChangedSendsItOnceADay() {
        val render = FakeRender()
        val w = world(Block("a", "A", targetPackages = setOf("com.example.chat"), trigger = timer))
        val upload = upload(w, render)
        upload.pass()
        render.version = 2
        now += 60_000
        assertEquals(0, upload.pass())
        now += Icons.RESEND_MS
        assertEquals(1, upload.pass())
        assertEquals(0, upload.pass())
        assertTrue(stored["app:com.example.chat"]!!.contentEquals(png("com.example.chat v2")))
    }

    @Test
    fun anIconTheServerRefusesIsntSentEveryPass() {
        refused += "app:com.example.chat"
        val w = world(Block("a", "A", targetPackages = setOf("com.example.chat"), trigger = timer))
        upload(w, FakeRender()).pass()
        upload(w, FakeRender()).pass()
        assertEquals(1, puts.size)
        now += Icons.RESEND_MS
        upload(w, FakeRender()).pass()
        assertEquals(2, puts.size)
    }

    @Test
    fun aPassSendsSoManyAndTheRestNextTime() {
        val packages = (1..Icons.MAX_PER_PASS + 5).map { "com.example.app$it" }.toSet()
        val w = world(Block("a", "A", targetPackages = packages, trigger = timer))
        assertEquals(Icons.MAX_PER_PASS, upload(w, FakeRender()).pass())
        assertEquals(5, upload(w, FakeRender()).pass())
        assertEquals(packages.map { "app:$it" }.toSet(), stored.keys)
    }

    @Test
    fun whatIsTooBigOrNoPngNeverGoes() {
        val w = world(Block("a", "A", targetPackages = setOf("com.example.big", "com.example.svg"), trigger = timer))
        val render = object : IconUpload.Render {
            override fun app(packageName: String) = if (packageName.endsWith("big")) png("x") + ByteArray(Icons.MAX_BYTES) else "<svg/>".toByteArray()
            override fun site(host: String): ByteArray? = null
        }
        assertEquals(0, upload(w, render).pass())
        assertEquals(0, puts.size)
    }

    @Test
    fun withSyncOffNothingIsAsked() {
        val w = world(Block("a", "A", targetPackages = setOf("com.example.chat"), trigger = timer))
        assertEquals(0, upload(w, FakeRender(), to = null).pass())
        assertEquals(0, puts.size)
    }

    @Test
    fun syncRoundsBringAPassAtMostSoOftenAndNewTargetsOneAtOnce() {
        val render = FakeRender()
        val world = MutableStateFlow(world(Block("a", "A", targetPackages = setOf("com.example.chat"), trigger = timer)))
        val status = MutableStateFlow<SharedSyncStatus?>(null)
        IconUpload({ server }, world, render, java.io.File(dir, "icons-sent.json"), scope) { now }.start(status)
        await("the first pass") { puts.size == 1 }
        // Rounds every minute: no pass, not even the server's list asked for, until PASS_MS has gone by.
        repeat(5) {
            now += 60_000
            status.value = SharedSyncStatus(now, true, "Up to date")
            Thread.sleep(100)
        }
        assertEquals(1, lists.get())
        // A block names a new app: it goes now.
        world.value = world(Block("a", "A", targetPackages = setOf("com.example.chat", "com.example.new"), trigger = timer))
        await("the new app sent") { "app:com.example.new" in puts }
        assertEquals(2, lists.get())
        now += IconUpload.PASS_MS
        status.value = SharedSyncStatus(now, true, "Up to date")
        await("a pass after the wait") { lists.get() == 3 }
        Thread.sleep(100)
        assertEquals("nothing new to send", 2, puts.size)
        assertEquals("nor drawn again to compare", 2, render.drawn.size)
    }

    private fun await(what: String, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!ok()) {
            check(System.currentTimeMillis() < until) { "timed out waiting for: $what" }
            Thread.sleep(20)
        }
    }
}
