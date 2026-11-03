package com.xern.xrnrule.app.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.BlockState
import com.xern.xrnrule.domain.ChecklistItem
import com.xern.xrnrule.domain.CombineMode
import com.xern.xrnrule.domain.DayWindow
import com.xern.xrnrule.domain.Defaults
import com.xern.xrnrule.domain.EnforcementTier
import com.xern.xrnrule.domain.Habit
import com.xern.xrnrule.domain.HabitKind
import com.xern.xrnrule.domain.LinkedDay
import com.xern.xrnrule.domain.Reward
import com.xern.xrnrule.domain.Schedule
import com.xern.xrnrule.domain.Sites
import com.xern.xrnrule.domain.TimeWindow
import com.xern.xrnrule.domain.Trigger
import com.xern.xrnrule.domain.TriggerType
import com.xern.xrnrule.domain.TriggerUnit
import com.xern.xrnrule.domain.World
import java.util.UUID

/** Step 3 of a new block: always on, on a schedule, or (with nothing to unlock it) for a while. */
enum class WhenMode { ALWAYS, SCHEDULE, FOR_A_WHILE }

/** A task row being edited; [id] is the checklist item it was, null for a new one. */
data class TaskDraft(val key: String, val id: String?, val text: String)

/**
 * A habit item with a day rule being edited (contract I23: yesterday's entry, or days its goal is
 * met); [itemId] is null for a new one.
 */
data class LinkDraft(
    val itemId: String?,
    val uuid: String,
    val name: String,
    val yesterday: Boolean,
    val goal: Boolean,
    /** Every-day habits have no "goal days" choice: ticked and goal met are the same day. */
    val daily: Boolean,
)

/**
 * Everything the new-block wizard and the edit page let you set, in the words of the form
 * ("app time", "manual timer", "nothing, just block it"), and the [Block] it makes. A block's
 * kind can't change once made (an edit keeps HABIT a HABIT); within a HABIT block anything can.
 */
@Stable
class BlockFormState(val existing: Block?, existingItems: List<ChecklistItem>, habits: List<Habit> = emptyList()) {
    val editing get() = existing != null

    var name by mutableStateOf(existing?.name ?: "")
    var apps by mutableStateOf(existing?.targetPackages ?: emptySet())
    var sites by mutableStateOf(existing?.targetSites ?: emptySet())
    var exceptSites by mutableStateOf(existing?.exceptSites ?: emptySet())

    private val trigger = existing?.trigger
    private val hadTimer = existing?.mode == BlockMode.HABIT &&
        (trigger?.type == TriggerType.TIMER || trigger?.type == TriggerType.COMBINED)
    private val timerMinutesOf = trigger?.takeIf { hadTimer }?.requiredAmount?.div(60_000)

    // ---- what unlocks it ----
    var appTimeOn by mutableStateOf(hadTimer && trigger!!.habitPackages.isNotEmpty())
    var appTimeApps by mutableStateOf(trigger?.habitPackages ?: emptySet())
    var appTimeMinutes by mutableLongStateOf(if (appTimeOn) timerMinutesOf!! else 15L)

    var timerOn by mutableStateOf(hadTimer && trigger!!.habitPackages.isEmpty())
    var timerMinutes by mutableLongStateOf(if (timerOn) timerMinutesOf!! else 30L)

    /** Every habit there is, kept current by the screen: the kinds below are looked up here. */
    var knownHabits by mutableStateOf(habits)

    /** Tasks typed into a block before habits were shared ones; kept until removed. */
    var tasks by mutableStateOf(
        existingItems.filter { !it.derived }.sortedBy { it.order }
            .map { TaskDraft(it.id, it.id, it.text) },
    )

    /** Habit items with a day rule (yesterday's, or goal days): the "Tracked habit" card. */
    private fun hasDayRule(item: ChecklistItem) = item.linkedDay == LinkedDay.YESTERDAY || item.countsGoal

