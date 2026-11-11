package com.xern.xrnrule.desktop

import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockEngine
import com.xern.xrnrule.domain.BlockEvent
import com.xern.xrnrule.domain.BlockLock
import com.xern.xrnrule.domain.ChecklistItem
import com.xern.xrnrule.domain.Clock
import com.xern.xrnrule.domain.Defaults
import com.xern.xrnrule.domain.DeviceKind
import com.xern.xrnrule.domain.EnforcementTier
import com.xern.xrnrule.domain.PersistPort
import com.xern.xrnrule.domain.SiteIcons
import com.xern.xrnrule.domain.Trigger
import com.xern.xrnrule.domain.TriggerType
import com.xern.xrnrule.domain.TriggerUnit
import com.xern.xrnrule.domain.World
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.util.concurrent.ConcurrentHashMap

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

    /**
     * A daemon holding [blocks], with [known] the browsers that have had the extension (I49), and
     * [fetcher] what fetches site icons (nothing, by default).
     */
    private inner class Daemon(
        blocks: List<Block> = emptyList(),
        checklist: Map<String, List<ChecklistItem>> = emptyMap(),
        known: String? = null,
        fetcher: (String, Int) -> SiteIcons.Fetched? = { _, _ -> null },
    ) {
        val clock = TestClock()
        val port = ServerSocket(0).use { it.localPort }
        private val config = Config(server = "", user = "", password = "", port = port, dataDir = dir)
        private val scope = CoroutineScope(Dispatchers.Unconfined)
        private val world = World(blocks = blocks, checklist = checklist, deviceId = "laptop-1", deviceKind = DeviceKind.LAPTOP)
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
            LocalServer(config, store, foreground, enforcer, shared, HabitClient(config, store, scope), SiteIconCache(icons, scope, fetcher), telemetry).start()
        }

        /** The answer's status code. [xrnrule]: with the header that only our pages and the extension can send. */
        fun get(path: String, xrnrule: Boolean = true): Int = request("GET", path, null, xrnrule)

        fun post(path: String, body: String): Int = request("POST", path, body, xrnrule = true)

        /** What a POST answers, as JSON. */
        fun postJson(path: String, body: String): JsonObject {
            var answer = ""
            request("POST", path, body, xrnrule = true) { answer = it }
            return Json.parseToJsonElement(answer).jsonObject
        }

        private fun request(method: String, path: String, body: String?, xrnrule: Boolean, read: (String) -> Unit = {}): Int {
            val conn = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
            conn.requestMethod = method
            if (xrnrule) conn.setRequestProperty("X-Xrnrule", "1")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            (if (code < 400) conn.inputStream else conn.errorStream)?.use { read(it.readBytes().decodeToString()) }
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

    private fun await(what: String, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!ok()) {
            check(System.currentTimeMillis() < until) { "timed out waiting for: $what" }
            Thread.sleep(20)
        }
    }

    @Test
    fun aSiteIconIsOnlyLookedForForASiteABlockNames() {
        val looked = ConcurrentHashMap.newKeySet<String>()
        val block = Block(
            "b", "B", targetSites = setOf("youtube.com"), exceptSites = setOf("music.youtube.com"),
            trigger = Trigger(TriggerType.TIMER, 60_000, TriggerUnit.MS),
        )
        val d = Daemon(listOf(block), fetcher = { url, _ -> looked += URI(url).host; null })
        // Any web page can ask, in an <img>, with no X-Xrnrule: no host of its choosing is fetched.
        assertEquals(404, d.get("/api/site-icon?host=tracker.example", xrnrule = false))
        // A block's sites are, and a host under one: the block page shows the host it stands in for.
        for (host in listOf("youtube.com", "m.youtube.com", "music.youtube.com")) {
            assertEquals(404, d.get("/api/site-icon?host=$host", xrnrule = false))
        }
        await("the block's sites looked for") { looked.containsAll(listOf("youtube.com", "m.youtube.com", "music.youtube.com")) }
        Thread.sleep(300) // a fetch started before those would have come by now
        assertFalse(looked.toString(), "tracker.example" in looked)
        assertFalse("nothing kept for it", File(d.icons, "tracker.example.miss").exists())
    }

    @Test
    fun i49_aPagePollingTheRuleListIsNotTheExtension() {
        val d = Daemon(known = "zen\n")
        d.foreground.active = AppWindow("zen", "zen", 10)
        // The extension is off, and a web page in the browser asks for the rule list. It can't add
        // X-Xrnrule: that takes a preflight, which is never approved.
        repeat(13) {
            d.clock.mono += 10_000
            assertEquals(403, d.get("/ext/rules", xrnrule = false))
        }
        assertTrue(d.foreground.extensionMissing())
        assertEquals("zen", d.foreground.browsers.toClose(d.clock.mono))
        // The extension sends it with every call.
        assertEquals(200, d.get("/ext/rules?focused=1"))
        assertNull(d.foreground.browsers.toClose(d.clock.mono))
    }

    @Test
    fun aWaitNobodyConfirmsIsDroppedOnceItCantBeConfirmed() {
        val d = Daemon()
        // A break waits 60 s (§2.3, I25); the page that asked went before it could confirm or cancel.
        val stale = d.postJson("/api/event", """{"type":"pause"}""")["token"]!!.jsonPrimitive.content
        d.clock.mono += LocalServer.GUARD_MS + 5 * 60_000 + 1
        // The next wait started finds it past confirming, and drops it: it isn't kept for good.
        val next = d.postJson("/api/event", """{"type":"pause"}""")["token"]!!.jsonPrimitive.content
        assertEquals("That wait was cancelled. Start again.", d.postJson("/api/confirm", """{"token":"$stale"}""")["error"]!!.jsonPrimitive.content)
        // One that can still be confirmed stays.
        d.clock.mono += LocalServer.GUARD_MS
        assertEquals(JsonObject(emptyMap()), d.postJson("/api/confirm", """{"token":"$next"}"""))
    }

    /** A habit block on [site], its unlock a 60-minute timer; [lockedFor] from now, if locked (I39). */
    private fun habitOn(id: String, site: String, lockedFor: Long? = null, hardcore: Boolean = false) = Block(
        id, id.replaceFirstChar { it.uppercase() }, targetSites = setOf(site),
        trigger = Trigger(TriggerType.TIMER, 60 * 60_000, TriggerUnit.MS),
        enforcementTier = if (hardcore) EnforcementTier.HARDCORE else EnforcementTier.SOFT,
        lock = lockedFor?.let { BlockLock(untilWall = TestClock().nowWallMs() + it, setAtWall = TestClock().nowWallMs()) },
    )

    private fun JsonObject.error(): String? = this["error"]?.jsonPrimitive?.content

    @Test
    fun i06_aNewHabitBlockOnAnotherOnesSiteAsksThatOnesGuard() {
        val d = Daemon(listOf(habitOn("locked", "youtube.com", lockedFor = 86_400_000), habitOn("hard", "x.com", hardcore = true), habitOn("soft", "reddit.com")))
        fun make(site: String) = d.postJson("/api/block", """{"name":"Easy","sites":["$site"],"items":["Stretch"]}""")
        fun made() = d.store.world.value.blocks.count { it.name == "Easy" }
        // An easy habit block on a site a locked one holds: its unlock would open that one (I6), so the lock refuses it (I39).
        for (site in listOf("youtube.com", "m.youtube.com")) assertTrue(site, make(site).error().orEmpty().contains("Locked"))
        // On a hardcore one's: only the phone, which has the password.
        assertTrue(make("x.com").error().orEmpty().contains("phone"))
        assertEquals(0, made())
        // On a soft one's: its 60 s.
        val token = make("reddit.com")["token"]!!.jsonPrimitive.content
        assertEquals(0, made())
        d.clock.mono += LocalServer.GUARD_MS
        assertEquals(JsonObject(emptyMap()), d.postJson("/api/confirm", """{"token":"$token"}"""))
        assertEquals(1, made())
        // On no habit block's site, or not a habit block: made at once.
        assertEquals(JsonObject(emptyMap()), make("example.com"))
        assertEquals(JsonObject(emptyMap()), d.postJson("/api/block", """{"name":"Hours","mode":"SCHEDULE","sites":["youtube.com"]}"""))
        assertEquals(2, made())
    }

    @Test
    fun i06_givingAHabitBlockALockedOnesSiteIsRefused() {
        val d = Daemon(listOf(habitOn("locked", "youtube.com", lockedFor = 86_400_000), habitOn("mine", "example.com")))
        fun sites() = d.store.world.value.block("mine")!!.targetSites
        // More to block is no loosening of its own, but its unlock would open what the locked one blocks (I6).
        val targets = d.postJson("/api/laptop", """{"blockId":"mine","desktopApps":[],"sites":["example.com","youtube.com"]}""")
        assertTrue(targets.toString(), targets.error().orEmpty().contains("Locked"))
        val form = d.postJson("/api/block", """{"id":"mine","name":"Mine","sites":["example.com","youtube.com"],"timerMin":60}""")
        assertTrue(form.toString(), form.error().orEmpty().contains("Locked"))
        assertEquals(setOf("example.com"), sites())
        // A site no other habit block holds: at once.
        assertEquals(JsonObject(emptyMap()), d.postJson("/api/laptop", """{"blockId":"mine","desktopApps":[],"sites":["example.com","example.org"]}"""))
        assertEquals(setOf("example.com", "example.org"), sites())
    }

    @Test
    fun i06_aTaskTakenFromABlockOnALockedOnesSiteIsRefused() {
        val mine = habitOn("mine", "youtube.com").copy(trigger = Trigger(TriggerType.CHECKLIST, Defaults.ALL_ITEMS, TriggerUnit.ITEMS))
        val tasks = listOf(ChecklistItem(id = "t1", blockId = "mine", text = "Stretch", order = 0), ChecklistItem(id = "t2", blockId = "mine", text = "Read", order = 1))
        val d = Daemon(listOf(habitOn("locked", "youtube.com", lockedFor = 86_400_000), mine), checklist = mapOf("mine" to tasks))
        // Easier to earn, its unlock opens youtube.com, which the locked block holds, the sooner (I6).
        val answer = d.postJson("/api/item", """{"blockId":"mine","op":"remove","itemId":"t1"}""")
        assertTrue(answer.toString(), answer.error().orEmpty().contains("Locked"))
        assertEquals(2, d.store.world.value.items("mine").size)
    }
}
