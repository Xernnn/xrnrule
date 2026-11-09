package com.xern.xrnrule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * One test per invariant in `docs/domain-contract.md` §3. If you change an invariant, change
 * the contract first, then this file, then the engine.
 */
class InvariantTests {

    private val engine = BlockEngine()

    private fun earn(world: World, clock: Clock, itemId: String = "c1-i0"): World =
        engine.reduce(world, BlockEvent.ToggleChecklistItem(itemId, true), clock).world

    @Test
    fun i01_creditExpiresAfterTtlOnWallClock() {
        val clock = FakeClock()
        val world = buildWorld(listOf(checklistBlock("c1")), mapOf("c1" to items("c1", 1)), clock)

        val earned = earn(world, clock)
        assertEquals(BlockState.EARNED, earned.block("c1")!!.state)

        clock.advance(Defaults.CREDIT_TTL_MS + 1)
        val out = engine.reduce(earned, BlockEvent.Tick, clock)

        assertEquals(BlockState.ARMED, out.world.block("c1")!!.state)
        assertTrue(out.world.credits.isEmpty())
    }

    @Test
    fun i02_unlockCountdownUsesMonotonicNotWall() {
        val clock = FakeClock()
        val world = buildWorld(listOf(checklistBlock("c1")), mapOf("c1" to items("c1", 1)), clock)
        var out = engine.reduce(earn(world, clock), BlockEvent.Redeem("c1"), clock)
        assertEquals(BlockState.UNLOCKED, out.world.block("c1")!!.state)

        // Wall jumps 10h forward but monotonic time did not advance: session must not end.
        clock.advanceWallOnly(10L * 60 * 60 * 1000)
        out = engine.reduce(out.world, BlockEvent.Tick, clock)

        assertEquals(BlockState.UNLOCKED, out.world.block("c1")!!.state)
    }

    @Test
    fun i03_windingClockBackDuringUnlockEndsSession() {
        val clock = FakeClock()
        val world = buildWorld(listOf(checklistBlock("c1")), mapOf("c1" to items("c1", 1)), clock)
        var out = engine.reduce(earn(world, clock), BlockEvent.Redeem("c1"), clock)

        clock.advanceMonoOnly(60_000)          // one real minute passed
        clock.advanceWallOnly(-60L * 60 * 1000) // user set the clock back an hour
        out = engine.reduce(out.world, BlockEvent.Tick, clock)

        assertEquals(BlockState.ARMED, out.world.block("c1")!!.state)
        assertTrue(out.effects.any { it is Effect.TamperDetected })
    }

    @Test
    fun i04_readingTimerPausesWhileTargetIsNotForeground() {
        val clock = FakeClock()
        val block = timerBlock("t1", target = "com.target", requiredMs = Long.MAX_VALUE / 4)
        var world = buildWorld(listOf(block), clock = clock, foregroundPkg = "com.target")

        world = engine.reduce(world, BlockEvent.TimerControl("t1", running = true), clock).world

        clock.advance(10_000) // 10s in target
        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.other"), clock).world
        clock.advance(10_000) // 10s away
        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.target"), clock).world
        clock.advance(10_000) // 10s back in target
        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.other"), clock).world

        assertEquals(20_000L, world.progress["t1"]!!.accumulatedMs)
    }

    @Test
    fun i05_shieldOnUnlessUnlocked() {
        val clock = FakeClock()
        val world = buildWorld(listOf(checklistBlock("c1")), mapOf("c1" to items("c1", 1)), clock)

        assertTrue(engine.shieldOn(world, "c1", clock))
        assertTrue(engine.blockedPackages(world, clock).contains("com.target"))

        val earned = earn(world, clock)
        assertTrue(engine.shieldOn(earned, "c1", clock))

        val unlocked = engine.reduce(earned, BlockEvent.Redeem("c1"), clock).world
        assertFalse(engine.shieldOn(unlocked, "c1", clock))
        assertFalse(engine.blockedPackages(unlocked, clock).contains("com.target"))
    }

    @Test
    fun i05_shieldStaysOnWhileTimerRuns() {
        val clock = FakeClock()
        var world = buildWorld(listOf(timerBlock("t1")), clock = clock, foregroundPkg = "com.target")
        world = engine.reduce(world, BlockEvent.TimerControl("t1", running = true), clock).world

        clock.advance(5_000)
        val out = engine.reduce(world, BlockEvent.Tick, clock)

        assertTrue(engine.blockedPackages(out.world, clock).contains("com.target"))
        assertNull("no shield change while merely running a timer", out.shieldPlan)
    }

    @Test
    fun i06_appUnlockedIfAnyCoveringBlockUnlocked() {
        val clock = FakeClock()
        val b1 = checklistBlock("c1", target = "com.x")
        val b2 = checklistBlock("c2", target = "com.x")
        val world = buildWorld(
            listOf(b1, b2),
            mapOf("c1" to items("c1", 1), "c2" to items("c2", 1)),
            clock,
        )

        var out = engine.reduce(earn(world, clock, "c2-i0"), BlockEvent.Redeem("c2"), clock)

        assertEquals(BlockState.UNLOCKED, out.world.block("c2")!!.state)
        assertEquals(BlockState.ARMED, out.world.block("c1")!!.state)
        assertTrue(engine.isAppUnlocked(out.world, "com.x"))
        assertFalse(engine.blockedPackages(out.world, clock).contains("com.x"))
    }

    @Test
    fun i07_persistHappensBeforeShield() {
        val clock = FakeClock()
        val world = buildWorld(listOf(checklistBlock("c1")), mapOf("c1" to items("c1", 1)), clock)
        val earned = earn(world, clock)
        val outcome = engine.reduce(earned, BlockEvent.Redeem("c1"), clock)
        assertNotNull("redeem must change the shield", outcome.shieldPlan)

        val log = mutableListOf<String>()
        applyOutcome(
            outcome,
            RecordingPersist(earned, log),
            RecordingShield(log),
            NoopTelemetry,
            clock.nowWallMs(),
        )

        assertEquals(listOf("persist", "shield"), log)
    }

    @Test
    fun i08_coldStartRebuildsExpiredCreditAndReappliesShield() {
        val clock = FakeClock()
        val world = buildWorld(listOf(checklistBlock("c1")), mapOf("c1" to items("c1", 1)), clock)
        val earned = earn(world, clock)

        // Persist, then "reboot": wall advanced past the TTL, monotonic clock reset.
        clock.advanceWallOnly(Defaults.CREDIT_TTL_MS + 60_000)
        clock.setMono(1234)
        val out = engine.reduce(earned, BlockEvent.BootOrColdStart, clock)

        assertEquals(BlockState.ARMED, out.world.block("c1")!!.state)
        assertNotNull("boot always re-applies the shield", out.shieldPlan)
    }

    @Test
    fun i08_coldStartKeepsStillValidCredit() {
        val clock = FakeClock()
        val world = buildWorld(listOf(checklistBlock("c1")), mapOf("c1" to items("c1", 1)), clock)
        val earned = earn(world, clock)

        clock.advanceWallOnly(60_000) // one minute later
        clock.setMono(50)
        val out = engine.reduce(earned, BlockEvent.BootOrColdStart, clock)

        assertEquals(BlockState.EARNED, out.world.block("c1")!!.state)
    }

    @Test
    fun i09_redeemRejectedWhenDailyCapReached() {
        val clock = FakeClock()
        val block = checklistBlock("c1", unlockMs = 5L * 60_000)
        val world = buildWorld(listOf(block), mapOf("c1" to items("c1", 1)), clock)
            .copy(dailyUnlockUsedMs = 58L * 60_000, dailyUsageEpochDay = clock.epochDay())

        val out = engine.reduce(earn(world, clock), BlockEvent.Redeem("c1"), clock)

        assertEquals(BlockState.EARNED, out.world.block("c1")!!.state)
        assertTrue(out.effects.any { it is Effect.RedeemRejected && it.reason == RejectReason.CAP_REACHED })
        assertEquals("the UI can say why before the tap", RejectReason.CAP_REACHED, engine.redeemRejection(earn(world, clock), "c1", clock))
    }

    @Test
    fun i10_returnToArmedResetsProgressAndChecklist() {
        val clock = FakeClock()
        val block = checklistBlock("c1", required = Defaults.ALL_ITEMS)
        var world = buildWorld(listOf(block), mapOf("c1" to items("c1", 3)), clock)

        world = engine.reduce(world, BlockEvent.ToggleChecklistItem("c1-i0", true), clock).world
        world = engine.reduce(world, BlockEvent.ToggleChecklistItem("c1-i1", true), clock).world
        world = engine.reduce(world, BlockEvent.ToggleChecklistItem("c1-i2", true), clock).world
        assertEquals(BlockState.EARNED, world.block("c1")!!.state)
        assertEquals(3, world.items("c1").count { it.done })

        clock.advance(Defaults.CREDIT_TTL_MS + 1)
        val out = engine.reduce(world, BlockEvent.Tick, clock)

        assertEquals(BlockState.ARMED, out.world.block("c1")!!.state)
        assertEquals(0, out.world.items("c1").count { it.done })
        assertEquals(0L, out.world.progress["c1"]!!.accumulatedMs)
    }

    @Test
    fun i11_checklistThreshold() {
        val clock = FakeClock()
        data class Case(val size: Int, val required: Long, val done: Int, val expected: Boolean)
        val cases = listOf(
            Case(3, Defaults.ALL_ITEMS, 3, true),
            Case(3, Defaults.ALL_ITEMS, 2, false),
            Case(3, 2, 2, true),
            Case(3, 2, 1, false),
            Case(0, Defaults.ALL_ITEMS, 0, false),
            Case(0, 5, 0, false),
        )
        for (c in cases) {
            val block = checklistBlock("c1", required = c.required)
            val checklist = if (c.size > 0) mapOf("c1" to items("c1", c.size, done = c.done)) else emptyMap()
            val world = buildWorld(listOf(block), checklist, clock)
            assertEquals(
                "size=${c.size} required=${c.required} done=${c.done}",
                c.expected,
                engine.triggerMet(world, world.block("c1")!!),
            )
        }
    }

    @Test
    fun i12_toggleIsNoOpUnlessArmed() {
        val clock = FakeClock()
        val world = buildWorld(listOf(checklistBlock("c1")), mapOf("c1" to items("c1", 1)), clock)
        val earned = earn(world, clock)
        assertEquals(BlockState.EARNED, earned.block("c1")!!.state)

        val out = engine.reduce(earned, BlockEvent.ToggleChecklistItem("c1-i0", done = false), clock)

        assertEquals(BlockState.EARNED, out.world.block("c1")!!.state)
        assertTrue("untick on a non-ARMED block is ignored", out.world.items("c1").single().done)
    }

    @Test
    fun i13_dayRolloverResetsArmedProgress() {
        val zone = ZoneId.of("UTC")
        val clock = FakeClock(
            mono = 0,
            wall = Instant.parse("2026-01-15T00:01:00Z").toEpochMilli(),
            zoneId = zone,
        )
        val block = timerBlock("t1")
        val yesterday = Instant.parse("2026-01-14T23:59:00Z").toEpochMilli()
        val world = World(
            blocks = listOf(block),
            progress = mapOf(
                "t1" to HabitProgress("t1", accumulatedMs = 123_456, windowStart = yesterday, lastUpdated = 0),
            ),
            dailyUsageEpochDay = clock.epochDay(),
        )

        val out = engine.reduce(world, BlockEvent.Tick, clock)

        assertEquals(BlockState.ARMED, out.world.block("t1")!!.state)
        assertEquals(0L, out.world.progress["t1"]!!.accumulatedMs)
        assertEquals(
            Instant.parse("2026-01-15T00:00:00Z").toEpochMilli(),
            out.world.progress["t1"]!!.windowStart,
        )
    }

    @Test
    fun i14_checklistTriggerWithNoItemsIsNeverMet() {
        val clock = FakeClock()
        val world = buildWorld(listOf(checklistBlock("c1", required = Defaults.ALL_ITEMS)), emptyMap(), clock)

        assertFalse(engine.triggerMet(world, world.block("c1")!!))

        val strayToggle = engine.reduce(world, BlockEvent.ToggleChecklistItem("ghost", true), clock)
        assertEquals(BlockState.ARMED, strayToggle.world.block("c1")!!.state)

        val tick = engine.reduce(strayToggle.world, BlockEvent.Tick, clock)
        assertEquals(BlockState.ARMED, tick.world.block("c1")!!.state)
    }

    @Test
    fun i15_combinedAndRequiresBoth() {
        val clock = FakeClock()
        val block = combinedBlock("x1", requiredMs = 10_000, combineMode = CombineMode.AND)
        var world = buildWorld(listOf(block), mapOf("x1" to items("x1", 2)), clock, foregroundPkg = "com.target")
        world = engine.reduce(world, BlockEvent.TimerControl("x1", running = true), clock).world

        // Checklist finished alone: not enough.
        world = engine.reduce(world, BlockEvent.ToggleChecklistItem("x1-i0", true), clock).world
        world = engine.reduce(world, BlockEvent.ToggleChecklistItem("x1-i1", true), clock).world
        assertEquals(BlockState.ARMED, world.block("x1")!!.state)

        // Timer alone (checklist already done above, but still short of its own threshold too):
        // advance only the timer and confirm it is *also* insufficient on its own by checking
        // mid-way, then finish it — order of completion must not matter (I15).
        clock.advance(5_000)
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        assertEquals(BlockState.ARMED, world.block("x1")!!.state)

        clock.advance(5_000)
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        assertEquals(BlockState.EARNED, world.block("x1")!!.state)
    }

    @Test
    fun i15_combinedOrRequiresEither() {
        val clock = FakeClock()
        val block = combinedBlock("x1", requiredMs = 10_000, combineMode = CombineMode.OR)
        val world = buildWorld(listOf(block), mapOf("x1" to items("x1", 2)), clock, foregroundPkg = "com.target")

        // Checklist alone earns it — timer never started.
        var out = engine.reduce(world, BlockEvent.ToggleChecklistItem("x1-i0", true), clock).world
        out = engine.reduce(out, BlockEvent.ToggleChecklistItem("x1-i1", true), clock).world
        assertEquals(BlockState.EARNED, out.block("x1")!!.state)

        // Independently: timer alone earns it — checklist never touched.
        var timerOnly = engine.reduce(world, BlockEvent.TimerControl("x1", running = true), clock).world
        clock.advance(10_000)
        timerOnly = engine.reduce(timerOnly, BlockEvent.Tick, clock).world
        assertEquals(BlockState.EARNED, timerOnly.block("x1")!!.state)
        assertEquals(0, timerOnly.items("x1").count { it.done })
    }

    @Test
    fun i16_habitPackagesOverridesTargetForAccrual() {
        val clock = FakeClock()
        val block = timerBlock("t1", target = "com.target", requiredMs = Long.MAX_VALUE / 4, habitPackages = setOf("com.habit"))
        var world = buildWorld(listOf(block), clock = clock, foregroundPkg = "com.target")
        world = engine.reduce(world, BlockEvent.TimerControl("t1", running = true), clock).world

        clock.advance(10_000) // sitting in the blocked target app — must NOT count
        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.habit"), clock).world
        assertEquals(0L, world.progress["t1"]!!.accumulatedMs)

        clock.advance(10_000) // now in the designated habit app — counts
        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.other"), clock).world
        assertEquals(10_000L, world.progress["t1"]!!.accumulatedMs)
    }

    @Test
    fun i16_emptyHabitPackagesFallsBackToTarget() {
        val clock = FakeClock()
        val block = timerBlock("t1", target = "com.target", requiredMs = Long.MAX_VALUE / 4, habitPackages = emptySet())
        var world = buildWorld(listOf(block), clock = clock, foregroundPkg = "com.target")
        world = engine.reduce(world, BlockEvent.TimerControl("t1", running = true), clock).world

        clock.advance(10_000)
        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.other"), clock).world

        assertEquals(10_000L, world.progress["t1"]!!.accumulatedMs)
    }

    @Test
    fun i17_multiTargetAccrualCountsAnyTarget() {
        val clock = FakeClock()
        val block = Block(
            id = "t1",
            name = "Multi",
            targetPackages = setOf("com.a", "com.b"),
            trigger = Trigger(TriggerType.TIMER, Long.MAX_VALUE / 4, TriggerUnit.MS),
        )
        var world = buildWorld(listOf(block), clock = clock, foregroundPkg = "com.a")
        world = engine.reduce(world, BlockEvent.TimerControl("t1", running = true), clock).world

        assertTrue(engine.blockedPackages(world, clock).containsAll(setOf("com.a", "com.b")))

        clock.advance(10_000) // in com.a
        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.b"), clock).world
        clock.advance(10_000) // in com.b
        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.other"), clock).world

        assertEquals(20_000L, world.progress["t1"]!!.accumulatedMs)
    }

    @Test
    fun i18_siteTargetBlocksAndMatchesSubdomains() {
        val clock = FakeClock()
        val block = Block(
            id = "s1",
            name = "Sites",
            targetSites = setOf("youtube.com"),
            trigger = Trigger(TriggerType.TIMER, 30L * 60_000, TriggerUnit.MS),
        )
        val world = buildWorld(listOf(block), clock = clock)

        val plan = engine.shieldPlan(world, clock)
        assertEquals(setOf("youtube.com"), plan.blockedSites)
        assertTrue(plan.covers("com.browser", "youtube.com"))
        assertTrue(plan.covers("com.browser", "m.youtube.com"))
        assertFalse(plan.covers("com.browser", "notyoutube.com"))
        assertFalse(plan.covers("com.browser", null))
    }

    @Test
    fun i18_timerAccruesOnTargetSite() {
        val clock = FakeClock()
        val block = Block(
            id = "s1",
            name = "Sites",
            targetSites = setOf("youtube.com"),
            trigger = Trigger(TriggerType.TIMER, Long.MAX_VALUE / 4, TriggerUnit.MS),
        )
        var world = buildWorld(listOf(block), clock = clock)
        world = engine.reduce(world, BlockEvent.TimerControl("s1", running = true), clock).world
        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.browser", "m.youtube.com"), clock).world

        clock.advance(10_000) // on the target site
        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.browser", "example.com"), clock).world
        clock.advance(10_000) // same browser, different site — must not count
        world = engine.reduce(world, BlockEvent.ForegroundChanged(null), clock).world

        assertEquals(10_000L, world.progress["s1"]!!.accumulatedMs)
    }

    @Test
    fun i19_allowanceBlocksOnceUsedUp() {
        val clock = FakeClock()
        val block = modeBlock("a1", BlockMode.ALLOWANCE, dailyAllowanceMs = 10L * 60_000)
        var world = buildWorld(listOf(block), clock = clock, foregroundPkg = "com.target")

        clock.advance(9L * 60_000)
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        assertFalse(engine.blockedPackages(world, clock).contains("com.target"))

        clock.advance(2L * 60_000)
        val out = engine.reduce(world, BlockEvent.Tick, clock)
        assertTrue(out.shieldPlan!!.blockedPackages.contains("com.target"))
        assertEquals(10L * 60_000, out.world.progress["a1"]!!.accumulatedMs) // capped
        assertEquals(BlockState.ARMED, out.world.block("a1")!!.state)
    }

    @Test
    fun i19_allowanceResetsAtDayRollover() {
        val clock = FakeClock() // 12:00 UTC
        val block = modeBlock("a1", BlockMode.ALLOWANCE, dailyAllowanceMs = 10L * 60_000)
        var world = buildWorld(listOf(block), clock = clock, foregroundPkg = "com.target")
        clock.advance(10L * 60_000)
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        assertTrue(engine.blockedPackages(world, clock).contains("com.target"))

        clock.advance(12L * 60 * 60_000) // past midnight
        world = engine.reduce(world, BlockEvent.Tick, clock).world

        assertFalse(engine.blockedPackages(world, clock).contains("com.target"))
        assertEquals(0L, world.progress["a1"]!!.accumulatedMs)
    }

    @Test
    fun i19_allowanceIgnoresTimeBehindShield() {
        val clock = FakeClock() // 12:00 UTC, inside 09:00–17:00
        val allowance = modeBlock("a1", BlockMode.ALLOWANCE, dailyAllowanceMs = 10L * 60_000)
        val schedule = modeBlock("s1", BlockMode.SCHEDULE, schedule = everyDay(9 * 60, 17 * 60))
        var world = buildWorld(listOf(allowance, schedule), clock = clock, foregroundPkg = "com.target")

        clock.advance(5L * 60_000) // in the app, but the schedule block has it shielded
        world = engine.reduce(world, BlockEvent.Tick, clock).world

        assertEquals(0L, world.progress["a1"]!!.accumulatedMs)
    }

