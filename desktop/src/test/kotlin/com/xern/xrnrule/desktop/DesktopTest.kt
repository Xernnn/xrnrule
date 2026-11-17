package com.xern.xrnrule.desktop

import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockEngine
import com.xern.xrnrule.domain.BlockEvent
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.Clock
import com.xern.xrnrule.domain.DeviceKind
import com.xern.xrnrule.domain.PersistPort
import com.xern.xrnrule.domain.Trigger
import com.xern.xrnrule.domain.TriggerType
import com.xern.xrnrule.domain.TriggerUnit
import com.xern.xrnrule.domain.World
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneId

class DesktopTest {

    private class TestClock(var mono: Long = 1_000_000) : Clock {
        override fun nowMonotonicMs() = mono
        override fun nowWallMs() = 1_800_000_000_000 + mono
        override fun zone(): ZoneId = ZoneId.of("UTC")
    }

    private object NoPersist : PersistPort {
        override fun loadWorld() = World()
        override fun save(world: World) = Unit
    }

    private fun foreground(clock: TestClock = TestClock()) =
        Foreground(Store(BlockEngine(), NoPersist, clock, CoroutineScope(Dispatchers.Unconfined)))

    private fun Foreground.current(): Pair<String?, String?>? = sent

    /** A store in which the laptop app [app] is blocked right now, by a schedule that is always on. */
    private fun blocking(app: String, clock: TestClock): Store {
        val block = Block("b", "B", targetDesktopApps = setOf(app), trigger = Trigger(TriggerType.TIMER, 0, TriggerUnit.MS), mode = BlockMode.SCHEDULE)
        val saved = object : PersistPort {
            override fun loadWorld() = World(blocks = listOf(block), deviceId = "laptop-1", deviceKind = DeviceKind.LAPTOP)
            override fun save(world: World) = Unit
        }
        return Store(BlockEngine(), saved, clock, CoroutineScope(Dispatchers.Unconfined)).also {
            runBlocking { it.dispatchAndGet(BlockEvent.BootOrColdStart) }
        }
    }

    @Test
    fun focusedTabSpeaksForTheForeground_unfocusedOneDoesNot() {
        val fg = foreground()
        fg.active = AppWindow("helium-browser", "helium", 10)
        fg.onHeartbeat(Foreground.Heartbeat(host = "www.youtube.com", focused = true))
        assertEquals("helium-browser" to "youtube.com", fg.current())

        // A second, visible but unfocused window reports in: it mustn't take over.
        fg.onHeartbeat(Foreground.Heartbeat(host = "docs.example.com", focused = false))
        assertEquals("helium-browser" to "youtube.com", fg.current())

        // The focused tab loses focus: nothing site-wise is in front any more.
        fg.onHeartbeat(Foreground.Heartbeat(host = "www.youtube.com", focused = false))
        assertEquals("helium-browser" to null, fg.current())
    }

    @Test
    fun switchingToAnotherAppDropsTheTab() {
        val fg = foreground()
        fg.active = AppWindow("helium-browser", "helium", 10)
        fg.onHeartbeat(Foreground.Heartbeat(host = "youtube.com", focused = true))
        fg.active = AppWindow("org.kde.dolphin", "dolphin", 11)
        fg.update()
        assertEquals("org.kde.dolphin" to null, fg.current())
    }

    @Test
    fun blockPageStandsInForTheBlockedAppAndLockMeansNothing() {
        val clock = TestClock()
        val fg = Foreground(blocking("steam", clock))
        fg.active = AppWindow("helium-browser", "helium", 10)
        fg.onHeartbeat(Foreground.Heartbeat(app = "steam", focused = true, fromBlockPage = true))
        assertEquals("steam" to null, fg.current())

        fg.locked = true
        fg.update()
        assertEquals(null to null, fg.current())

        fg.locked = false
        clock.mono += Foreground.FRESH_MS + 1 // the page went quiet
        fg.update()
        assertEquals("helium-browser" to null, fg.current())
    }

