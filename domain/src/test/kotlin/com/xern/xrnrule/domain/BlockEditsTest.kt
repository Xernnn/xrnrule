package com.xern.xrnrule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract §2.3: which edits count as loosening a block. */
class BlockEditsTest {

    private val habit = combinedBlock("x1", requiredMs = 30L * 60_000).copy(targetPackages = setOf("a", "b"))

    @Test
    fun stricterEditsDontLoosen() {
        assertFalse(BlockEdits.loosens(habit, habit.copy(name = "Renamed")))
        assertFalse(BlockEdits.loosens(habit, habit.copy(targetPackages = setOf("a", "b", "c"))))
        assertFalse(BlockEdits.loosens(habit, habit.copy(trigger = habit.trigger.copy(requiredAmount = 45L * 60_000))))
        assertFalse(BlockEdits.loosens(habit, habit.copy(reward = habit.reward.copy(unlockDurationMs = 60_000))))
        assertFalse(BlockEdits.loosens(habit, habit.copy(enforcementTier = EnforcementTier.HARDCORE)))
    }

    @Test
    fun weakerEditsLoosen() {
        assertTrue(BlockEdits.loosens(habit, habit.copy(targetPackages = setOf("a"))))
        assertTrue(BlockEdits.loosens(habit, habit.copy(trigger = habit.trigger.copy(requiredAmount = 60_000))))
        assertTrue(BlockEdits.loosens(habit, habit.copy(trigger = habit.trigger.copy(combineMode = CombineMode.OR))))
        assertTrue(BlockEdits.loosens(habit, habit.copy(reward = habit.reward.copy(unlockDurationMs = 60L * 60_000))))
        val hard = habit.copy(enforcementTier = EnforcementTier.HARDCORE)
        assertTrue(BlockEdits.loosens(hard, hard.copy(enforcementTier = EnforcementTier.SOFT)))
    }

    @Test
    fun strictModesLoosenByTheirOwnNumbers() {
        val limit = modeBlock("l", BlockMode.ALLOWANCE, dailyAllowanceMs = 30L * 60_000)
        assertTrue(BlockEdits.loosens(limit, limit.copy(dailyAllowanceMs = 60L * 60_000)))
        assertFalse(BlockEdits.loosens(limit, limit.copy(dailyAllowanceMs = 10L * 60_000)))

        val schedule = modeBlock("s", BlockMode.SCHEDULE, schedule = everyDay(9 * 60, 17 * 60))
        assertTrue(BlockEdits.loosens(schedule, schedule.copy(schedule = everyDay(10 * 60, 17 * 60))))
        assertTrue(BlockEdits.loosens(schedule, schedule.copy(schedule = Schedule(setOf(1), schedule.schedule.activeWindow))))
    }

    @Test
    fun onlyAnIdleLockdownIsFreeToEdit() {
        val engine = BlockEngine()
        val clock = FakeClock()
        val lockdown = modeBlock("l", BlockMode.LOCKDOWN, lockdownMs = 60L * 60_000)
        var world = buildWorld(listOf(lockdown, habit), clock = clock)
        assertFalse(BlockEdits.guardNeeded(engine, world, lockdown, clock))
        assertTrue(BlockEdits.guardNeeded(engine, world, habit, clock))

        world = engine.reduce(world, BlockEvent.StartLockdown("l"), clock).world
        assertTrue(BlockEdits.guardNeeded(engine, world, lockdown, clock))
    }

    @Test
    fun aLockStopsLooseningUntilItEnds() {
        val engine = BlockEngine()
        val clock = FakeClock()
        val now = clock.nowWallMs()
        val lockdown = modeBlock("l", BlockMode.LOCKDOWN, lockdownMs = 60L * 60_000)
        val lock = BlockLock(untilWall = now + DAY_MS, setAtWall = now)
        val world = buildWorld(listOf(lockdown.copy(lock = lock)), clock = clock)
        assertTrue(BlockEdits.locked(world.block("l")!!, now))
        assertTrue("even an idle lockdown, once locked", BlockEdits.guardNeeded(engine, world, lockdown, clock))
        assertEquals("l", BlockEdits.lockedBy(world, listOf("l", "gone"), now)?.id)

        clock.advance(DAY_MS)
        assertFalse(BlockEdits.locked(world.block("l")!!, clock.nowWallMs()))
        assertFalse(BlockEdits.guardNeeded(engine, world, lockdown, clock))
        assertNull(BlockEdits.lockedBy(world, listOf("l"), clock.nowWallMs()))
    }

    @Test
    fun aShorterLockLoosensALongerOneDoesnt() {
        val locked = habit.copy(lock = BlockLock(untilWall = 2 * DAY_MS, setAtWall = 0))
        assertFalse(BlockEdits.loosens(habit, locked))
        assertFalse(BlockEdits.loosens(locked, locked.copy(lock = BlockLock(3 * DAY_MS, DAY_MS))))
        assertTrue(BlockEdits.loosens(locked, locked.copy(lock = BlockLock(DAY_MS, DAY_MS))))
        assertTrue(BlockEdits.loosens(locked, locked.copy(lock = null)))
    }

    @Test
    fun habitsLoosenWithASmallerUnlockOrAnotherKind() {
        val push = habit("push", goal = 20)
        assertTrue(BlockEdits.habitLoosens(push, push.copy(goal = units(10))))
        assertTrue("20 a week opens for 3 a day", BlockEdits.habitLoosens(push, push.copy(freqDen = 7)))
        assertTrue(BlockEdits.habitLoosens(push, push.copy(kind = HabitKind.CHECK)))
        assertFalse(BlockEdits.habitLoosens(push, push.copy(goal = units(30))))
        assertFalse(BlockEdits.habitLoosens(push, push.copy(name = "Push-ups", unit = "reps", paletteColor = 3, icon = "dumbbell")))
        assertFalse("at most never opens anything", BlockEdits.habitLoosens(push, push.copy(targetType = TargetType.AT_MOST)))
        assertTrue(BlockEdits.habitLoosens(push.copy(targetType = TargetType.AT_MOST), push))

        val gym = habit("gym", HabitKind.CHECK)
        assertTrue("Loop fills in more days by itself", BlockEdits.habitLoosens(gym, gym.copy(freqNum = 3, freqDen = 7)))
        assertFalse(BlockEdits.habitLoosens(gym.copy(freqNum = 3, freqDen = 7), gym))
    }
}
