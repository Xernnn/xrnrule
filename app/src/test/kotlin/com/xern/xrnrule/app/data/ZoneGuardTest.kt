package com.xern.xrnrule.app.data

import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockEngine
import com.xern.xrnrule.domain.BlockEvent
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.Clock
import com.xern.xrnrule.domain.Trigger
import com.xern.xrnrule.domain.TriggerType
import com.xern.xrnrule.domain.TriggerUnit
import com.xern.xrnrule.domain.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ZoneGuardTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val brisbane = ZoneId.of("Australia/Brisbane")

    /** 15:00 in Berlin on 15 January is midnight in Brisbane, the next day. */
    private val threePm = Instant.parse("2026-01-15T14:00:00Z").toEpochMilli()

    private val engine = BlockEngine()
    private val limit = Block(
        "a1", "Limit", targetPackages = setOf("com.target"), trigger = Trigger(TriggerType.TIMER, 0, TriggerUnit.MS),
        mode = BlockMode.ALLOWANCE, dailyAllowanceMs = 30L * 60_000,
    )

    /** The phone: its clock, the zone it's set to ([system]), and what the engine is told ([zone]). */
    private class Phone(val guard: ZoneGuard?, var system: ZoneId, var wall: Long) : Clock {
        var mono = 9_000_000L
        override fun nowMonotonicMs() = mono
        override fun nowWallMs() = wall
        override fun zone(): ZoneId = guard?.zone(system) ?: system

        fun pick(zone: ZoneId, automatic: Boolean) {
            system = zone
            guard?.check(zone, automatic)
        }

        fun advance(ms: Long) {
            wall += ms
            mono += ms
        }
    }

    private fun phone(guard: ZoneGuard? = ZoneGuard(null) { _, _ -> }) = Phone(guard, berlin, threePm - 30L * 60_000).also { it.guard?.check(berlin, true) }

    /** Today's 30 minutes, used up by 15:00. */
    private fun usedUp(phone: Phone): World {
        var world = engine.reduce(World(blocks = listOf(limit), deviceId = "phone"), BlockEvent.BootOrColdStart, phone).world
        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.target"), phone).world
        phone.advance(30L * 60_000)
        world = engine.reduce(world, BlockEvent.Tick, phone).world
        world = engine.reduce(world, BlockEvent.ForegroundChanged(null), phone).world
        assertTrue(engine.isBlocking(world, world.block("a1")!!, phone))
        return world
    }

    private fun Phone.tick(world: World) = engine.reduce(world, BlockEvent.Tick, this).world

    @Test
    fun aZonePickedByHandStartsNoNewDay() {
        // How it went with the zone passed through: Brisbane by hand, a new day, the limit gone,
        // and gone still once back in Berlin.
        val unguarded = phone(guard = null)
        var world = usedUp(unguarded)
        unguarded.pick(brisbane, automatic = false)
        world = unguarded.tick(world)
        unguarded.pick(berlin, automatic = false)
        world = unguarded.tick(world)
        assertFalse(engine.isBlocking(world, world.block("a1")!!, unguarded))

        val phone = phone()
        world = usedUp(phone)
        phone.pick(brisbane, automatic = false)
        world = phone.tick(world)
        assertTrue("still today's limit", engine.isBlocking(world, world.block("a1")!!, phone))
        assertEquals(berlin, phone.zone())
        phone.pick(berlin, automatic = false)
        world = phone.tick(world)
        assertTrue(engine.isBlocking(world, world.block("a1")!!, phone))
    }

    @Test
    fun aZonePickedByHandGivesNoSecondBreak() {
        val phone = phone()
        var world = engine.reduce(World(deviceId = "phone"), BlockEvent.StartPause(), phone).world
        world = engine.reduce(world, BlockEvent.EndPause, phone).world
        assertFalse(engine.canPause(world, phone))
        phone.pick(brisbane, automatic = false)
        assertFalse("tomorrow in Brisbane, but not here", engine.canPause(world, phone))
    }

    @Test
    fun aZoneThePhoneSetsItselfIsTaken() {
        val phone = phone()
        var world = usedUp(phone)
        // Flown to Brisbane, the zone set from the network: it is the next day there.
        phone.pick(brisbane, automatic = true)
        world = phone.tick(world)
        assertEquals(brisbane, phone.zone())
        assertFalse(engine.isBlocking(world, world.block("a1")!!, phone))
    }

    @Test
    fun theZoneKeptOutlivesARestart() {
        val saved = mutableListOf<String>()
        ZoneGuard(null) { zone, _ -> saved += zone }.check(berlin, automatic = true)
        assertEquals(listOf("Europe/Berlin"), saved)
        val again = ZoneGuard(saved.last(), savedSetItself = true) { zone, _ -> saved += zone }
        again.check(brisbane, automatic = false)
        assertEquals(berlin, again.zone(brisbane))
        assertEquals("nothing new to keep", 1, saved.size)
        // Nothing kept, or nothing readable: the zone the phone has when first asked.
        val fresh = ZoneGuard("Not/AZone") { zone, _ -> saved += zone }
        fresh.check(brisbane, automatic = false)
        assertEquals(brisbane, fresh.zone(berlin))
    }

    @Test
    fun onlyAZoneThePhoneSetItselfIsShared() {
        // First asked with a zone picked by hand: kept, but not shared (I54), as it may be wrong.
        val guard = ZoneGuard(null) { _, _ -> }
        guard.check(brisbane, automatic = false)
        assertEquals(brisbane, guard.zone(brisbane))
        assertNull(guard.ownZone)
        // Then the phone sets it itself: that one is.
        guard.check(berlin, automatic = true)
        assertEquals(berlin, guard.ownZone)
        // A zone picked by hand afterwards changes neither.
        guard.check(brisbane, automatic = false)
        assertEquals(berlin, guard.zone(brisbane))
        assertEquals(berlin, guard.ownZone)
        // An install from before kept its zone without saying how: shared once the phone sets it itself.
        val older = ZoneGuard("Europe/Berlin") { _, _ -> }
        assertNull(older.ownZone)
        older.check(berlin, automatic = true)
        assertEquals(berlin, older.ownZone)
    }
}