    @Test
    fun extensionMissingOnlyWhenABrowserIsInFront() {
        val clock = TestClock()
        val fg = foreground(clock)
        clock.mono += 60_000
        fg.active = AppWindow("org.kde.kate", "kate", 5)
        assertFalse(fg.extensionMissing())
        fg.active = AppWindow("helium-browser", "helium", 10)
        assertFalse("just came to the front", fg.extensionMissing())
        clock.mono += Foreground.EXTENSION_SILENCE_MS + 1
        assertTrue(fg.extensionMissing())
        fg.onHeartbeat(Foreground.Heartbeat(host = "example.com", focused = true))
        assertFalse(fg.extensionMissing())
    }

    @Test
    fun i49_aBrowserIsKnownByAWordOfItsIdNotByPartOfOne() {
        val clock = TestClock()
        // Zen has the extension: a browser without it goes after 10 s in front.
        val known = File(Files.createTempDirectory("xrnrule").toFile(), "browsers.txt").apply { writeText("zen\n") }
        val fg = Foreground(Store(BlockEngine(), NoPersist, clock, CoroutineScope(Dispatchers.Unconfined)), known)
        fun closedAfterAWhile(appId: String): Boolean {
            fg.active = AppWindow(appId, appId, 10)
            clock.mono += BrowserWatch.OTHER_GRACE_MS + 1
            return fg.browsers.toClose(clock.mono) == appId
        }
        // Steam's prompts are zenity's, and other names only hold a browser's.
        for (app in listOf("zenity", "org.gnome.Zenity", "org.kde.knowledge", "operator", "chromatic", "steam")) {
            assertFalse(app, closedAfterAWhile(app))
        }
        for (app in listOf(
            "chromium", "google-chrome", "org.chromium.Chromium", "com.brave.Browser", "vivaldi-stable", "microsoft-edge",
            "opera", "firefox", "org.mozilla.firefox", "firefoxdeveloperedition", "app.zen_browser.zen", "zen-twilight",
            "librewolf", "one.ablaze.floorp", "thorium-browser", "helium-browser",
        )) {
            assertTrue(app, closedAfterAWhile(app))
        }
    }

    @Test
    fun i49_aBlockPageLeftInTheBackgroundIsNotTheExtensionAnswering() {
        val clock = TestClock()
        val known = File(Files.createTempDirectory("xrnrule").toFile(), "browsers.txt").apply { writeText("zen\n") }
        val fg = Foreground(Store(BlockEngine(), NoPersist, clock, CoroutineScope(Dispatchers.Unconfined)), known)
        fg.active = AppWindow("zen", "zen", 10)
        // The extension turned off, with a block page still open in another tab: it beats on, out of sight.
        repeat(70) {
            clock.mono += 2_000
            fg.onHeartbeat(Foreground.Heartbeat(host = "youtube.com", focused = false, fromBlockPage = true))
        }
        assertTrue(fg.extensionMissing())
        assertEquals("zen", fg.browsers.toClose(clock.mono))
    }

    @Test
    fun i49_aBlockPageInFrontIsNotTheExtensionAnsweringEither() {
        val clock = TestClock()
        val known = File(Files.createTempDirectory("xrnrule").toFile(), "browsers.txt").apply { writeText("zen\n") }
        val fg = Foreground(Store(BlockEngine(), NoPersist, clock, CoroutineScope(Dispatchers.Unconfined)), known)
        fg.active = AppWindow("zen", "zen", 10)
        // The page is ours: it opens in a private window the extension can't see, or with the
        // extension off, and kept in front it kept the browser open, with sound from a window
        // behind it never counted (I47). With the extension on, its minute poll speaks for it.
        repeat(70) {
            clock.mono += 2_000
            fg.onHeartbeat(Foreground.Heartbeat(host = "youtube.com", focused = true, fromBlockPage = true))
        }
        assertTrue(fg.extensionMissing())
        assertEquals("zen", fg.browsers.toClose(clock.mono))
    }

