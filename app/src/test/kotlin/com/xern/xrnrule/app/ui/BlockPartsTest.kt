package com.xern.xrnrule.app.ui

import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.ChecklistItem
import com.xern.xrnrule.domain.CombineMode
import com.xern.xrnrule.domain.Defaults
import com.xern.xrnrule.domain.Reward
import com.xern.xrnrule.domain.Trigger
import com.xern.xrnrule.domain.TriggerType
import com.xern.xrnrule.domain.TriggerUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a block asks for in words: the sentence on the block screen and the tag above its rows. */
class BlockPartsTest {
    private fun block(trigger: Trigger) = Block(id = "b", name = "B", trigger = trigger, reward = Reward(60_000), mode = BlockMode.HABIT)

    private val allTasks = Trigger(TriggerType.CHECKLIST, Defaults.ALL_ITEMS, TriggerUnit.ITEMS)
    private val timer = Trigger(TriggerType.TIMER, 10L * 60_000, TriggerUnit.MS)

    private fun task(id: String, text: String, done: Boolean = false) =
        HabitPart.Task(ChecklistItem(id = id, blockId = "b", text = text, order = 0, done = done))

    @Test
    fun tasksLeftAreNamed() {
        val parts = listOf(task("1", "Read"), task("2", "Stretch", done = true), task("3", "Walk"))
        assertEquals("Tick Read and Walk.", whatsLeft(block(allTasks), parts))
    }

    @Test
    fun nothingLeftIsNoSentence() {
        assertNull(whatsLeft(block(allTasks), listOf(task("1", "Read", done = true))))
    }

    @Test
    fun aTimerSaysHowLongIsLeft() {
        val parts = listOf(HabitPart.Timer(doneMs = 4L * 60_000, requiredMs = 10L * 60_000, running = false, appLabel = null))
        assertEquals("Run the timer for 6 min more.", whatsLeft(block(timer), parts))
    }

    @Test
    fun oneRowNeedsNoTag() {
        assertNull(joinTag(block(allTasks), listOf(task("1", "Read"))))
    }

    @Test
    fun theTagSaysHowRowsAddUp() {
        val two = listOf(task("1", "Read"), task("2", "Walk"))
        assertEquals("Do both", joinTag(block(allTasks), two))
        assertEquals("Do all", joinTag(block(allTasks), two + task("3", "Run")))
        assertEquals("Do 2 of 3", joinTag(block(allTasks.copy(requiredAmount = 2)), two + task("3", "Run")))
        val either = Trigger(TriggerType.COMBINED, 60_000, TriggerUnit.MS, CombineMode.OR)
        assertEquals("Do either", joinTag(block(either), listOf(task("1", "Read"), HabitPart.Timer(0, 60_000, false, null))))
    }
}
