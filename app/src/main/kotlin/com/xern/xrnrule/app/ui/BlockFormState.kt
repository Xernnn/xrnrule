package com.xern.xrnrule.app.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockDraft
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.ChecklistItem
import com.xern.xrnrule.domain.DayWindow
import com.xern.xrnrule.domain.FormKind
import com.xern.xrnrule.domain.Habit
import com.xern.xrnrule.domain.TaskDraft
import com.xern.xrnrule.domain.TimeWindow
import java.util.UUID

/**
 * The phone's block form as Compose state: one observable field per [BlockDraft] field, so the
 * screens can read and set them directly. Everything the form decides (the block's kind, the
 * hints, the [Block] and checklist it makes, whether a save loosens) is [BlockDraft]'s, in
 * `:domain`, where it is tested.
 */
@Stable
class BlockFormState(val existing: Block?, existingItems: List<ChecklistItem>, habits: List<Habit> = emptyList()) {
    val editing get() = existing != null
    private val start = BlockDraft.of(existing, existingItems, habits)

    var name by mutableStateOf(start.name)
    var apps by mutableStateOf(start.apps)
    var sites by mutableStateOf(start.sites)
    var exceptSites by mutableStateOf(start.exceptSites)
    var devices by mutableStateOf(start.devices)

    // ---- what unlocks it ----
    var appTimeOn by mutableStateOf(start.appTimeOn)
    var appTimeApps by mutableStateOf(start.appTimeApps)
    var appTimeMinutes by mutableLongStateOf(start.appTimeMinutes)
    var timerOn by mutableStateOf(start.timerOn)
    var timerMinutes by mutableLongStateOf(start.timerMinutes)
    /** Every habit there is, kept current by the screen: picked habits' kinds are looked up here. */
    var knownHabits by mutableStateOf(start.habits)
    var tasks by mutableStateOf(start.tasks)
    var habitIds by mutableStateOf(start.habitIds)
    var manualOn by mutableStateOf(start.manualOn)
    var focusOn by mutableStateOf(start.focusOn)
    var links by mutableStateOf(start.links)
    var loopOn by mutableStateOf(start.loopOn)
    var combineMode by mutableStateOf(start.combineMode)
    /** I43: NFC tags to scan. */
    var tagOn by mutableStateOf(start.tagOn)
    var tags by mutableStateOf(start.tags)

    // ---- daily limit ----
    var limitOn by mutableStateOf(start.limitOn)
    var dailyOn by mutableStateOf(start.dailyOn)
    var dailyMinutes by mutableLongStateOf(start.dailyMinutes)
    var rowOn by mutableStateOf(start.rowOn)
    var rowMinutes by mutableLongStateOf(start.rowMinutes)
    var rowBreakMinutes by mutableLongStateOf(start.rowBreakMinutes)
    var perDevice by mutableStateOf(start.perDevice)
    /** I44: with a habit and a daily limit, the habit comes first. */
    var habitFirst by mutableStateOf(start.habitFirst)

    // ---- blocked hours (I44) ----
    var hoursOn by mutableStateOf(start.hoursOn)
    var hoursDays by mutableStateOf(start.hoursDays)
    var hoursTimeOn by mutableStateOf(start.hoursTimeOn)
    var hoursStart by mutableIntStateOf(start.hoursStart)
    var hoursEnd by mutableIntStateOf(start.hoursEnd)
    var hoursMore by mutableStateOf(start.hoursMore)

    // ---- when ----
    var whenMode by mutableStateOf(start.whenMode)
    var scheduleDays by mutableStateOf(start.scheduleDays)
    var timeOn by mutableStateOf(start.timeOn)
    var startMinute by mutableIntStateOf(start.startMinute)
    var endMinute by mutableIntStateOf(start.endMinute)
    var moreWindows by mutableStateOf(start.moreWindows)
    var lockdownMinutes by mutableLongStateOf(start.lockdownMinutes)