    @Test
    fun i20_scheduleBlocksOnlyInsideWindow() {
        val clock = FakeClock() // Thursday 12:00 UTC
        val day = modeBlock("d1", BlockMode.SCHEDULE, targetPackages = setOf("com.day"), schedule = everyDay(9 * 60, 17 * 60))
        val night = modeBlock("n1", BlockMode.SCHEDULE, targetPackages = setOf("com.night"), schedule = everyDay(22 * 60, 6 * 60))
        val world = buildWorld(listOf(day, night), clock = clock)
        fun blocked() = engine.blockedPackages(world, clock)

        assertEquals(setOf("com.day"), blocked()) // 12:00
        clock.advance(5L * 60 * 60_000) // 17:00 — window end is exclusive
        assertEquals(emptySet<String>(), blocked())
        clock.advance(6L * 60 * 60_000) // 23:00
        assertEquals(setOf("com.night"), blocked())
        clock.advance(6L * 60 * 60_000) // 05:00 next day
        assertEquals(setOf("com.night"), blocked())
        clock.advance(3L * 60 * 60_000 + 59L * 60_000) // 08:59
        assertEquals(emptySet<String>(), blocked())
        clock.advance(60_000) // 09:00
        assertEquals(setOf("com.day"), blocked())
    }

    @Test
    fun i20_overnightWindowBelongsToItsStartDay() {
        val clock = FakeClock() // Thursday 2026-01-15 12:00 UTC
        val fridayNight = Schedule(setOf(5), TimeWindow(23 * 60, 7 * 60))
        val block = modeBlock("n1", BlockMode.SCHEDULE, schedule = fridayNight)
        val world = buildWorld(listOf(block), clock = clock)
        fun blocked() = engine.isBlocking(world, block, clock)

        clock.advance(14L * 60 * 60_000) // Fri 02:00: Thursday night, not in the schedule
        assertFalse(blocked())
        clock.advance(21L * 60 * 60_000 + 30L * 60_000) // Fri 23:30
        assertTrue(blocked())
        clock.advance(2L * 60 * 60_000 + 30L * 60_000) // Sat 02:00: still Friday night
        assertTrue(blocked())
        clock.advance(5L * 60 * 60_000) // Sat 07:00: window over
        assertFalse(blocked())
    }

    @Test
    fun i21_lockdownIgnoresWallClockAndEnds() {
        val clock = FakeClock()
        val block = modeBlock("l1", BlockMode.LOCKDOWN, lockdownMs = 60L * 60_000)
        var world = buildWorld(listOf(block), clock = clock)
        assertFalse(engine.blockedPackages(world, clock).contains("com.target"))

        world = engine.reduce(world, BlockEvent.StartLockdown("l1"), clock).world
        val started = world.lockdowns["l1"]!!
        assertTrue(engine.blockedPackages(world, clock).contains("com.target"))

        clock.advanceWallOnly(5L * 60 * 60_000) // user pushes the clock forward 5 h
        clock.advanceMonoOnly(60_000)
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        assertTrue(engine.blockedPackages(world, clock).contains("com.target"))
        world = engine.reduce(world, BlockEvent.StartLockdown("l1"), clock).world
        assertEquals(started, world.lockdowns["l1"]) // no restart while running

        clock.advanceMonoOnly(59L * 60_000)
        val out = engine.reduce(world, BlockEvent.Tick, clock)
        assertFalse(out.shieldPlan!!.blockedPackages.contains("com.target"))
        assertTrue(out.world.lockdowns.isEmpty())
    }

    @Test
    fun i21_lockdownSurvivesRebootViaWallDelta() {
        val clock = FakeClock(mono = 50_000_000)
        val block = modeBlock("l1", BlockMode.LOCKDOWN, lockdownMs = 60L * 60_000)
        var world = buildWorld(listOf(block), clock = clock)
        world = engine.reduce(world, BlockEvent.StartLockdown("l1"), clock).world

        clock.advance(10L * 60_000)
        clock.setMono(1_000) // reboot: monotonic restarts near zero
        world = engine.reduce(world, BlockEvent.BootOrColdStart, clock).world

        assertEquals(50L * 60_000, engine.lockdownRemainingMs(world, "l1", clock))
        assertTrue(engine.blockedPackages(world, clock).contains("com.target"))
    }

    @Test
    fun i22_habitUnlockDoesNotLiftStrictBlock() {
        val clock = FakeClock() // 12:00 UTC
        val habit = checklistBlock("h1", target = "com.x", state = BlockState.UNLOCKED)
        val schedule = modeBlock("s1", BlockMode.SCHEDULE, targetPackages = setOf("com.x"), schedule = everyDay(9 * 60, 17 * 60))
        val world = buildWorld(listOf(habit, schedule), clock = clock)

        assertTrue(engine.blockedPackages(world, clock).contains("com.x"))
        clock.advance(6L * 60 * 60_000) // 18:00 — schedule over, habit still UNLOCKED
        assertFalse(engine.blockedPackages(world, clock).contains("com.x"))
    }

    @Test
    fun i25_pauseLiftsSoftBlocksOnly() {
        val clock = FakeClock()
        val soft = checklistBlock("soft", target = "com.soft")
        val hard = checklistBlock("hard", target = "com.hard").copy(enforcementTier = EnforcementTier.HARDCORE)
        val lockdown = modeBlock("lock", BlockMode.LOCKDOWN, targetPackages = setOf("com.lock"), lockdownMs = 60L * 60_000)
        var world = buildWorld(listOf(soft, hard, lockdown), clock = clock)
        world = engine.reduce(world, BlockEvent.StartLockdown("lock"), clock).world

        val out = engine.reduce(world, BlockEvent.StartPause(), clock)

        assertEquals(setOf("com.hard", "com.lock"), out.shieldPlan!!.blockedPackages)
        assertFalse(engine.isBlocking(out.world, soft, clock))
        assertTrue(engine.isBlocking(out.world, hard, clock))
    }

    @Test
    fun i25_pauseEndsAndIsOncePerDay() {
        val clock = FakeClock()
        var world = buildWorld(listOf(checklistBlock("c1")), clock = clock)
        world = engine.reduce(world, BlockEvent.StartPause(), clock).world
        assertTrue(engine.blockedPackages(world, clock).isEmpty())

        clock.advanceWallOnly(-60L * 60_000) // winding the clock back doesn't stretch it
        clock.advanceMonoOnly(Defaults.PAUSE_MS)
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        assertNull(world.pause)
        assertEquals(setOf("com.target"), engine.blockedPackages(world, clock))

        clock.advanceWallOnly(60L * 60_000)
        world = engine.reduce(world, BlockEvent.StartPause(), clock).world
        assertNull("already had today's break", world.pause)

        clock.advance(24L * 60 * 60_000)
        world = engine.reduce(world, BlockEvent.StartPause(), clock).world
        assertNotNull(world.pause)
        world = engine.reduce(world, BlockEvent.EndPause, clock).world
        assertEquals(setOf("com.target"), engine.blockedPackages(world, clock))
    }

    @Test
    fun i25_pauseSurvivesRebootViaWallDelta() {
        val clock = FakeClock(mono = 50_000_000)
        var world = buildWorld(listOf(checklistBlock("c1")), clock = clock)
        world = engine.reduce(world, BlockEvent.StartPause(), clock).world

        clock.advance(5L * 60_000)
        clock.setMono(1_000)
        world = engine.reduce(world, BlockEvent.BootOrColdStart, clock).world

        assertEquals(10L * 60_000, engine.pauseRemainingMs(world, clock))
    }

    // ---- several devices (contract §2.4) -----------------------------------------------

    /** A phone and a laptop on the same wall clock but different monotonic clocks. */
    private fun twoDevices(blocks: List<Block>, checklist: Map<String, List<ChecklistItem>> = emptyMap()): Triple<Device, Device, FakeServer> {
        val phoneClock = FakeClock(mono = 7_000_000)
        val laptopClock = FakeClock(mono = 300_000)
        val phone = Device("phone", buildWorld(blocks, checklist, phoneClock), phoneClock)
        val laptop = Device("laptop", World(), laptopClock) // a fresh install: everything comes from the server
        val server = FakeServer()
        phone.sync(server)
        laptop.sync(server)
        return Triple(phone, laptop, server)
    }

    @Test
    fun i19_allowanceCountsOtherDevices() {
        val block = modeBlock("a1", BlockMode.ALLOWANCE, targetPackages = setOf("com.target"), dailyAllowanceMs = 10L * 60_000)
            .copy(targetSites = setOf("youtube.com"))
        val (phone, laptop, server) = twoDevices(listOf(block))

        phone.on(BlockEvent.ForegroundChanged("com.target"))
        laptop.on(BlockEvent.ForegroundChanged("browser", "youtube.com"))
        advanceAll(7L * 60_000, phone)
        phone.on(BlockEvent.Tick)
        phone.sync(server)
        laptop.sync(server)
        assertFalse(engine.isBlocking(laptop.world, laptop.block("a1"), laptop.clock))

        advanceAll(4L * 60_000, laptop) // 7 on the phone + 4 here: over the 10-minute allowance
        laptop.on(BlockEvent.Tick)
        assertTrue(engine.isBlocking(laptop.world, laptop.block("a1"), laptop.clock))

        // Yesterday's use elsewhere doesn't count today.
        advanceAll(24L * 60 * 60_000, phone, laptop)
        laptop.on(BlockEvent.Tick)
        assertEquals(0L, engine.allowanceUsedMs(laptop.world, laptop.block("a1"), laptop.clock))
    }

    /** Rounds in turn on both devices, until a whole pass writes nothing. */
    private fun settle(phone: Device, laptop: Device, server: FakeServer) {
        repeat(6) {
            val phoneWrote = phone.sync(server)
            val laptopWrote = laptop.sync(server)
            if (!phoneWrote && !laptopWrote) return
        }
        throw AssertionError("the devices keep rewriting each other's state")
    }

    @Test
    fun i19_aStaleUseOfThisDeviceSettles() {
        val block = modeBlock("a1", BlockMode.ALLOWANCE, dailyAllowanceMs = 10L * 60_000)
        val (phone, laptop, server) = twoDevices(listOf(block))

        laptop.on(BlockEvent.ForegroundChanged("com.target"))
        advanceAll(3L * 60_000, phone, laptop)
        laptop.on(BlockEvent.Tick)
        laptop.on(BlockEvent.ForegroundChanged(null))
        laptop.sync(server)
        phone.sync(server)
        assertEquals(3L * 60_000, phone.world.otherAllowance.getValue("a1").getValue("laptop").ms)

        // The next day the laptop uses nothing, and the phone still holds yesterday's report.
        advanceAll(24L * 60 * 60_000, phone, laptop)
        phone.on(BlockEvent.Tick)
        laptop.on(BlockEvent.Tick)

        settle(phone, laptop, server)
        val report = phone.world.otherAllowance.getValue("a1").getValue("laptop")
        assertEquals(phone.clock.startOfLocalDayMs(), report.dayStartWall)
        assertEquals(0L, report.ms)
    }

    @Test
    fun i19_thisDevicesUseComesBackFromTheServer() {
        val block = modeBlock("a1", BlockMode.ALLOWANCE, dailyAllowanceMs = 10L * 60_000)
        val (phone, laptop, server) = twoDevices(listOf(block))

        laptop.on(BlockEvent.ForegroundChanged("com.target"))
        advanceAll(4L * 60_000, phone, laptop)
        laptop.on(BlockEvent.Tick)
        laptop.on(BlockEvent.ForegroundChanged(null))
        laptop.sync(server)
        phone.sync(server)

        // The laptop loses its count; the server and the phone still have it.
        laptop.world = laptop.world.copy(progress = emptyMap())

        settle(phone, laptop, server)
        assertEquals(4L * 60_000, laptop.world.progress.getValue("a1").accumulatedMs)
        assertEquals(4L * 60_000, engine.allowanceUsedMs(laptop.world, laptop.block("a1"), laptop.clock))
        assertEquals(4L * 60_000, engine.allowanceUsedMs(phone.world, phone.block("a1"), phone.clock))
    }

    // ---- several rules on one block (contract §2.2) ----------------------------------------

    private val tenMinutes = 10L * 60_000

    /** Every day, [startH]:00 to [endH]:00. */
    private fun hours(startH: Int, endH: Int) = Schedule((1..7).toSet(), TimeWindow(startH * 60, endH * 60))

    /** [ms] in the target, then out of it. */
    private fun use(world: World, ms: Long, clock: FakeClock): World {
        val inTarget = engine.reduce(world, BlockEvent.ForegroundChanged("com.target"), clock).world
        clock.advance(ms)
        val ticked = engine.reduce(inTarget, BlockEvent.Tick, clock).world
        return engine.reduce(ticked, BlockEvent.ForegroundChanged(null), clock).world
    }

    private fun tick(world: World, clock: FakeClock) = engine.reduce(world, BlockEvent.Tick, clock).world

    /** Ticks the block's first item and redeems it. */
    private fun unlock(world: World, blockId: String, clock: FakeClock): World {
        val ticked = engine.reduce(world, BlockEvent.ToggleChecklistItem("$blockId-i0", true), clock).world
        return engine.reduce(ticked, BlockEvent.Redeem(blockId), clock).world
    }

    private fun reason(world: World, id: String, clock: Clock) = engine.blockReason(world, world.block(id)!!, clock)

    @Test
    fun i44_blockedHoursAddToALimit() {
        val clock = FakeClock() // 12:00
        val block = modeBlock("a1", BlockMode.ALLOWANCE, dailyAllowanceMs = tenMinutes).copy(blockedHours = hours(14, 18))
        var world = buildWorld(listOf(block), clock = clock)
        assertNull(reason(world, "a1", clock))

        world = use(world, 4L * 60_000, clock)
        assertEquals(4L * 60_000, engine.allowanceUsedMs(world, block, clock))

        clock.advance(2L * 60 * 60_000) // 14:04
        world = tick(world, clock)
        assertEquals(BlockReason.HOURS, reason(world, "a1", clock))
        assertTrue("com.target" in engine.blockedPackages(world, clock))

        clock.advance(4L * 60 * 60_000) // 18:04: the limit still has six minutes
        world = tick(world, clock)
        assertNull(reason(world, "a1", clock))
        world = use(world, 6L * 60_000, clock)
        assertEquals(BlockReason.LIMIT, reason(world, "a1", clock))
    }

    @Test
    fun i44_aMergedBlockBlocksLikeTheTwoItReplaces() {
        val blockedHours = Schedule(
            activeDays = (1..6).toSet(),
            activeWindow = TimeWindow(0, 14 * 60),
            moreWindows = listOf(DayWindow((1..7).toSet(), TimeWindow(18 * 60, 22 * 60)), DayWindow(setOf(7), TimeWindow(23 * 60, 2 * 60))),
        )
        val limit = modeBlock("a1", BlockMode.ALLOWANCE, dailyAllowanceMs = tenMinutes)
        val alone = modeBlock("s1", BlockMode.SCHEDULE, schedule = blockedHours)
        val merged = limit.copy(blockedHours = blockedHours)
        for (usedMs in listOf(0L, tenMinutes / 2, tenMinutes)) {
            val clock = FakeClock()
            repeat(7 * 24 * 2) { // every half hour for a week
                clock.advance(30L * 60_000)
                val used = mapOf("a1" to HabitProgress("a1", usedMs, clock.startOfLocalDayMs(), clock.nowMonotonicMs()))
                val two = buildWorld(listOf(limit, alone), clock = clock).copy(progress = used)
                val one = buildWorld(listOf(merged), clock = clock).copy(progress = used)
                assertEquals(engine.blockedPackages(two, clock), engine.blockedPackages(one, clock))
            }
        }
    }

    @Test
    fun i44_aHabitNeverOpensBlockedHours() {
        val clock = FakeClock() // 12:00, before the hours
        val inside = checklistBlock("c1", unlockMs = 3L * 60 * 60_000).copy(blockedHours = hours(13, 14))
        val other = checklistBlock("c2", unlockMs = 3L * 60 * 60_000)
        var world = buildWorld(listOf(inside, other), mapOf("c1" to items("c1", 1), "c2" to items("c2", 1)), clock).let { w ->
            w.copy(settings = w.settings.copy(dailyCapMs = 8L * 60 * 60_000))
        }
        world = unlock(world, "c1", clock)
        world = unlock(world, "c2", clock)
        assertFalse("com.target" in engine.blockedPackages(world, clock))

        clock.advance(61L * 60_000) // 13:01
        world = tick(world, clock)
        assertEquals(BlockState.UNLOCKED, world.block("c1")!!.state)
        assertEquals(BlockReason.HOURS, reason(world, "c1", clock))
        assertTrue("I6's other block doesn't lift it either", "com.target" in engine.blockedPackages(world, clock))

        clock.advance(60L * 60_000) // 14:01
        world = tick(world, clock)
        assertNull(reason(world, "c1", clock))
        assertFalse("com.target" in engine.blockedPackages(world, clock))
    }

    @Test
    fun i44_nothingIsCashedInWhileItsBlockIsHeld() {
        val clock = FakeClock() // 12:00, inside the hours
        val block = checklistBlock("c1").copy(blockedHours = hours(11, 13))
        var world = buildWorld(listOf(block), mapOf("c1" to items("c1", 1)), clock)
        world = engine.reduce(world, BlockEvent.ToggleChecklistItem("c1-i0", true), clock).world
        assertEquals(BlockState.EARNED, world.block("c1")!!.state)
        assertEquals(RejectReason.HELD, engine.redeemRejection(world, "c1", clock))

        val refused = engine.reduce(world, BlockEvent.Redeem("c1"), clock)
        assertEquals(BlockState.EARNED, refused.world.block("c1")!!.state)
        assertNotNull("the credit is still there", refused.world.credits["c1"])
        assertTrue(Effect.RedeemRejected("c1", RejectReason.HELD) in refused.effects)

        clock.advance(61L * 60_000) // 13:01
        world = tick(refused.world, clock)
        assertNull(engine.redeemRejection(world, "c1", clock))
    }

    @Test
    fun i44_limitFirstGivesTheFreeMinutesThenTheHabitOpensIt() {
        val clock = FakeClock()
        val block = checklistBlock("c1", unlockMs = 5L * 60_000).copy(dailyAllowanceMs = tenMinutes)
        var world = buildWorld(listOf(block), mapOf("c1" to items("c1", 1)), clock)
        assertNull("the free minutes come first", reason(world, "c1", clock))

        world = use(world, tenMinutes, clock)
        assertEquals(BlockReason.HABIT, reason(world, "c1", clock))
        assertTrue("com.target" in engine.blockedPackages(world, clock))

        world = unlock(world, "c1", clock)
        assertNull("the habit opens it again", reason(world, "c1", clock))
        assertFalse("com.target" in engine.blockedPackages(world, clock))

        clock.advance(5L * 60_000)
        world = tick(world, clock)
        assertEquals(BlockState.ARMED, world.block("c1")!!.state)
        assertEquals(BlockReason.HABIT, reason(world, "c1", clock))

        clock.advance(DAY_MS)
        world = tick(world, clock)
        assertNull("a new day, new minutes", reason(world, "c1", clock))
        assertEquals(0L, engine.allowanceUsedMs(world, block, clock))
    }

    @Test
    fun i44_habitFirstBlocksUntilTheHabitAndTheLimitHolds() {
        val clock = FakeClock()
        val block = checklistBlock("c1", unlockMs = 30L * 60_000).copy(dailyAllowanceMs = tenMinutes, habitFirst = true)
        var world = buildWorld(listOf(block), mapOf("c1" to items("c1", 1)), clock)
        assertEquals("the habit comes first", BlockReason.HABIT, reason(world, "c1", clock))

        world = unlock(world, "c1", clock)
        assertNull(reason(world, "c1", clock))

        world = use(world, tenMinutes, clock)
        assertEquals(BlockState.UNLOCKED, world.block("c1")!!.state)
        assertEquals("no habit gets past the limit", BlockReason.LIMIT, reason(world, "c1", clock))
        assertTrue("com.target" in engine.blockedPackages(world, clock))
    }

