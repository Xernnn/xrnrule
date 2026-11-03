package com.xern.xrnrule.app.ui

import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockEngine
import com.xern.xrnrule.domain.ChecklistItem
import com.xern.xrnrule.domain.Clock
import com.xern.xrnrule.domain.CombineMode
import com.xern.xrnrule.domain.Defaults
import com.xern.xrnrule.domain.Habit
import com.xern.xrnrule.domain.HabitEntry
import com.xern.xrnrule.domain.HabitKind
import com.xern.xrnrule.domain.LinkedDay
import com.xern.xrnrule.domain.TriggerType
import com.xern.xrnrule.domain.World

/**
 * What a HABIT block asks for, one part per row on its card and its block screen: your habits,
 * the tasks you tick, and the timer. Pure: the card builds it from the [World], the block screen
 * from what the overlay was handed.
 */
sealed interface HabitPart {
    val done: Boolean

    /** A task ticked by hand. */
    data class Task(val item: ChecklistItem) : HabitPart {
        override val done get() = item.done
    }

    /**
     * A habit (contract I23, I36): [amount] of it counts toward this block's next unlock, out of
     * the habit's unlock's worth; [spent] is what earlier unlocks of this block used on [day], the
     * habit day the item counts. [dayValue] is that day's entry. [habit] is null once the habit
     * was deleted.
     */
    data class Goal(
        val item: ChecklistItem,
        val habit: Habit?,
        val amount: Long,
        val spent: Long,
        val day: Long,
        val dayValue: Long,
        val focusing: Boolean,
    ) : HabitPart {
        private val unit: Long? get() = habit?.unlockUnit
        override val done get() = item.done || unit?.let { amount >= it } == true
        val name: String get() = habit?.name ?: item.text.ifBlank { "Deleted habit" }
        val fraction: Float get() = unit?.let { (amount.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
        val yesterday: Boolean get() = item.linkedDay == LinkedDay.YESTERDAY
    }

    /** The timer; [appLabel] names the app(s) it counts in, or null when it runs on the block screen (I4, I16). */
    data class Timer(val doneMs: Long, val requiredMs: Long, val running: Boolean, val appLabel: String?) : HabitPart {
        override val done get() = doneMs >= requiredMs
        val fraction: Float get() = if (requiredMs <= 0) 1f else (doneMs.toFloat() / requiredMs).coerceIn(0f, 1f)
    }
}

/** What the rows need to know about each habit item, by item id (I23, I36). */
data class GoalInfo(val habit: Habit?, val amount: Long, val spent: Long, val day: Long, val dayValue: Long)

fun habitParts(
    block: Block,
    items: List<ChecklistItem>,
    timerMs: Long,
    timerRunning: Boolean,
    appLabel: String?,
    goals: Map<String, GoalInfo> = emptyMap(),
    focusHabitId: String? = null,
): List<HabitPart> {
    val type = block.trigger.type
    val checklist = if (type == TriggerType.CHECKLIST || type == TriggerType.COMBINED) {
        items.sortedBy { it.order }.map { item ->
            val habitId = item.habitId ?: item.externalHabitId
            if (habitId != null) {
                val g = goals[item.id]
                HabitPart.Goal(
                    item, g?.habit, g?.amount ?: 0, g?.spent ?: 0, g?.day ?: 0, g?.dayValue ?: HabitEntry.UNKNOWN,
                    focusing = focusHabitId != null && focusHabitId == habitId,
                )
            } else {
                HabitPart.Task(item)
            }
        }
    } else {
        emptyList()
    }
    val timer = if (type == TriggerType.TIMER || type == TriggerType.COMBINED) {
        listOf(HabitPart.Timer(timerMs, block.trigger.requiredAmount, timerRunning, appLabel))
    } else {
        emptyList()
    }
    return checklist + timer
}

fun World.habitParts(block: Block, appLabel: String?, engine: BlockEngine, clock: Clock): List<HabitPart> {
    val p = progress[block.id]
    return habitParts(
        block, items(block.id), p?.accumulatedMs ?: 0, p?.timerRunning == true, appLabel,
        goalInfo(block.id, engine, clock), focus?.takeIf { it.running }?.habitId,
    )
}

/** [GoalInfo] for each habit item of a block, as of now (the focus timer included). */
fun World.goalInfo(blockId: String, engine: BlockEngine, clock: Clock): Map<String, GoalInfo> =
    items(blockId).filter { it.habitId != null }.associate { item ->
        val day = engine.itemDay(this, item, clock)
        val habit = habit(item.habitId!!)
        val value = when {
            habit == null -> HabitEntry.UNKNOWN
            habit.numerical -> engine.habitAmount(this, habit.id, day, clock)
            item.countsGoal -> engine.checkValue(this, habit, day)
            else -> entry(habit.id, day)?.value ?: HabitEntry.UNKNOWN
        }
        item.id to GoalInfo(habit, engine.habitAvailable(this, item, clock), item.spentOn(day), day, value)
    }

/** "DO BOTH", "DO EITHER", "DO 2 OF 3": how the rows add up, or null for a single row. */
fun joinTag(block: Block, parts: List<HabitPart>): String? {
    if (parts.size < 2) return null
    val t = block.trigger
    return when (t.type) {
        TriggerType.COMBINED -> when {
            t.combineMode == CombineMode.OR -> if (parts.size == 2) "Do either" else "Timer or all tasks"
            parts.size == 2 -> "Do both"
            else -> "Do all"
        }
        TriggerType.CHECKLIST -> if (t.requiredAmount == Defaults.ALL_ITEMS || t.requiredAmount >= parts.size) {
            if (parts.size == 2) "Do both" else "Do all"
        } else {
            "Do ${t.requiredAmount} of ${parts.size}"
        }
        TriggerType.TIMER -> null
    }
}

/** How far along a block is overall, 0..1, for a single bar. */
fun partsFraction(block: Block, parts: List<HabitPart>): Float {
    if (parts.isEmpty()) return 0f
    val timer = parts.filterIsInstance<HabitPart.Timer>().firstOrNull()
    val checks = parts.filterNot { it is HabitPart.Timer }
    val checkFraction = if (checks.isEmpty()) null else checks.sumOf { p ->
        (if (p is HabitPart.Goal && !p.done) p.fraction else if (p.done) 1f else 0f).toDouble()
    }.toFloat() / checks.size
    return when {
        timer == null -> checkFraction ?: 0f
        checkFraction == null -> timer.fraction
        block.trigger.combineMode == CombineMode.OR -> maxOf(timer.fraction, checkFraction)
        else -> minOf(timer.fraction, checkFraction)
    }
}

/**
 * One sentence of what's left, as the block screen says it: "Tick Read and Stretch, and run the
 * timer for 25 min more." Null once nothing is.
 */
fun whatsLeft(block: Block, parts: List<HabitPart>): String? {
    val tasks = parts.filterIsInstance<HabitPart.Task>().filterNot { it.done }.map { it.item.text.ifBlank { "your task" } }
    val goals = parts.filterIsInstance<HabitPart.Goal>().filterNot { it.done }
    val checks = goals.filter { it.habit == null || it.habit.kind == HabitKind.CHECK }
    val checkNames = checks.map { it.name }
    val counts = goals.filter { it.habit?.kind == HabitKind.COUNT }
    val focus = goals.filter { it.habit?.kind == HabitKind.TIMER }
    val timer = parts.filterIsInstance<HabitPart.Timer>().firstOrNull()?.takeIf { !it.done }

    val checklistClauses = buildList {
        val need = block.trigger.requiredAmount
        val partial = block.trigger.type == TriggerType.CHECKLIST && need != Defaults.ALL_ITEMS
        if (partial) {
            val doneCount = parts.count { it.done }
            val more = (need - doneCount).coerceAtLeast(0)
            if (more > 0) add("tick $more more of ${andList(tasks + goals.map { it.name })}")
        } else {
            if (checks.isNotEmpty()) {
                add("check off ${andList(checkNames)}" + if (checks.all { it.yesterday }) " for yesterday" else "")
            }
            counts.forEach { add("do ${habitLeftText(it.habit!!, it.amount)}") }
            focus.forEach { add("focus on ${it.name} for ${habitLeftText(it.habit!!, it.amount)}") }
            if (tasks.isNotEmpty()) add("tick ${andList(tasks)}")
        }
    }
    val timerClause = timer?.let {
        val left = formatRemaining(it.requiredMs - it.doneMs)
        if (it.appLabel != null) "spend $left more in ${it.appLabel}" else "run the timer for $left more"
    }

    val checklistText = checklistClauses.takeIf { it.isNotEmpty() }?.joinToString(" and ")
    val sentence = when {
        checklistText == null && timerClause == null -> return null
        checklistText == null -> timerClause!!
        timerClause == null -> checklistText
        block.trigger.combineMode == CombineMode.OR -> "$timerClause, or $checklistText"
        else -> "$checklistText, and $timerClause"
    }
    return sentence.replaceFirstChar { it.uppercase() } + "."
}

/** "Read", "Read and Stretch", "Read, Stretch and Walk". */
fun andList(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
}

/** Time left in a block's unlock, 0 if it has none. */
fun sessionLeftMs(world: World, blockId: String, clock: Clock): Long {
    val s = world.sessions[blockId] ?: return 0
    return (s.durationMs - (clock.nowMonotonicMs() - s.startedAtMonotonic)).coerceAtLeast(0)
}

/** "1h 14m", "25m", "40s": a compact countdown for the card's corner. */
fun compactDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "${s}s"
    }
}
