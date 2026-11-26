package com.xern.xrnrule.app.data

import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockEngine
import com.xern.xrnrule.domain.BlockEvent
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

class PersistenceTest {

    private val now = 1_800_000_000_000L
    private val locked = Block(
        id = "b1",
        name = "Hardcore",
        targetSites = setOf("reddit.com"),
        trigger = Trigger(TriggerType.TIMER, 0, TriggerUnit.MS),
        mode = BlockMode.SCHEDULE,
        enforcementTier = EnforcementTier.HARDCORE,
        lock = BlockLock(untilWall = now + 86_400_000L, setAtWall = now),
    )

    private class Synced(val dir: File, val server: SharedState) {
        val world = File(dir, "world.json")
        val base = File(dir, SharedSync.BASE_FILE)
        fun persist() = FilePersistPort(world, sharedBase = base)
    }

    /** A phone that synced [locked]: its world, and the base it agreed on with the server (the server's copy). */
    private fun synced(): Synced {
        val world = World(blocks = listOf(locked), deviceId = "phone-1")
        val s = Synced(Files.createTempDirectory("xrnrule-persist").toFile(), SharedState.of(world))
        s.persist().save(world)
        s.base.writeText("""{"etag": "\"7\"", "state": {}}""")
        return s
    }

    /** The first round after starting with [world]: the server's copy, merged against the base if one is left. */
    private fun firstRound(s: Synced, world: World): SharedState =
        SharedState.merge(if (s.base.exists()) s.server else null, SharedState.of(world), s.server, now)

    @Test
    fun aWorldThatCantBeReadDropsTheBase_soTheServersBlocksComeBack() {
        val s = synced()
        s.world.writeText("{ not json")
        val world = s.persist().loadWorld()
        assertEquals(emptyList<Block>(), world.blocks)
        assertFalse("the base belonged to the world that's gone", s.base.exists())
        // Merged without a base, nothing here reads as deleted: the locked block stays everywhere.
        assertEquals(setOf("b1"), firstRound(s, world).blocks.keys)
    }

    @Test
    fun aWorldThatsGoneDropsTheBase() {
        val s = synced()
        assertTrue(s.world.delete())
        val world = s.persist().loadWorld()
        assertFalse(s.base.exists())
        assertEquals(setOf("b1"), firstRound(s, world).blocks.keys)
    }

    @Test
    fun aLockedBlockLoosenedInTheServersCopyStaysLockedHere() {
        // The server's copy, PUT with curl and the sync password: the lock ended and the site gone.
        val s = synced()
        val world = s.persist().loadWorld()
        val text = SharedState.json.encodeToString(
            SharedState.serializer(),
            s.server.copy(blocks = s.server.blocks.mapValues { (_, b) -> b.copy(lock = BlockLock(0, now + 1), targetSites = emptySet(), editedAtWall = now + 1) }),
        )
        val clock = object : Clock {
            override fun nowMonotonicMs() = 1_000_000L
            override fun nowWallMs() = now + 60_000
            override fun zone(): ZoneId = ZoneId.of("UTC")
        }
        // A round as SharedSync runs it: the copy read against the base, merged and taken in by the engine.
        val remote = SharedState.fromServer(text, s.server, SharedState.of(world))
        val after = BlockEngine().reduce(world, BlockEvent.SharedStateMerged(s.server, remote), clock).world
        assertEquals(locked.lock, after.block("b1")!!.lock)
        assertEquals(setOf("reddit.com"), after.block("b1")!!.targetSites)
        assertEquals("and that is what goes back to the server", locked.lock, SharedState.of(after).blocks.getValue("b1").lock)
    }

    @Test
    fun aWorldReadBackKeepsTheBase() {
        val s = synced()
        assertEquals(listOf("b1"), s.persist().loadWorld().blocks.map { it.id })
        assertTrue(s.base.exists())
    }
}