    @Test
    fun i44_aRowRuleAloneActsLikeHabitFirst() {
        val clock = FakeClock()
        val block = checklistBlock("c1").copy(sessionMs = tenMinutes, sessionBreakMs = tenMinutes)
        val world = buildWorld(listOf(block), mapOf("c1" to items("c1", 1)), clock)
        assertFalse(block.limitComesFirst)
        assertEquals(BlockReason.HABIT, reason(world, "c1", clock))
    }

    @Test
    fun i44_aForcedBreakHoldsThroughAnUnlock() {
        val clock = FakeClock()
        val block = checklistBlock("c1", unlockMs = 30L * 60_000).copy(dailyAllowanceMs = 3 * tenMinutes, sessionMs = tenMinutes, sessionBreakMs = tenMinutes)
        var world = buildWorld(listOf(block), mapOf("c1" to items("c1", 1)), clock)
        world = unlock(world, "c1", clock)

        world = use(world, tenMinutes, clock)
        assertEquals(BlockState.UNLOCKED, world.block("c1")!!.state)
        assertEquals("a habit doesn't end a forced break", BlockReason.BREAK, reason(world, "c1", clock))
        assertTrue("com.target" in engine.blockedPackages(world, clock))

        clock.advance(tenMinutes)
        world = tick(world, clock)
        assertNull(reason(world, "c1", clock))
    }

    @Test
    fun i44_theLimitAndTheHabitTimerCountApart() {
        val clock = FakeClock()
        val block = timerBlock("t1", habitPackages = setOf("com.habit"), requiredMs = 30L * 60_000).copy(dailyAllowanceMs = tenMinutes)
        var world = buildWorld(listOf(block), clock = clock)
        world = engine.reduce(world, BlockEvent.TimerControl("t1", true), clock).world

        world = use(world, 4L * 60_000, clock)
        assertEquals(0L, world.progress.getValue("t1").accumulatedMs)
        assertEquals(4L * 60_000, engine.allowanceUsedMs(world, block, clock))

        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.habit"), clock).world
        clock.advance(3L * 60_000)
        world = tick(world, clock)
        assertEquals(3L * 60_000, world.progress.getValue("t1").accumulatedMs)
        assertEquals(4L * 60_000, engine.allowanceUsedMs(world, block, clock))
    }

    @Test
    fun i44_goingBackToArmedKeepsTheLimitUse() {
        val clock = FakeClock()
        val block = checklistBlock("c1", unlockMs = 5L * 60_000).copy(dailyAllowanceMs = tenMinutes, habitFirst = true)
        var world = buildWorld(listOf(block), mapOf("c1" to items("c1", 1)), clock)
        world = unlock(world, "c1", clock)
        world = use(world, 3L * 60_000, clock)

        clock.advance(2L * 60_000) // the unlock ends
        world = tick(world, clock)
        assertEquals(BlockState.ARMED, world.block("c1")!!.state)
        assertEquals(3L * 60_000, engine.allowanceUsedMs(world, block, clock))

        clock.advance(DAY_MS)
        world = tick(world, clock)
        assertEquals(0L, engine.allowanceUsedMs(world, block, clock))
    }

    @Test
    fun i44_theLimitsCountIsOnlyBookkeeping() {
        val clock = FakeClock()
        val block = checklistBlock("c1").copy(dailyAllowanceMs = tenMinutes)
        val before = buildWorld(listOf(block), mapOf("c1" to items("c1", 1)), clock)
        val after = use(before, 60_000, clock)
        assertTrue(engine.allowanceUsedMs(after, block, clock) > 0)
        assertEquals("a save needn't hurry for a count that moves every tick", before.withoutBookkeeping(), after.withoutBookkeeping())
    }

    @Test
    fun i44_aWaitTimerDoesNotCountDuringFreeMinutes() {
        val clock = FakeClock()
        val block = timerBlock("t1", requiredMs = 30L * 60_000).copy(dailyAllowanceMs = tenMinutes)
        var world = buildWorld(listOf(block), clock = clock)
        world = engine.reduce(world, BlockEvent.TimerControl("t1", true), clock).world

        world = use(world, tenMinutes, clock)
        assertEquals("using it isn't waiting", 0L, world.progress.getValue("t1").accumulatedMs)

        world = use(world, 5L * 60_000, clock) // on the block screen now
        assertEquals(5L * 60_000, world.progress.getValue("t1").accumulatedMs)
    }

    @Test
    fun i44_boughtMinutesLiftHoursAndLimit() {
        val clock = FakeClock() // inside the hours, and the limit is used up
        val block = modeBlock("a1", BlockMode.ALLOWANCE, dailyAllowanceMs = tenMinutes).copy(blockedHours = hours(11, 13))
        var world = buildWorld(listOf(block), clock = clock)
        world = world.copy(progress = mapOf("a1" to HabitProgress("a1", tenMinutes, clock.startOfLocalDayMs(), clock.nowMonotonicMs())))
        assertEquals(BlockReason.HOURS, reason(world, "a1", clock))

        world = engine.reduce(world, BlockEvent.BeginOverride(setOf("a1")), clock).world
        clock.advance(Defaults.OVERRIDE_WAIT_MS)
        world = claim(world, clock).world
        assertNull(reason(world, "a1", clock))
        assertEquals(ClockKind.OVERRIDE, engine.timeLeft(world, "com.target", null, clock)?.kind)

        clock.advance(Defaults.OVERRIDE_GRANT_MS)
        world = tick(world, clock)
        assertEquals(BlockReason.HOURS, reason(world, "a1", clock))

        val habit = checklistBlock("c1").copy(blockedHours = hours(11, 13))
        assertEquals(OverrideRejection.NOT_OVERRIDABLE, engine.overrideRejection(buildWorld(listOf(habit), clock = clock), listOf(habit), clock))
    }

    @Test
    fun i44_aLimitOnAHabitBlockIsShared() {
        val block = checklistBlock("c1").copy(dailyAllowanceMs = tenMinutes)
        val (phone, laptop, server) = twoDevices(listOf(block), mapOf("c1" to items("c1", 1)))

        phone.world = use(phone.world, 7L * 60_000, phone.clock)
        settle(phone, laptop, server)
        assertEquals(7L * 60_000, engine.allowanceUsedMs(laptop.world, laptop.block("c1"), laptop.clock))

        laptop.world = use(laptop.world, 3L * 60_000, laptop.clock)
        settle(phone, laptop, server)
        assertEquals(BlockReason.HABIT, reason(phone.world, "c1", phone.clock))
        assertEquals(BlockReason.HABIT, reason(laptop.world, "c1", laptop.clock))
    }

    @Test
    fun i44_changingModeMovesTheCount() {
        val limit = modeBlock("a1", BlockMode.ALLOWANCE, dailyAllowanceMs = tenMinutes)
        val (phone, laptop, server) = twoDevices(listOf(limit))
        laptop.world = use(laptop.world, 4L * 60_000, laptop.clock)
        settle(phone, laptop, server)

        val habit = limit.copy(mode = BlockMode.HABIT, trigger = Trigger(TriggerType.TIMER, tenMinutes, TriggerUnit.MS), editedAtWall = phone.clock.nowWallMs() + 1)
        phone.world = phone.world.withBlock(habit, emptyList())
        settle(phone, laptop, server)

        val p = laptop.world.progress.getValue("a1")
        assertEquals("its use isn't a head start on the timer", 0L, p.accumulatedMs)
        assertEquals(4L * 60_000, p.limitUsedMs)
        assertEquals(4L * 60_000, engine.allowanceUsedMs(laptop.world, laptop.block("a1"), laptop.clock))
    }

    @Test
    fun i26_creditUsedElsewhereIsNotResurrected() {
        val (phone, laptop, server) = twoDevices(listOf(checklistBlock("c1")), mapOf("c1" to items("c1", 1)))
        phone.on(BlockEvent.ToggleChecklistItem("c1-i0", true))
        phone.sync(server)
        laptop.sync(server)
        assertEquals(BlockState.EARNED, laptop.block("c1").state)

        laptop.on(BlockEvent.Redeem("c1"))
        laptop.sync(server)
        phone.sync(server) // the phone still has the credit, unchanged since it last synced

        assertEquals(BlockState.UNLOCKED, phone.block("c1").state)
        assertTrue(phone.world.credits.isEmpty())
        assertEquals(laptop.world.sessions["c1"]!!.startedAtWall, phone.world.sessions["c1"]!!.startedAtWall)
        assertFalse(engine.blockedPackages(phone.world, phone.clock).contains("com.target"))
    }

    @Test
    fun i26_offlineDoubleRedeemKeepsLaterSession() {
        val (phone, laptop, server) = twoDevices(listOf(checklistBlock("c1")), mapOf("c1" to items("c1", 1)))
        phone.on(BlockEvent.ToggleChecklistItem("c1-i0", true))
        phone.sync(server)
        laptop.sync(server)

        phone.on(BlockEvent.Redeem("c1")) // both redeem while offline, the laptop a minute later
        advanceAll(60_000, phone, laptop)
        laptop.on(BlockEvent.Redeem("c1"))
        phone.sync(server)
        laptop.sync(server)
        phone.sync(server)

        val later = laptop.world.sessions["c1"]!!
        assertEquals("the laptop's, a minute later", laptop.clock.nowWallMs(), later.startedAtWall)
        assertEquals(later.startedAtWall, phone.world.sessions["c1"]!!.startedAtWall)
        assertTrue(phone.world.credits.isEmpty() && laptop.world.credits.isEmpty())
    }

    @Test
    fun i26_sessionEndedElsewhereEndsHere() {
        val (phone, laptop, server) = twoDevices(listOf(checklistBlock("c1")), mapOf("c1" to items("c1", 1)))
        phone.on(BlockEvent.ToggleChecklistItem("c1-i0", true))
        phone.on(BlockEvent.Redeem("c1"))
        phone.sync(server)
        laptop.sync(server)
        assertEquals(BlockState.UNLOCKED, laptop.block("c1").state)

        phone.clock.advanceWallOnly(-60L * 60_000) // the phone's clock is wound back: tamper (I3)
        phone.clock.advanceMonoOnly(60_000)
        phone.on(BlockEvent.Tick)
        assertEquals(BlockState.ARMED, phone.block("c1").state)
        phone.sync(server)
        laptop.sync(server)

        assertEquals(BlockState.ARMED, laptop.block("c1").state)
        assertTrue(engine.blockedPackages(laptop.world, laptop.clock).contains("com.target"))
    }

    @Test
    fun i27_adoptedSessionRunsOutOnTime() {
        val (phone, laptop, server) = twoDevices(listOf(checklistBlock("c1", unlockMs = 5L * 60_000)), mapOf("c1" to items("c1", 1)))
        phone.on(BlockEvent.ToggleChecklistItem("c1-i0", true))
        phone.on(BlockEvent.Redeem("c1"))
        phone.sync(server)

        advanceAll(2L * 60_000, phone, laptop) // the laptop hears about it two minutes in
        laptop.sync(server)
        val tick = laptop.on(BlockEvent.Tick)
        assertEquals(BlockState.UNLOCKED, laptop.block("c1").state)
        assertFalse("no false tamper", tick.effects.any { it is Effect.TamperDetected })

        advanceAll(3L * 60_000, phone, laptop)
        laptop.on(BlockEvent.Tick)
        assertEquals("ends with the phone's, not five minutes after hearing of it", BlockState.ARMED, laptop.block("c1").state)
    }

    @Test
    fun i28_sharedSessionCountsOnceTowardCap() {
        val (phone, laptop, server) = twoDevices(listOf(checklistBlock("c1", unlockMs = 5L * 60_000)), mapOf("c1" to items("c1", 1)))
        phone.on(BlockEvent.ToggleChecklistItem("c1-i0", true))
        phone.on(BlockEvent.Redeem("c1"))
        phone.sync(server)
        laptop.sync(server)

        advanceAll(5L * 60_000, phone, laptop) // both see the same session run out
        val endedOnPhone = phone.on(BlockEvent.Tick).effects.filterIsInstance<Effect.UnlockConsumed>().single()
        val endedOnLaptop = laptop.on(BlockEvent.Tick).effects.filterIsInstance<Effect.UnlockConsumed>().single()
        // Each logs it, under the session's one start, so a history across devices counts it once too.
        assertEquals(endedOnPhone.startedAtWall, endedOnLaptop.startedAtWall)
        assertTrue(endedOnPhone.startedAtWall > 0)
        phone.sync(server)
        laptop.sync(server)
        phone.sync(server)

        assertEquals(5L * 60_000, engine.unlockUsedTodayMs(phone.world, phone.clock))
        assertEquals(5L * 60_000, engine.unlockUsedTodayMs(laptop.world, laptop.clock))
    }

    @Test
    fun i29_desktopAppTargetsActLikePackages() {
        val clock = FakeClock()
        val block = checklistBlock("c1").copy(targetPackages = emptySet(), targetDesktopApps = setOf("steam"))
        var world = buildWorld(listOf(block), mapOf("c1" to items("c1", 1)), clock)

        assertTrue(engine.blockedPackages(world, clock).contains("steam"))
        val shown = engine.reduce(world, BlockEvent.ForegroundChanged("steam"), clock)
        assertTrue(shown.effects.contains(Effect.BlockedImpression("steam")))

        world = engine.reduce(shown.world, BlockEvent.ToggleChecklistItem("c1-i0", true), clock).world
        world = engine.reduce(world, BlockEvent.Redeem("c1"), clock).world
        assertTrue(engine.isAppUnlocked(world, "steam"))
        assertFalse(engine.blockedPackages(world, clock).contains("steam"))

        assertTrue(BlockEdits.loosens(block, block.copy(targetDesktopApps = emptySet())))
        assertFalse(BlockEdits.loosens(block, block.copy(targetDesktopApps = setOf("steam", "discord"))))

        val allowance = modeBlock("a1", BlockMode.ALLOWANCE, targetPackages = emptySet(), dailyAllowanceMs = 60L * 60_000)
            .copy(targetDesktopApps = setOf("steam"))
        var w = buildWorld(listOf(allowance), clock = clock, foregroundPkg = "steam")
        clock.advance(3L * 60_000)
        w = engine.reduce(w, BlockEvent.Tick, clock).world
        assertEquals(3L * 60_000, engine.allowanceUsedMs(w, allowance, clock))
    }

    // ---- schedules with several windows, site exceptions (I30, I31) --------------------------

    @Test
    fun i30_anyScheduleWindowCounts() {
        val clock = FakeClock() // Thursday 12:00 UTC
        val schedule = everyDay(0, 14 * 60).copy(moreWindows = listOf(DayWindow((1..7).toSet(), TimeWindow(18 * 60, 22 * 60))))
        val block = modeBlock("s1", BlockMode.SCHEDULE, schedule = schedule)
        val world = buildWorld(listOf(block), clock = clock)
        fun blocked() = engine.isBlocking(world, block, clock)

        assertTrue(blocked()) // 12:00, the first window
        clock.advance(3L * 60 * 60_000) // 15:00, between them
        assertFalse(blocked())
        clock.advance(4L * 60 * 60_000) // 19:00, the second window
        assertTrue(blocked())
        clock.advance(4L * 60 * 60_000) // 23:00
        assertFalse(blocked())

        // Adding a window tightens; dropping one loosens.
        assertFalse(BlockEdits.loosens(modeBlock("s1", BlockMode.SCHEDULE, schedule = everyDay(0, 14 * 60)), block))
        assertTrue(BlockEdits.loosens(block, block.copy(schedule = everyDay(0, 14 * 60))))
    }

    @Test
    fun i31_exceptedHostIsNeitherBlockedNorCounted() {
        val clock = FakeClock()
        val limit = modeBlock("a1", BlockMode.ALLOWANCE, targetPackages = emptySet(), targetSites = setOf("youtube.com"), dailyAllowanceMs = 10L * 60_000)
            .copy(exceptSites = setOf("music.youtube.com"))
        var world = buildWorld(listOf(limit), clock = clock)

        world = engine.reduce(world, BlockEvent.ForegroundChanged("browser", "music.youtube.com"), clock).world
        clock.advance(5L * 60_000)
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        assertEquals(0L, engine.allowanceUsedMs(world, limit, clock))

        world = engine.reduce(world, BlockEvent.ForegroundChanged("browser", "m.youtube.com"), clock).world
        clock.advance(10L * 60_000)
        val plan = engine.reduce(world, BlockEvent.Tick, clock).world.appliedShield
        assertTrue(plan.covers(null, "m.youtube.com"))
        assertFalse(plan.covers(null, "music.youtube.com"))
        assertEquals(setOf("music.youtube.com"), plan.siteExceptions["youtube.com"])

        // Another block on the same domain without that exception blocks music too.
        val habit = checklistBlock("h1").copy(targetPackages = emptySet(), targetSites = setOf("youtube.com"))
        val both = buildWorld(listOf(limit, habit), mapOf("h1" to items("h1", 1)), clock)
        assertTrue(both.appliedShield.covers(null, "music.youtube.com"))

        assertTrue(BlockEdits.loosens(limit, limit.copy(exceptSites = setOf("music.youtube.com", "studio.youtube.com"))))
        assertFalse(BlockEdits.loosens(limit, limit.copy(exceptSites = emptySet())))
    }

    // ---- in a row, per device (I32, I33) ---------------------------------------------------

    private fun rowBlock(id: String = "r1", dailyMs: Long = 0, rowMs: Long = 15L * 60_000, breakMs: Long = 15L * 60_000) =
        modeBlock(id, BlockMode.ALLOWANCE, targetPackages = setOf("com.target"), dailyAllowanceMs = dailyMs)
            .copy(sessionMs = rowMs, sessionBreakMs = breakMs)

    /** Minute-by-minute ticks, as the phone's 1 s tick would do it. */
    private fun ticks(world: World, clock: FakeClock, minutes: Int): World {
        var w = world
        repeat(minutes) {
            clock.advance(60_000)
            w = engine.reduce(w, BlockEvent.Tick, clock).world
        }
        return w
    }

    @Test
    fun i32_rowRuleForcesABreak() {
        val clock = FakeClock()
        val block = rowBlock()
        var world = buildWorld(listOf(block), clock = clock, foregroundPkg = "com.target")

        world = ticks(world, clock, 14)
        assertFalse(engine.isBlocking(world, block, clock)) // no daily limit, 14 of 15 in a row
        world = ticks(world, clock, 1)
        assertTrue(engine.isBlocking(world, block, clock))
        assertEquals(15L * 60_000, engine.rowBreakRemainingMs(world, block, clock))
        assertEquals(15L * 60_000, engine.allowanceUsedMs(world, block, clock)) // no daily cap to stop at

        world = ticks(world, clock, 14) // sitting on the block screen costs nothing
        assertTrue(engine.isBlocking(world, block, clock))
        world = ticks(world, clock, 1)
        assertFalse(engine.isBlocking(world, block, clock))
        assertTrue(engine.rowUsedMs(world, block, clock) <= 60_000) // a fresh row
    }

    @Test
    fun i32_restingLongEnoughStartsAFreshRow() {
        val clock = FakeClock()
        val block = rowBlock()
        var world = buildWorld(listOf(block), clock = clock, foregroundPkg = "com.target")
        world = ticks(world, clock, 10)

        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.other"), clock).world
        world = ticks(world, clock, 15) // a rest as long as the break
        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.target"), clock).world
        world = ticks(world, clock, 10)
        assertFalse(engine.isBlocking(world, block, clock))
        assertEquals(10L * 60_000, engine.rowUsedMs(world, block, clock))

        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.other"), clock).world
        world = ticks(world, clock, 5) // too short: still the same row
        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.target"), clock).world
        world = ticks(world, clock, 5)
        assertTrue(engine.isBlocking(world, block, clock))

        assertTrue(BlockEdits.loosens(block, block.copy(sessionMs = 30L * 60_000)))
        assertTrue(BlockEdits.loosens(block, block.copy(sessionBreakMs = 5L * 60_000)))
        assertTrue(BlockEdits.loosens(block, block.copy(sessionMs = 0)))
        assertFalse(BlockEdits.loosens(block, block.copy(sessionMs = 10L * 60_000, sessionBreakMs = 20L * 60_000)))
    }

    @Test
    fun i32_rowCountsAcrossDevices() {
        val block = rowBlock().copy(targetSites = setOf("youtube.com"))
        val (phone, laptop, server) = twoDevices(listOf(block))

        phone.on(BlockEvent.ForegroundChanged("com.target"))
        repeat(10) { advanceAll(60_000, phone, laptop); phone.on(BlockEvent.Tick) }
        phone.on(BlockEvent.ForegroundChanged(null))
        phone.sync(server)
        laptop.sync(server)

        laptop.on(BlockEvent.ForegroundChanged("browser", "youtube.com"))
        repeat(5) { advanceAll(60_000, phone, laptop); laptop.on(BlockEvent.Tick) }
        assertTrue(engine.isBlocking(laptop.world, laptop.block("r1"), laptop.clock)) // 10 there + 5 here
        laptop.sync(server)
        phone.sync(server)
        assertTrue(engine.isBlocking(phone.world, phone.block("r1"), phone.clock)) // the break is shared
    }

