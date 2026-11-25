package com.xern.xrnrule.app.data

import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.Clock
import com.xern.xrnrule.domain.PersistPort
import com.xern.xrnrule.domain.RunUse
import com.xern.xrnrule.domain.Trigger
import com.xern.xrnrule.domain.TriggerType
import com.xern.xrnrule.domain.TriggerUnit
import com.xern.xrnrule.domain.World
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** What [ThrottledPersist] holds back: what moves every tick, and nothing else. */
class ThrottledPersistTest {

    private class Ticking : Clock {
        var mono = 0L
        override fun nowMonotonicMs() = mono
        override fun nowWallMs() = 1_800_000_000_000L + mono
        override fun zone(): ZoneId = ZoneId.of("UTC")
    }

    private class Counting(private val first: World) : PersistPort {
        val saved = mutableListOf<World>()
        override fun loadWorld() = first
        override fun save(world: World) {
            saved += world
        }
    }

    private val row = Block(
        id = "videos",
        name = "Videos",
        targetPackages = setOf("com.example.videos"),
        trigger = Trigger(TriggerType.TIMER, 0, TriggerUnit.MS),
        mode = BlockMode.ALLOWANCE,
        sessionMs = 15 * 60_000L,
        sessionBreakMs = 15 * 60_000L,
    )

    @Test
    fun aRowMovingOnIsNotWrittenEverySecond() {
        // The row's ms and last use moved every tick, and each one rewrote world.json with an fsync.
        val clock = Ticking()
        val start = World(blocks = listOf(row), runs = mapOf("videos" to RunUse(ms = 0, lastActiveWall = clock.nowWallMs())))
        val inner = Counting(start)
        val persist = ThrottledPersist(inner, clock)
        persist.loadWorld()
        for (second in 1..29) {
            clock.mono = second * 1_000L
            persist.save(start.copy(runs = mapOf("videos" to RunUse(ms = second * 1_000L, lastActiveWall = clock.nowWallMs()))))
        }
        assertEquals("nothing written for 30 s", 0, inner.saved.size)

        clock.mono = 30_000
        val last = start.copy(runs = mapOf("videos" to RunUse(ms = 30_000, lastActiveWall = clock.nowWallMs())))
        persist.save(last)
        assertEquals("then the held-back use goes", listOf(last), inner.saved)
    }

    @Test
    fun aBreakStartingIsWrittenAtOnce() {
        val clock = Ticking()
        val start = World(blocks = listOf(row), runs = mapOf("videos" to RunUse(ms = 14 * 60_000L, lastActiveWall = clock.nowWallMs())))
        val inner = Counting(start)
        val persist = ThrottledPersist(inner, clock)
        persist.loadWorld()
        clock.mono = 1_000
        val resting = start.copy(runs = mapOf("videos" to RunUse(15 * 60_000L, clock.nowWallMs(), breakUntilWall = clock.nowWallMs() + 15 * 60_000L)))
        persist.save(resting)
        assertEquals(listOf(resting), inner.saved)
    }
}
