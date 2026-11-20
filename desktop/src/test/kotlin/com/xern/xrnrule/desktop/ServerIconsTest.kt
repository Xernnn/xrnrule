package com.xern.xrnrule.desktop

import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.Icons
import com.xern.xrnrule.domain.SiteIcons
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.util.Base64

/**
 * The laptop's side of the logos, against the real `server/xrnrule-sync.py` on a free port: what
 * it sends, how often, and what it keeps of the phone's. Skipped without python3 and bcrypt.
 */
class ServerIconsTest {
    private val repo = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val dir = Files.createTempDirectory("xrnrule-server-icons").toFile()
    private val log = File(dir, "server.log")
    private var server: Process? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var url: String

    @Before
    fun startServer() {
        val hasPython = runCatching { ProcessBuilder("python3", "-c", "import bcrypt").start().waitFor() == 0 }.getOrDefault(false)
        assumeTrue("needs python3 with bcrypt", hasPython)
        val hash = ProcessBuilder("python3", "-c", "import bcrypt; print(bcrypt.hashpw(b'pw', bcrypt.gensalt(4)).decode())")
            .start().inputStream.bufferedReader().readText().trim()
        val users = File(dir, "users").apply { writeText("you:$hash\n") }
        val port = ServerSocket(0).use { it.localPort }
        server = ProcessBuilder("python3", File(repo, "server/xrnrule-sync.py").path, "--port", "$port", "--data", File(dir, "data").path, "--users", users.path)
            .redirectErrorStream(true).redirectOutput(log).start()
        url = "http://127.0.0.1:$port/xrnrule"
        await("server up") { runCatching { (URI("$url/health").toURL().openConnection() as HttpURLConnection).responseCode == 200 }.getOrDefault(false) }
    }

    @After
    fun stop() {
        scope.cancel()
        server?.destroy()
        dir.deleteRecursively()
    }

    private fun await(what: String, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + 20_000
        while (!ok()) {
            check(System.currentTimeMillis() < until) { "timed out waiting for: $what" }
            Thread.sleep(50)
        }
    }

    /** A request to the server as the phone makes it (with no prefix, which Funnel may have taken off): (status, body). */
    private fun phone(method: String, path: String, body: ByteArray? = null): Pair<Int, ByteArray> {
        val conn = URI("${url.removeSuffix("/xrnrule")}$path").toURL().openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("you:pw".toByteArray()))
        conn.setRequestProperty("X-Device", "phone")
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        return code to ((if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes() } ?: ByteArray(0))
    }

    private fun listed(): Map<String, String> = Icons.index(phone("GET", "/icons").second.decodeToString())!!

    /** The icons the laptop has sent so far. */
    private fun puts(): Int = log.readLines().count { "\"PUT /xrnrule/icons/" in it }

    private val timer = Trigger(TriggerType.TIMER, 0, TriggerUnit.MS)

    private class Clock(var now: Long = 1_800_000_000_000)

    private fun icons(world: World, clock: Clock = Clock(), own: (World) -> Map<String, () -> ByteArray?>) =
        ServerIcons(File(dir, "copies"), url, Http("you", "pw"), MutableStateFlow(world), own, scope) { clock.now }

    @Test
    fun theLaptopSendsTheLogosItsBlocksNameOnceAndKeepsThePhonesForThePage() {
        val phonesApp = testPng(96, tint = 1)
        assertEquals(204, phone("PUT", "/icons/app%3Acom.example.chat", phonesApp).first)
        val data = File(dir, "share").apply { File(this, "icons/hicolor/128x128/apps").mkdirs() }
        File(data, "icons/hicolor/128x128/apps/steam.png").writeBytes(testPng(128, tint = 2))
        val apps = LaptopAppIcons({ if (it == "steam") "steam" else null }, listOf(data), rsvg = null, homeIcons = null)
        val siteIcon = testPng(180, tint = 3)
        val sites = SiteIconCache(File(dir, "site-icons"), scope) { u, _ ->
            if (u == "https://example.org/apple-touch-icon.png") SiteIcons.Fetched(u, siteIcon) else null
        }
        val world = World(blocks = listOf(Block("b", "B", targetSites = setOf("example.org/watch"), targetDesktopApps = setOf("steam", "not-installed"), trigger = timer)))
        val icons = icons(world) { ServerIcons.owned(it, sites, apps) }

        icons.pass()
        await("the site's own icon fetched") { sites.icon("example.org") != null }
        icons.pass()
        val on = listed()
        assertEquals(setOf("app:com.example.chat", "site:example.org", "desk:steam"), on.keys)
        assertArrayEquals(testPng(128, tint = 2), phone("GET", "/icons/desk%3Asteam").second)
        assertArrayEquals(siteIcon, phone("GET", "/icons/site%3Aexample.org").second)
        // The phone's app, kept here for the page; not the laptop's own, which it has.
        assertArrayEquals(phonesApp, icons.icon("app:com.example.chat")!!.bytes)
        assertNull(icons.icon("desk:steam"))

        // Nothing again while nothing changed.
        val sent = puts()
        assertEquals(2, sent)
        repeat(3) { icons.pass() }
        assertEquals(sent, puts())
    }

    @Test
    fun aChangedLogoGoesAgainButNotEveryPass() {
        var steam = testPng(64, tint = 4)
        val clock = Clock()
        val world = World(blocks = listOf(Block("b", "B", targetDesktopApps = setOf("steam"), trigger = timer)))
        val icons = icons(world, clock) { mapOf("desk:steam" to { steam }) }
        icons.pass()
        assertEquals(Icons.hash(steam), listed()["desk:steam"])
        // The app's icon changed: sent once the day since the last is up, then not again.
        steam = testPng(64, tint = 5)
        clock.now += 60_000
        icons.pass()
        assertEquals(1, puts())
        clock.now += Icons.RESEND_MS
        icons.pass()
        icons.pass()
        assertEquals(2, puts())
        assertEquals(Icons.hash(steam), listed()["desk:steam"])
    }

    @Test
    fun aPassSendsSoManyAndTheRestNextTime() {
        val many = (1..Icons.MAX_PER_PASS + 5).associate { "desk:app$it" to { testPng(16, tint = it) } }
        val icons = icons(World()) { many }
        icons.pass()
        assertEquals(Icons.MAX_PER_PASS, puts())
        icons.pass()
        assertEquals(many.size, puts())
        assertEquals(many.keys, listed().keys)
    }

    @Test
    fun whatTheServerWouldRefuseIsNeverSent() {
        val ico = byteArrayOf(0, 0, 1, 0, 1, 0) + ByteArray(Icons.MAX_BYTES) // too big, and the laptop can't draw it smaller
        val icons = icons(World()) { mapOf("desk:big" to { ico }, "desk:page" to { "<svg/>".toByteArray() }) }
        icons.pass()
        assertEquals(0, puts())
    }

    @Test
    fun aCopyTheServerNoLongerListsGoes() {
        val icons = icons(World()) { emptyMap() }
        assertEquals(true, icons.keep("app:com.example.gone", testPng(32)))
        assertEquals(32, SiteIcons.pixelSize(icons.icon("app:com.example.gone")!!.bytes))
        icons.pass()
        assertNull(icons.icon("app:com.example.gone"))
        assertFalse(File(dir, "copies").listFiles().orEmpty().any { it.length() > 0 && it.name != "index.json" })
        // Only raster images are kept, and only under a key.
        assertFalse(icons.keep("app:com.example.page", "<svg/>".toByteArray()))
        assertFalse(icons.keep("../../x", testPng(32)))
    }
}
