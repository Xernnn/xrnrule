package com.xern.xrnrule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's block form (§2.3): what it makes, and what it says loosens a block. */
class BlockDraftTest {

    private val habits = listOf(
        habit("read", HabitKind.CHECK),
        habit("run", HabitKind.TIMER, goal = 20),
        habit("gym", HabitKind.CHECK, freqNum = 3, freqDen = 7),
    )

    private fun ids(): () -> String {
        var n = 0
        return { "n${n++}" }
    }

    /** A block made by the form for a new block, and its checklist. */
    private fun made(draft: BlockDraft, id: String = "b1"): Pair<Block, List<ChecklistItem>> =
        draft.toBlock(id, "auto") to draft.toItems(id, emptyList(), ids())

    private val habitBlock = BlockDraft(
        habits = habits,
        name = "Social",
        apps = setOf("com.instagram"),
        sites = setOf("reddit.com"),
        exceptSites = setOf("old.reddit.com", "unrelated.com"),
        manualOn = true,
        habitIds = listOf("read"),
        tasks = listOf(TaskDraft("k1", null, " Stretch ")),
        focusOn = true,
        loopOn = true,
        links = listOf(LinkDraft(null, "gym", "gym", yesterday = true, goal = false, daily = false)),
        unlockMinutes = 20,
    ).withHabit("run")

    @Test
    fun aNewHabitBlockIsWhatTheFormSays() {
        val (block, items) = made(habitBlock)
        assertEquals(BlockMode.HABIT, block.mode)
        assertEquals("Social", block.name)
        assertEquals("an exception outside the blocked sites is dropped", setOf("old.reddit.com"), block.exceptSites)
        assertEquals(TriggerType.CHECKLIST, block.trigger.type)
        assertEquals(20L * 60_000, block.reward.unlockDurationMs)
        assertEquals(listOf("read", "run", null, "gym"), items.map { it.habitId })
        assertEquals("Stretch", items[2].text)
        assertEquals(LinkedDay.YESTERDAY, items[3].linkedDay)
        assertEquals(Schedule.ALWAYS, block.schedule)
    }

    @Test
    fun everyKindOfBlockComesBackFromTheFormUnchanged() {
        val drafts = listOf(
            habitBlock,
            habitBlock.copy(appTimeOn = true, appTimeApps = setOf("com.duolingo"), appTimeMinutes = 10, combineMode = CombineMode.OR, selfPaced = true),
            habitBlock.copy(manualOn = false, focusOn = false, loopOn = false, timerOn = true, timerMinutes = 5, hardcore = true),
            BlockDraft(name = "YouTube", sites = setOf("youtube.com")).withKind(FormKind.LIMIT, true)
                .copy(dailyMinutes = 45, rowOn = true, rowMinutes = 20, rowBreakMinutes = 10, perDevice = true),
            BlockDraft(name = "Evenings", apps = setOf("com.x"), whenMode = WhenMode.SCHEDULE, scheduleDays = setOf(1, 2, 3), timeOn = true,
                startMinute = 22 * 60, endMinute = 6 * 60, moreWindows = listOf(DayWindow(setOf(6, 7), TimeWindow(9 * 60, 12 * 60)))),
            BlockDraft(name = "Focus", sites = setOf("x.com"), whenMode = WhenMode.FOR_A_WHILE, lockdownMinutes = 90, devices = Devices.LAPTOP),
            BlockDraft(name = "Phone", apps = setOf("com.x"), devices = Devices.PHONE),
            habitBlock.copy(tagOn = true, tags = listOf(TagDraft("k", null, "the kitchen tag", Tags.id(byteArrayOf(1, 2, 3, 4))))),
            BlockDraft(name = "Tag only", apps = setOf("com.x")).withKind(FormKind.TAG, true)
                .copy(tags = listOf(TagDraft("k", null, "desk", Tags.id(byteArrayOf(5))))),
        )
        for (draft in drafts) {
            val (block, items) = made(draft)
            val again = BlockDraft.of(block, items, habits)
            assertEquals("${block.name}: the block", block, again.toBlock(block.id, "other"))
            assertEquals("${block.name}: its checklist", items, again.toItems(block.id, items, ids()))
            assertFalse("${block.name}: an unchanged save doesn't loosen", again.saveLoosens(items))
            assertNull("${block.name}: and can be saved", again.hint(60))
        }
    }

