package com.xern.xrnrule.desktop

import com.xern.xrnrule.desktop.BlockForms.Form
import com.xern.xrnrule.desktop.BlockForms.LinkedHabit
import com.xern.xrnrule.desktop.BlockForms.Result
import com.xern.xrnrule.domain.BlockEdits
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.BlockState
import com.xern.xrnrule.domain.Clock
import com.xern.xrnrule.domain.CombineMode
import com.xern.xrnrule.domain.DayWindow
import com.xern.xrnrule.domain.Devices
import com.xern.xrnrule.domain.EnforcementTier
import com.xern.xrnrule.domain.LinkedDay
import com.xern.xrnrule.domain.Schedule
import com.xern.xrnrule.domain.TimeWindow
import com.xern.xrnrule.domain.TriggerType
import com.xern.xrnrule.domain.World
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockFormsTest {

    private val clock = object : Clock {
        override fun nowMonotonicMs() = 5_000_000L
        override fun nowWallMs() = 1_800_000_000_000L
        override fun zone(): ZoneId = ZoneId.of("UTC")
    }

    private fun ok(r: Result) = (r as? Result.Ok) ?: error("expected Ok, got $r")

    private fun reason(r: Result) = (r as? Result.Invalid)?.reason ?: error("expected Invalid, got $r")

    @Test
    fun aHabitBlockGetsItsTasksAndLinkedHabits() {
        val r = ok(
            BlockForms.create(
                World(),
                Form(
                    name = " Games ",
                    desktopApps = listOf("steam"),
                    sites = listOf("https://www.Reddit.com/r/x"),
                    items = listOf("Stretch", " "),
                    linked = listOf(LinkedHabit("u1", "Plank", yesterday = true, goal = true)),
                ),
                clock,
            ),
        )
        val b = r.block
        assertEquals("Games", b.name)
        assertEquals(setOf("steam"), b.targetDesktopApps)
        assertEquals(setOf("reddit.com/r/x"), b.targetSites) // I46
        assertEquals(TriggerType.CHECKLIST, b.trigger.type)
        assertEquals(EnforcementTier.SOFT, b.enforcementTier)
        val items = r.world.items(b.id)
        assertEquals(listOf("Stretch", "Plank"), items.map { it.text })
        assertEquals("u1", items[1].habitId)
        assertEquals(LinkedDay.YESTERDAY, items[1].linkedDay)
        assertTrue(items[1].countsGoal)
    }

    @Test
    fun theFormSaysWhatsMissing_inThePhonesWords() {
        assertEquals("Pick a laptop app or add a website to block", reason(BlockForms.create(World(), Form(name = "x", items = listOf("a")), clock)))
        assertEquals("Give the block a name", reason(BlockForms.create(World(), Form(sites = listOf("a.com"), items = listOf("a")), clock)))
        assertEquals("Add at least one task", reason(BlockForms.create(World(), Form(name = "x", sites = listOf("a.com")), clock)))
        assertEquals(
            "An unlock can't be longer than your daily unlock limit (60 min)",
            reason(BlockForms.create(World(), Form(name = "x", sites = listOf("a.com"), items = listOf("a"), unlockMin = 90), clock)),
        )
        assertEquals(
            "Start and end time can't be the same",
            reason(BlockForms.create(World(), Form(name = "x", sites = listOf("a.com"), mode = BlockMode.SCHEDULE, startMin = 600, endMin = 600), clock)),
        )
    }

    @Test
    fun aBlockCanHoldOnOneDevice() {
        val phoneOnly = ok(BlockForms.create(World(), Form(name = "x", sites = listOf("a.com"), items = listOf("a"), devices = Devices.PHONE), clock))
        assertEquals(Devices.PHONE, phoneOnly.block.devices)
        assertEquals(
            "Laptop apps aren't blocked on the phone: add a website, or phone apps on the phone",
            reason(BlockForms.create(World(), Form(name = "y", desktopApps = listOf("steam"), items = listOf("a"), devices = Devices.PHONE), clock)),
        )
        val both = ok(BlockForms.edit(phoneOnly.world, Form(id = phoneOnly.block.id, name = "x", sites = listOf("a.com"), items = listOf("a"))))
        assertEquals(Devices.BOTH, both.block.devices)
    }

    @Test
    fun selfPacedIsForHabitBlocksOnly() {
        val habit = ok(BlockForms.create(World(), Form(name = "x", sites = listOf("a.com"), items = listOf("a"), selfPaced = true), clock))
        assertTrue(habit.block.selfPaced)
        val limit = ok(BlockForms.create(World(), Form(name = "y", sites = listOf("b.com"), mode = BlockMode.ALLOWANCE, selfPaced = true), clock))
        assertFalse(limit.block.selfPaced)
        val back = ok(BlockForms.edit(habit.world, Form(id = habit.block.id, name = "x", sites = listOf("a.com"), items = listOf("a"), selfPaced = false)))
        assertFalse(back.block.selfPaced)
    }

    @Test
    fun aLockdownStartsWhenMade_andATimerAndTasksCombine() {
        val lockdown = ok(BlockForms.create(World(), Form(name = "Focus", sites = listOf("a.com"), mode = BlockMode.LOCKDOWN, lockdownMin = 45), clock))
        assertEquals(45 * 60_000L, lockdown.world.lockdowns[lockdown.block.id]?.durationMs)

        val both = ok(BlockForms.create(World(), Form(name = "Both", sites = listOf("a.com"), timer = true, timerMin = 20, combineOr = true, items = listOf("a")), clock))
        assertEquals(TriggerType.COMBINED, both.block.trigger.type)
        assertEquals(CombineMode.OR, both.block.trigger.combineMode)
        assertEquals(20 * 60_000L, both.block.trigger.requiredAmount)
    }

    @Test
    fun anEditKeepsKindMethodPhoneAppsAndState_andTakingASiteAwayLoosens() {
        val made = ok(BlockForms.create(World(), Form(name = "Games", sites = listOf("a.com", "b.com"), items = listOf("a")), clock))
        val world = made.world.copy(
            blocks = made.world.blocks.map { it.copy(targetPackages = setOf("com.game"), state = BlockState.EARNED) },
        )
        val edited = ok(BlockForms.edit(world, Form(id = made.block.id, name = "Games", mode = BlockMode.LOCKDOWN, timer = true, sites = listOf("a.com"))))
        assertEquals(BlockMode.HABIT, edited.block.mode)
        assertEquals(TriggerType.CHECKLIST, edited.block.trigger.type)
        assertEquals(setOf("com.game"), edited.block.targetPackages)
        assertEquals(BlockState.EARNED, edited.block.state)
        assertTrue(BlockEdits.loosens(world.block(made.block.id)!!, edited.block))
        assertFalse(BlockEdits.loosens(world.block(made.block.id)!!, edited.block.copy(targetSites = setOf("a.com", "b.com", "c.com"))))
    }

    @Test
    fun minutesTooManyToCountAreRefused() {
        val made = ok(BlockForms.create(World(), Form(name = "Feeds", mode = BlockMode.ALLOWANCE, sites = listOf("a.com"), allowanceMin = 30), clock))
        // Times 60 000 this wraps around a Long to a limit below zero, which the engine reads as no
        // limit and BlockEdits.loosens doesn't take for a bigger one: it was saved at once, lock or not.
        val huge = Form(id = made.block.id, name = "Feeds", mode = BlockMode.ALLOWANCE, sites = listOf("a.com"), allowanceMin = 200_000_000_000_000)
        assertEquals("That's more minutes than there are in a year", reason(BlockForms.edit(made.world, huge)))
        assertEquals("That's more minutes than there are in a year", reason(BlockForms.create(World(), huge.copy(id = null), clock)))
        ok(BlockForms.edit(made.world, huge.copy(allowanceMin = 24 * 60)))
    }

    @Test
    fun aTaskIsRemovedOnlyFromTheBlockItWasAskedOf() {
        val a = ok(BlockForms.create(World(), Form(name = "A", sites = listOf("a.com"), items = listOf("easy")), clock))
        val b = ok(BlockForms.create(a.world, Form(name = "B", sites = listOf("b.com"), items = listOf("hard")), clock))
        val hard = b.world.items(b.block.id)[0].id
        // The wait, the lock and hardcore were checked for the block named: not a way into another's tasks.
        assertEquals(b.world, BlockForms.removeItem(b.world, a.block.id, hard))
        assertTrue(BlockForms.removeItem(b.world, b.block.id, hard).items(b.block.id).isEmpty())
    }

    @Test
    fun itemsComeAndGo_andDeletingClearsEverything() {
        val made = ok(BlockForms.create(World(), Form(name = "G", sites = listOf("a.com"), items = listOf("a")), clock))
        var w = BlockForms.addItem(made.world, made.block.id, " Read ")
        w = BlockForms.linkHabit(w, made.block.id, LinkedHabit("u1", "Plank"))
        w = BlockForms.linkHabit(w, made.block.id, LinkedHabit("u1", "Plank")) // once only
        assertEquals(listOf("a", "Read", "Plank"), w.items(made.block.id).map { it.text })
        w = BlockForms.removeItem(w, made.block.id, w.items(made.block.id)[0].id)
        assertEquals(listOf(0, 1), w.items(made.block.id).map { it.order })
        w = BlockForms.delete(w, made.block.id)
        assertTrue(w.blocks.isEmpty())
        assertNotNull(w.checklist)
        assertTrue(made.block.id !in w.checklist)
    }

    @Test
    fun aLimitCanHaveARowRuleExceptionsAndItsOwnPerDevice() {
        val r = ok(
            BlockForms.create(
                World(),
                Form(
                    name = "YouTube",
                    mode = BlockMode.ALLOWANCE,
                    sites = listOf("https://www.youtube.com/"),
                    exceptSites = listOf("music.youtube.com"),
                    allowanceMin = 30,
                    rowMin = 15,
                    rowBreakMin = 10,
                    perDevice = true,
                ),
                clock,
            ),
        )
        assertEquals(setOf("youtube.com"), r.block.targetSites)
        assertEquals(setOf("music.youtube.com"), r.block.exceptSites)
        assertEquals(30L * 60_000, r.block.dailyAllowanceMs)
        assertEquals(15L * 60_000, r.block.sessionMs)
        assertEquals(10L * 60_000, r.block.sessionBreakMs)
        assertTrue(r.block.perDevice)

        // No daily limit, only the row rule; but not neither.
        val rowOnly = ok(BlockForms.create(World(), Form(name = "X", mode = BlockMode.ALLOWANCE, sites = listOf("x.com"), dailyOn = false, rowMin = 20), clock))
        assertEquals(0L, rowOnly.block.dailyAllowanceMs)
        assertEquals("Set minutes a day, minutes in a row, or opens a day", reason(BlockForms.create(World(), Form(name = "X", mode = BlockMode.ALLOWANCE, sites = listOf("x.com"), dailyOn = false), clock)))

        // Turning the row rule off loosens the block.
        val edited = ok(BlockForms.edit(r.world, Form(id = r.block.id, name = "YouTube", sites = listOf("youtube.com"), exceptSites = listOf("music.youtube.com"), allowanceMin = 30, rowMin = 0, perDevice = true)))
        assertEquals(0L, edited.block.sessionMs)
        assertTrue(BlockEdits.loosens(r.block, edited.block))
    }

    @Test
    fun aLimitCanBeOpensAloneWithAPause_andLeavingItOutLoosens() {
        val form = Form(name = "Insta", mode = BlockMode.ALLOWANCE, sites = listOf("instagram.com"), dailyOn = false, opensPerDay = 3, openWaitS = 5)
        val r = ok(BlockForms.create(World(), form, clock))
        assertEquals(3, r.block.opensPerDay)
        assertEquals(5, r.block.openWaitS)
        assertEquals(0L, r.block.dailyAllowanceMs)

        val nothing = form.copy(opensPerDay = 0, openWaitS = 0)
        assertTrue(reason(BlockForms.create(World(), nothing, clock)).startsWith("Set minutes a day"))
        val edited = ok(BlockForms.edit(r.world, form.copy(id = r.block.id, openWaitS = 0)))
        assertTrue(BlockEdits.loosens(r.block, edited.block))
    }

    @Test
    fun aHabitBlockCanHoldALimitAndHours_andAnEditKeepsThem() {
        val form = Form(
            name = "YouTube", sites = listOf("youtube.com"), items = listOf("Read"),
            limitOn = true, allowanceMin = 30, rowMin = 15, rowBreakMin = 10, perDevice = true, habitFirst = true,
            hoursOn = true, hoursDays = (1..6).toSet(), hoursStartMin = 0, hoursEndMin = 840,
            hoursMore = listOf(BlockForms.Window((1..7).toSet(), 1080, 1320)),
        )
        val made = ok(BlockForms.create(World(), form, clock))
        val block = made.block
        assertEquals(BlockMode.HABIT, block.mode)
        assertEquals(30L * 60_000, block.dailyAllowanceMs)
        assertEquals(15L * 60_000, block.sessionMs)
        assertEquals(10L * 60_000, block.sessionBreakMs)
        assertTrue(block.perDevice)
        assertTrue(block.habitFirst)
        val hours = Schedule((1..6).toSet(), TimeWindow(0, 840), listOf(DayWindow((1..7).toSet(), TimeWindow(1080, 1320))))
        assertEquals(hours, block.blockedHours)
        assertEquals(Schedule.ALWAYS, block.schedule)

        val same = ok(BlockForms.edit(made.world, form.copy(id = block.id))).block
        assertEquals("nothing lost in an edit", block, same)
        assertFalse(BlockEdits.loosens(block, same))

        val fewer = ok(BlockForms.edit(made.world, form.copy(id = block.id, hoursOn = false))).block
        assertEquals(null, fewer.blockedHours)
        assertTrue("dropping the hours loosens", BlockEdits.loosens(block, fewer))

        val noLimit = ok(BlockForms.edit(made.world, form.copy(id = block.id, limitOn = false))).block
        assertFalse(noLimit.hasLimit)
        assertFalse(noLimit.habitFirst)
        assertTrue("dropping the limit loosens", BlockEdits.loosens(block, noLimit))

        val limitFirst = ok(BlockForms.edit(made.world, form.copy(id = block.id, habitFirst = false))).block
        assertTrue("so does putting the limit first", BlockEdits.loosens(block, limitFirst))
    }

    @Test
    fun aLimitBlockCanHoldHours_butAScheduleKeepsItsOwn() {
        val limit = Form(name = "Reddit", mode = BlockMode.ALLOWANCE, sites = listOf("reddit.com"), hoursOn = true, hoursAllDay = true, hoursDays = setOf(6, 7))
        val made = ok(BlockForms.create(World(), limit, clock)).block
        assertEquals(Schedule(setOf(6, 7), null), made.blockedHours)

        val schedule = Form(name = "Nights", mode = BlockMode.SCHEDULE, sites = listOf("reddit.com"), hoursOn = true, limitOn = true)
        val strict = ok(BlockForms.create(World(), schedule, clock)).block
        assertEquals(null, strict.blockedHours)
        assertFalse(strict.hasLimit)
    }

    @Test
    fun blockedHoursAreChecked() {
        val form = Form(name = "X", sites = listOf("x.com"), items = listOf("Read"), hoursOn = true)
        assertEquals("Pick at least one day for the blocked hours", reason(BlockForms.create(World(), form.copy(hoursDays = emptySet()), clock)))
        assertEquals("Blocked hours can't start and end at the same time", reason(BlockForms.create(World(), form.copy(hoursStartMin = 60, hoursEndMin = 60), clock)))
        assertEquals("Those times don't make sense", reason(BlockForms.create(World(), form.copy(hoursEndMin = 2000), clock)))
        assertEquals("Set how long the break is", reason(BlockForms.create(World(), form.copy(limitOn = true, rowMin = 10, rowBreakMin = 0), clock)))
    }

    @Test
    fun aScheduleCanHaveMoreTimes() {
        val r = ok(
            BlockForms.create(
                World(),
                Form(
                    name = "Social",
                    mode = BlockMode.SCHEDULE,
                    sites = listOf("reddit.com"),
                    days = (1..7).toSet(),
                    startMin = 0,
                    endMin = 14 * 60,
                    moreWindows = listOf(BlockForms.Window((1..7).toSet(), 18 * 60, 23 * 60 + 59)),
                ),
                clock,
            ),
        )
        assertEquals(1, r.block.schedule.moreWindows.size)
        assertEquals(18 * 60, r.block.schedule.moreWindows.single().window.startMinuteOfDay)
        assertEquals(
            "Pick at least one day for each time",
            reason(BlockForms.create(World(), Form(name = "S", mode = BlockMode.SCHEDULE, sites = listOf("reddit.com"), moreWindows = listOf(BlockForms.Window(emptySet()))), clock)),
        )
    }
}
