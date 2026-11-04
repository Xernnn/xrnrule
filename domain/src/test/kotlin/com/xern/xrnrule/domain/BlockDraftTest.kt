package com.xern.xrnrule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    }

    @Test
    fun takingOffHardcoreLoosens() {
        val (block, items) = made(habitBlock.copy(hardcore = true))
        assertTrue(BlockDraft.of(block, items, habits).copy(hardcore = false).saveLoosens(items))
    }

    @Test
    fun aBlockHasOneKind() {
        val habit = BlockDraft(habits = habits, apps = setOf("a")).withKind(FormKind.MANUAL, true).withHabit("read")
        assertEquals(BlockMode.HABIT, habit.mode)
        val limit = habit.withKind(FormKind.LIMIT, true)
        assertEquals(BlockMode.ALLOWANCE, limit.mode)
        assertFalse("a limit drops the habits", limit.manualOn)
        assertEquals("and a habit drops the limit", BlockMode.HABIT, limit.withKind(FormKind.FOCUS, true).mode)

        val appTime = habit.withKind(FormKind.TIMER, true).withKind(FormKind.APP_TIME, true)
        assertTrue(appTime.appTimeOn)
        assertFalse("one timer per block", appTime.timerOn)

        val lockdown = BlockDraft(apps = setOf("a"), whenMode = WhenMode.FOR_A_WHILE)
        assertEquals(BlockMode.LOCKDOWN, lockdown.mode)
        assertEquals("something to unlock it isn't a lockdown", WhenMode.ALWAYS, lockdown.withKind(FormKind.MANUAL, true).whenMode)
        assertEquals("an edit keeps the kind", BlockMode.HABIT, BlockDraft.of(made(habitBlock).first, emptyList(), habits).withKind(FormKind.LIMIT, true).mode)
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
}
