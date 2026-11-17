package com.xern.xrnrule.desktop

import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockEdits
import com.xern.xrnrule.domain.BlockEngine
import com.xern.xrnrule.domain.BlockEvent
import com.xern.xrnrule.domain.BlockLock
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.Clock
import com.xern.xrnrule.domain.Trigger
import com.xern.xrnrule.domain.TriggerType
import com.xern.xrnrule.domain.TriggerUnit
import com.xern.xrnrule.domain.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId

class ClockGuardTest {
    private val day = 24 * 3_600_000L
    private val berlin = ZoneId.of("Europe/Berlin")
    private val brisbane = ZoneId.of("Australia/Brisbane")

    /** 15:00 in Berlin on 15 January is midnight in Brisbane, the next day. */
    private val threePm = Instant.parse("2026-01-15T14:00:00Z").toEpochMilli()

    /** The laptop as Linux has it: the clock anyone with sudo can set, and the zone set by hand. */
    private class Laptop(var wall: Long, var mono: Long = 5_000_000L, var system: ZoneId) : Clock {
        override fun nowMonotonicMs() = mono
        override fun nowWallMs() = wall
        override fun zone(): ZoneId = system

        fun pass(ms: Long) {
            wall += ms
            mono += ms
        }
    }

    private val file: File = File(Files.createTempDirectory("xrnrule-clock").toFile(), "clock.json")
    private val laptop = Laptop(wall = threePm - 30L * 60_000, system = berlin)

    /** Whether NTP keeps the laptop's clock: setting it by hand clears this until NTP steps it back. */
    private var ntp = true

    private fun guard() = ClockGuard(laptop, file, synced = { ntp }, systemZone = { laptop.system })

    /** `sudo date -s` [by] ahead (or back): the kernel no longer counts the clock as NTP's. */
    private fun setByHand(by: Long) {
        ntp = false
        laptop.wall += by
    }

    private val engine = BlockEngine()
    private val limit = Block(
        "a1", "Limit", targetDesktopApps = setOf("steam"), trigger = Trigger(TriggerType.TIMER, 0, TriggerUnit.MS),
        mode = BlockMode.ALLOWANCE, dailyAllowanceMs = 30L * 60_000,
    )

    /** Today's 30 minutes of Steam, used up by 15:00 by [clock]. */
    private fun usedUp(clock: Clock): World {
        var world = engine.reduce(World(blocks = listOf(limit), deviceId = "laptop"), BlockEvent.BootOrColdStart, clock).world
        world = engine.reduce(world, BlockEvent.ForegroundChanged("steam"), clock).world
        laptop.pass(30L * 60_000)
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        world = engine.reduce(world, BlockEvent.ForegroundChanged(null), clock).world
        assertTrue(engine.isBlocking(world, world.block("a1")!!, clock))
        return world
    }

    private fun Clock.blocks(world: World) = engine.reduce(world, BlockEvent.Tick, this).world.let { engine.isBlocking(it, it.block("a1")!!, this) }

    @Test
    fun aClockSetAheadByHandStartsNoNewDay() {
        // How it went with the system clock as it is: a day ahead, a new day, the limit gone.
        val world = usedUp(laptop)
        setByHand(day)
        assertFalse(laptop.blocks(world))
        laptop.wall -= day
        ntp = true

        val clock = guard()
        val guarded = usedUp(clock)
        setByHand(day)
        assertTrue("still today's limit", clock.blocks(guarded))
        assertEquals(day, clock.skewMs)
        assertNotNull(clock.note())
    }

    @Test
    fun aWeekAheadByHandEndsNoLock() {
        val clock = guard()
        val block = limit.copy(lock = BlockLock(untilWall = clock.nowWallMs() + 7 * day, setAtWall = clock.nowWallMs()))
        setByHand(8 * day)
        assertFalse("the laptop's own clock says it's over", BlockEdits.locked(block, laptop.nowWallMs()))
        assertTrue(BlockEdits.locked(block, clock.nowWallMs()))
    }

    @Test
    fun theClockIsTrustedAgainOnceNtpKeepsIt() {
        val clock = guard()
        val before = clock.nowWallMs()
        setByHand(day)
        assertEquals(before, clock.nowWallMs())
        // timesyncd steps it back and keeps it again: the step is NTP's, and the skew goes.
        ntp = true
        laptop.wall -= day
        assertEquals(before, clock.nowWallMs())
        assertEquals(0L, clock.skewMs)

        // Set ahead with NTP off, then NTP turned on; checked before NTP has stepped it back.
        setByHand(2 * day)
        assertEquals(before, clock.nowWallMs())
        ntp = true
        laptop.wall -= 2 * day
        clock.check()
        assertEquals(0L, clock.skewMs)
        assertNull(clock.note())
    }

    @Test
    fun aJumpWhileTheDaemonWasStoppedIsCaughtAtItsStart() {
        val before = guard().nowWallMs()
        // systemctl --user stop xrnrule-desktop; sudo date -s +2days; start it again.
        setByHand(2 * day)
        laptop.pass(60_000)
        assertEquals(before + 60_000, guard().nowWallMs())
    }