    @Test
    fun aSaveLoosensWhenItMakesTheBlockEasierToGetPast() {
        val (block, items) = made(habitBlock)
        val form = BlockDraft.of(block, items, habits)
        fun loosens(change: (BlockDraft) -> BlockDraft) = change(form).saveLoosens(items)

        assertTrue("an app removed", loosens { it.copy(apps = emptySet()) })
        assertFalse("an app added", loosens { it.copy(apps = it.apps + "com.tiktok") })
        assertTrue("a site removed", loosens { it.copy(sites = emptySet()) })
        assertTrue("a longer unlock", loosens { it.copy(unlockMinutes = 30) })
        assertFalse("a shorter unlock", loosens { it.copy(unlockMinutes = 10) })
        assertTrue("self paced on (I41)", loosens { it.copy(selfPaced = true) })
        assertTrue("a task removed", loosens { it.copy(tasks = emptyList()) })
        assertTrue("a habit removed", loosens { it.copy(habitIds = listOf("run")) })
        assertFalse("a habit added", loosens { it.copy(habitIds = it.habitIds + "gym") })
        assertTrue("a tracked habit counting another day", loosens { d -> d.copy(links = d.links.map { it.copy(yesterday = false) }) })
        assertTrue("a tracked habit counting goal days", loosens { d -> d.copy(links = d.links.map { it.copy(goal = true) }) })
        assertTrue("a schedule", loosens { it.copy(whenMode = WhenMode.SCHEDULE, scheduleDays = setOf(1)) })
        assertFalse("a new exception that matches nothing", loosens { it.copy(exceptSites = it.exceptSites + "nothing.org") })
        assertTrue("an exception under a blocked site", loosens { it.copy(exceptSites = it.exceptSites + "new.reddit.com") })
        assertTrue("phone only (I42)", loosens { it.copy(devices = Devices.PHONE) })
        assertFalse("a tag added (I43)", loosens { it.copy(tagOn = true, tags = listOf(TagDraft("k", null, "desk", Tags.id(byteArrayOf(5))))) })
    }

    @Test
    fun aSaveIsJudgedAgainstTheBlockAsItIsNow() {
        // I39: the edit page opened on a 30-minute limit; meanwhile the laptop cut it to 10 and
        // locked it. Saving the page's 30 was "no change" against what it opened on, so it went
        // through with no guard and no lock, and its newer stamp won the merge.
        val clock = FakeClock()
        val (opened, items) = made(BlockDraft(apps = setOf("com.instagram"), limitOn = true, dailyMinutes = 30))
        val page = BlockDraft.of(opened, items, habits).copy(name = "Renamed")
        val lock = BlockLock(untilWall = clock.nowWallMs() + DAY_MS, setAtWall = clock.nowWallMs())
        val stored = opened.copy(dailyAllowanceMs = 10L * 60_000, lock = lock, targetDesktopApps = setOf("steam"))

        assertTrue("30 minutes over 10 loosens", page.onto(stored).saveLoosens(items))
        val saved = page.onto(stored).toBlock(stored.id, stored.name)
        assertEquals("the lock as stored, so it isn't a lock taken off", lock, saved.lock)
        assertEquals("what the page doesn't show, as stored", setOf("steam"), saved.targetDesktopApps)
        assertFalse("a rename of the block as it is", BlockDraft.of(stored, items, habits).copy(name = "Renamed").onto(stored).saveLoosens(items))

        // The checklist too: a task added on the laptop meanwhile isn't on the page, and saving drops it.
        val (habitOpened, habitItems) = made(habitBlock)
        val habitPage = BlockDraft.of(habitOpened, habitItems, habits)
        val addedThere = habitItems + ChecklistItem(id = "there", blockId = habitOpened.id, text = "Walk", order = 9)
        assertTrue(habitPage.onto(habitOpened).saveLoosens(addedThere))
    }

    @Test
    fun takingOffHardcoreLoosens() {
        val (block, items) = made(habitBlock.copy(hardcore = true))
        assertTrue(BlockDraft.of(block, items, habits).copy(hardcore = false).saveLoosens(items))
    }