    @Test
    fun i33_perDeviceLimitIgnoresOtherDevices() {
        val block = modeBlock("a1", BlockMode.ALLOWANCE, targetPackages = setOf("com.target"), dailyAllowanceMs = 10L * 60_000)
            .copy(targetSites = setOf("youtube.com"), perDevice = true)
        val (phone, laptop, server) = twoDevices(listOf(block))

        phone.on(BlockEvent.ForegroundChanged("com.target"))
        advanceAll(7L * 60_000, phone, laptop)
        phone.on(BlockEvent.Tick)
        phone.on(BlockEvent.ForegroundChanged(null))
        phone.sync(server)
        laptop.sync(server)

        laptop.on(BlockEvent.ForegroundChanged("browser", "youtube.com"))
        advanceAll(4L * 60_000, phone, laptop)
        laptop.on(BlockEvent.Tick)
        assertFalse(engine.isBlocking(laptop.world, laptop.block("a1"), laptop.clock)) // only its own 4 minutes count
        assertEquals(4L * 60_000, engine.allowanceUsedMs(laptop.world, laptop.block("a1"), laptop.clock))

        val shared = block.copy(perDevice = false)
        assertTrue(BlockEdits.loosens(shared, block))
        assertFalse(BlockEdits.loosens(block, shared))
    }

    // ---- buying minutes (I34) --------------------------------------------------------------

    private fun claim(world: World, clock: Clock, phrase: String = Defaults.OVERRIDE_PHRASE) =
        engine.reduce(world, BlockEvent.ClaimOverride(phrase), clock)

    private fun rejection(out: Outcome): OverrideRejection? =
        out.effects.filterIsInstance<Effect.OverrideRejected>().singleOrNull()?.reason

    @Test
    fun i34_overrideNeedsTheWaitAndThePhrase() {
        val clock = FakeClock()
        val block = modeBlock("s1", BlockMode.SCHEDULE)
        var world = buildWorld(listOf(block), clock = clock)
        assertEquals(listOf(block), engine.blockersOf(world, "com.target", null, clock))

        world = engine.reduce(world, BlockEvent.BeginOverride(setOf("s1")), clock).world
        assertNotNull(world.pendingOverride)
        var out = claim(world, clock)
        assertEquals(OverrideRejection.TOO_EARLY, rejection(out))
        world = out.world

        clock.advance(Defaults.OVERRIDE_WAIT_MS)
        out = claim(world, clock, "I choose to stop")
        assertEquals(OverrideRejection.WRONG_PHRASE, rejection(out))
        out = claim(out.world, clock, "  i CHOOSE to keep   scrolling! ")
        assertNull(rejection(out))
        world = out.world
        assertFalse(engine.isBlocking(world, block, clock))
        assertNull(world.pendingOverride)
        assertEquals(Defaults.OVERRIDES_PER_DAY - 1, engine.overridesLeftToday(world, "s1", clock))

        clock.advance(Defaults.OVERRIDE_GRANT_MS)
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        assertTrue(engine.isBlocking(world, block, clock))
        assertTrue(world.overrides.isEmpty())

        // Waiting too long after the delay means starting again.
        world = engine.reduce(world, BlockEvent.BeginOverride(setOf("s1")), clock).world
        clock.advance(Defaults.OVERRIDE_WAIT_MS + Defaults.OVERRIDE_CLAIM_WINDOW_MS + 1)
        assertNull(engine.reduce(world, BlockEvent.Tick, clock).world.pendingOverride)
    }

    @Test
    fun i34_overrideLiftsLimitButNotABreak() {
        val clock = FakeClock()
        val block = rowBlock(dailyMs = 10L * 60_000, rowMs = 15L * 60_000, breakMs = 5L * 60_000)
        var world = buildWorld(listOf(block), clock = clock, foregroundPkg = "com.target")
        world = ticks(world, clock, 10)
        assertTrue(engine.dailyLimitReached(world, block, clock))
        assertNull(engine.overrideRejection(world, listOf(block), clock))

        world = engine.reduce(world, BlockEvent.BeginOverride(setOf("r1")), clock).world
        clock.advance(Defaults.OVERRIDE_WAIT_MS)
        world = claim(world, clock).world
        assertFalse(engine.isBlocking(world, block, clock))

        // The row rule still runs during bought minutes, and its break can't be bought off.
        world = ticks(world, clock, 6) // 15 in a row: on a 5-minute break
        assertTrue(engine.rowBreakRemainingMs(world, block, clock) > 0)
        assertTrue(engine.isBlocking(world, block, clock))
        assertEquals(OverrideRejection.NOT_OVERRIDABLE, engine.overrideRejection(world, listOf(block), clock))
        assertNull(engine.reduce(world, BlockEvent.BeginOverride(setOf("r1")), clock).world.pendingOverride)
    }

    @Test
    fun i34_overridesPerDayAreShared() {
        val block = modeBlock("s1", BlockMode.SCHEDULE).copy(targetSites = setOf("reddit.com"))
        val (phone, laptop, server) = twoDevices(listOf(block))
        phone.world = phone.world.copy(settings = phone.world.settings.copy(overridesPerDay = 1))
        phone.sync(server)
        laptop.sync(server)

        phone.on(BlockEvent.BeginOverride(setOf("s1")))
        advanceAll(Defaults.OVERRIDE_WAIT_MS, phone, laptop)
        phone.on(BlockEvent.ClaimOverride(Defaults.OVERRIDE_PHRASE))
        phone.sync(server)
        laptop.sync(server)

        assertFalse(engine.isBlocking(laptop.world, laptop.block("s1"), laptop.clock)) // the minutes open it here too
        assertEquals(OverrideRejection.NONE_LEFT, engine.overrideRejection(laptop.world, listOf(laptop.block("s1")), laptop.clock))
        advanceAll(Defaults.OVERRIDE_GRANT_MS, phone, laptop)
        laptop.on(BlockEvent.Tick)
        assertTrue(engine.isBlocking(laptop.world, laptop.block("s1"), laptop.clock))
    }

    @Test
    fun i39_lockMergesByWhenItWasSet() {
        val block = modeBlock("s1", BlockMode.SCHEDULE)
        val (phone, laptop, server) = twoDevices(listOf(block))
        phone.sync(server)
        laptop.sync(server)

        // The phone locks it; the laptop, not knowing, makes a later edit of its own.
        val now = phone.clock.nowWallMs()
        val lock = BlockLock(untilWall = now + 7 * DAY_MS, setAtWall = now)
        phone.world = phone.world.copy(blocks = listOf(phone.block("s1").copy(lock = lock, editedAtWall = now)))
        laptop.world = laptop.world.copy(blocks = listOf(laptop.block("s1").copy(targetSites = setOf("x.com"), editedAtWall = now + 1)))
        phone.sync(server)
        laptop.sync(server)
        phone.sync(server)

        for (device in listOf(phone, laptop)) {
            assertEquals(device.id, setOf("x.com"), device.block("s1").targetSites) // the later edit
            assertEquals(device.id, lock, device.block("s1").lock) // with the lock it never saw
        }

        // An emergency unlock is newer than the lock it ends, whichever edit wins.
        val ended = BlockEdits.emergencyUnlock(laptop.world, "s1", laptop.clock.nowWallMs() + 10)!!
        laptop.world = ended
        phone.world = phone.world.copy(blocks = listOf(phone.block("s1").copy(name = "Later", editedAtWall = now + 100)))
        laptop.sync(server)
        phone.sync(server)
        assertEquals("Later", phone.block("s1").name)
        assertFalse(BlockEdits.locked(phone.block("s1"), phone.clock.nowWallMs() + 20))
    }

    @Test
    fun i40_fiveEmergencyUnlocksInAll() {
        val clock = FakeClock()
        val now = clock.nowWallMs()
        val blocks = (1..6).map { modeBlock("b$it", BlockMode.SCHEDULE).copy(lock = BlockLock(now + DAY_MS, now)) }
        var world = buildWorld(blocks, clock = clock)
        assertNull("not locked: nothing to end", BlockEdits.emergencyUnlock(buildWorld(listOf(modeBlock("x", BlockMode.SCHEDULE)), clock = clock), "x", now))

        for (i in 1..5) {
            world = BlockEdits.emergencyUnlock(world, "b$i", now + i)!!
            assertFalse(BlockEdits.locked(world.block("b$i")!!, now + i))
            assertEquals(5 - i, BlockEdits.emergencyLeft(world))
        }
        assertNull("the sixth is refused", BlockEdits.emergencyUnlock(world, "b6", now + 6))
    }

    @Test
    fun i40_emergencyLogIsShared() {
        val clock = FakeClock()
        val now = clock.nowWallMs()
        val blocks = listOf("a", "b").map { modeBlock(it, BlockMode.SCHEDULE).copy(lock = BlockLock(now + DAY_MS, now)) }
        val (phone, laptop, server) = twoDevices(blocks)
        phone.sync(server)
        laptop.sync(server)

        // One each, before either hears of the other's: both count, everywhere.
        phone.world = BlockEdits.emergencyUnlock(phone.world, "a", phone.clock.nowWallMs())!!
        laptop.world = BlockEdits.emergencyUnlock(laptop.world, "b", laptop.clock.nowWallMs())!!
        phone.sync(server)
        laptop.sync(server)
        phone.sync(server)
        assertEquals(3, BlockEdits.emergencyLeft(phone.world))
        assertEquals(3, BlockEdits.emergencyLeft(laptop.world))
    }

    @Test
    fun i34_habitOrLockdownIsNotOverridable() {
        val clock = FakeClock()
        val schedule = modeBlock("s1", BlockMode.SCHEDULE)
        val habit = checklistBlock("h1")
        val world = buildWorld(listOf(schedule, habit), mapOf("h1" to items("h1", 1)), clock)
        val blockers = engine.blockersOf(world, "com.target", null, clock)
        assertEquals(setOf("s1", "h1"), blockers.map { it.id }.toSet())
        assertEquals(OverrideRejection.NOT_OVERRIDABLE, engine.overrideRejection(world, blockers, clock))

        val hard = schedule.copy(enforcementTier = EnforcementTier.HARDCORE)
        assertEquals(OverrideRejection.NOT_OVERRIDABLE, engine.overrideRejection(world, listOf(hard), clock))
        val lockdown = modeBlock("l1", BlockMode.LOCKDOWN, lockdownMs = 60L * 60_000)
        assertEquals(OverrideRejection.NOT_OVERRIDABLE, engine.overrideRejection(world, listOf(lockdown), clock))
        val off = world.copy(settings = world.settings.copy(overridesPerDay = 0))
        assertEquals(OverrideRejection.OFF, engine.overrideRejection(off, listOf(schedule), clock))

        assertTrue(BlockEdits.settingsLoosen(Settings(), Settings(overridesPerDay = 3)))
        assertTrue(BlockEdits.settingsLoosen(Settings(), Settings(overridePhrase = "ok")))
        assertFalse(BlockEdits.settingsLoosen(Settings(), Settings(overrideWaitMs = 90_000, overrideGrantMs = 5L * 60_000)))
    }

    @Test
    fun timeLeftShowsWhatRunsOutFirst() {
        val clock = FakeClock()
        val block = rowBlock(dailyMs = 30L * 60_000, rowMs = 15L * 60_000).copy(targetSites = setOf("youtube.com"))
        var world = buildWorld(listOf(block), clock = clock)
        world = engine.reduce(world, BlockEvent.ForegroundChanged("browser", "youtube.com"), clock).world
        world = ticks(world, clock, 10)

        assertEquals(TimeLeft(5L * 60_000, ClockKind.ROW, "r1"), engine.timeLeft(world, "browser", "youtube.com", clock))
        assertNull(engine.timeLeft(world, "browser", "example.com", clock))
    }

    // ---- habits (I23, I24, I27, I35-I38) -----------------------------------------------------

    private val minute = 60_000L
    private val day = 24L * 60 * minute

    private fun on(world: World, clock: Clock, event: BlockEvent): World = engine.reduce(world, event, clock).world

    private fun log(world: World, clock: Clock, habitId: String, amount: Long): World =
        on(world, clock, BlockEvent.LogHabit(habitId, amount))

    private fun set(world: World, clock: Clock, habitId: String, epochDay: Long, value: Long, notes: String = ""): World =
        on(world, clock, BlockEvent.SetHabitEntry(habitId, epochDay, value, notes))

    private fun habitWorld(clock: Clock, habits: List<Habit>, items: Map<String, List<ChecklistItem>>): World =
        buildWorld(items.keys.map { checklistBlock(it) }, items, clock).copy(habits = habits, deviceId = "here")

    private fun habitWorld(clock: Clock, habits: List<Habit>, vararg blocks: Pair<String, List<String>>): World =
        habitWorld(clock, habits, blocks.associate { (id, habitIds) -> id to habitIds.mapIndexed { i, h -> habitItem(id, h, i) } })

    private fun World.state(blockId: String): BlockState = block(blockId)!!.state

    private fun at(text: String): Long = Instant.parse(text).toEpochMilli()

    /** A phone and a laptop sharing blocks through one server and habits through the habit server. */
    private class HabitDevices(val phone: Device, val laptop: Device, val server: FakeServer, val habits: FakeHabitServer) {
        fun syncBlocks() {
            phone.sync(server)
            laptop.sync(server)
            phone.sync(server)
        }
    }

    private fun twoDevicesWithHabits(habits: List<Habit>, vararg blocks: Pair<String, List<String>>): HabitDevices {
        val habitServer = FakeHabitServer()
        habits.forEach { habitServer.put(HabitRecord(it.id, HabitRecords.data(it), BASE_WALL)) }
        val phoneClock = FakeClock(mono = 7_000_000)
        val phone = Device("phone", habitWorld(phoneClock, emptyList(), *blocks), phoneClock)
        val laptop = Device("laptop", World(), FakeClock(mono = 300_000))
        val devices = HabitDevices(phone, laptop, FakeServer(), habitServer)
        phone.syncHabits(habitServer)
        laptop.syncHabits(habitServer)
        devices.syncBlocks()
        return devices
    }

    @Test
    fun i23_manualToggleIgnoredOnHabitItem() {
        val clock = FakeClock()
        var world = habitWorld(clock, listOf(habit("push")), "c1" to listOf("push"))
        world = on(world, clock, BlockEvent.ToggleChecklistItem("c1-push", true))
        assertFalse(world.items("c1").single().done)
        assertEquals(BlockState.ARMED, world.state("c1"))
    }

    @Test
    fun i23_yesterdayItemCountsYesterdaysTick() {
        val clock = FakeClock()
        val today = clock.epochDay()
        val item = habitItem("c1", "journal", day = LinkedDay.YESTERDAY)
        var world = habitWorld(clock, listOf(habit("journal", HabitKind.CHECK)), mapOf("c1" to listOf(item)))
        world = log(world, clock, "journal", 1)
        assertEquals("today's tick isn't yesterday's", BlockState.ARMED, world.state("c1"))
        world = set(world, clock, "journal", today - 1, HabitEntry.YES_MANUAL)
        assertEquals(BlockState.EARNED, world.state("c1"))
    }

    @Test
    fun i23_goalItemCountsAutomaticTicks() {
        val clock = FakeClock()
        val today = clock.epochDay()
        val weekly = habit("gym", HabitKind.CHECK, freqNum = 1, freqDen = 7)
        var world = habitWorld(
            clock,
            listOf(weekly),
            mapOf("c1" to listOf(habitItem("c1", "gym", countsGoal = true)), "c2" to listOf(habitItem("c2", "gym"))),
        )
        world = set(world, clock, "gym", today - 3, HabitEntry.YES_MANUAL) // this week's one time
        assertEquals("the week's goal is met today too", BlockState.EARNED, world.state("c1"))
        assertEquals("without the goal, only a tick today counts", BlockState.ARMED, world.state("c2"))

        clock.advance(4 * day) // a week after the tick: a new week, not done yet
        world = on(world, clock, BlockEvent.Tick)
        assertEquals(BlockState.ARMED, world.state("c1"))
    }

    @Test
    fun i23_dayStartsAtTheTrackersHour() {
        val clock = FakeClock(wall = at("2026-01-16T01:00:00Z"))
        val date = clock.epochDay()
        var world = habitWorld(clock, listOf(habit("push")), "c1" to listOf("push")).copy(habitDayStartHour = 3)
        world = log(world, clock, "push", units(10))
        assertEquals("before 3 am it is still yesterday's habit day", units(10), world.entry("push", date - 1)?.value)
        assertNull(world.entry("push", date))
        assertEquals(BlockState.EARNED, world.state("c1"))
    }

    @Test
    fun i23_linkedItemsBecomeHabitItems() {
        val clock = FakeClock()
        val linked = linkedItem("c1", "meditate").copy(spentEpochDay = clock.epochDay())
        var world = habitWorld(clock, listOf(habit("meditate", HabitKind.CHECK)), mapOf("c1" to listOf(linked)))
        world = on(world, clock, BlockEvent.Tick)
        val item = world.items("c1").single()
        assertEquals("meditate", item.habitId)
        assertNull(item.externalHabitId)
        assertEquals("a spent Loop tick is one check spent", 1L, item.spentAmount)
        world = log(world, clock, "meditate", 1)
        assertEquals("today's tick already paid for an unlock", BlockState.ARMED, world.state("c1"))
    }

    @Test
    fun i24_checkOpensEachBlockOncePerDay() {
        val clock = FakeClock()
        var world = habitWorld(clock, listOf(habit("meditate", HabitKind.CHECK)), "c1" to listOf("meditate"), "c2" to listOf("meditate"))
        world = log(world, clock, "meditate", 1)
        assertEquals(BlockState.EARNED, world.state("c1"))
        assertEquals("one habit opens every block that asks for it", BlockState.EARNED, world.state("c2"))

        world = on(world, clock, BlockEvent.Redeem("c1"))
        assertEquals(clock.epochDay(), world.items("c1").single().spentEpochDay)
        clock.advance(5 * minute)
        world = on(world, clock, BlockEvent.Tick)
        assertEquals(BlockState.ARMED, world.state("c1"))
        world = log(world, clock, "meditate", 1)
        assertEquals("a check can't be done twice in a day", BlockState.ARMED, world.state("c1"))
        assertEquals(BlockState.EARNED, world.state("c2"))
    }

    @Test
    fun i24_expiredCreditDoesNotSpend() {
        val clock = FakeClock(wall = at("2026-01-15T08:00:00Z"))
        var world = habitWorld(clock, listOf(habit("meditate", HabitKind.CHECK)), "c1" to listOf("meditate"))
        world = log(world, clock, "meditate", 1)
        assertEquals(BlockState.EARNED, world.state("c1"))
        clock.advance(Defaults.CREDIT_TTL_MS + 1)
        world = on(world, clock, BlockEvent.Tick)
        assertNull(world.items("c1").single().spentEpochDay)
        assertEquals("the tick still pays for an unlock", BlockState.EARNED, world.state("c1"))
    }

    @Test
    fun i24_nextDayCanEarnAgain() {
        val clock = FakeClock()
        var world = habitWorld(clock, listOf(habit("meditate", HabitKind.CHECK)), "c1" to listOf("meditate"))
        world = log(world, clock, "meditate", 1)
        world = on(world, clock, BlockEvent.Redeem("c1"))
        clock.advance(day)
        world = on(world, clock, BlockEvent.Tick)
        assertEquals("not ticked today yet", BlockState.ARMED, world.state("c1"))
        world = log(world, clock, "meditate", 1)
        assertEquals(BlockState.EARNED, world.state("c1"))
    }

    @Test
    fun i24_eveningTickDoesNotPayAgainAfterMidnight() {
        val clock = FakeClock(wall = at("2026-01-15T23:00:00Z"))
        var world = habitWorld(clock, listOf(habit("meditate", HabitKind.CHECK)), "c1" to listOf("meditate")).copy(habitDayStartHour = 3)
        world = log(world, clock, "meditate", 1)
        world = on(world, clock, BlockEvent.Redeem("c1"))
        clock.advance(2 * 60 * minute) // 01:00: a new date, the same habit day
        world = on(world, clock, BlockEvent.Tick)
        assertEquals(BlockState.ARMED, world.state("c1"))

        clock.advance(3 * 60 * minute) // 04:00: a new habit day
        world = on(world, clock, BlockEvent.Tick)
        assertEquals(BlockState.ARMED, world.state("c1"))
        world = log(world, clock, "meditate", 1)
        assertEquals(BlockState.EARNED, world.state("c1"))
    }