    /** The habits the block asks for with no day rule (contract I36), in order. */
    var habitIds by mutableStateOf(
        existingItems.filter { it.derived && !hasDayRule(it) }.sortedBy { it.order }.map { it.habitId ?: it.externalHabitId!! },
    )

    private fun isTimer(habitId: String) = knownHabits.firstOrNull { it.id == habitId }?.kind == HabitKind.TIMER

    /** Picked habits you tick or count ("Manual habit"), and timer habits ("Manual timer"). */
    val doHabits get() = habitIds.filterNot(::isTimer)
    val timerHabits get() = habitIds.filter(::isTimer)

    var manualOn by mutableStateOf(tasks.isNotEmpty() || doHabits.isNotEmpty())
    var focusOn by mutableStateOf(timerHabits.isNotEmpty())

    var links by mutableStateOf(
        existingItems.filter { it.derived && hasDayRule(it) }.sortedBy { it.order }.map {
            val id = it.habitId ?: it.externalHabitId!!
            val habit = habits.firstOrNull { h -> h.id == id }
            LinkDraft(it.id, id, habit?.name ?: it.text, it.linkedDay == LinkedDay.YESTERDAY, it.countsGoal, daily = habit?.let(::isDaily) ?: !it.countsGoal)
        },
    )
    var loopOn by mutableStateOf(links.isNotEmpty())

    var combineMode by mutableStateOf(trigger?.combineMode ?: CombineMode.AND)

    // ---- daily limit ----
    private val limit = existing?.takeIf { it.mode == BlockMode.ALLOWANCE }
    var limitOn by mutableStateOf(limit != null)
    var dailyOn by mutableStateOf(limit == null || limit.dailyAllowanceMs > 0)
    var dailyMinutes by mutableLongStateOf(limit?.dailyAllowanceMs?.takeIf { it > 0 }?.div(60_000) ?: 30L)
    var rowOn by mutableStateOf(limit?.hasRowRule == true)
    var rowMinutes by mutableLongStateOf(limit?.takeIf { it.hasRowRule }?.sessionMs?.div(60_000) ?: 15L)
    var rowBreakMinutes by mutableLongStateOf(limit?.takeIf { it.hasRowRule }?.sessionBreakMs?.div(60_000) ?: 15L)
    var perDevice by mutableStateOf(limit?.perDevice == true)

    // ---- when ----
    private val schedule = existing?.schedule ?: Schedule.ALWAYS
    var whenMode by mutableStateOf(
        when {
            existing?.mode == BlockMode.LOCKDOWN -> WhenMode.FOR_A_WHILE
            existing != null && schedule != Schedule.ALWAYS -> WhenMode.SCHEDULE
            else -> WhenMode.ALWAYS
        },
    )
    var scheduleDays by mutableStateOf(schedule.activeDays.takeIf { whenMode == WhenMode.SCHEDULE } ?: (1..7).toSet())
    var timeOn by mutableStateOf(schedule.activeWindow != null)
    var startMinute by mutableIntStateOf(schedule.activeWindow?.startMinuteOfDay ?: (9 * 60))
    var endMinute by mutableIntStateOf(schedule.activeWindow?.endMinuteOfDay ?: (17 * 60))
    var moreWindows by mutableStateOf(schedule.moreWindows)
    var lockdownMinutes by mutableLongStateOf(existing?.lockdownMs?.takeIf { it > 0 }?.div(60_000) ?: 60L)

    // ---- the unlock ----
    var unlockMinutes by mutableLongStateOf((existing?.reward?.unlockDurationMs ?: (15L * 60_000)) / 60_000)
    var hardcore by mutableStateOf(existing?.enforcementTier == EnforcementTier.HARDCORE)

    val habitChosen get() = appTimeOn || timerOn || manualOn || focusOn || loopOn
    val hasTimer get() = appTimeOn || timerOn
    val hasChecklist get() = (manualOn && (tasks.any { it.text.isNotBlank() } || doHabits.isNotEmpty())) ||
        (focusOn && timerHabits.isNotEmpty()) || (loopOn && links.isNotEmpty())