    @Test
    fun aBlockTakesAHabitALimitAndHoursTogether() {
        val habit = BlockDraft(habits = habits, apps = setOf("a")).withKind(FormKind.MANUAL, true).withHabit("read")
        assertEquals(BlockMode.HABIT, habit.mode)
        val both = habit.withKind(FormKind.LIMIT, true)
        assertTrue("a limit keeps the habit", both.manualOn)
        assertTrue(both.limitOn)
        assertEquals(BlockMode.HABIT, both.mode)
        assertTrue(both.orderMatters)
        assertTrue("and a habit keeps the limit", BlockDraft(apps = setOf("a")).withKind(FormKind.LIMIT, true).withKind(FormKind.MANUAL, true).limitOn)

        val draft = both.copy(dailyMinutes = 20, habitFirst = true, hoursOn = true, hoursDays = (1..5).toSet(), hoursStart = 0, hoursEnd = 14 * 60)
        val (block, items) = made(draft)
        assertEquals(20L * 60_000, block.dailyAllowanceMs)
        assertTrue(block.habitFirst)
        assertEquals(Schedule((1..5).toSet(), TimeWindow(0, 14 * 60)), block.blockedHours)
        assertEquals("and back through the form", block, BlockDraft.of(block, items, habits).toBlock(block.id, block.name))
    }

    @Test
    fun hoursKeepMeaningBlockedAsRulesComeAndGo() {
        val hours = Schedule((1..6).toSet(), TimeWindow(0, 14 * 60), listOf(DayWindow((1..7).toSet(), TimeWindow(18 * 60, 22 * 60))))
        val strict = made(BlockDraft(apps = setOf("a"), whenMode = WhenMode.SCHEDULE, scheduleDays = hours.activeDays, timeOn = true, startMinute = 0, endMinute = 14 * 60, moreWindows = hours.moreWindows)).first
        assertEquals(hours, strict.schedule)
        assertNull(strict.blockedHours)

        val limited = BlockDraft.of(strict, emptyList(), habits).withKind(FormKind.LIMIT, true)
        val block = limited.toBlock(strict.id, strict.name)
        assertEquals(BlockMode.ALLOWANCE, block.mode)
        assertEquals("they move to the blocked hours", hours, block.blockedHours)
        assertEquals(Schedule.ALWAYS, block.schedule)
        assertFalse("adding a limit to hours loosens nothing", limited.saveLoosens(emptyList()))

        val back = BlockDraft.of(block, emptyList(), habits).withKind(FormKind.LIMIT, false).toBlock(block.id, block.name)
        assertEquals(BlockMode.SCHEDULE, back.mode)
        assertEquals(hours, back.schedule)
        assertNull(back.blockedHours)
    }

    @Test
    fun anEditCanAddAndDropRules() {
        val (limit, _) = made(BlockDraft(apps = setOf("a")).withKind(FormKind.LIMIT, true))
        val withHabit = BlockDraft.of(limit, emptyList(), habits).withKind(FormKind.MANUAL, true).withHabit("read")
        val (block, items) = withHabit.toBlock(limit.id, limit.name) to withHabit.toItems(limit.id, emptyList(), ids())
        assertEquals("a limit block gains a habit", BlockMode.HABIT, block.mode)
        assertTrue(block.hasLimit)
        assertEquals(1, items.size)
        assertTrue("the habit is a way past the limit", withHabit.saveLoosens(emptyList()))
        assertFalse("not with the habit first", withHabit.copy(habitFirst = true).saveLoosens(emptyList()))

        val dropped = BlockDraft.of(block, items, habits).withKind(FormKind.MANUAL, false)
        assertEquals(BlockMode.ALLOWANCE, dropped.mode)
        assertTrue("its items go with it", dropped.toItems(block.id, items).isEmpty())
        assertTrue(dropped.saveLoosens(items))
    }