    @Test
    fun i24_yesterdayItemSpendsYesterdaysTick() {
        val clock = FakeClock()
        val today = clock.epochDay()
        val item = habitItem("c1", "journal", day = LinkedDay.YESTERDAY)
        var world = habitWorld(clock, listOf(habit("journal", HabitKind.CHECK)), mapOf("c1" to listOf(item)))
        world = set(world, clock, "journal", today - 1, HabitEntry.YES_MANUAL)
        world = on(world, clock, BlockEvent.Redeem("c1"))
        assertEquals(today - 1, world.items("c1").single().spentEpochDay)

        clock.advance(day) // tomorrow: "yesterday" is the day just gone
        world = on(world, clock, BlockEvent.Tick)
        assertEquals(BlockState.ARMED, world.state("c1"))
        world = set(world, clock, "journal", today, HabitEntry.YES_MANUAL)
        assertEquals(BlockState.EARNED, world.state("c1"))
    }

    @Test
    fun i24_redeemSpendsOneUnitAndKeepsTheRest() {
        val clock = FakeClock()
        var world = habitWorld(clock, listOf(habit("push", goal = 10)), "c1" to listOf("push"))
        world = log(world, clock, "push", units(25))
        world = on(world, clock, BlockEvent.Redeem("c1"))
        val item = world.items("c1").single()
        assertEquals(clock.epochDay(), item.spentEpochDay)
        assertEquals(units(10), item.spentAmount)

        clock.advance(5 * minute) // the unlock runs out; 15 left is another unlock's worth
        world = on(world, clock, BlockEvent.Tick)
        assertEquals(BlockState.EARNED, world.state("c1"))
        world = on(world, clock, BlockEvent.Redeem("c1"))
        assertEquals(units(20), world.items("c1").single().spentAmount)

        clock.advance(5 * minute) // 5 left: not enough
        world = on(world, clock, BlockEvent.Tick)
        assertEquals(BlockState.ARMED, world.state("c1"))
        assertEquals(units(5), engine.habitAvailable(world, world.items("c1").single(), clock))
        world = log(world, clock, "push", units(5))
        assertEquals(BlockState.EARNED, world.state("c1"))
    }

    @Test
    fun i27_adoptedUnlockSpendsHereWhatTheRedeemerDidNot() {
        val d = twoDevicesWithHabits(listOf(habit("meditate", HabitKind.CHECK)), "c1" to listOf("meditate"))
        // The phone ticks and earns; the laptop hasn't had the tick from the habit server yet,
        // so it redeems the phone's credit and spends nothing of its own.
        d.phone.on(BlockEvent.LogHabit("meditate", 1))
        d.syncBlocks()
        d.laptop.on(BlockEvent.Redeem("c1"))
        assertNull(d.laptop.world.items("c1").single().spentEpochDay)
        d.syncBlocks()

        assertEquals(BlockState.UNLOCKED, d.phone.block("c1").state)
        assertEquals("the phone's tick pays for it", 1L, d.phone.world.items("c1").single().spentAmount)
        advanceAll(5 * minute, d.phone, d.laptop)
        d.phone.on(BlockEvent.Tick)
        assertEquals("one tick, one unlock", BlockState.ARMED, d.phone.block("c1").state)
        d.syncBlocks()
        assertEquals(1L, d.laptop.world.items("c1").single().spentAmount)
    }

    @Test
    fun i27_habitItemDoneIsNeverShared() {
        val d = twoDevicesWithHabits(listOf(habit("meditate", HabitKind.CHECK)), "c1" to listOf("meditate"))
        d.phone.on(BlockEvent.LogHabit("meditate", 1))
        assertTrue(d.phone.world.items("c1").single().done)
        d.syncBlocks()

        assertEquals(BlockState.EARNED, d.laptop.block("c1").state) // the credit is shared
        assertFalse("the tick is the laptop's own to work out", d.laptop.world.items("c1").single().done)
        assertFalse(SharedState.of(d.phone.world).items.getValue("c1-meditate").done)
    }

    @Test
    fun i35_logAddsToTodaysEntry() {
        val clock = FakeClock()
        var world = habitWorld(clock, listOf(habit("push", goal = 30)))
        world = log(world, clock, "push", units(12))
        world = log(world, clock, "push", units(10))
        assertEquals(units(22), world.entry("push", clock.epochDay())?.value)
        world = log(world, clock, "nope", units(5))
        assertEquals("an unknown habit logs nothing", 1, world.habitEntries.size)
    }

    @Test
    fun i35_checkLogsYesOrNo() {
        val clock = FakeClock()
        val today = clock.epochDay()
        var world = habitWorld(clock, listOf(habit("meditate", HabitKind.CHECK)))
        world = log(world, clock, "meditate", 1)
        world = log(world, clock, "meditate", 1)
        assertEquals(HabitEntry.YES_MANUAL, world.entry("meditate", today)?.value)
        world = log(world, clock, "meditate", -1)
        assertEquals(HabitEntry.NO, world.entry("meditate", today)?.value)
    }

    @Test
    fun i35_undoNeverGoesBelowZero() {
        val clock = FakeClock()
        var world = habitWorld(clock, listOf(habit("push")))
        world = log(world, clock, "push", units(5))
        world = log(world, clock, "push", -units(10))
        assertEquals(0L, world.entry("push", clock.epochDay())?.value)
    }

    @Test
    fun i35_setEntryEditsAPastDay() {
        val clock = FakeClock()
        val today = clock.epochDay()
        var world = habitWorld(clock, listOf(habit("push"), habit("meditate", HabitKind.CHECK)))
        world = set(world, clock, "push", today - 10, units(7), "sore")
        assertEquals(HabitEntry(units(7), "sore", clock.nowWallMs(), dirty = true), world.entry("push", today - 10))
        world = set(world, clock, "push", today + 1, units(7))
        assertNull("not a day that hasn't come", world.entry("push", today + 1))
        world = set(world, clock, "push", today - 1, -5)
        assertNull("not below zero", world.entry("push", today - 1))

        world = set(world, clock, "meditate", today - 2, HabitEntry.SKIP)
        world = set(world, clock, "meditate", today - 2, 7)
        assertEquals("a check takes Loop's values only", HabitEntry.SKIP, world.entry("meditate", today - 2)?.value)
    }

    @Test
    fun i35_writesAreMarkedForSync() {
        val clock = FakeClock()
        val today = clock.epochDay()
        val fromServer = HabitEntry(units(5), "", mtime = 1, seq = 4)
        var world = habitWorld(clock, listOf(habit("push"))).copy(habitEntries = mapOf(HabitEntry.key("push", today) to fromServer))
        clock.advance(minute)
        world = log(world, clock, "push", units(3))
        assertEquals(HabitEntry(units(8), "", clock.nowWallMs(), seq = 4, dirty = true, baseValue = units(5)), world.entry("push", today))
        world = log(world, clock, "push", units(2))
        assertEquals("still based on the server's", units(5), world.entry("push", today)!!.baseValue)
        assertEquals(units(10), world.entry("push", today)!!.value)
    }

    @Test
    fun i36_habitItemEarnsWhenGoalMet() {
        val clock = FakeClock()
        var world = habitWorld(clock, listOf(habit("push", goal = 20)), "c1" to listOf("push"))
        world = log(world, clock, "push", units(15))
        assertFalse(world.items("c1").single().done)
        assertEquals(BlockState.ARMED, world.state("c1"))
        world = log(world, clock, "push", units(5))
        assertTrue(world.items("c1").single().done)
        assertEquals(BlockState.EARNED, world.state("c1"))
    }

    @Test
    fun i36_weeklyGoalOpensPerDaysShare() {
        val clock = FakeClock()
        val weekly = habit("pages", goal = 70, freqDen = 7)
        assertEquals(units(10), weekly.unlockUnit)
        var world = habitWorld(clock, listOf(weekly), "c1" to listOf("pages"))
        world = log(world, clock, "pages", units(9))
        assertEquals(BlockState.ARMED, world.state("c1"))
        world = log(world, clock, "pages", units(1))
        assertEquals(BlockState.EARNED, world.state("c1"))
    }

    @Test
    fun i36_atMostHabitNeverOpens() {
        val clock = FakeClock()
        val coffee = habit("coffee", goal = 2, targetType = TargetType.AT_MOST)
        assertNull(coffee.unlockUnit)
        var world = habitWorld(clock, listOf(coffee), "c1" to listOf("coffee"))
        world = log(world, clock, "coffee", units(1))
        world = log(world, clock, "coffee", units(5))
        assertEquals(BlockState.ARMED, world.state("c1"))
    }

    @Test
    fun i36_deletedHabitKeepsTheBlockClosed() {
        val clock = FakeClock()
        var world = habitWorld(clock, listOf(habit("push")), "c1" to listOf("push"))
        world = world.withoutHabit("push", clock.nowWallMs())
            .copy(habitEntries = mapOf(HabitEntry.key("push", clock.epochDay()) to HabitEntry(units(50))))
        world = on(world, clock, BlockEvent.Tick)
        assertEquals("push", world.items("c1").single().habitId)
        assertEquals(BlockState.ARMED, world.state("c1"))
    }

    @Test
    fun i36_adoptedUnlockDoesNotSpendAgain() {
        val d = twoDevicesWithHabits(listOf(habit("push", goal = 10)), "c1" to listOf("push"))
        d.phone.on(BlockEvent.LogHabit("push", units(10)))
        d.phone.syncHabits(d.habits)
        d.laptop.syncHabits(d.habits)
        assertEquals("the laptop works it out from the entry", BlockState.EARNED, d.laptop.block("c1").state)
        d.laptop.on(BlockEvent.Redeem("c1"))
        d.syncBlocks()
        assertEquals(BlockState.UNLOCKED, d.phone.block("c1").state)
        assertEquals("spent once, by the device that redeemed", units(10), d.phone.world.items("c1").single().spentAmount)
    }

    @Test
    fun i36_otherDevicesWritesCount() {
        val d = twoDevicesWithHabits(listOf(habit("push", goal = 20)), "c1" to listOf("push"))
        d.phone.on(BlockEvent.LogHabit("push", units(8)))
        d.laptop.on(BlockEvent.LogHabit("push", units(12)))
        assertEquals(BlockState.ARMED, d.phone.block("c1").state)
        d.laptop.syncHabits(d.habits)
        d.phone.syncHabits(d.habits)
        assertEquals(BlockState.EARNED, d.phone.block("c1").state)
        assertEquals(units(20), d.habits.entries.values.single().value)
    }

    @Test
    fun i36_untickAfterEarnTakesTheEarnBack() {
        val clock = FakeClock()
        val today = clock.epochDay()
        var world = habitWorld(clock, listOf(habit("plank", HabitKind.CHECK)), "c1" to listOf("plank"))
        world = set(world, clock, "plank", today, HabitEntry.YES_MANUAL)
        assertEquals(BlockState.EARNED, world.state("c1"))

        // Unticked before the unlock is used: no unlock for nothing, and no second one later.
        world = set(world, clock, "plank", today, HabitEntry.NO)
        assertEquals(BlockState.ARMED, world.state("c1"))
        assertNull(world.credits["c1"])
        assertFalse(world.items("c1").single().done)
        assertEquals(BlockState.ARMED, on(world, clock, BlockEvent.Redeem("c1")).state("c1"))

        // Ticked again: earned again, and the unlock spends that tick.
        world = set(world, clock, "plank", today, HabitEntry.YES_MANUAL)
        world = on(world, clock, BlockEvent.Redeem("c1"))
        assertEquals(BlockState.UNLOCKED, world.state("c1"))
        assertEquals(today, world.items("c1").single().spentEpochDay)
    }

    @Test
    fun i36_untickDoesNotTakeBackWhatOtherItemsStillEarn() {
        val clock = FakeClock()
        val today = clock.epochDay()
        val items = listOf(habitItem("c1", "plank", 0), habitItem("c1", "read", 1))
        var world = buildWorld(listOf(checklistBlock("c1", required = 1)), mapOf("c1" to items), clock)
            .copy(habits = listOf(habit("plank", HabitKind.CHECK), habit("read", HabitKind.CHECK)), deviceId = "here")
        world = set(world, clock, "plank", today, HabitEntry.YES_MANUAL)
        world = set(world, clock, "read", today, HabitEntry.YES_MANUAL)
        assertEquals(BlockState.EARNED, world.state("c1"))
        world = set(world, clock, "plank", today, HabitEntry.NO)
        assertEquals("one of two is still enough", BlockState.EARNED, world.state("c1"))
        world = set(world, clock, "read", today, HabitEntry.NO)
        assertEquals(BlockState.ARMED, world.state("c1"))
    }

    @Test
    fun i36_adoptedEarnIsNotTakenBackBeforeTheEntriesArrive() {
        val d = twoDevicesWithHabits(listOf(habit("plank", HabitKind.CHECK)), "c1" to listOf("plank"))
        val day = d.laptop.world.habitDay(d.laptop.clock)
        d.laptop.on(BlockEvent.SetHabitEntry("plank", day, HabitEntry.YES_MANUAL))
        assertEquals(BlockState.EARNED, d.laptop.block("c1").state)
        d.syncBlocks() // the phone hears of the earn before the tick
        assertEquals(BlockState.EARNED, d.phone.block("c1").state)
        d.phone.on(BlockEvent.Tick)
        assertEquals("not seen here yet, not undone here", BlockState.EARNED, d.phone.block("c1").state)

        // Unticked on the laptop: the earn goes there, and the phone follows.
        d.laptop.on(BlockEvent.SetHabitEntry("plank", day, HabitEntry.NO))
        assertEquals(BlockState.ARMED, d.laptop.block("c1").state)
        d.laptop.syncHabits(d.habits)
        d.phone.syncHabits(d.habits)
        d.syncBlocks()
        assertEquals(BlockState.ARMED, d.phone.block("c1").state)
        assertEquals(BlockState.ARMED, d.laptop.block("c1").state)
    }

    @Test
    fun i37_focusCountsMonotonicAndFoldsOnPause() {
        val clock = FakeClock()
        val today = clock.epochDay()
        var world = habitWorld(clock, listOf(habit("study", HabitKind.TIMER, 30)))
        world = on(world, clock, BlockEvent.StartFocus("study"))
        clock.advance(10 * minute)
        clock.advanceWallOnly(60 * minute) // moving the clock forward doesn't count
        assertEquals(units(10), engine.habitAmount(world, "study", today, clock))
        assertNull("nothing written while it runs", world.entry("study", today))

        world = on(world, clock, BlockEvent.PauseFocus())
        assertFalse(world.focus!!.running)
        assertEquals(units(10), world.entry("study", today)?.value)
        clock.advance(10 * minute)
        assertEquals(units(10), engine.habitAmount(world, "study", today, clock))

        world = on(world, clock, BlockEvent.StartFocus("study"))
        clock.advance(5 * minute)
        world = on(world, clock, BlockEvent.EndFocus)
        assertNull(world.focus)
        assertEquals(units(15), world.entry("study", today)?.value)
    }

    @Test
    fun i37_focusEarnsWhileRunning() {
        val clock = FakeClock()
        var world = habitWorld(clock, listOf(habit("study", HabitKind.TIMER, 30)), "c1" to listOf("study"))
        world = on(world, clock, BlockEvent.StartFocus("study"))
        clock.advance(29 * minute)
        world = on(world, clock, BlockEvent.Tick)
        assertEquals(BlockState.ARMED, world.state("c1"))
        clock.advance(minute)
        world = on(world, clock, BlockEvent.Tick)
        assertEquals(BlockState.EARNED, world.state("c1"))
        assertTrue("the timer keeps going past the goal", world.focus!!.running)
    }

    @Test
    fun i37_breakResumesByItself() {
        val clock = FakeClock()
        val today = clock.epochDay()
        var world = habitWorld(clock, listOf(habit("study", HabitKind.TIMER, 30)))
        world = on(world, clock, BlockEvent.StartFocus("study"))
        clock.advance(5 * minute)
        world = on(world, clock, BlockEvent.PauseFocus(Defaults.FOCUS_BREAK_MS))
        clock.advance(4 * minute)
        world = on(world, clock, BlockEvent.Tick)
        assertFalse(world.focus!!.running)
        clock.advance(minute)
        world = on(world, clock, BlockEvent.Tick)
        assertTrue(world.focus!!.running)
        clock.advance(3 * minute)
        assertEquals(units(8), engine.habitAmount(world, "study", today, clock))
    }

    @Test
    fun i37_focusSurvivesReboot() {
        val clock = FakeClock(mono = 50 * minute)
        var world = habitWorld(clock, listOf(habit("study", HabitKind.TIMER, 30)))
        world = on(world, clock, BlockEvent.StartFocus("study"))
        clock.advance(10 * minute)
        clock.setMono(1_000) // reboot: the monotonic clock starts over
        clock.advanceWallOnly(2 * minute)
        world = on(world, clock, BlockEvent.BootOrColdStart)
        assertEquals(units(12), engine.habitAmount(world, "study", clock.epochDay(), clock))
    }

    @Test
    fun i37_focusAcrossMidnightFoldsIntoItsDay() {
        val clock = FakeClock(wall = at("2026-01-15T23:50:00Z"))
        val firstDay = clock.epochDay()
        var world = habitWorld(clock, listOf(habit("study", HabitKind.TIMER, 30)))
        world = on(world, clock, BlockEvent.StartFocus("study"))
        clock.advance(20 * minute) // 00:10
        world = on(world, clock, BlockEvent.Tick)
        assertEquals(units(20), world.entry("study", firstDay)?.value)
        clock.advance(5 * minute)
        assertEquals(units(5), engine.habitAmount(world, "study", firstDay + 1, clock))
    }

    @Test
    fun i37_onlyTimerHabitsFocus() {
        val clock = FakeClock()
        var world = habitWorld(clock, listOf(habit("push"), habit("study", HabitKind.TIMER, 30)))
        world = on(world, clock, BlockEvent.StartFocus("push"))
        assertNull(world.focus)
        world = on(world, clock, BlockEvent.StartFocus("study"))
        assertEquals("study", world.focus!!.habitId)
        world = world.copy(habits = world.habits.filterNot { it.id == "study" }) // deleted elsewhere
        world = on(world, clock, BlockEvent.Tick)
        assertNull(world.focus)
    }

    @Test
    fun i38_serverRecordsAreTakenWhenNothingChangedHere() {
        val clock = FakeClock()
        val today = clock.epochDay()
        val server = FakeHabitServer()
        server.put(HabitRecord("push", HabitRecords.data(habit("push", goal = 20)), BASE_WALL))
        server.put(EntryRecord("push", HabitRecords.date(today), units(5), "", BASE_WALL))
        val phone = Device("phone", World(), clock)
        phone.syncHabits(server)
        assertEquals(units(20), phone.world.habit("push")!!.goal)
        assertEquals(HabitEntry(units(5), "", BASE_WALL, seq = 2), phone.world.entry("push", today))
        assertEquals(2L, phone.world.habitSyncSeq)

        server.put(EntryRecord("push", HabitRecords.date(today), units(9), "", BASE_WALL + 1)) // the web app
        phone.syncHabits(server)
        assertEquals(HabitEntry(units(9), "", BASE_WALL + 1, seq = 3), phone.world.entry("push", today))
    }

    @Test
    fun i38_amountsAddedOnTwoDevicesAddUp() {
        val d = twoDevicesWithHabits(listOf(habit("push", goal = 50)))
        val today = d.phone.clock.epochDay()
        d.phone.on(BlockEvent.LogHabit("push", units(5)))
        d.phone.syncHabits(d.habits)
        d.laptop.syncHabits(d.habits)
        // Both add while apart.
        d.phone.on(BlockEvent.LogHabit("push", units(3)))
        d.laptop.on(BlockEvent.LogHabit("push", units(4)))
        d.phone.syncHabits(d.habits)
        d.laptop.syncHabits(d.habits)
        d.phone.syncHabits(d.habits)
        assertEquals(units(12), d.phone.world.entry("push", today)?.value)
        assertEquals(units(12), d.laptop.world.entry("push", today)?.value)
        assertFalse(d.phone.world.entry("push", today)!!.dirty || d.laptop.world.entry("push", today)!!.dirty)
    }