    @Test
    fun aRebootKeepsTheSkewAndStartsAFreshBaseline() {
        guard().also { setByHand(day) }.nowWallMs()
        // Rebooted: the boot clock starts again, the wall clock keeps its day ahead.
        laptop.mono = 20_000L
        laptop.pass(60_000)
        val clock = guard()
        assertEquals(laptop.wall - day, clock.nowWallMs())
        laptop.pass(60_000)
        assertEquals("no jump since", laptop.wall - day, clock.nowWallMs())
    }

    @Test
    fun aRebootWithTheHardwareClockRightDoesntGoBackADay() {
        val clock = guard()
        setByHand(day)
        val seen = clock.nowWallMs()
        // Rebooted: `date -s` never touched the hardware clock, so the laptop comes back at the right time.
        laptop.wall -= day
        laptop.mono = 20_000L
        laptop.pass(60_000)
        val again = guard()
        assertTrue("not back in yesterday", again.nowWallMs() >= seen)
        // Once NTP keeps the clock, it's the time again.
        ntp = true
        again.check()
        assertEquals(laptop.wall, again.nowWallMs())
    }

    @Test
    fun aClockSetAheadAcrossARebootIsTakenBackByTheSyncServersTime() {
        guard()
        // systemctl --user stop xrnrule-desktop; timedatectl set-ntp false; the date and the
        // hardware clock a day ahead; reboot. The boot clock starts again: nothing to compare
        // against, and the day ahead was a new day of limits, a week ahead a lock run out.
        setByHand(day)
        laptop.mono = 20_000L
        laptop.pass(60_000)
        val clock = guard()
        assertEquals("nothing can tell yet", laptop.wall, clock.nowWallMs())

        // The first answer from the sync server: sent in the second it says, 300 ms before now.
        val real = laptop.wall - day
        clock.serverTime((real - 300) / 1000 * 1000, tookMs = 300)
        assertTrue(clock.nowWallMs() in real..real + 1_300)
        assertTrue(clock.skewMs in day - 1_300..day)
        assertNotNull(clock.note())

        // Kept across a restart, and across the next reboot too.
        assertTrue(guard().nowWallMs() in real..real + 1_300)
        laptop.mono = 10_000L
        laptop.pass(30_000)
        assertTrue(guard().nowWallMs() in real + 30_000..real + 31_300)
    }

    @Test
    fun theSyncServersTimeNeverTakesTheClockForward() {
        val clock = guard()
        ntp = false
        val before = clock.nowWallMs()
        // An answer saying it's a day later: whatever sent it, it ends no lock and starts no day.
        clock.serverTime(laptop.wall + day, tookMs = 0)
        assertEquals(before, clock.nowWallMs())
        // Within its seconds and a slow answer, it's the same time.
        clock.serverTime(laptop.wall - 90_000, tookMs = 20_000)
        assertEquals(0L, clock.skewMs)
        // NTP keeps the clock: that is the time.
        ntp = true
        clock.serverTime(laptop.wall - day, tookMs = 0)
        assertEquals(0L, clock.skewMs)
    }

    @Test
    fun theSyncServersTimeCountsOnlyOverHttpsFromAnotherMachine() {
        val date = "Tue, 17 Nov 2026 10:00:00 GMT"
        val at = Instant.parse("2026-11-17T10:00:00Z").toEpochMilli()
        assertEquals(at, Http.serverDate(URI("https://home.example.ts.net/xrnrule/state"), date))
        assertNull("anything on the way can write it", Http.serverDate(URI("http://home.example.ts.net/xrnrule/state"), date))
        for (local in listOf("https://localhost:8443/state", "https://127.0.0.1:8443/state", "https://[::1]:8443/state")) {
            assertNull("any program here can answer", Http.serverDate(URI(local), date))
        }
        assertNull(Http.serverDate(URI("https://home.example.ts.net/state"), null))
        assertNull(Http.serverDate(URI("https://home.example.ts.net/state"), "yesterday"))
    }

    @Test
    fun aZonePickedByHandStartsNoNewDay() {
        // How it went with the system zone as it is: Brisbane picked at 15:00, a new day, the limit gone.
        val world = usedUp(laptop)
        laptop.system = brisbane
        assertFalse(laptop.blocks(world))
        laptop.system = berlin
        laptop.wall -= 30L * 60_000
        laptop.mono -= 30L * 60_000

        val clock = guard()
        val guarded = usedUp(clock)
        laptop.system = brisbane // sudo timedatectl set-timezone Australia/Brisbane
        assertTrue("still today's limit", clock.blocks(guarded))
        assertEquals(berlin, clock.zone())
        assertEquals(brisbane, clock.handPickedZone)
        assertTrue(clock.note()!!.contains("Australia/Brisbane"))
        assertEquals("still so after a restart", berlin, guard().zone())
    }

    @Test
    fun theLaptopGoesByTheZoneThePhoneSetItself() {
        val clock = guard()
        val world = usedUp(clock)
        // Flown to Brisbane: the phone took its zone from the network, and the laptop takes the phone's.
        var phone: ZoneId? = brisbane
        clock.sharedZone = { phone }
        assertEquals(brisbane, clock.zone())
        assertFalse("the next day in Brisbane", clock.blocks(world))
        // Kept should the phone's stop arriving.
        clock.check()
        phone = null
        assertEquals(brisbane, clock.zone())
        assertEquals(brisbane, guard().zone())
    }
}