    fun addHabit(id: String) {
        if (id !in habitIds) habitIds = habitIds + id
    }

    /** The habits of one card after its picker: the other card's stay as they are. */
    fun setHabits(timers: Boolean, ids: Set<String>) {
        val keep = habitIds.filter { isTimer(it) != timers || it in ids }
        habitIds = keep + ids.filter { it !in keep }
    }

    /** The block's kind, from what's picked: a limit, habits, or nothing (a schedule or a lockdown). */
    val mode: BlockMode
        get() = existing?.mode ?: when {
            limitOn -> BlockMode.ALLOWANCE
            habitChosen -> BlockMode.HABIT
            whenMode == WhenMode.FOR_A_WHILE -> BlockMode.LOCKDOWN
            else -> BlockMode.SCHEDULE
        }

    /** Picking a habit drops the limit and the other way round; a block has one timer, so app time and the timer exclude each other. */
    fun setKind(kind: FormKind, on: Boolean) {
        when (kind) {
            FormKind.APP_TIME -> { appTimeOn = on; if (on) timerOn = false }
            FormKind.TIMER -> { timerOn = on; if (on) appTimeOn = false }
            FormKind.MANUAL -> manualOn = on
            FormKind.FOCUS -> focusOn = on
            FormKind.LOOP -> loopOn = on
            FormKind.LIMIT -> limitOn = on
        }
        if (on && kind == FormKind.LIMIT) { appTimeOn = false; timerOn = false; manualOn = false; focusOn = false; loopOn = false }
        if (on && kind != FormKind.LIMIT) limitOn = false
        if (habitChosen || limitOn) { if (whenMode == WhenMode.FOR_A_WHILE) whenMode = WhenMode.ALWAYS }
    }

    fun newTask() = TaskDraft(UUID.randomUUID().toString(), null, "")

    /** The "Tracked habit" card after its picker: kept ones keep their choices, new ones count today's tick. */
    fun setLinks(ids: Set<String>) {
        val kept = links.filter { it.uuid in ids }
        val added = ids.filter { id -> kept.none { it.uuid == id } }.mapNotNull { id ->
            knownHabits.firstOrNull { it.id == id }?.let { LinkDraft(null, it.id, it.name, yesterday = false, goal = false, daily = isDaily(it)) }
        }
        links = kept + added
    }

    val hasTargets get() = apps.isNotEmpty() || sites.isNotEmpty() || existing?.targetDesktopApps?.isNotEmpty() == true

    /** Why step 1 (what to block) can't go on yet. */
    fun targetsHint(): String? = if (!hasTargets) "Pick an app or a website to block" else null

    /** Why step 2 (what unlocks it) can't go on yet. */
    fun unlockHint(): String? = when {
        appTimeOn && appTimeApps.isEmpty() -> "Pick the apps whose time counts"
        manualOn && tasks.none { it.text.isNotBlank() } && doHabits.isEmpty() -> "Pick or make a habit"
        focusOn && timerHabits.isEmpty() -> "Pick or make a timer"
        loopOn && links.isEmpty() -> "Pick at least one habit"
        limitOn && !dailyOn && !rowOn -> "Set minutes a day, minutes in a row, or both"
        existing?.mode == BlockMode.HABIT && !habitChosen -> "Pick at least one way to unlock it"
        else -> null
    }