    @Test
    fun i38_laterCheckWins() {
        val d = twoDevicesWithHabits(listOf(habit("meditate", HabitKind.CHECK)))
        val today = d.phone.clock.epochDay()
        d.phone.on(BlockEvent.LogHabit("meditate", 1))
        advanceAll(minute, d.phone, d.laptop)
        d.laptop.on(BlockEvent.LogHabit("meditate", -1)) // a minute later, on the laptop
        d.phone.syncHabits(d.habits)
        d.laptop.syncHabits(d.habits)
        d.phone.syncHabits(d.habits)
        assertEquals(HabitEntry.NO, d.phone.world.entry("meditate", today)?.value)
        assertEquals(HabitEntry.NO, d.laptop.world.entry("meditate", today)?.value)

        d.laptop.on(BlockEvent.LogHabit("meditate", 1))
        advanceAll(minute, d.phone, d.laptop)
        d.phone.on(BlockEvent.SetHabitEntry("meditate", today, HabitEntry.SKIP)) // later again, now on the phone
        d.phone.syncHabits(d.habits)
        d.laptop.syncHabits(d.habits) // the laptop's earlier tick loses
        assertEquals(HabitEntry.SKIP, d.laptop.world.entry("meditate", today)?.value)
        assertFalse(d.laptop.world.entry("meditate", today)!!.dirty)
    }

    @Test
    fun i38_pushCleansOnlyWhatWasSent() {
        val clock = FakeClock()
        val today = clock.epochDay()
        val server = FakeHabitServer()
        server.put(HabitRecord("push", HabitRecords.data(habit("push")), BASE_WALL))
        val phone = Device("phone", World(), clock)
        phone.syncHabits(server)
        phone.on(BlockEvent.LogHabit("push", units(3)))
        val (habits, entries, sent) = HabitRecords.pending(phone.world)
        phone.on(BlockEvent.LogHabit("push", units(2))) // while the push is on its way
        val result = server.write(HabitWrite(habits, entries))
        phone.on(BlockEvent.HabitServerPushed(sent, result.acceptance()))
        val entry = phone.world.entry("push", today)!!
        assertEquals(units(5), entry.value)
        assertTrue("the last 2 still have to go", entry.dirty)
        assertEquals(units(3), entry.baseValue)

        phone.syncHabits(server)
        assertEquals(units(5), server.entries.values.single().value)
        assertFalse(phone.world.entry("push", today)!!.dirty)
    }

    @Test
    fun i38_rejectedWriteIsRebased() {
        val d = twoDevicesWithHabits(listOf(habit("push", goal = 50)))
        val today = d.phone.clock.epochDay()
        d.phone.on(BlockEvent.LogHabit("push", units(5)))
        d.phone.syncHabits(d.habits)
        d.laptop.syncHabits(d.habits)
        d.phone.on(BlockEvent.LogHabit("push", units(3)))
        d.phone.syncHabits(d.habits) // the server has 8 now
        d.laptop.on(BlockEvent.LogHabit("push", units(4)))

        val (habits, entries, sent) = HabitRecords.pending(d.laptop.world) // sent without reading first
        val result = d.habits.write(HabitWrite(habits, entries))
        assertEquals(1, result.rejected.entries.size)
        d.laptop.on(BlockEvent.HabitServerPushed(sent, result.acceptance(), result.rejected.habits, result.rejected.entries))
        assertEquals(units(12), d.laptop.world.entry("push", today)?.value)
        assertTrue(d.laptop.world.entry("push", today)!!.dirty)

        d.laptop.syncHabits(d.habits)
        assertEquals(units(12), d.habits.entries.values.single().value)
    }

    @Test
    fun i38_editBeatsDelete() {
        val d = twoDevicesWithHabits(listOf(habit("push"), habit("read")))
        // Deleted on the phone, then edited on the laptop, which syncs first.
        d.phone.world = d.phone.world.withoutHabit("push", d.phone.clock.nowWallMs())
        advanceAll(minute, d.phone, d.laptop)
        d.laptop.world = d.laptop.world.withHabit(d.laptop.world.habit("push")!!.copy(name = "Push-ups"), d.laptop.clock.nowWallMs())
        d.laptop.syncHabits(d.habits)
        d.phone.syncHabits(d.habits)
        assertEquals("Push-ups", d.phone.world.habit("push")?.name)
        assertFalse(d.habits.habits.getValue("push").deleted)

        // Deleted on the phone, which syncs first; edited later on the laptop.
        d.phone.world = d.phone.world.withoutHabit("read", d.phone.clock.nowWallMs())
        d.phone.syncHabits(d.habits)
        assertTrue(d.habits.habits.getValue("read").deleted)
        advanceAll(minute, d.phone, d.laptop)
        d.laptop.world = d.laptop.world.withHabit(d.laptop.world.habit("read")!!.copy(name = "Read more"), d.laptop.clock.nowWallMs())
        d.laptop.syncHabits(d.habits)
        d.phone.syncHabits(d.habits)
        assertFalse(d.habits.habits.getValue("read").deleted)
        assertEquals("Read more", d.phone.world.habit("read")?.name)

        // Deleted with nothing edited since: gone everywhere.
        d.phone.world = d.phone.world.withoutHabit("read", d.phone.clock.nowWallMs())
        d.phone.syncHabits(d.habits)
        d.laptop.syncHabits(d.habits)
        assertNull(d.laptop.world.habit("read"))
        assertTrue(d.phone.world.habitTombstones.isEmpty())
    }

    @Test
    fun i38_restoredServerGetsBackWhatItLost() {
        val d = twoDevicesWithHabits(listOf(habit("push", goal = 50), habit("meditate", HabitKind.CHECK), habit("old")))
        val today = d.phone.clock.epochDay()
        d.phone.on(BlockEvent.LogHabit("push", units(5)))
        d.phone.syncHabits(d.habits)
        d.laptop.syncHabits(d.habits)
        val backup = d.habits.backup()

        // After the backup: more push-ups, a tick, a new habit, a deleted one. Both devices see them.
        // (A delete the server took is forgotten here, so the restore brings "old" back: §2.5.)
        advanceAll(minute, d.phone, d.laptop)
        d.phone.on(BlockEvent.LogHabit("push", units(3)))
        d.phone.on(BlockEvent.LogHabit("meditate", 1))
        d.phone.world = d.phone.world.withHabit(habit("stretch"), d.phone.clock.nowWallMs())
        d.phone.world = d.phone.world.withoutHabit("old", d.phone.clock.nowWallMs())
        d.phone.syncHabits(d.habits)
        d.laptop.syncHabits(d.habits)
        assertNull(d.laptop.world.habit("old"))

        // The server is put back from its backup, and the web app ticks something there meanwhile.
        d.habits.restore(backup)
        advanceAll(minute, d.phone, d.laptop)
        d.habits.put(EntryRecord("push", HabitRecords.date(today - 1), units(10), "", d.phone.clock.nowWallMs()))
        assertTrue(d.habits.seq < d.laptop.world.habitSyncSeq)

        d.laptop.syncHabits(d.habits)
        d.phone.syncHabits(d.habits)
        d.laptop.syncHabits(d.habits)
        for (device in listOf(d.phone, d.laptop)) {
            assertEquals(units(8), device.world.entry("push", today)?.value)
            assertEquals(HabitEntry.YES_MANUAL, device.world.entry("meditate", today)?.value)
            assertEquals(units(10), device.world.entry("push", today - 1)?.value) // written after the restore
            assertNotNull(device.world.habit("stretch"))
            assertNotNull(device.world.habit("old"))
            assertTrue(device.world.habitEntries.values.none { it.dirty })
            assertEquals(d.habits.seq, device.world.habitSyncSeq)
        }
        assertEquals(units(8), d.habits.entries.getValue("push@${HabitRecords.date(today)}").value)
        assertNotNull(d.habits.habits["stretch"])
    }

    @Test
    fun i38_theEpochIsKeptEvenWhenNothingElseIsNew() {
        val d = twoDevicesWithHabits(listOf(habit("push")))
        // A device that synced before the server had epochs: nothing new, but the epoch is.
        d.phone.world = d.phone.world.copy(habitServerEpoch = null)
        d.phone.syncHabits(d.habits)
        assertEquals(d.habits.epoch, d.phone.world.habitServerEpoch)
        assertTrue(HabitRecords.nothingNew(d.phone.world, d.habits.changes(d.phone.world.habitSyncSeq)))
    }

    @Test
    fun i38_dayStartHourComesFromTheServer() {
        val clock = FakeClock(wall = at("2026-01-16T01:00:00Z"))
        val phone = Device("phone", World(), clock)
        phone.syncHabits(FakeHabitServer(dayStartHour = 3))
        assertEquals(3, phone.world.habitDayStartHour)
        assertEquals(clock.epochDay() - 1, phone.world.habitDay(clock))
    }

    @Test
    fun i09_openUnlocksCountTowardCap() {
        val clock = FakeClock()
        val blocks = listOf("a", "b", "c").map { checklistBlock(it, target = "com.$it", unlockMs = 30L * 60_000) }
        var world = buildWorld(blocks, blocks.associate { it.id to items(it.id, 1) }, clock = clock)
        for (id in listOf("a", "b", "c")) world = engine.reduce(world, BlockEvent.ToggleChecklistItem("$id-i0", true), clock).world
        world = engine.reduce(world, BlockEvent.Redeem("a"), clock).world
        world = engine.reduce(world, BlockEvent.Redeem("b"), clock).world
        assertEquals(60L * 60_000, engine.unlockCommittedMs(world, clock))

        val third = engine.reduce(world, BlockEvent.Redeem("c"), clock)
        assertEquals("two open 30-minute unlocks fill a 60-minute day", BlockState.EARNED, third.world.block("c")!!.state)
        assertEquals(RejectReason.CAP_REACHED, third.effects.filterIsInstance<Effect.RedeemRejected>().single().reason)

        clock.advance(30L * 60_000) // both run out: 60 minutes used, still nothing left
        world = engine.reduce(third.world, BlockEvent.Tick, clock).world
        assertEquals(60L * 60_000, engine.unlockCommittedMs(world, clock))
        assertEquals(RejectReason.CAP_REACHED, engine.redeemRejection(world, "c", clock))
    }

    // ---- I41: self-paced unlocks ---------------------------------------------------------

    private fun selfPacedWorld(clock: FakeClock): World =
        buildWorld(
            listOf(checklistBlock("c1", unlockMs = 5L * 60_000).copy(selfPaced = true)),
            mapOf("c1" to items("c1", 1)),
            clock = clock,
        )

    private fun World.redeemed(clock: FakeClock): World {
        var w = engine.reduce(this, BlockEvent.ToggleChecklistItem("c1-i0", true), clock).world
        w = engine.reduce(w, BlockEvent.Redeem("c1"), clock).world
        assertEquals(BlockState.UNLOCKED, w.block("c1")!!.state)
        return w
    }

    @Test
    fun i41_selfPacedCountsOnlyUse() {
        val clock = FakeClock()
        var world = selfPacedWorld(clock).redeemed(clock)
        assertTrue(world.sessions["c1"]!!.selfPaced)

        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.other"), clock).world
        clock.advance(2L * 60 * 60_000) // two hours elsewhere
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        assertEquals(BlockState.UNLOCKED, world.block("c1")!!.state)
        assertEquals(0L, world.sessions["c1"]!!.totalUsedMs)

        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.target"), clock).world
        clock.advance(3L * 60_000)
        world = engine.reduce(world, BlockEvent.ForegroundChanged(null), clock).world // screen off
        clock.advance(60L * 60_000)
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        assertEquals(BlockState.UNLOCKED, world.block("c1")!!.state)
        assertEquals(3L * 60_000, world.sessions["c1"]!!.totalUsedMs)
        assertEquals(2L * 60_000, engine.timeLeft(world, "com.target", null, clock)!!.ms)
    }

    @Test
    fun i41_selfPacedEndsWhenUsedUp() {
        val clock = FakeClock()
        var world = selfPacedWorld(clock).redeemed(clock)
        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.target"), clock).world

        clock.advance(4L * 60_000 + 40_000)
        val warned = engine.reduce(world, BlockEvent.Tick, clock)
        assertTrue(warned.effects.any { it is Effect.UnlockEndingSoon })
        world = warned.world

        clock.advance(20_000)
        val out = engine.reduce(world, BlockEvent.Tick, clock)
        assertEquals(BlockState.ARMED, out.world.block("c1")!!.state)
        assertTrue(out.shieldPlan!!.blockedPackages.contains("com.target"))
        assertEquals(5L * 60_000, out.effects.filterIsInstance<Effect.UnlockConsumed>().single().ms)
        assertEquals(5L * 60_000, engine.unlockUsedTodayMs(out.world, clock)) // I28
    }

    @Test
    fun i41_selfPacedEndsWithTheDay() {
        val clock = FakeClock() // 12:00 UTC
        var world = selfPacedWorld(clock).redeemed(clock)
        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.target"), clock).world
        clock.advance(60_000)
        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.other"), clock).world

        clock.advance(11L * 60 * 60_000) // 23:01
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        assertEquals(BlockState.UNLOCKED, world.block("c1")!!.state)

        clock.advance(60L * 60_000) // past midnight
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        assertEquals(BlockState.ARMED, world.block("c1")!!.state)
        assertEquals("yesterday's unlock isn't on today's cap", 0L, engine.unlockUsedTodayMs(world, clock))
    }

    @Test
    fun i41_selfPacedSurvivesARebootWithoutCountingIt() {
        val clock = FakeClock()
        var world = selfPacedWorld(clock).redeemed(clock)
        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.target"), clock).world
        clock.advance(60_000)
        world = engine.reduce(world, BlockEvent.Tick, clock).world

        clock.advance(10L * 60_000) // powered off for ten minutes; the monotonic clock starts again
        clock.setMono(5_000)
        world = engine.reduce(world, BlockEvent.BootOrColdStart, clock).world
        world = engine.reduce(world, BlockEvent.ForegroundChanged("com.target"), clock).world
        clock.advance(60_000)
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        assertEquals(BlockState.UNLOCKED, world.block("c1")!!.state)
        assertEquals(2L * 60_000, world.sessions["c1"]!!.totalUsedMs)
    }

    @Test
    fun i41_selfPacedUseAddsUpAcrossDevices() {
        val block = checklistBlock("c1", unlockMs = 5L * 60_000).copy(selfPaced = true, targetSites = setOf("youtube.com"))
        val (phone, laptop, server) = twoDevices(listOf(block), mapOf("c1" to items("c1", 1)))
        phone.on(BlockEvent.ToggleChecklistItem("c1-i0", true))
        phone.on(BlockEvent.Redeem("c1"))
        phone.sync(server)
        laptop.sync(server)
        assertEquals(BlockState.UNLOCKED, laptop.block("c1").state)

        phone.on(BlockEvent.ForegroundChanged("com.target"))
        laptop.on(BlockEvent.ForegroundChanged("browser", "youtube.com"))
        advanceAll(2L * 60_000, phone, laptop)
        phone.on(BlockEvent.Tick)
        laptop.on(BlockEvent.Tick)
        phone.sync(server)
        laptop.sync(server)
        phone.sync(server)
        assertEquals(4L * 60_000, phone.world.sessions["c1"]!!.totalUsedMs)
        assertEquals(4L * 60_000, laptop.world.sessions["c1"]!!.totalUsedMs)

        advanceAll(60_000, phone) // the last minute, on the phone
        phone.on(BlockEvent.Tick)
        assertEquals(BlockState.ARMED, phone.block("c1").state)
        phone.sync(server)
        laptop.on(BlockEvent.Tick) // its own use moved on meanwhile: it mustn't bring the unlock back
        laptop.sync(server)
        assertEquals(BlockState.ARMED, laptop.block("c1").state)
        phone.sync(server)
        assertEquals(BlockState.ARMED, phone.block("c1").state)
    }

    @Test
    fun i41_turningSelfPacedOnLoosens() {
        val block = checklistBlock("c1")
        assertTrue(BlockEdits.loosens(block, block.copy(selfPaced = true)))
        assertFalse(BlockEdits.loosens(block.copy(selfPaced = true), block))
    }

    // ---- I42: phone, laptop or both ----------------------------------------------------

    @Test
    fun i42_laptopOnlyBlockDoesNothingOnThePhone() {
        val clock = FakeClock()
        val laptopOnly = modeBlock("a1", BlockMode.ALLOWANCE, targetSites = setOf("youtube.com"), dailyAllowanceMs = 10L * 60_000)
            .copy(devices = Devices.LAPTOP)
        val schedule = modeBlock("s1", BlockMode.SCHEDULE, targetSites = setOf("reddit.com")).copy(devices = Devices.LAPTOP)
        var phone = buildWorld(listOf(laptopOnly, schedule), clock = clock).copy(deviceKind = DeviceKind.PHONE)

        phone = engine.reduce(phone, BlockEvent.ForegroundChanged("browser", "youtube.com"), clock).world
        clock.advance(30L * 60_000)
        phone = engine.reduce(phone, BlockEvent.Tick, clock).world
        assertEquals("the phone's use doesn't count", 0L, engine.allowanceUsedMs(phone, laptopOnly, clock))
        assertFalse(engine.isBlocking(phone, schedule, clock))
        assertTrue(engine.shieldPlan(phone, clock).blockedSites.isEmpty())
        assertNull(engine.timeLeft(phone, "browser", "youtube.com", clock))

        val laptop = phone.copy(deviceKind = DeviceKind.LAPTOP)
        assertEquals(setOf("reddit.com"), engine.shieldPlan(laptop, clock).blockedSites)
        assertEquals("old data with no kind: every block holds", setOf("reddit.com"), engine.shieldPlan(phone.copy(deviceKind = null), clock).blockedSites)
    }

    @Test
    fun i42_phoneOnlyBlockStillBlocksThePhone() {
        val clock = FakeClock()
        val block = modeBlock("a1", BlockMode.ALLOWANCE, dailyAllowanceMs = 10L * 60_000).copy(devices = Devices.PHONE)
        var phone = buildWorld(listOf(block), clock = clock, foregroundPkg = "com.target").copy(deviceKind = DeviceKind.PHONE)
        clock.advance(11L * 60_000)
        val out = engine.reduce(phone, BlockEvent.Tick, clock)
        assertTrue(out.shieldPlan!!.blockedPackages.contains("com.target"))
        phone = out.world
        assertFalse("the laptop doesn't block it", engine.isBlocking(phone.copy(deviceKind = DeviceKind.LAPTOP), block, clock))
    }

    @Test
    fun i42_aHabitAppElsewhereStillEarnsIt() {
        val clock = FakeClock()
        // Reddit on the laptop only, unlocked by 10 minutes of Duolingo on the phone.
        val block = timerBlock("t1", requiredMs = 10L * 60_000).copy(
            targetPackages = emptySet(),
            targetSites = setOf("reddit.com"),
            devices = Devices.LAPTOP,
            trigger = Trigger(TriggerType.TIMER, 10L * 60_000, TriggerUnit.MS, habitPackages = setOf("com.duolingo")),
        )
        var phone = buildWorld(listOf(block), clock = clock).copy(deviceKind = DeviceKind.PHONE)
        phone = engine.reduce(phone, BlockEvent.TimerControl("t1", true), clock).world
        phone = engine.reduce(phone, BlockEvent.ForegroundChanged("com.duolingo"), clock).world
        clock.advance(10L * 60_000)
        phone = engine.reduce(phone, BlockEvent.Tick, clock).world
        assertEquals(BlockState.EARNED, phone.block("t1")!!.state)
    }

    @Test
    fun i42_aTargetTimerOnlyCountsWhereTheBlockHolds() {
        val clock = FakeClock()
        val block = timerBlock("t1", requiredMs = 5L * 60_000).copy(devices = Devices.LAPTOP)
        var phone = buildWorld(listOf(block), clock = clock).copy(deviceKind = DeviceKind.PHONE)
        phone = engine.reduce(phone, BlockEvent.TimerControl("t1", true), clock).world
        phone = engine.reduce(phone, BlockEvent.ForegroundChanged("com.target"), clock).world // open, not waiting: it isn't blocked here
        clock.advance(10L * 60_000)
        phone = engine.reduce(phone, BlockEvent.Tick, clock).world
        assertEquals(BlockState.ARMED, phone.block("t1")!!.state)
    }