    @Test
    fun i49_aLockedScreenClosesNoBrowser() {
        val clock = TestClock()
        val known = File(Files.createTempDirectory("xrnrule").toFile(), "browsers.txt").apply { writeText("zen\n") }
        val fg = Foreground(Store(BlockEngine(), NoPersist, clock, CoroutineScope(Dispatchers.Unconfined)), known)
        fg.active = AppWindow("zen", "zen", 10)
        // Under the lock a browser may not count its window as focused: nothing speaks for it.
        fg.locked = true
        clock.mono += 10 * 60_000
        assertFalse(fg.extensionMissing())
        assertEquals(null, fg.browsers.toClose(clock.mono))
        // Unlocked, its silence starts again, as after a sleep.
        fg.locked = false
        assertEquals(null, fg.browsers.toClose(clock.mono))
        clock.mono += BrowserWatch.SILENT_GRACE_MS
        assertEquals("zen", fg.browsers.toClose(clock.mono))
    }

    @Test
    fun i49_aBrowsersOwnWindowInFrontIsNoBrowserInFront() {
        val clock = TestClock()
        val known = File(Files.createTempDirectory("xrnrule").toFile(), "browsers.txt").apply { writeText("zen\n") }
        val store = Store(BlockEngine(), NoPersist, clock, CoroutineScope(Dispatchers.Unconfined))
        val fg = Foreground(store, known)
        val config = Config("", "", "", 0, File("/nonexistent"), openBlockPage = false)
        val quiet = object : Notifier(config) {
            override fun notify(title: String, body: String, urgent: Boolean) = Unit
            override fun blocked(appId: String) = Unit
        }
        val enforcer = Enforcer(store, quiet, config).also { it.foreground = fg }
        // Clicked to pause or seek, as Hyprland reports it: no page of the extension is focused
        // there, and it sees no focused window of its own. It was closed two minutes in, mid-video.
        for (title in listOf(
            "Picture-in-Picture", "Picture in picture",
            "Developer Tools — YouTube — https://www.youtube.com/watch?v=1", "DevTools - www.youtube.com/watch?v=1",
        )) {
            enforcer.Activated("zen", "zen", "10")
            enforcer.Titled(title)
            clock.mono += 10 * 60_000
            assertFalse(title, fg.extensionMissing())
            assertEquals(title, null, fg.browsers.toClose(clock.mono))
        }
        // Back in the browser's own window, it is watched again, its silence from then on.
        enforcer.Activated("zen", "zen", "10")
        enforcer.Titled("YouTube — Zen Browser")
        assertEquals(null, fg.browsers.toClose(clock.mono))
        clock.mono += BrowserWatch.SILENT_GRACE_MS
        assertEquals("zen", fg.browsers.toClose(clock.mono))
        // A page named like one is no such window: the browser's window title has more to it.
        for (title in listOf("Picture-in-Picture — Zen Browser", "DevTools tips - Chromium", "Developer Tools guide — Zen Browser")) {
            assertFalse(title, Foreground.isOwnWindow(title))
        }
    }

    @Test
    fun i49_aPageTitledLikeDevToolsIsNoDevTools() {
        // A bookmarklet, document.title = "DevTools - x": the window's title began like DevTools',
        // so a browser without the extension was never in front, and every blocked site was open in it.
        for (title in listOf(
            "DevTools - x - Chromium", "DevTools - www.youtube.com/watch?v=1 - Brave",
            "Developer Tools — x — Zen Browser", "Developer Tools — x — https://www.youtube.com/ — Mozilla Firefox",
            "Developer Tools — https://www.youtube.com/ — Zen Browser Private Browsing",
        )) {
            assertFalse(title, Foreground.isOwnWindow(title))
        }
        for (title in listOf("Developer Tools — about:blank", "Developer Tools — https://www.youtube.com/")) {
            assertTrue(title, Foreground.isOwnWindow(title))
        }
    }

    @Test
    fun i49_aBrowserWithoutTheExtensionIsWatchedWhicheverOfItsWindowsIsInFront() {
        val clock = TestClock()
        val known = File(Files.createTempDirectory("xrnrule").toFile(), "browsers.txt").apply { writeText("zen\n") }
        val store = Store(BlockEngine(), NoPersist, clock, CoroutineScope(Dispatchers.Unconfined))
        val fg = Foreground(store, known)
        val config = Config("", "", "", 0, File("/nonexistent"), openBlockPage = false)
        val quiet = object : Notifier(config) {
            override fun notify(title: String, body: String, urgent: Boolean) = Unit
            override fun blocked(appId: String) = Unit
        }
        val enforcer = Enforcer(store, quiet, config).also { it.foreground = fg }
        // Chromium has never had the extension: its video in Picture-in-Picture, clicked, is still it.
        enforcer.Activated("chromium", "chromium", "20")
        enforcer.Titled("Picture in picture")
        clock.mono += BrowserWatch.OTHER_GRACE_MS
        assertEquals("chromium", fg.browsers.toClose(clock.mono))
    }

