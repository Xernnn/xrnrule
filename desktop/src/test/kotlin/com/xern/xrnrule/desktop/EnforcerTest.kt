package com.xern.xrnrule.desktop

import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockEngine
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.Clock
import com.xern.xrnrule.domain.PersistPort
import com.xern.xrnrule.domain.Trigger
import com.xern.xrnrule.domain.TriggerType
import com.xern.xrnrule.domain.TriggerUnit
import com.xern.xrnrule.domain.World
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList

class EnforcerTest {

    private object TestClock : Clock {
        override fun nowMonotonicMs() = 1_000_000L
        override fun nowWallMs() = 1_800_000_000_000L
        override fun zone(): ZoneId = ZoneId.of("UTC")
    }

    private object NoPersist : PersistPort {
        override fun loadWorld() = World()
        override fun save(world: World) = Unit
    }

    private val config = Config("", "", "", 0, File("/nonexistent"), openBlockPage = false)

    /** No popups and no block page from a test. */
    private inner class Quiet : Notifier(config) {
        override fun blocked(appId: String) = Unit
        override fun notify(title: String, body: String, urgent: Boolean) = Unit
    }

    private class Windows : WindowSystem {
        override val name = "test"
        val closed = CopyOnWriteArrayList<Set<String>>()
        override fun start(bus: DesktopBus, connected: () -> Unit) = true
        override fun closeWindowsOf(appIds: Set<String>) {
            closed += appIds
        }
        override fun screenLocked() = false
    }

    /** Stand-ins for running apps, so a stopped one can be told from one left running. */
    private val processes = mutableListOf<Process>()

    private fun process(): Process = ProcessBuilder("sleep", "60").start().also { processes += it }

    @After
    fun stopProcesses() = processes.forEach { it.destroyForcibly() }

    private fun waitFor(what: String, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!ok()) {
            check(System.currentTimeMillis() < until) { "timed out waiting for: $what" }
            Thread.sleep(20)
        }
    }

    /** Blocks [apps] from now on, as an edit does: the store saves, then hands the plan to the enforcer. */
    private fun block(store: Store, apps: Set<String>) = runBlocking {
        store.edit {
            it.copy(blocks = listOf(Block("b", "B", targetDesktopApps = apps, trigger = Trigger(TriggerType.TIMER, 0, TriggerUnit.MS), mode = BlockMode.SCHEDULE)))
        }
    }

    private fun enforcer(windows: Windows): Pair<Store, Enforcer> {
        val store = Store(BlockEngine(), NoPersist, TestClock, CoroutineScope(Dispatchers.Unconfined))
        val enforcer = Enforcer(store, Quiet(), config, trayGraceMs = 0)
        enforcer.foreground = Foreground(store)
        enforcer.windowSystem = windows
        store.shield = enforcer
        return store to enforcer
    }

    // Omarchy's web apps are Chromium windows of their own app id, in the browser's process.
    private val youtube = "chrome-youtube.com__-default"

    @Test
    fun aBlockedWebAppClosesWithoutTakingItsBrowser() {
        for (webAppLast in listOf(false, true)) {
            val windows = Windows()
            val (store, enforcer) = enforcer(windows)
            val browser = process()
            val steam = process()
            val reports = listOf("chromium", youtube).let { if (webAppLast) it else it.reversed() }
            for (app in reports) enforcer.Added(app, app, browser.pid().toString())
            enforcer.Added("steam", "steam", steam.pid().toString())

            block(store, setOf(youtube, "steam"))
            // Steam hides to the tray when its window closes, so its process goes.
            waitFor("steam stopped") { !steam.isAlive }
            assertEquals("web app last: $webAppLast", listOf(setOf(youtube, "steam")), windows.closed.toList())
            Thread.sleep(200)
            assertTrue("the browser has windows that aren't blocked (web app last: $webAppLast)", browser.isAlive)
        }
    }

    @Test
    fun aProcessGoesOnlyWhenEverythingItShowsIsBlocked() {
        val shown = mapOf(1L to setOf("chromium", youtube), 2L to setOf("steam"), 3L to setOf(youtube), 4L to emptySet())
        assertEquals(setOf(2L, 3L), Enforcer.toStop(shown, setOf(youtube, "steam")))
        assertEquals(setOf(1L, 2L, 3L), Enforcer.toStop(shown, setOf("chromium", youtube, "steam")))
        assertEquals(emptySet<Long>(), Enforcer.toStop(shown, emptySet()))
    }
}
