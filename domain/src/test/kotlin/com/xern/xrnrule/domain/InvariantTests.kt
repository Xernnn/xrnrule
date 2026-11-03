package com.xern.xrnrule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