    /** Why step 3 (when) can't be saved yet. */
    fun whenHint(capMinutes: Long): String? = when {
        whenMode == WhenMode.SCHEDULE && scheduleDays.isEmpty() -> "Pick at least one day"
        whenMode == WhenMode.SCHEDULE && timeOn && startMinute == endMinute -> "Start and end can't be the same time"
        whenMode == WhenMode.SCHEDULE && moreWindows.any { it.days.isEmpty() } -> "Pick at least one day for each time"
        whenMode == WhenMode.SCHEDULE && moreWindows.any { it.window.startMinuteOfDay == it.window.endMinuteOfDay } ->
            "Start and end can't be the same time"
        mode == BlockMode.LOCKDOWN && lockdownMinutes <= 0 -> "Set how long to block"
        mode == BlockMode.HABIT && unlockMinutes <= 0 -> "Set how long an unlock lasts"
        mode == BlockMode.HABIT && unlockMinutes > capMinutes ->
            "An unlock can't be longer than your daily unlock limit ($capMinutes min, in Settings)"
        else -> null
    }

    fun hint(capMinutes: Long): String? =
        targetsHint() ?: unlockHint() ?: whenHint(capMinutes) ?: if (editing && name.isBlank()) "Give the block a name" else null

    private fun builtSchedule(): Schedule = when {
        mode == BlockMode.LOCKDOWN || whenMode != WhenMode.SCHEDULE -> Schedule.ALWAYS
        else -> Schedule(scheduleDays, if (timeOn) TimeWindow(startMinute, endMinute) else null, moreWindows)
    }

    private fun builtTrigger(): Trigger {
        if (mode != BlockMode.HABIT) return existing?.trigger ?: Trigger(TriggerType.CHECKLIST, Defaults.ALL_ITEMS, TriggerUnit.ITEMS)
        val timerMs = (if (appTimeOn) appTimeMinutes else timerMinutes) * 60_000
        val pkgs = if (appTimeOn) appTimeApps else emptySet()
        return when {
            hasTimer && hasChecklist -> Trigger(TriggerType.COMBINED, timerMs, TriggerUnit.MS, combineMode, pkgs)
            hasTimer -> Trigger(TriggerType.TIMER, timerMs, TriggerUnit.MS, habitPackages = pkgs)
            else -> Trigger(
                TriggerType.CHECKLIST,
                // An old "any N of them" block keeps its N while it stays a checklist.
                existing?.trigger?.takeIf { it.type == TriggerType.CHECKLIST }?.requiredAmount ?: Defaults.ALL_ITEMS,
                TriggerUnit.ITEMS,
            )
        }
    }

    /** The block this form describes: a new one with [id], or [existing] with its settings replaced. */
    fun toBlock(id: String, autoName: String): Block {
        val mode = mode
        val base = existing ?: Block(id = id, name = "", trigger = builtTrigger(), state = BlockState.ARMED)
        return base.copy(
            name = name.trim().ifEmpty { autoName },
            targetPackages = apps,
            targetSites = sites,
            exceptSites = exceptSites.filterTo(mutableSetOf()) { e -> sites.any { Sites.matches(e, it) } },
            trigger = builtTrigger(),
            reward = (existing?.reward ?: Reward()).copy(unlockDurationMs = unlockMinutes * 60_000),
            schedule = builtSchedule(),
            mode = mode,
            dailyAllowanceMs = if (mode == BlockMode.ALLOWANCE) (if (dailyOn) dailyMinutes * 60_000 else 0) else base.dailyAllowanceMs,
            sessionMs = if (mode == BlockMode.ALLOWANCE) (if (rowOn) rowMinutes * 60_000 else 0) else base.sessionMs,
            sessionBreakMs = if (mode == BlockMode.ALLOWANCE) (if (rowOn) rowBreakMinutes * 60_000 else 0) else base.sessionBreakMs,
            perDevice = if (mode == BlockMode.ALLOWANCE) perDevice else base.perDevice,
            lockdownMs = if (mode == BlockMode.LOCKDOWN) lockdownMinutes * 60_000 else base.lockdownMs,
            enforcementTier = if (hardcore) EnforcementTier.HARDCORE else EnforcementTier.SOFT,
        )
    }