    @Test
    fun i16_theBlockPageStandsInOnlyForAnAppThatIsBlocked() {
        val fg = Foreground(blocking("steam", TestClock()))
        // /block?app=… is anyone's to type: named after a habit app, it would run that app's timer unopened.
        fg.onHeartbeat(Foreground.Heartbeat(app = "com.reading.app", focused = true, fromBlockPage = true))
        fg.update()
        assertEquals(null to null, fg.current())
        // Steam is blocked: waiting on its block page counts as waiting on it (I4).
        fg.onHeartbeat(Foreground.Heartbeat(app = "steam", focused = true, fromBlockPage = true))
        assertEquals("steam" to null, fg.current())
    }

    @Test
    fun configReadsPasswordFileAndDerivesUrls() {
        val dir = Files.createTempDirectory("xrnrule").toFile()
        val pw = File(dir, "pw").apply { writeText("secret\n") }
        val props = File(dir, "desktop.properties").apply {
            writeText("server=https://example.org/\nuser=you\npasswordFile=${pw.path}\nport=40000\n")
        }
        val config = Config.load(props)
        assertEquals("secret", config.password)
        assertEquals("https://example.org/xrnrule", config.ruleUrl)
        assertEquals("https://example.org/xrnloops", config.loopUrl)
        assertEquals("http://127.0.0.1:40000", config.localUrl)
        assertTrue(config.syncConfigured)
        assertTrue(config.openBlockPage)
    }

    @Test
    fun windowsWithoutADesktopFileFallBackToTheirClass() {
        assertEquals("org.kde.kcalc", DesktopApps.resolve("org.kde.kcalc.desktop", "kcalc"))
        assertEquals(null, DesktopApps.resolve("", ""))
        assertTrue(DesktopApps.resolve("", "SomeGame")!!.isNotEmpty())
    }

    @Test
    fun aDesktopFileThatCantBeReadIsSkipped() {
        val dir = Files.createTempDirectory("xrnrule-apps").toFile()
        try {
            File(dir, "org.kde.kcalc.desktop").writeText("[Desktop Entry]\nType=Application\nName=KCalc\nStartupWMClass=kcalc\n")
            Files.createSymbolicLink(File(dir, "gone.desktop").toPath(), File(dir, "nowhere").toPath()) // dangling
            File(dir, "folder.desktop").mkdirs()
            File(dir, "secret.desktop").apply { writeText("[Desktop Entry]\nType=Application\nName=Secret\n") }.setReadable(false)
            assertEquals(listOf(DesktopApps.App("org.kde.kcalc", "KCalc", "kcalc")), DesktopApps.scan(listOf(dir, File(dir, "missing"))))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun telemetryHandsOutOnlyNewerEventsAndForgetsOldOnes() {
        val log = FileTelemetry(File(Files.createTempDirectory("xrnrule").toFile(), "telemetry.log"))
        val day = 86_400_000L
        log.onBlockedImpression("org.kde.kcalc", 1_000)
        log.onHabitCompleted("b1", 200 * day)
        log.onUnlockConsumed("b1", 300_000, 200 * day + 1, 200 * day - 299_999)
        val after = log.eventsAfter(1_000)
        assertEquals(listOf("habit", "unlock"), after.map { it["t"]!!.jsonPrimitive.content })
        assertEquals(300_000L, after[1]["ms"]!!.jsonPrimitive.long)
        assertEquals(200 * day - 299_999, after[1]["start"]!!.jsonPrimitive.long)
        log.prune(nowWallMs = 200 * day, keepDays = 120)
        assertEquals(2, log.eventsAfter(0).size) // the blocked event at 1 s was 200 days old
    }
}
