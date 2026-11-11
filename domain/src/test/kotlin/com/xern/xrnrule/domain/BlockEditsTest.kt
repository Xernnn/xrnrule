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
    fun anEditStampsWhatItChanged_settingsOrChecklist() {
        val block = checklistBlock("c1").copy(editedAtWall = 100, checklistEditedAtWall = 100)
        val todos = items("c1", 2)
        val before = World(blocks = listOf(block), checklist = mapOf("c1" to todos))
        fun stamped(after: World) = BlockEdits.stamped(before, after, nowWall = 500).blocks.single()

        val removed = stamped(before.copy(checklist = mapOf("c1" to todos.take(1))))
        assertEquals("the settings didn't change", 100L, removed.editedAtWall)
        assertEquals(500L, removed.checklistEditedAtWall)

        val renamed = stamped(before.copy(blocks = listOf(block.copy(name = "renamed"))))
        assertEquals(500L, renamed.editedAtWall)
        assertEquals("the checklist didn't change", 100L, renamed.checklistEditedAtWall)

        val ticked = stamped(before.copy(checklist = mapOf("c1" to todos.map { it.copy(done = true, spentEpochDay = 3) })))
        assertEquals("a tick is use, not an edit", block, ticked)
    }

    @Test
    fun stricterEditsDontLoosen() {
        assertFalse(BlockEdits.loosens(habit, habit.copy(name = "Renamed")))
        assertFalse(BlockEdits.loosens(habit, habit.copy(targetPackages = setOf("a", "b", "c"))))
        assertFalse(BlockEdits.loosens(habit, habit.copy(trigger = habit.trigger.copy(requiredAmount = 45L * 60_000))))
        assertFalse(BlockEdits.loosens(habit, habit.copy(reward = habit.reward.copy(unlockDurationMs = 60_000))))
        assertFalse(BlockEdits.loosens(habit, habit.copy(enforcementTier = EnforcementTier.HARDCORE)))
    }

    @Test
    fun addingRulesLoosensOnlyWhenItOpensAWayPast() {
        val day = 30L * 60_000
        val hours = Schedule((1..5).toSet(), TimeWindow(0, 14 * 60))
        val limit = modeBlock("b", BlockMode.ALLOWANCE, dailyAllowanceMs = day)
        val strict = modeBlock("b", BlockMode.SCHEDULE, schedule = hours)
        val gate = checklistBlock("b")
        val limitFirst = gate.copy(dailyAllowanceMs = day)
        val habitFirst = limitFirst.copy(habitFirst = true)

        assertFalse("hours on a limit", BlockEdits.loosens(limit, limit.copy(blockedHours = hours)))
        assertFalse("hours on a habit", BlockEdits.loosens(gate, gate.copy(blockedHours = hours)))
        assertTrue("dropping hours", BlockEdits.loosens(limit.copy(blockedHours = hours), limit))
        assertTrue("fewer hours", BlockEdits.loosens(limit.copy(blockedHours = hours), limit.copy(blockedHours = hours.copy(activeDays = (1..4).toSet()))))

        assertFalse("a limit beside a block's hours", BlockEdits.loosens(strict, limit.copy(blockedHours = hours)))
        assertTrue("that keeps fewer hours", BlockEdits.loosens(strict, limit.copy(blockedHours = hours.copy(activeDays = setOf(1)))))
        assertTrue("that drops them", BlockEdits.loosens(strict, limit))
        assertFalse("a habit beside a block's hours", BlockEdits.loosens(strict, gate.copy(blockedHours = hours)))

        assertTrue("a habit opening a limit", BlockEdits.loosens(limit, limitFirst))
        assertFalse("a habit before a limit", BlockEdits.loosens(limit, habitFirst))
        assertTrue("free minutes on a habit block", BlockEdits.loosens(gate, limitFirst))
        assertFalse("a limit on top of a habit", BlockEdits.loosens(gate, habitFirst))
        assertTrue("putting the limit first", BlockEdits.loosens(habitFirst, limitFirst))
        assertFalse("putting the habit first", BlockEdits.loosens(limitFirst, habitFirst))
        assertTrue("dropping the habit", BlockEdits.loosens(habitFirst, limit))
        assertTrue("more free minutes", BlockEdits.loosens(limitFirst, limitFirst.copy(dailyAllowanceMs = 2 * day)))
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
    fun aLimitBelowZeroIsNoLimitAtAll() {
        // Minutes times 60 000 that overflowed came out negative, and the engine reads any limit
        // that isn't above zero as none: as loosening as taking the limit off.
        val limit = modeBlock("l", BlockMode.ALLOWANCE, dailyAllowanceMs = 30L * 60_000)
        assertTrue(BlockEdits.loosens(limit, limit.copy(dailyAllowanceMs = -1)))
        assertTrue(BlockEdits.loosens(limit, limit.copy(dailyAllowanceMs = Long.MIN_VALUE)))
        val opens = limit.copy(opensPerDay = 3)
        assertTrue(BlockEdits.loosens(opens, opens.copy(opensPerDay = -1)))
    }

    @Test
    fun aHabitBlockOnAnothersTargetIsAWayPastIt() {
        // I6: any UNLOCKED HABIT block opens its targets for every HABIT block on them, so a
        // second one on a locked block's target, with a habit of one tap, opened it at once.
        val clock = FakeClock()
        val now = clock.nowWallMs()
        val strict = timerBlock("strict", target = "com.insta", requiredMs = 60L * 60_000)
            .copy(targetSites = setOf("youtube.com"), lock = BlockLock(untilWall = now + DAY_MS, setAtWall = now))
        val world = World(blocks = listOf(strict))
        fun opened(w: World, old: Block?, new: Block, loosened: Boolean = old != null && BlockEdits.loosens(old, new)) =
            BlockEdits.opens(w, old, new, loosened).map { it.id }

        val easy = checklistBlock("easy", target = "com.insta")
        assertEquals("a new block on its app", listOf("strict"), opened(world, null, easy))
        assertEquals("so the lock refuses it", "strict", BlockEdits.lockedBy(world, opened(world, null, easy), now)?.id)
        val under = checklistBlock("easy", target = "com.other").copy(targetSites = setOf("m.youtube.com"))
        assertEquals("on a site under one of its sites", listOf("strict"), opened(world, null, under))
        val path = checklistBlock("easy", target = "com.other").copy(targetSites = setOf("youtube.com/shorts"))
        assertEquals("on a path of one of its sites", listOf("strict"), opened(world, null, path))
        val apart = checklistBlock("easy", target = "com.other").copy(targetSites = setOf("notyoutube.com"))
        assertEquals("nothing in common", emptyList<String>(), opened(world, null, apart))
        val limit = modeBlock("easy", BlockMode.ALLOWANCE, targetPackages = setOf("com.insta"), dailyAllowanceMs = 10L * 60_000)
        assertEquals("a block with no unlock opens nothing", emptyList<String>(), opened(world, null, limit))
        val hours = World(blocks = listOf(modeBlock("hours", BlockMode.SCHEDULE, targetPackages = setOf("com.insta"))))
        assertEquals("an unlock never opens a strict mode (I22)", emptyList<String>(), opened(hours, null, easy))
        val laptop = World(blocks = listOf(strict.copy(devices = Devices.LAPTOP)))
        assertEquals("nowhere both hold", emptyList<String>(), opened(laptop, null, easy.copy(devices = Devices.PHONE)))

        // Edits: given its target or another device, or made easier while sharing one.
        val both = world.copy(blocks = listOf(strict, apart))
        assertEquals("renamed", emptyList<String>(), opened(both, apart, apart.copy(name = "Renamed")))
        assertEquals("given its app", listOf("strict"), opened(both, apart, apart.copy(targetPackages = setOf("com.other", "com.insta"))))
        val sharing = world.copy(blocks = listOf(strict, easy))
        assertEquals("already sharing, made stricter", emptyList<String>(), opened(sharing, easy, easy.copy(name = "Renamed", targetPackages = setOf("com.insta", "com.x"))))
        assertEquals("already sharing, made easier", listOf("strict"), opened(sharing, easy, easy.copy(reward = Reward(unlockDurationMs = 60L * 60_000))))
        assertEquals("its checklist made easier", listOf("strict"), opened(sharing, easy, easy, loosened = true))
        val phone = easy.copy(devices = Devices.PHONE)
        assertEquals("held on one more device", listOf("strict"), opened(sharing, phone, phone.copy(devices = Devices.BOTH)))
        val schedule = modeBlock("easy", BlockMode.SCHEDULE, targetPackages = setOf("com.insta"))
        assertEquals("given a habit", listOf("strict"), opened(sharing, schedule, easy))
        assertEquals("not itself", emptyList<String>(), opened(world, strict, strict.copy(reward = Reward(unlockDurationMs = 60L * 60_000))))
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