    /** The block's checklist after this form: kept items keep their ticks, new ones start unticked. */
    fun toItems(blockId: String, old: List<ChecklistItem>): List<ChecklistItem> {
        if (mode != BlockMode.HABIT) return old
        val byId = old.associateBy { it.id }
        val taskItems = if (manualOn) tasks.filter { it.text.isNotBlank() } else emptyList()
        val linkItems = if (loopOn) links else emptyList()
        val linkedIds = linkItems.mapNotNull { it.itemId }.toSet()
        val byHabit = old.filter { it.habitId != null && it.id !in linkedIds }.associateBy { it.habitId }
        val picked = (if (manualOn) doHabits else emptyList()) + (if (focusOn) timerHabits else emptyList())
        val habitItems = picked.mapIndexed { i, id ->
            val name = knownHabits.firstOrNull { it.id == id }?.name ?: ""
            // A kept habit keeps what this block spent of it today (I36).
            byHabit[id]?.copy(text = name, order = i)
                ?: ChecklistItem(id = "$blockId-${UUID.randomUUID()}", blockId = blockId, text = name, order = i, habitId = id)
        }
        val plain = habitItems + taskItems.mapIndexed { i, t ->
            byId[t.id]?.copy(text = t.text.trim(), order = habitItems.size + i)
                ?: ChecklistItem(id = "$blockId-${UUID.randomUUID()}", blockId = blockId, text = t.text.trim(), order = habitItems.size + i)
        }
        val linked = linkItems.mapIndexed { i, l ->
            val day = if (l.yesterday) LinkedDay.YESTERDAY else LinkedDay.TODAY
            // Unticking both choices keeps the item: it then counts today's tick, like a plain one.
            byId[l.itemId]?.copy(order = plain.size + i, habitId = l.uuid, externalHabitId = null, linkedDay = day, countsGoal = l.goal)
                ?: ChecklistItem(
                    id = "$blockId-${UUID.randomUUID()}",
                    blockId = blockId,
                    text = l.name,
                    order = plain.size + i,
                    habitId = l.uuid,
                    linkedDay = day,
                    countsGoal = l.goal,
                )
        }
        return plain + linked
    }
}

enum class FormKind { APP_TIME, TIMER, MANUAL, FOCUS, LOOP, LIMIT }

/**
 * Whether going from [old] to [new] items makes the checklist easier: an item gone, or a habit
 * item counting a different day or goal days (contract §2.3). Adding items only tightens it.
 */
fun itemsLoosen(old: List<ChecklistItem>, new: List<ChecklistItem>): Boolean {
    val newById = new.associateBy { it.id }
    return old.any { o ->
        val n = newById[o.id] ?: return@any true
        o.derived && (n.linkedDay != o.linkedDay || (n.countsGoal && !o.countsGoal))
    }
}

/** A habit whose ticked days and goal days are the same: every day, or an amount. */
fun isDaily(habit: Habit): Boolean = habit.numerical || habit.freqNum == habit.freqDen

// =====================================================================================
//  World edits (pure transforms applied through store.edit { })
// =====================================================================================

fun World.withBlock(block: Block, items: List<ChecklistItem>): World {
    val exists = blocks.any { it.id == block.id }
    return copy(
        // An edit keeps the block's state: it moves on through events only.
        blocks = if (exists) blocks.map { if (it.id == block.id) block.copy(state = it.state) else it } else blocks + block,
        checklist = if (items.isEmpty()) checklist - block.id else checklist + (block.id to items),
    )
}

fun World.deleteBlock(blockId: String): World = copy(
    blocks = blocks.filterNot { it.id == blockId },
    progress = progress - blockId,
    credits = credits - blockId,
    sessions = sessions - blockId,
    lockdowns = lockdowns - blockId,
    checklist = checklist - blockId,
    runs = runs - blockId,
    overrides = overrides - blockId,
)

/** One more window of a schedule, defaulting to evenings every day. */
fun newDayWindow() = DayWindow((1..7).toSet(), TimeWindow(18 * 60, 22 * 60))