    @Test
    fun hoursAndOrderOnlyStayWhileTheyMeanSomething() {
        val habit = BlockDraft(apps = setOf("a"), habits = habits, habitFirst = true).withKind(FormKind.MANUAL, true).withHabit("read")
        assertFalse("no limit, no order", made(habit).first.habitFirst)
        val strict = BlockDraft(apps = setOf("a"), hoursOn = true, whenMode = WhenMode.SCHEDULE)
        assertNull("a schedule's hours are its schedule", made(strict).first.blockedHours)
        assertEquals("Blocked hours can't start and end at the same time", habit.copy(hoursOn = true, hoursStart = 60, hoursEnd = 60).whenHint(60))
        assertEquals("Pick at least one day for the blocked hours", habit.copy(hoursOn = true, hoursDays = emptySet()).whenHint(60))
    }

    @Test
    fun aSaveMovesTheLimitCountWithTheBlocksMode() {
        val clock = FakeClock()
        val limit = modeBlock("b", BlockMode.ALLOWANCE, dailyAllowanceMs = 30L * 60_000)
        val used = HabitProgress("b", 12L * 60_000, clock.startOfLocalDayMs(), 0)
        val world = buildWorld(listOf(limit), clock = clock).copy(progress = mapOf("b" to used))
        val habit = limit.copy(mode = BlockMode.HABIT, trigger = Trigger(TriggerType.TIMER, 30L * 60_000, TriggerUnit.MS))
        val progress = world.withBlock(habit, emptyList()).progress.getValue("b")
        assertEquals("not a head start on its timer", 0L, progress.accumulatedMs)
        assertEquals(12L * 60_000, progress.limitUsedMs)

        val earned = world.withBlock(habit, emptyList()).let { w ->
            w.copy(blocks = w.blocks.map { it.copy(state = BlockState.EARNED) })
        }
        val back = earned.withBlock(limit, emptyList())
        assertEquals("only a habit block has states", BlockState.ARMED, back.block("b")!!.state)
        assertEquals(12L * 60_000, back.progress.getValue("b").accumulatedMs)
    }

    @Test
    fun anEditKeepsSomeThingsApart() {
        val habit = BlockDraft(habits = habits, apps = setOf("a")).withKind(FormKind.MANUAL, true).withHabit("read")
        val appTime = habit.withKind(FormKind.TIMER, true).withKind(FormKind.APP_TIME, true)
        assertTrue(appTime.appTimeOn)
        assertFalse("one timer per block", appTime.timerOn)

        val lockdown = BlockDraft(apps = setOf("a"), whenMode = WhenMode.FOR_A_WHILE)
        assertEquals(BlockMode.LOCKDOWN, lockdown.mode)
        assertEquals("something to unlock it isn't a lockdown", WhenMode.ALWAYS, lockdown.withKind(FormKind.MANUAL, true).whenMode)
        val keptLockdown = BlockDraft.of(made(lockdown).first, emptyList(), habits).withKind(FormKind.LIMIT, true)
        assertEquals("a lockdown stays one", BlockMode.LOCKDOWN, keptLockdown.mode)
    }

    @Test
    fun theHintSaysWhatsMissing() {
        assertEquals("Pick an app or a website to block", BlockDraft().hint(60))
        assertEquals("Pick or make a habit", BlockDraft(apps = setOf("a"), manualOn = true).hint(60))
        assertEquals("Pick at least one day", BlockDraft(apps = setOf("a"), whenMode = WhenMode.SCHEDULE, scheduleDays = emptySet()).hint(60))
        assertEquals(
            "An unlock can't be longer than your daily unlock limit (60 min, in Settings)",
            habitBlock.copy(unlockMinutes = 90).hint(60),
        )
        val (block, items) = made(habitBlock)
        assertEquals("Give the block a name", BlockDraft.of(block, items, habits).copy(name = " ").hint(60))
        assertNull("a new block is named for you", habitBlock.copy(name = "").hint(60))
        assertEquals(
            "a laptop-only block of phone apps blocks nothing",
            "Phone apps aren't blocked on the laptop: add a website, or laptop apps on the laptop's page",
            BlockDraft(apps = setOf("a"), devices = Devices.LAPTOP).hint(60),
        )
    }

    @Test
    fun linksKeepTheirChoices() {
        val (block, items) = made(habitBlock)
        val form = BlockDraft.of(block, items, habits).withLinks(setOf("gym", "read"))
        assertEquals(listOf("gym", "read"), form.links.map { it.uuid })
        assertTrue("the kept one keeps yesterday", form.links[0].yesterday)
        assertFalse("a new one counts today", form.links[1].yesterday)
        assertTrue("an every-day habit has no goal-days choice", form.links[1].daily)
    }