    @Test
    fun i42_droppingADeviceLoosens() {
        val block = checklistBlock("c1")
        assertTrue(BlockEdits.loosens(block, block.copy(devices = Devices.PHONE)))
        assertTrue(BlockEdits.loosens(block.copy(devices = Devices.PHONE), block.copy(devices = Devices.LAPTOP)))
        assertFalse(BlockEdits.loosens(block.copy(devices = Devices.LAPTOP), block))
    }

    // ---- I43: NFC tags -----------------------------------------------------------------

    private val kitchen = Tags.id(byteArrayOf(4, 0x51, 0x22, 0x7a, 0x1c, 0x60, 0x80.toByte()))

    @Test
    fun i43_aTagItemTicksOnlyFromItsTag() {
        val clock = FakeClock()
        val item = ChecklistItem(id = "t", blockId = "c1", text = "the kitchen tag", order = 0, tag = kitchen)
        var w = buildWorld(listOf(checklistBlock("c1")), mapOf("c1" to listOf(item)), clock = clock)

        w = engine.reduce(w, BlockEvent.ToggleChecklistItem("t", true), clock).world
        assertFalse("a tap doesn't tick it", w.items("c1").single().done)
        w = engine.reduce(w, BlockEvent.TagScanned(Tags.id(byteArrayOf(9, 9, 9, 9))), clock).world
        assertFalse("another tag doesn't", w.items("c1").single().done)

        w = engine.reduce(w, BlockEvent.TagScanned(kitchen), clock).world
        assertTrue(w.items("c1").single().done)
        assertEquals(BlockState.EARNED, w.block("c1")!!.state)
    }

    @Test
    fun i43_oneScanTicksEveryBlockWaitingForIt() {
        val clock = FakeClock()
        fun tagItem(block: String) = ChecklistItem(id = "$block-t", blockId = block, text = "tag", order = 0, tag = kitchen)
        val plain = ChecklistItem(id = "c2-p", blockId = "c2", text = "Stretch", order = 1)
        var w = buildWorld(
            listOf(checklistBlock("c1"), checklistBlock("c2", target = "com.other"), checklistBlock("c3", state = BlockState.UNLOCKED)),
            mapOf("c1" to listOf(tagItem("c1")), "c2" to listOf(tagItem("c2"), plain), "c3" to listOf(tagItem("c3"))),
            clock = clock,
        )
        w = engine.reduce(w, BlockEvent.TagScanned(kitchen), clock).world
        assertEquals(BlockState.EARNED, w.block("c1")!!.state)
        assertTrue(w.items("c2").first { it.tag != null }.done)
        assertEquals("the other item is still wanted", BlockState.ARMED, w.block("c2")!!.state)
        assertFalse("an open block's items wait for it to lock again", w.items("c3").single().done)
        w = engine.reduce(w, BlockEvent.ToggleChecklistItem("c2-t", false), clock).world
        assertFalse("unticking by hand is fine", w.items("c2").first { it.tag != null }.done)
    }

    @Test
    fun i43_aDifferentTagLoosens() {
        val item = ChecklistItem(id = "t", blockId = "c1", text = "tag", order = 0, tag = kitchen)
        assertTrue(itemsLoosen(listOf(item), listOf(item.copy(tag = Tags.id(byteArrayOf(1))))))
        assertTrue("a plain task instead", itemsLoosen(listOf(item), listOf(item.copy(tag = null))))
        assertFalse("a renamed one", itemsLoosen(listOf(item), listOf(item.copy(text = "the fridge tag"))))
        assertFalse("a tag on a plain task", itemsLoosen(listOf(item.copy(tag = null)), listOf(item)))
        assertEquals("the id is the hash, not the UID", 16, kitchen.length)
    }

    // ---- I45: a server that went back ------------------------------------------------------

    @Test
    fun i45_aServerThatLostItsStateIsFilledAgain() {
        val clock = FakeClock()
        val phone = Device("phone", buildWorld(listOf(timerBlock(id = "a"), timerBlock(id = "b", target = "com.b")), clock = clock), clock)
        val laptop = Device("laptop", World(), FakeClock(mono = 5_000))
        val server = FakeServer()
        phone.sync(server)
        laptop.sync(server)
        assertEquals(2, laptop.world.blocks.size)

        server.wentBack() // what the server answers with its file gone: version 0, no document
        phone.sync(server)
        assertEquals("nobody deleted them", 2, phone.world.blocks.size)
        assertEquals("and the server has them again", 2, server.state.blocks.size)
        laptop.sync(server)
        assertEquals(2, laptop.world.blocks.size)
        assertFalse("and it settles", phone.sync(server))
    }

    @Test
    fun i45_aBackupDoesntDeleteWhatCameAfterIt() {
        val clock = FakeClock()
        val phone = Device("phone", buildWorld(listOf(timerBlock(id = "old")), clock = clock), clock)
        val server = FakeServer()
        phone.sync(server)
        val backup = server.state
        val backupAt = server.version
        phone.world = phone.world.copy(blocks = phone.world.blocks + timerBlock(id = "new", target = "com.new"))
        phone.sync(server)
        phone.world = phone.world.copy(blocks = phone.world.blocks + timerBlock(id = "newer", target = "com.newer"))
        phone.sync(server)

        server.wentBack(backup, backupAt)
        phone.sync(server)
        assertEquals(setOf("old", "new", "newer"), phone.world.blocks.map { it.id }.toSet())
        assertEquals(3, server.state.blocks.size)
    }

    @Test
    fun i45_aServerMovingOnStillCarriesDeletions() {
        val clock = FakeClock()
        val phone = Device("phone", buildWorld(listOf(timerBlock(id = "a"), timerBlock(id = "b", target = "com.b")), clock = clock), clock)
        val laptop = Device("laptop", World(), FakeClock(mono = 5_000))
        val server = FakeServer()
        phone.sync(server)
        laptop.sync(server)
        laptop.world = laptop.world.copy(blocks = laptop.world.blocks.filterNot { it.id == "b" })
        laptop.sync(server)
        phone.sync(server)
        assertEquals("a real delete still goes through", listOf("a"), phone.world.blocks.map { it.id })
    }

    @Test
    fun i45_baseForOnlyDropsTheBaseWhenTheVersionWentBack() {
        val base = SharedState()
        assertEquals(base, SharedState.baseFor(base, "\"7\"", "\"7\""))
        assertEquals(base, SharedState.baseFor(base, "\"7\"", "\"8\""))
        assertEquals(null, SharedState.baseFor(base, "\"7\"", "\"6\""))
        assertEquals(null, SharedState.baseFor(base, "\"7\"", "\"0\""))
        assertEquals("no etag to compare", base, SharedState.baseFor(base, "", "\"3\""))
        assertEquals("a weak etag reads the same", null, SharedState.baseFor(base, "W/\"7\"", "\"6\""))
    }

    private fun siteLimit(id: String, site: String, allowanceMs: Long = 10L * 60_000) =
        modeBlock(id, BlockMode.ALLOWANCE, targetPackages = emptySet(), targetSites = setOf(site), dailyAllowanceMs = allowanceMs)

    @Test
    fun i46_aPathTargetBlocksThatPartOfTheSiteOnly() {
        val clock = FakeClock()
        val block = siteLimit("a", "youtube.com/shorts", allowanceMs = 0)
            .copy(mode = BlockMode.SCHEDULE, schedule = Schedule.ALWAYS)
        val world = buildWorld(listOf(block), clock = clock)
        val plan = engine.shieldPlan(world, clock)
        assertTrue(plan.siteBlocked("youtube.com/shorts/abc"))
        assertTrue(plan.siteBlocked("m.youtube.com/shorts"))
        assertFalse(plan.siteBlocked("youtube.com/watch"))
        assertFalse(plan.siteBlocked("youtube.com"))
    }

    @Test
    fun i46_aPathExceptionLeavesThatPartOpen() {
        val clock = FakeClock()
        val block = siteLimit("a", "youtube.com", allowanceMs = 0)
            .copy(mode = BlockMode.SCHEDULE, schedule = Schedule.ALWAYS, exceptSites = setOf("youtube.com/watch"))
        val world = buildWorld(listOf(block), clock = clock)
        val plan = engine.shieldPlan(world, clock)
        assertTrue(plan.siteBlocked("youtube.com"))
        assertTrue(plan.siteBlocked("youtube.com/shorts"))
        assertFalse(plan.siteBlocked("youtube.com/watch/abc"))
    }

    @Test
    fun i46_timeOnAPathCountsOnlyThere() {
        val clock = FakeClock()
        var world = buildWorld(listOf(siteLimit("a", "youtube.com/shorts")), clock = clock)
        world = engine.reduce(world, BlockEvent.ForegroundChanged("browser", "youtube.com/watch"), clock).world
        clock.advance(60_000)
        world = engine.reduce(world, BlockEvent.ForegroundChanged("browser", "youtube.com/shorts/x"), clock).world
        assertEquals(0L, world.progress["a"]!!.accumulatedMs)
        clock.advance(60_000)
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        assertEquals(60_000L, world.progress["a"]!!.accumulatedMs)
    }

    @Test
    fun i47_soundOutOfSightCountsAgainstTheLimit() {
        val clock = FakeClock()
        var world = buildWorld(listOf(siteLimit("a", "youtube.com")), clock = clock)
        world = engine.reduce(world, BlockEvent.BackgroundChanged(setOf("youtube.com/watch")), clock).world
        clock.advance(60_000)
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        assertEquals(60_000L, world.progress["a"]!!.accumulatedMs)

        world = engine.reduce(world, BlockEvent.BackgroundChanged(emptySet()), clock).world
        assertEquals("the last stretch still counts", 60_000L, world.progress["a"]!!.accumulatedMs)
        clock.advance(60_000)
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        assertEquals("and nothing after it", 60_000L, world.progress["a"]!!.accumulatedMs)
    }

    @Test
    fun i47_aPageInFrontAndOneBehindCountOnce() {
        val clock = FakeClock()
        var world = buildWorld(listOf(siteLimit("a", "youtube.com")), clock = clock)
        world = engine.reduce(world, BlockEvent.ForegroundChanged("browser", "youtube.com"), clock).world
        world = engine.reduce(world, BlockEvent.BackgroundChanged(setOf("m.youtube.com/watch")), clock).world
        clock.advance(60_000)
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        assertEquals(60_000L, world.progress["a"]!!.accumulatedMs)
    }

    @Test
    fun i47_soundDoesntRunTheWaitTimer() {
        val clock = FakeClock()
        val block = timerBlock("t", target = "com.target").copy(targetSites = setOf("youtube.com"))
        var world = buildWorld(listOf(block), clock = clock)
        world = engine.reduce(world, BlockEvent.BackgroundChanged(setOf("youtube.com")), clock).world
        clock.advance(60_000)
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        assertEquals(0L, world.progress["t"]!!.accumulatedMs)
    }

    @Test
    fun i47_aBlockedPageBehindCountsNothing() {
        val clock = FakeClock()
        var world = buildWorld(listOf(siteLimit("a", "youtube.com", allowanceMs = 60_000)), clock = clock)
        world = engine.reduce(world, BlockEvent.BackgroundChanged(setOf("youtube.com")), clock).world
        clock.advance(120_000)
        world = engine.reduce(world, BlockEvent.Tick, clock).world
        assertTrue(engine.shieldPlan(world, clock).siteBlocked("youtube.com"))
        assertEquals(60_000L, world.progress["a"]!!.accumulatedMs) // capped
    }

    @Test
    fun i47_whatsPlayingIsntKeptOrWorthASave() {
        val clock = FakeClock()
        var world = buildWorld(listOf(siteLimit("a", "youtube.com")), clock = clock)
        val before = world.withoutBookkeeping()
        world = engine.reduce(world, BlockEvent.BackgroundChanged(setOf("youtube.com")), clock).world
        assertEquals(before, world.withoutBookkeeping())
        world = engine.reduce(world, BlockEvent.BootOrColdStart, clock).world
        assertTrue(world.backgroundSites.isEmpty())
    }

    @Test
    fun i48_everySiteWithARuleIsWatched() {
        val clock = FakeClock()
        val open = siteLimit("a", "youtube.com")
        val off = siteLimit("b", "off.com").copy(enabled = false)
        val idle = modeBlock("c", BlockMode.LOCKDOWN, targetPackages = emptySet(), targetSites = setOf("idle.com"), lockdownMs = 60_000)
        val rules = ExtensionApi.rules(buildWorld(listOf(open, off, idle), clock = clock), null)
        assertTrue("nothing is blocked yet", rules.blocked.isEmpty())
        assertEquals(listOf("youtube.com"), rules.watched.map { it.domain })
    }

    @Test
    fun i48_aRunningLockdownIsWatched() {
        val clock = FakeClock()
        val lockdown = modeBlock("c", BlockMode.LOCKDOWN, targetPackages = emptySet(), targetSites = setOf("reddit.com"), lockdownMs = 60 * 60_000)
        var world = buildWorld(listOf(lockdown), clock = clock)
        assertTrue("not started: nothing to hold", ExtensionApi.rules(world, null).watched.isEmpty())
        world = BlockEngine().reduce(world, BlockEvent.StartLockdown("c"), clock).world
        val rules = ExtensionApi.rules(world, null)
        assertEquals(listOf("reddit.com"), rules.blocked.map { it.domain })
        // Without this, an extension that loses xrnrule for a minute would open what the lockdown holds.
        assertEquals(listOf("reddit.com"), rules.watched.map { it.domain })
    }

    @Test
    fun i48_anExceptionHoldsOnlyIfEveryBlockHasIt() {
        val clock = FakeClock()
        val a = siteLimit("a", "youtube.com").copy(exceptSites = setOf("music.youtube.com", "kids.youtube.com"))
        val b = siteLimit("b", "youtube.com").copy(exceptSites = setOf("music.youtube.com"))
        val rules = ExtensionApi.rules(buildWorld(listOf(a, b), clock = clock), null)
        assertEquals(listOf("music.youtube.com"), rules.watched.single().except)
    }

    @Test
    fun i48_aChangeToTheWatchedListIsANewVersion() {
        val clock = FakeClock()
        val one = ExtensionApi.rules(buildWorld(listOf(siteLimit("a", "a.com")), clock = clock), null)
        val two = ExtensionApi.rules(buildWorld(listOf(siteLimit("a", "b.com")), clock = clock), null)
        assertNotEquals(one.v, two.v)
    }

    @Test
    fun i49_otherBrowsersMatterOnlyWithAWebsiteRuleHere() {
        val clock = FakeClock()
        val site = siteLimit("a", "youtube.com")
        assertTrue(ExtensionApi.otherBrowsersMatter(buildWorld(listOf(site), clock = clock)))
        val appsOnly = modeBlock("b", BlockMode.ALLOWANCE)
        assertFalse(ExtensionApi.otherBrowsersMatter(buildWorld(listOf(appsOnly), clock = clock)))
        assertFalse(ExtensionApi.otherBrowsersMatter(buildWorld(listOf(site.copy(enabled = false)), clock = clock)))
        val off = Settings(blockOtherBrowsers = false)
        assertFalse(ExtensionApi.otherBrowsersMatter(buildWorld(listOf(site), clock = clock, settings = off)))
        val laptopOnly = buildWorld(listOf(site.copy(devices = Devices.LAPTOP)), clock = clock).copy(deviceKind = DeviceKind.PHONE)
        assertFalse(ExtensionApi.otherBrowsersMatter(laptopOnly))
    }

    @Test
    fun i49_turningItOffLoosens() {
        val on = Settings()
        assertTrue(BlockEdits.settingsLoosen(on, on.copy(blockOtherBrowsers = false)))
        assertFalse(BlockEdits.settingsLoosen(on.copy(blockOtherBrowsers = false), on))
    }

    /** [id] locked for [ms] from now (I39). */
    private fun locking(world: World, id: String, clock: Clock, ms: Long = 7 * DAY_MS): World {
        val at = clock.nowWallMs()
        return world.copy(blocks = world.blocks.map { if (it.id == id) it.copy(lock = BlockLock(untilWall = at + ms, setAtWall = at)) else it })
    }

    /** A schedule block that is blocking, with the sentence typed after the wait. */
    private fun bought(clock: FakeClock): World {
        val world = engine.reduce(buildWorld(listOf(modeBlock("s", BlockMode.SCHEDULE)), clock = clock), BlockEvent.BeginOverride(setOf("s")), clock).world
        clock.advance(Defaults.OVERRIDE_WAIT_MS)
        return claim(world, clock).world
    }

    @Test
    fun i50_aLockStopsBuyingMinutes() {
        val clock = FakeClock()
        val world = locking(buildWorld(listOf(modeBlock("s", BlockMode.SCHEDULE)), clock = clock), "s", clock)
        assertEquals(OverrideRejection.NOT_OVERRIDABLE, engine.overrideRejection(world, listOf(world.block("s")!!), clock))
        assertNull(engine.reduce(world, BlockEvent.BeginOverride(setOf("s")), clock).world.pendingOverride)
    }

    @Test
    fun i50_aLockSetDuringTheWaitStopsTheClaimAndCostsNothing() {
        val clock = FakeClock()
        val waiting = engine.reduce(buildWorld(listOf(modeBlock("s", BlockMode.SCHEDULE)), clock = clock), BlockEvent.BeginOverride(setOf("s")), clock).world
        assertNotNull(waiting.pendingOverride)
        clock.advance(Defaults.OVERRIDE_WAIT_MS)

        val out = claim(locking(waiting, "s", clock), clock)

        assertTrue(Effect.OverrideRejected(OverrideRejection.NOT_OVERRIDABLE) in out.effects)
        assertNull(out.world.pendingOverride)
        assertTrue(out.world.overrides.isEmpty())
        assertTrue(out.world.overrideLog.isEmpty())
    }

    @Test
    fun i50_minutesAlreadyBoughtDontCountWhileLocked() {
        val clock = FakeClock()
        val paid = bought(clock)
        val left = engine.overrideRemainingMs(paid, "s", clock)
        assertTrue(left > 0)
        assertFalse(engine.isBlocking(paid, paid.block("s")!!, clock))

        val world = locking(paid, "s", clock)

        assertEquals(0L, engine.overrideRemainingMs(world, "s", clock))
        assertTrue(engine.isBlocking(world, world.block("s")!!, clock))
        assertEquals(setOf("com.target"), engine.shieldPlan(world, clock).blockedPackages)
        assertNotEquals(ClockKind.OVERRIDE, engine.timeLeft(world, "com.target", null, clock)?.kind)

        // Nothing was thrown away: an emergency unlock gives back what's left of them.
        val ended = BlockEdits.emergencyUnlock(world, "s", clock.nowWallMs())!!
        assertEquals(left, engine.overrideRemainingMs(ended, "s", clock))
        assertFalse(engine.isBlocking(ended, ended.block("s")!!, clock))
    }

    @Test
    fun i50_theBreakLeavesALockedBlockBlocking() {
        val clock = FakeClock()
        val blocks = listOf(checklistBlock("free", target = "com.free"), checklistBlock("kept", target = "com.kept"))
        val world = locking(buildWorld(blocks, clock = clock), "kept", clock)

        val out = engine.reduce(world, BlockEvent.StartPause(), clock)

        assertEquals(setOf("com.kept"), out.shieldPlan!!.blockedPackages)
        assertFalse(engine.isBlocking(out.world, out.world.block("free")!!, clock))
        assertTrue(engine.isBlocking(out.world, out.world.block("kept")!!, clock))
        assertEquals(listOf("kept"), engine.blockersOf(out.world, "com.kept", null, clock).map { it.id })
    }

    @Test
    fun i50_aLockSetDuringABreakHoldsAtOnce() {
        val clock = FakeClock()
        val block = modeBlock("s", BlockMode.SCHEDULE)
        val resting = engine.reduce(buildWorld(listOf(block), clock = clock), BlockEvent.StartPause(), clock).world
        assertEquals(ClockKind.BREAK, engine.timeLeft(resting, "com.target", null, clock)?.kind)

        val world = locking(resting, "s", clock)

        assertTrue(engine.isBlocking(world, world.block("s")!!, clock))
        assertEquals(setOf("com.target"), engine.shieldPlan(world, clock).blockedPackages)
        assertNotEquals(ClockKind.BREAK, engine.timeLeft(world, "com.target", null, clock)?.kind)
    }

