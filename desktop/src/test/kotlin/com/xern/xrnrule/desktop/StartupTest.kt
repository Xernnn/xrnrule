package com.xern.xrnrule.desktop

import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockEngine
import com.xern.xrnrule.domain.BlockEvent
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.Clock
import com.xern.xrnrule.domain.DeviceKind
import com.xern.xrnrule.domain.HabitProgress
import com.xern.xrnrule.domain.PersistPort
import com.xern.xrnrule.domain.Trigger
import com.xern.xrnrule.domain.TriggerType
import com.xern.xrnrule.domain.TriggerUnit
import com.xern.xrnrule.domain.World
import com.xern.xrnrule.domain.startOfLocalDayMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class StartupTest {
    private val hour = 3_600_000L

    private class TestClock(val mono: Long) : Clock {
        override fun nowMonotonicMs() = mono
        override fun nowWallMs() = 1_800_000_000_000L + mono
        override fun zone(): ZoneId = ZoneId.of("UTC")
    }

    private class Saved(private val world: World) : PersistPort {
        override fun loadWorld() = world
        override fun save(world: World) = Unit
    }

    private val clock = TestClock(mono = 10 * hour)
    private val limit = Block(
        "b", "B", targetDesktopApps = setOf("steam"), trigger = Trigger(TriggerType.TIMER, 0, TriggerUnit.MS),
        mode = BlockMode.ALLOWANCE, dailyAllowanceMs = 2 * hour,
    )

    /** The daemon stopped an hour ago with Steam in front: its limit's count last moved on then. */
    private fun stopped() = Store(
        BlockEngine(),
        Saved(
            World(
                blocks = listOf(limit), deviceId = "laptop-1", deviceKind = DeviceKind.LAPTOP, foregroundPkg = "steam",
                progress = mapOf("b" to HabitProgress("b", windowStart = clock.startOfLocalDayMs(), lastUpdated = clock.mono - hour)),
            ),
        ),
        clock,
        CoroutineScope(Dispatchers.Unconfined),
    )

    /** What the window system does first on starting: say what's in front. */
    private fun Store.reportSteam() {
        Foreground(this).apply { active = AppWindow("steam", "steam", 1) }.update()
    }

    private fun Store.used() = BlockEngine().allowanceUsedMs(world.value, limit, clock)

    @Test
    fun theTimeTheDaemonWasStoppedIsChargedToNothing() = runBlocking {
        // How it went: the first window reported reached the engine before the rebuild.
        val before = stopped()
        before.reportSteam()
        before.dispatchAndGet(BlockEvent.BootOrColdStart)
        assertEquals(hour, before.used())

        val store = stopped()
        store.coldStart { store.reportSteam() }
        assertEquals(0L, store.used())
    }
}