    // ---- the unlock ----
    var unlockMinutes by mutableLongStateOf(start.unlockMinutes)
    /** I41: an unlock is so many minutes of use over the day rather than a timer. */
    var selfPaced by mutableStateOf(start.selfPaced)
    var hardcore by mutableStateOf(start.hardcore)

    /** The form as it stands. */
    fun draft() = BlockDraft(
        existing = existing, habits = knownHabits, name = name, apps = apps, sites = sites, exceptSites = exceptSites, devices = devices,
        appTimeOn = appTimeOn, appTimeApps = appTimeApps, appTimeMinutes = appTimeMinutes, timerOn = timerOn,
        timerMinutes = timerMinutes, tasks = tasks, habitIds = habitIds, manualOn = manualOn, focusOn = focusOn,
        links = links, loopOn = loopOn, combineMode = combineMode, tagOn = tagOn, tags = tags, limitOn = limitOn, dailyOn = dailyOn,
        dailyMinutes = dailyMinutes, rowOn = rowOn, rowMinutes = rowMinutes, rowBreakMinutes = rowBreakMinutes,
        perDevice = perDevice, habitFirst = habitFirst, hoursOn = hoursOn, hoursDays = hoursDays, hoursTimeOn = hoursTimeOn,
        hoursStart = hoursStart, hoursEnd = hoursEnd, hoursMore = hoursMore, whenMode = whenMode, scheduleDays = scheduleDays, timeOn = timeOn,
        startMinute = startMinute, endMinute = endMinute, moreWindows = moreWindows, lockdownMinutes = lockdownMinutes,
        unlockMinutes = unlockMinutes, selfPaced = selfPaced, hardcore = hardcore,
    )

    /** Takes in what a [BlockDraft] edit changed (only the fields such edits touch). */
    private fun load(d: BlockDraft) {
        appTimeOn = d.appTimeOn; timerOn = d.timerOn; manualOn = d.manualOn; focusOn = d.focusOn; loopOn = d.loopOn
        limitOn = d.limitOn; whenMode = d.whenMode; habitIds = d.habitIds; links = d.links; tagOn = d.tagOn
        // Hours move between the schedule and the blocked hours as rules come and go (I44).
        scheduleDays = d.scheduleDays; timeOn = d.timeOn; startMinute = d.startMinute; endMinute = d.endMinute; moreWindows = d.moreWindows
        hoursOn = d.hoursOn; hoursDays = d.hoursDays; hoursTimeOn = d.hoursTimeOn; hoursStart = d.hoursStart; hoursEnd = d.hoursEnd
        hoursMore = d.hoursMore
    }

    val doHabits get() = draft().doHabits
    val timerHabits get() = draft().timerHabits
    val habitChosen get() = draft().habitChosen
    val mode: BlockMode get() = draft().mode
    val hasTargets get() = draft().hasTargets

    fun addHabit(id: String) = load(draft().withHabit(id))
    fun setHabits(timers: Boolean, ids: Set<String>) = load(draft().withHabits(timers, ids))
    fun setLinks(ids: Set<String>) = load(draft().withLinks(ids))
    fun setKind(kind: FormKind, on: Boolean) = load(draft().withKind(kind, on))
    fun newTask() = TaskDraft(UUID.randomUUID().toString(), null, "")

    fun targetsHint(): String? = draft().targetsHint()
    fun unlockHint(): String? = draft().unlockHint()
    fun whenHint(capMinutes: Long): String? = draft().whenHint(capMinutes)
    fun hint(capMinutes: Long): String? = draft().hint(capMinutes)

    fun toBlock(id: String, autoName: String): Block = draft().toBlock(id, autoName)
    fun toItems(blockId: String, old: List<ChecklistItem>): List<ChecklistItem> = draft().toItems(blockId, old)
    fun saveLoosens(oldItems: List<ChecklistItem>): Boolean = draft().saveLoosens(oldItems)
}

/** One more window of a schedule, defaulting to evenings every day. */
fun newDayWindow() = DayWindow((1..7).toSet(), TimeWindow(18 * 60, 22 * 60))