    @Test
    fun i50_aLockThatRanOutStandsAside() {
        val clock = FakeClock()
        val world = locking(buildWorld(listOf(modeBlock("s", BlockMode.SCHEDULE)), clock = clock), "s", clock, ms = 60_000)
        clock.advance(60_000)
        val block = world.block("s")!!
        assertTrue(engine.pausable(block, clock))
        assertNull(engine.overrideRejection(world, listOf(block), clock))
    }

    @Test
    fun i50_aHabitStillOpensALockedBlock() {
        val clock = FakeClock()
        val world = locking(buildWorld(listOf(checklistBlock("c1")), mapOf("c1" to items("c1", 1)), clock), "c1", clock)

        val done = engine.reduce(world, BlockEvent.ToggleChecklistItem("c1-i0", true), clock).world
        assertEquals(BlockState.EARNED, done.block("c1")!!.state)
        val open = engine.reduce(done, BlockEvent.Redeem("c1"), clock).world

        assertEquals(BlockState.UNLOCKED, open.block("c1")!!.state)
        assertFalse(engine.isBlocking(open, open.block("c1")!!, clock))
    }

    @Test
    fun i50_aHardcoreBlockStaysUnliftedAfterItsLockRunsOut() {
        val clock = FakeClock()
        val hard = modeBlock("s", BlockMode.SCHEDULE).copy(enforcementTier = EnforcementTier.HARDCORE)
        val world = locking(buildWorld(listOf(hard), clock = clock), "s", clock, ms = 60_000)
        clock.advance(60_000)
        assertFalse(engine.pausable(world.block("s")!!, clock))
    }

    /** Sends [outcome] through to the log, as the store does. */
    private fun logged(outcome: Outcome, clock: Clock): List<String> {
        val telemetry = RecordingTelemetry()
        applyOutcome(outcome, InMemoryPersist(outcome.world), RecordingShield(mutableListOf()), telemetry, clock.nowWallMs())
        return telemetry.lines
    }

    private fun siteBlock() = modeBlock("s", BlockMode.SCHEDULE, targetPackages = emptySet(), targetSites = setOf("reddit.com"))

    @Test
    fun i51_aBlockedSiteIsLogged() {
        val clock = FakeClock()
        val world = buildWorld(listOf(siteBlock()), clock = clock)
        val out = engine.reduce(world, BlockEvent.ForegroundChanged("browser", "old.reddit.com"), clock)
        assertEquals(listOf("site old.reddit.com"), logged(out, clock))
    }

    @Test
    fun i51_aSiteLeftOpenIsNotLogged() {
        val clock = FakeClock()
        val world = buildWorld(listOf(siteBlock()), clock = clock)
        assertTrue(logged(engine.reduce(world, BlockEvent.ForegroundChanged("browser", "example.com"), clock), clock).isEmpty())
    }

    @Test
    fun i51_aFlickerCountsOnce() {
        val clock = FakeClock()
        var world = buildWorld(listOf(siteBlock()), clock = clock)
        var seen = emptyList<String>()
        fun show(site: String?) {
            val out = engine.reduce(world, BlockEvent.ForegroundChanged("browser", site), clock)
            world = out.world
            seen = seen + logged(out, clock)
        }
        show("reddit.com")
        show(null)
        clock.advance(Defaults.BLOCKED_AGAIN_MS - 1)
        show("reddit.com")
        assertEquals(1, seen.size)

        show(null)
        clock.advance(1)
        show("reddit.com")
        assertEquals(2, seen.size)
    }

    @Test
    fun i51_anotherTargetCountsAtOnce() {
        val clock = FakeClock()
        val other = modeBlock("t", BlockMode.SCHEDULE, targetPackages = emptySet(), targetSites = setOf("x.com"))
        var world = buildWorld(listOf(siteBlock(), other), clock = clock)
        val first = engine.reduce(world, BlockEvent.ForegroundChanged("browser", "reddit.com"), clock)
        world = first.world
        val second = engine.reduce(world, BlockEvent.ForegroundChanged("browser", "x.com"), clock)
        assertEquals(listOf("site reddit.com"), logged(first, clock))
        assertEquals(listOf("site x.com"), logged(second, clock))
    }

    @Test
    fun i51_aBlockedAppStillCountsAsAnApp() {
        val clock = FakeClock()
        val world = buildWorld(listOf(modeBlock("s", BlockMode.SCHEDULE)), clock = clock)
        val out = engine.reduce(world, BlockEvent.ForegroundChanged("com.target"), clock)
        assertEquals(listOf("app com.target"), logged(out, clock))
    }

    @Test
    fun i51_boughtMinutesAreLogged() {
        val clock = FakeClock()
        val waiting = engine.reduce(buildWorld(listOf(modeBlock("s", BlockMode.SCHEDULE)), clock = clock), BlockEvent.BeginOverride(setOf("s")), clock).world
        clock.advance(Defaults.OVERRIDE_WAIT_MS)
        assertEquals(listOf("bought [s] ${Defaults.OVERRIDE_GRANT_MS}"), logged(claim(waiting, clock), clock))
    }

    @Test
    fun i51_aBreakIsLoggedOnlyWhenTaken() {
        val clock = FakeClock()
        val world = buildWorld(listOf(modeBlock("s", BlockMode.SCHEDULE)), clock = clock)
        val taken = engine.reduce(world, BlockEvent.StartPause(), clock)
        assertEquals(listOf("break ${Defaults.PAUSE_MS}"), logged(taken, clock))
        assertTrue(logged(engine.reduce(taken.world, BlockEvent.StartPause(), clock), clock).isEmpty())
    }

    // ---- opens and the wait before them (I52, I53) -------------------------------------------

    private fun limited(id: String = "o", opens: Int = 0, waitS: Int = 0, targets: Set<String> = setOf("com.target")) =
        modeBlock(id, BlockMode.ALLOWANCE, targetPackages = targets, targetSites = setOf("target.com")).copy(opensPerDay = opens, openWaitS = waitS)

    /** Keeps one world and sends events through it. */
    private inner class Phone(block: List<Block>, val clock: FakeClock = FakeClock(), kind: DeviceKind = DeviceKind.PHONE) {
        var world = buildWorld(block, clock = clock).copy(deviceKind = kind)
        var plan = world.appliedShield
        val seen = mutableListOf<String>()
        fun on(event: BlockEvent) {
            val out = engine.reduce(world, event, clock)
            world = out.world
            plan = out.world.appliedShield
            seen += logged(out, clock)
        }
        fun open(pkg: String? = "com.target") = on(BlockEvent.ForegroundChanged(pkg))
        fun leave() = on(BlockEvent.ForegroundChanged("com.launcher"))
        fun pass(ms: Long) {
            var left = ms
            while (left > 0) {
                val step = minOf(left, 1_000)
                clock.advance(step)
                on(BlockEvent.Tick)
                left -= step
            }
        }
        fun opens(id: String = "o") = engine.opensUsed(world, world.block(id)!!, clock)
        fun blocked(pkg: String = "com.target") = pkg in plan.blockedPackages
        fun reason(id: String = "o") = engine.blockReason(world, world.block(id)!!, clock)
    }

    @Test
    fun i52_anOpenCountsOnceHoweverLongItLasts() {
        val phone = Phone(listOf(limited(opens = 3)))
        phone.open()
        phone.pass(5 * 60_000)
        assertEquals(1, phone.opens())
    }

    @Test
    fun i52_aQuickSwitchIsNotANewOpen() {
        val phone = Phone(listOf(limited(opens = 3)))
        phone.open()
        phone.leave()
        phone.pass(Defaults.OPEN_GRACE_MS - 1_000)
        phone.open()
        assertEquals(1, phone.opens())

        phone.leave()
        phone.pass(Defaults.OPEN_GRACE_MS)
        phone.open()
        assertEquals(2, phone.opens())
    }

    @Test
    fun i52_goingBetweenABlocksAppsIsOneOpen() {
        val phone = Phone(listOf(limited(opens = 3, targets = setOf("com.one", "com.two"))))
        phone.open("com.one")
        phone.open("com.two")
        phone.pass(Defaults.OPEN_GRACE_MS * 2)
        assertEquals(1, phone.opens())
    }

    @Test
    fun i52_theLastOpenRunsUntilYouHaveLeftIt() {
        val phone = Phone(listOf(limited(opens = 1)))
        phone.open()
        phone.pass(10 * 60_000)
        assertFalse(phone.blocked())

        phone.leave()
        phone.pass(Defaults.OPEN_GRACE_MS - 1_000)
        assertFalse(phone.blocked())
        phone.pass(1_000)
        assertTrue(phone.blocked())
        assertEquals(BlockReason.LIMIT, phone.reason())
    }

    @Test
    fun i52_aShieldMetIsNotAnOpen() {
        val phone = Phone(listOf(limited(opens = 1)))
        phone.open()
        phone.leave()
        phone.pass(Defaults.OPEN_GRACE_MS)
        phone.open()
        phone.pass(5_000)
        assertEquals(1, phone.opens())
        assertTrue(phone.blocked())
    }

    @Test
    fun i52_opensComeBackWithTheDay() {
        val phone = Phone(listOf(limited(opens = 1)))
        phone.open()
        phone.leave()
        phone.clock.advance(DAY_MS)
        phone.on(BlockEvent.Tick)
        assertEquals(0, phone.opens())
        assertFalse(phone.blocked())

        phone.open()
        assertEquals(1, phone.opens())
        assertFalse(phone.blocked())
    }

    @Test
    fun i52_aBreakOrBoughtMinutesSpendNoOpens() {
        val resting = Phone(listOf(limited(opens = 1)))
        resting.on(BlockEvent.StartPause())
        resting.open()
        assertEquals(0, resting.opens())

        val buying = Phone(listOf(limited(opens = 1)))
        buying.open()
        buying.leave()
        buying.pass(Defaults.OPEN_GRACE_MS)
        assertTrue(buying.blocked())
        buying.on(BlockEvent.BeginOverride(setOf("o")))
        buying.pass(Defaults.OVERRIDE_WAIT_MS)
        buying.on(BlockEvent.ClaimOverride(Defaults.OVERRIDE_PHRASE))
        assertFalse(buying.blocked())
        buying.open()
        assertEquals(1, buying.opens())
    }

    @Test
    fun i52_outsideItsScheduleNothingCounts() {
        val clock = FakeClock()
        val today = clock.localDate().dayOfWeek.value
        val notToday = Schedule(activeDays = (1..7).toSet() - today, activeWindow = null)
        val phone = Phone(listOf(limited(opens = 1).copy(schedule = notToday)), clock)
        phone.open()
        assertEquals(0, phone.opens())
    }

    @Test
    fun i52_aBlockForTheOtherDeviceCountsNothingHere() {
        val phone = Phone(listOf(limited(opens = 1).copy(devices = Devices.LAPTOP)))
        phone.open()
        assertEquals(0, phone.opens())
    }

    @Test
    fun i52_aHabitBlockOpensFreeThenAsksForItsHabit() {
        val clock = FakeClock()
        val block = checklistBlock("c1").copy(opensPerDay = 1)
        val phone = Phone(listOf(block), clock)
        phone.world = phone.world.copy(checklist = mapOf("c1" to items("c1", 1)))
        phone.on(BlockEvent.Tick)
        assertTrue(block.limitComesFirst)
        assertFalse(phone.blocked())

        phone.open()
        assertEquals(1, phone.opens("c1"))
        phone.leave()
        phone.pass(Defaults.OPEN_GRACE_MS)
        assertTrue(phone.blocked())
        assertEquals(BlockReason.HABIT, phone.reason("c1"))

        // The habit opens it, and the opens that follow are the unlock's, not the limit's.
        phone.on(BlockEvent.ToggleChecklistItem("c1-i0", true))
        phone.on(BlockEvent.Redeem("c1"))
        phone.open()
        assertEquals(1, phone.opens("c1"))
        assertFalse(phone.blocked())
    }

    @Test
    fun i52_opensOutliveAHabitsReset() {
        val clock = FakeClock()
        val phone = Phone(listOf(checklistBlock("c1").copy(opensPerDay = 2)), clock)
        phone.world = phone.world.copy(checklist = mapOf("c1" to items("c1", 1)))
        phone.open()
        phone.on(BlockEvent.ToggleChecklistItem("c1-i0", true))
        assertEquals(BlockState.EARNED, phone.world.block("c1")!!.state)
        phone.on(BlockEvent.UserResetEarned("c1"))
        assertEquals(1, phone.opens("c1"))
    }

    @Test
    fun i52_devicesShareOpensUnlessEachHasItsOwn() {
        fun together(perDevice: Boolean): Pair<Int, Int> {
            val server = FakeServer()
            val clock = FakeClock()
            val block = limited(opens = 3).copy(perDevice = perDevice)
            val phone = Device("phone", buildWorld(listOf(block), clock = clock).copy(deviceId = "phone"), clock)
            val laptop = Device("laptop", buildWorld(listOf(block), clock = clock).copy(deviceId = "laptop"), clock)
            phone.sync(server)
            laptop.sync(server)
            phone.on(BlockEvent.ForegroundChanged("com.target"))
            phone.sync(server)
            laptop.sync(server)
            laptop.on(BlockEvent.ForegroundChanged("com.target"))
            laptop.sync(server)
            phone.sync(server)
            return engine.opensUsed(phone.world, phone.block("o"), clock) to engine.opensUsed(laptop.world, laptop.block("o"), clock)
        }
        assertEquals(2 to 2, together(perDevice = false))
        assertEquals(1 to 1, together(perDevice = true))
    }

    /** With no minutes to compare, the opens alone have to tell a device's newer report (§2.4). */
    @Test
    fun i52_syncRoundsSettleOnALimitOfOpensAlone() {
        val server = FakeServer()
        val clock = FakeClock()
        val block = limited(opens = 3)
        val phone = Device("phone", buildWorld(listOf(block), clock = clock).copy(deviceId = "phone"), clock)
        val laptop = Device("laptop", buildWorld(listOf(block), clock = clock).copy(deviceId = "laptop"), clock)
        phone.sync(server)
        laptop.sync(server)
        phone.sync(server)
        phone.on(BlockEvent.ForegroundChanged("com.target"))
        assertTrue(phone.sync(server))
        laptop.sync(server)
        assertEquals(1, server.state.allowance["o"]!!["phone"]!!.opens)
        assertFalse("settled", phone.sync(server))
        assertFalse("settled", laptop.sync(server))
    }

    @Test
    fun i52_moreOpensLoosensAndFewerDoesnt() {
        val block = limited(opens = 3)
        assertTrue(BlockEdits.loosens(block, block.copy(opensPerDay = 4)))
        assertTrue(BlockEdits.loosens(block, block.copy(opensPerDay = 0)))
        assertFalse(BlockEdits.loosens(block, block.copy(opensPerDay = 2)))
        assertFalse(BlockEdits.loosens(limited(), limited(opens = 3)))
    }

    @Test
    fun i53_theWaitHoldsThenOpensByItself() {
        val phone = Phone(listOf(limited(opens = 3, waitS = 5)))
        phone.open()
        assertTrue(phone.blocked())
        assertEquals(BlockReason.WAIT, phone.reason())
        assertEquals(0, phone.opens())

        phone.pass(4_000)
        assertTrue(phone.blocked())
        phone.pass(1_000)
        assertFalse(phone.blocked())
        assertEquals(1, phone.opens())
    }

    @Test
    fun i53_leavingDuringTheWaitCostsNothingAndWaitsAgain() {
        val phone = Phone(listOf(limited(opens = 1, waitS = 5)))
        phone.open()
        phone.pass(2_000)
        phone.leave()
        assertEquals(0, phone.opens())
        assertFalse(phone.blocked())

        phone.open()
        assertEquals(BlockReason.WAIT, phone.reason())
    }

    @Test
    fun i53_aQuickSwitchDoesntWaitAgain() {
        val phone = Phone(listOf(limited(waitS = 5)))
        phone.open()
        phone.pass(5_000)
        phone.leave()
        phone.pass(10_000)
        phone.open()
        assertFalse(phone.blocked())
    }

    @Test
    fun i53_theWaitIsntUse() {
        val block = limited(waitS = 5).copy(dailyAllowanceMs = 30L * 60_000)
        val phone = Phone(listOf(block))
        phone.open()
        phone.pass(5_000)
        assertEquals(0L, engine.allowanceUsedMs(phone.world, phone.world.block("o")!!, phone.clock))
        phone.pass(10_000)
        assertEquals(10_000L, engine.allowanceUsedMs(phone.world, phone.world.block("o")!!, phone.clock))
    }

    @Test
    fun i53_theWaitIsntLoggedAsBeingBlocked() {
        val phone = Phone(listOf(limited(waitS = 5)))
        phone.open()
        phone.on(BlockEvent.ForegroundChanged("com.target", "target.com"))
        phone.pass(5_000)
        assertTrue(phone.seen.toString(), phone.seen.isEmpty())
    }

    @Test
    fun i53_theWaitCantBeBoughtOff() {
        val phone = Phone(listOf(limited(waitS = 5)))
        phone.open()
        assertEquals(OverrideRejection.NOT_OVERRIDABLE, engine.overrideRejection(phone.world, listOf(phone.world.block("o")!!), phone.clock))
    }

    @Test
    fun i53_noWaitOnceTheOpensAreGone() {
        val phone = Phone(listOf(limited(opens = 1, waitS = 5)))
        phone.open()
        phone.pass(5_000)
        phone.leave()
        phone.pass(Defaults.OPEN_GRACE_MS)
        phone.open()
        assertEquals(BlockReason.LIMIT, phone.reason())
    }

    @Test
    fun i53_aBreakSkipsTheWait() {
        val phone = Phone(listOf(limited(waitS = 5)))
        phone.on(BlockEvent.StartPause())
        phone.open()
        assertFalse(phone.blocked())
    }

    @Test
    fun i53_aLaptopAppCantWaitButASiteCan() {
        val app = limited(opens = 3, waitS = 5, targets = emptySet()).copy(targetDesktopApps = setOf("game"))
        val laptop = Phone(listOf(app), kind = DeviceKind.LAPTOP)
        laptop.open("game")
        assertFalse(laptop.blocked("game"))
        assertEquals(1, laptop.opens())
        laptop.leave()
        laptop.pass(Defaults.OPEN_GRACE_MS)

        laptop.on(BlockEvent.ForegroundChanged("browser", "target.com"))
        assertEquals(BlockReason.WAIT, laptop.reason())
        assertTrue(laptop.plan.siteBlocked("target.com"))
    }

    @Test
    fun i53_aWaitOnASiteLeavesTheBlocksLaptopAppAlone() {
        val both = limited(waitS = 5, targets = emptySet()).copy(targetDesktopApps = setOf("game"))
        val laptop = Phone(listOf(both), kind = DeviceKind.LAPTOP)
        laptop.on(BlockEvent.ForegroundChanged("browser", "target.com"))
        assertEquals(BlockReason.WAIT, laptop.reason())
        assertFalse(laptop.blocked("game"))
    }

    @Test
    fun i53_aColdStartDropsAWaitButNotAnOpen() {
        val waiting = Phone(listOf(limited(waitS = 5)))
        waiting.open()
        waiting.on(BlockEvent.BootOrColdStart)
        assertTrue(waiting.world.openRuns.isEmpty())

        val open = Phone(listOf(limited(opens = 1)))
        open.open()
        open.on(BlockEvent.BootOrColdStart)
        assertFalse(open.blocked())
    }

    @Test
    fun i53_aBlockOnTheSameTargetCountsOnlyOnceTheWaitIsOver() {
        val phone = Phone(listOf(limited("a", opens = 1), limited("b", waitS = 5)))
        phone.open()
        assertEquals(0, phone.opens("a"))
        phone.leave()
        assertEquals(0, phone.opens("a"))

        phone.open()
        phone.pass(5_000)
        assertEquals(1, phone.opens("a"))
        assertFalse(phone.blocked())
    }

    @Test
    fun i53_aShorterWaitLoosens() {
        val block = limited(waitS = 10)
        assertTrue(BlockEdits.loosens(block, block.copy(openWaitS = 5)))
        assertTrue(BlockEdits.loosens(block, block.copy(openWaitS = 0)))
        assertFalse(BlockEdits.loosens(block, block.copy(openWaitS = 30)))
        assertFalse(BlockEdits.loosens(limited(), limited(waitS = 5)))
    }

    @Test
    fun i53_runsDontWriteTheFileEveryTick() {
        val phone = Phone(listOf(limited(opens = 3)))
        phone.open()
        val before = phone.world.withoutBookkeeping()
        phone.pass(5_000)
        assertEquals(before, phone.world.withoutBookkeeping())
    }
}
