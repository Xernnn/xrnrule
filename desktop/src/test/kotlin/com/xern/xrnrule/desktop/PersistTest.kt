package com.xern.xrnrule.desktop

import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockLock
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.Clock
import com.xern.xrnrule.domain.EnforcementTier
import com.xern.xrnrule.domain.SharedState
import com.xern.xrnrule.domain.Trigger
import com.xern.xrnrule.domain.TriggerType
import com.xern.xrnrule.domain.TriggerUnit
import com.xern.xrnrule.domain.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneId

class PersistTest {

    private object TestClock : Clock {
        override fun nowMonotonicMs() = 1_000_000L
        override fun nowWallMs() = 1_800_000_000_000L
        override fun zone(): ZoneId = ZoneId.of("UTC")
    }

    private val locked = Block(
        id = "b1",
        name = "Hardcore",
        targetSites = setOf("reddit.com"),
        trigger = Trigger(TriggerType.TIMER, 0, TriggerUnit.MS),
        mode = BlockMode.SCHEDULE,
        enforcementTier = EnforcementTier.HARDCORE,
        lock = BlockLock(untilWall = TestClock.nowWallMs() + 86_400_000L, setAtWall = TestClock.nowWallMs()),
    )

    /** A laptop that synced [locked]: its world, the base it agreed on with the server, and the server's copy. */
    private fun synced(): Triple<File, File, SharedState> {
        val dir = Files.createTempDirectory("xrnrule-persist").toFile()
        val world = World(blocks = listOf(locked), deviceId = "laptop-1")
        val server = SharedState.of(world)
        val worldFile = File(dir, "world.json")
        FilePersist(worldFile, TestClock).apply { save(world) }
        val base = File(dir, SharedSyncClient.BASE_FILE).apply { writeText("""{"etag": "\"7\"", "state": ${json.encodeToString(SharedState.serializer(), server)}}""") }
        return Triple(worldFile, base, server)
    }

    /** The first round after starting with [world]: the server's copy, merged against the base if one is left. */
    private fun firstRound(world: World, base: File, server: SharedState): SharedState =
        SharedState.merge(if (base.isFile) server else null, SharedState.of(world), server)

    @Test
    fun aWorldThatCantBeReadDropsTheBase_soTheServersBlocksComeBack() {
        val (worldFile, base, server) = synced()
        worldFile.writeText("{ not json")
        val world = FilePersist(worldFile, TestClock, base).loadWorld()
        assertEquals(emptyList<Block>(), world.blocks)
        assertFalse("the base belonged to the world that's gone", base.exists())
        // Merged without a base, nothing here reads as deleted: the locked block stays everywhere.
        assertEquals(setOf("b1"), firstRound(world, base, server).blocks.keys)
    }

    @Test
    fun aWorldThatsGoneDropsTheBase() {
        val (worldFile, base, server) = synced()
        assertTrue(worldFile.delete())
        val world = FilePersist(worldFile, TestClock, base).loadWorld()
        assertFalse(base.exists())
        assertEquals(setOf("b1"), firstRound(world, base, server).blocks.keys)
    }

    @Test
    fun aWorldReadBackKeepsTheBase() {
        val (worldFile, base, _) = synced()
        val world = FilePersist(worldFile, TestClock, base).loadWorld()
        assertEquals(listOf("b1"), world.blocks.map { it.id })
        assertTrue(base.exists())
    }
}
