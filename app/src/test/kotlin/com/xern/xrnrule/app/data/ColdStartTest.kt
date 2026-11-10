package com.xern.xrnrule.app.data

import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockEngine
import com.xern.xrnrule.domain.BlockEvent
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.Clock
import com.xern.xrnrule.domain.HabitProgress
import com.xern.xrnrule.domain.PersistPort
import com.xern.xrnrule.domain.Telemetry
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

class ColdStartTest {
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

    private object NoTelemetry : Telemetry {
        override fun onBlockedImpression(pkg: String, atWallMs: Long) = Unit
        override fun onSiteBlocked(host: String, atWallMs: Long) = Unit
        override fun onHabitCompleted(blockId: String, atWallMs: Long) = Unit
        override fun onUnlockConsumed(blockId: String, ms: Long, atWallMs: Long, startedAtWall: Long) = Unit
        override fun onMinutesBought(blockIds: Set<String>, ms: Long, atWallMs: Long) = Unit
        override fun onBreakTaken(ms: Long, atWallMs: Long) = Unit
    }

    private val clock = TestClock(mono = 10 * hour)
    private val limit = Block(
        "b", "B", targetSites = setOf("tiktok.com"), trigger = Trigger(TriggerType.TIMER, 0, TriggerUnit.MS),
        mode = BlockMode.ALLOWANCE, dailyAllowanceMs = 2 * hour,
    )

    /** MIUI killed xrnrule an hour ago with TikTok playing in a tab out of sight (I47). */
    private fun killed() = WorldStore(
        BlockEngine(),
        Saved(
            World(
                blocks = listOf(limit), deviceId = "phone-1", backgroundSites = setOf("tiktok.com"),
                progress = mapOf("b" to HabitProgress("b", windowStart = clock.startOfLocalDayMs(), lastUpdated = clock.mono - hour)),
            ),
        ),
        NoTelemetry,
        clock,
        CoroutineScope(Dispatchers.Unconfined),
    )

    /** What the service's collector of sites playing does first: say what plays now. */
    private fun WorldStore.reportPlaying() = dispatch(BlockEvent.BackgroundChanged(setOf("tiktok.com")))

    private fun WorldStore.used() = engine.allowanceUsedMs(world.value, limit, clock)

    @Test
    fun theTimeTheServiceWasDeadIsChargedToNothing() = runBlocking {
        // How it went: the collector's first word reached the engine before the rebuild.
        val before = killed()
        before.reportPlaying()
        before.dispatchAndGet(BlockEvent.BootOrColdStart)
        assertEquals(hour, before.used())

        val store = killed()
        store.coldStart { store.reportPlaying() }
        assertEquals(0L, store.used())
    }
}