    @Test
    fun itemsLoosenWhenOneGoesOrCountsMoreEasily() {
        val item = ChecklistItem(id = "i", blockId = "b", text = "gym", order = 0, habitId = "gym")
        assertTrue(itemsLoosen(listOf(item), emptyList()))
        assertFalse(itemsLoosen(emptyList(), listOf(item)))
        assertTrue(itemsLoosen(listOf(item), listOf(item.copy(linkedDay = LinkedDay.YESTERDAY))))
        assertTrue(itemsLoosen(listOf(item), listOf(item.copy(countsGoal = true))))
        assertFalse(itemsLoosen(listOf(item.copy(countsGoal = true)), listOf(item)))
    }

    @Test
    fun anEditKeepsStateAndLock_andADeleteTakesEverything() {
        val clock = FakeClock()
        val (block, items) = made(habitBlock)
        val lock = BlockLock(setAtWall = clock.nowWallMs(), untilWall = clock.nowWallMs() + DAY_MS)
        val world = buildWorld(listOf(block.copy(state = BlockState.EARNED, lock = lock)), mapOf(block.id to items), clock = clock)

        val edited = world.withBlock(block.copy(name = "Renamed", state = BlockState.ARMED, lock = null), items)
        assertEquals("Renamed", edited.block(block.id)!!.name)
        assertEquals(BlockState.EARNED, edited.block(block.id)!!.state)
        assertEquals(lock, edited.block(block.id)!!.lock)

        val gone = edited.deleteBlock(block.id)
        assertTrue(gone.blocks.isEmpty())
        assertTrue(gone.checklist.isEmpty() && gone.progress.isEmpty())
    }

    @Test
    fun aNewBlockCountsUseRatherThanTheClock() {
        val fresh = BlockDraft.of(null, emptyList(), habits)
        assertTrue(fresh.selfPaced)
        val (block, _) = made(habitBlock.copy(selfPaced = fresh.selfPaced))
        assertTrue(block.selfPaced)
    }

    @Test
    fun aNewLimitHasNoUnlockToPace() {
        val fresh = BlockDraft.of(null, emptyList(), habits).copy(name = "YouTube", sites = setOf("youtube.com"), limitOn = true)
        assertFalse(fresh.toBlock("b1", "auto").selfPaced)
    }

    @Test
    fun aBlockAlreadyMadeKeepsHowItCounts() {
        val (timer, timerItems) = made(habitBlock)
        assertFalse(BlockDraft.of(timer, timerItems, habits).selfPaced)
        val (paced, pacedItems) = made(habitBlock.copy(selfPaced = true))
        assertTrue(BlockDraft.of(paced, pacedItems, habits).selfPaced)
    }

    @Test
    fun opensAndThePauseGoOnALimitAndComeBack() {
        val draft = BlockDraft(name = "x", apps = setOf("a"), limitOn = true, dailyOn = false, opensOn = true, opensPerDay = 2, openWaitS = 5)
        assertNull(draft.unlockHint())
        val block = draft.toBlock("b", "x")
        assertEquals(2, block.opensPerDay)
        assertEquals(5, block.openWaitS)
        assertEquals(0L, block.dailyAllowanceMs)

        val back = BlockDraft.of(block, emptyList(), emptyList())
        assertTrue(back.opensOn)
        assertEquals(2, back.opensPerDay)
        assertEquals(5, back.openWaitS)
        assertFalse(back.dailyOn)
    }

    @Test
    fun aLimitWithNothingToCountIsNotReady() {
        assertNotNull(BlockDraft(name = "x", apps = setOf("a"), limitOn = true, dailyOn = false).unlockHint())
    }

    @Test
    fun droppingTheLimitDropsOpensAndThePause() {
        val habit = BlockDraft(name = "x", apps = setOf("a"), timerOn = true, opensOn = true, openWaitS = 5).toBlock("b", "x")
        assertEquals(0, habit.opensPerDay)
        assertEquals(0, habit.openWaitS)
    }
}
