package com.xern.xrnrule.domain

import java.util.UUID

/** Step 3 of a new block: always on, on a schedule, or (with nothing to unlock it) for a while. */
enum class WhenMode { ALWAYS, SCHEDULE, FOR_A_WHILE }

/** The ways to unlock (or limit) a block that the phone's form offers. */
enum class FormKind { APP_TIME, TIMER, FOCUS, LOOP, LIMIT, TAG }

/** A task row being edited; [id] is the checklist item it was, null for a new one. */
data class TaskDraft(val key: String, val id: String?, val text: String)

/** An NFC tag to scan (I43) being edited: its [name] ("the kitchen tag") and [tag] ([Tags.id]). */
data class TagDraft(val key: String, val id: String?, val name: String, val tag: String)

/**
 * A habit you tick or count that the block asks for, being edited, with the day it counts
 * (contract I23: today's entry, yesterday's, or days its goal is met); [itemId] is null for a new one.
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
 * Everything the phone's new-block wizard and edit page let you set, in the form's words ("app
 * time", "manual timer", "nothing, just block it"), and the [Block] and checklist it makes. Plain
 * data, so what decides whether a save loosens a block (§2.3) is tested here rather than only by
 * hand. A block may mix a habit, a limit and blocked hours, and its kind follows what it has (I44);
 * only a lockdown stays one.
 */
data class BlockDraft(
    /** The block being edited, null for a new one. */
    val existing: Block? = null,
    /** Every habit there is: a picked habit's kind and name are looked up here. */
    val habits: List<Habit> = emptyList(),

    val name: String = "",
    val apps: Set<String> = emptySet(),
    val sites: Set<String> = emptySet(),
    val exceptSites: Set<String> = emptySet(),
    /** I42: where it holds: the phone, the laptop, or both. */
    val devices: Devices = Devices.BOTH,

    // ---- what unlocks it ----
    val appTimeOn: Boolean = false,
    val appTimeApps: Set<String> = emptySet(),
    val appTimeMinutes: Long = 15,
    val timerOn: Boolean = false,
    val timerMinutes: Long = 30,
    /** Tasks typed into a block before habits were shared ones; kept until removed, beside the links. */
    val tasks: List<TaskDraft> = emptyList(),
    /** The timer habits the block asks for (contract I36), in order: the "Focus session" card. */
    val timerIds: List<String> = emptyList(),
    /** I43: tags to scan. */
    val tagOn: Boolean = false,
    val tags: List<TagDraft> = emptyList(),
    val focusOn: Boolean = false,
    /** The habits you tick or count, each with the day it counts (I23): the one "Linked habit" card. */
    val links: List<LinkDraft> = emptyList(),
    val loopOn: Boolean = false,
    val combineMode: CombineMode = CombineMode.AND,

    // ---- daily limit ----
    val limitOn: Boolean = false,
    val dailyOn: Boolean = true,
    val dailyMinutes: Long = 30,
    val rowOn: Boolean = false,
    val rowMinutes: Long = 15,
    val rowBreakMinutes: Long = 15,
    /** I52: how many times a day it opens. */
    val opensOn: Boolean = false,
    val opensPerDay: Int = 3,
    /** I53: seconds of block screen before each open; 0 = none. */
    val openWaitS: Int = 0,
    val perDevice: Boolean = false,
    /** I44: with a habit and a daily limit, the habit comes first and the limit holds on top. */
    val habitFirst: Boolean = false,

    // ---- blocked hours, on a block with a habit or a limit (I44) ----
    val hoursOn: Boolean = false,
    val hoursDays: Set<Int> = (1..7).toSet(),
    val hoursTimeOn: Boolean = true,
    val hoursStart: Int = 9 * 60,
    val hoursEnd: Int = 17 * 60,
    val hoursMore: List<DayWindow> = emptyList(),

    // ---- when ----
    val whenMode: WhenMode = WhenMode.ALWAYS,
    val scheduleDays: Set<Int> = (1..7).toSet(),
    val timeOn: Boolean = false,
    val startMinute: Int = 9 * 60,
    val endMinute: Int = 17 * 60,
    val moreWindows: List<DayWindow> = emptyList(),
    val lockdownMinutes: Long = 60,

    // ---- the unlock ----
    val unlockMinutes: Long = 15,
    /** I41: an unlock is so many minutes of use over the day rather than a timer. */
    val selfPaced: Boolean = false,
    val hardcore: Boolean = false,
) {
    private fun isTimer(habitId: String) = habits.firstOrNull { it.id == habitId }?.kind == HabitKind.TIMER

    val habitChosen: Boolean get() = appTimeOn || timerOn || focusOn || loopOn || tagOn
    val hasTimer: Boolean get() = appTimeOn || timerOn
    val hasChecklist: Boolean
        get() = (loopOn && (links.isNotEmpty() || tasks.any { it.text.isNotBlank() })) ||
            (focusOn && timerIds.isNotEmpty()) || (tagOn && tags.isNotEmpty())

    /** The block's kind, from what's picked: a habit, a limit, or neither (a schedule or a lockdown). */
    val mode: BlockMode
        get() = when {
            existing?.mode == BlockMode.LOCKDOWN -> BlockMode.LOCKDOWN
            habitChosen -> BlockMode.HABIT
            limitOn -> BlockMode.ALLOWANCE
            whenMode == WhenMode.FOR_A_WHILE -> BlockMode.LOCKDOWN
            else -> BlockMode.SCHEDULE
        }

    /** I44: whether it has the rules that blocked hours go on top of. */
    val hasRules: Boolean get() = habitChosen || limitOn

    /** I44: whether "limit first or habit first" means anything here. */
    val orderMatters: Boolean get() = habitChosen && limitOn && (dailyOn || opensOn)

    private val hasHours: Boolean get() = hoursOn && (mode == BlockMode.HABIT || mode == BlockMode.ALLOWANCE)

    /**
     * One rule turned on or off; they all go together (I44). A block has one timer, so app time and
     * the wait timer exclude each other; with something to unlock it or a limit, "for a while" (a
     * lockdown) goes back to always.
     */
    fun withKind(kind: FormKind, on: Boolean): BlockDraft {
        var d = when (kind) {
            FormKind.APP_TIME -> copy(appTimeOn = on, timerOn = if (on) false else timerOn)
            FormKind.TIMER -> copy(timerOn = on, appTimeOn = if (on) false else appTimeOn)
            FormKind.FOCUS -> copy(focusOn = on)
            FormKind.LOOP -> copy(loopOn = on)
            FormKind.LIMIT -> copy(limitOn = on)
            FormKind.TAG -> copy(tagOn = on)
        }
        if (d.hasRules && d.whenMode == WhenMode.FOR_A_WHILE) d = d.copy(whenMode = WhenMode.ALWAYS)
        return d.hoursFollowing(this)
    }

    /**
     * Hours keep meaning "blocked" as rules come and go: alone they are the block's schedule, beside
     * a habit or a limit they are its blocked hours, where the schedule says when those rules hold.
     */
    private fun hoursFollowing(was: BlockDraft): BlockDraft = when {
        !was.hasRules && hasRules && was.whenMode == WhenMode.SCHEDULE -> copy(
            whenMode = WhenMode.ALWAYS,
            hoursOn = true,
            hoursDays = scheduleDays,
            hoursTimeOn = timeOn,
            hoursStart = startMinute,
            hoursEnd = endMinute,
            hoursMore = moreWindows,
            scheduleDays = (1..7).toSet(),
            timeOn = false,
            moreWindows = emptyList(),
        )
        was.hasRules && !hasRules && hoursOn -> copy(
            whenMode = WhenMode.SCHEDULE,
            hoursOn = false,
            scheduleDays = hoursDays,
            timeOn = hoursTimeOn,
            startMinute = hoursStart,
            endMinute = hoursEnd,
            moreWindows = hoursMore,
        )
        else -> this
    }

    /** One habit added to the block, unless it's there: a timer to the timers, any other as a link counting today's tick. */
    fun withHabit(id: String): BlockDraft = when {
        isTimer(id) -> if (id in timerIds) this else copy(timerIds = timerIds + id)
        else -> withLinks(links.mapTo(linkedSetOf()) { it.uuid } + id)
    }

    /** The "Focus session" card after its picker: kept timers keep their place. */
    fun withTimers(ids: Set<String>): BlockDraft = copy(timerIds = timerIds.filter { it in ids } + ids.filter { it !in timerIds })

    /** The "Linked habit" card after its picker: kept ones keep their choices, new ones count today's tick. */
    fun withLinks(ids: Set<String>): BlockDraft {
        val kept = links.filter { it.uuid in ids }
        val added = ids.filter { id -> kept.none { it.uuid == id } }.mapNotNull { id ->
            habits.firstOrNull { it.id == id }?.let { LinkDraft(null, it.id, it.name, yesterday = false, goal = false, daily = isDaily(it)) }
        }
        return copy(links = kept + added)
    }

    /** Laptop apps are added on the laptop's page; the phone's form keeps the block's. */
    private val laptopApps: Set<String> get() = existing?.targetDesktopApps.orEmpty()

    val hasTargets: Boolean get() = apps.isNotEmpty() || sites.isNotEmpty() || laptopApps.isNotEmpty()

    /** I42: whether anything it blocks is on the devices it holds on. */
    val hasTargetsOnItsDevices: Boolean
        get() = when (devices) {
            Devices.BOTH -> hasTargets
            Devices.PHONE -> apps.isNotEmpty() || sites.isNotEmpty()
            Devices.LAPTOP -> sites.isNotEmpty() || laptopApps.isNotEmpty()
        }

    /** Why step 1 (what to block) can't go on yet. */
    fun targetsHint(): String? = when {
        !hasTargets -> "Pick an app or a website to block"
        !hasTargetsOnItsDevices -> "Phone apps aren't blocked on the laptop: add a website, or laptop apps on the laptop's page"
        else -> null
    }

    /** Why step 2 (what unlocks it) can't go on yet. */
    fun unlockHint(): String? = when {
        appTimeOn && appTimeApps.isEmpty() -> "Pick the apps whose time counts"
        loopOn && links.isEmpty() && tasks.none { it.text.isNotBlank() } -> "Pick or make a habit"
        focusOn && timerIds.isEmpty() -> "Pick or make a timer"
        tagOn && tags.isEmpty() -> "Scan a tag"
        limitOn && !dailyOn && !rowOn && !opensOn -> "Set minutes a day, minutes in a row, or opens a day"
        existing?.mode == BlockMode.HABIT && !hasRules -> "Pick at least one way to unlock it"
        else -> null
    }

    /** Why step 3 (when) can't be saved yet. [capMinutes] is the daily unlock limit (I9). */
    fun whenHint(capMinutes: Long): String? = when {
        whenMode == WhenMode.SCHEDULE && scheduleDays.isEmpty() -> "Pick at least one day"
        whenMode == WhenMode.SCHEDULE && timeOn && startMinute == endMinute -> "Start and end can't be the same time"
        whenMode == WhenMode.SCHEDULE && moreWindows.any { it.days.isEmpty() } -> "Pick at least one day for each time"
        whenMode == WhenMode.SCHEDULE && moreWindows.any { it.window.startMinuteOfDay == it.window.endMinuteOfDay } ->
            "Start and end can't be the same time"
        hasHours && hoursDays.isEmpty() -> "Pick at least one day for the blocked hours"
        hasHours && hoursTimeOn && hoursStart == hoursEnd -> "Blocked hours can't start and end at the same time"
        hasHours && hoursMore.any { it.days.isEmpty() } -> "Pick at least one day for each of the blocked hours"
        hasHours && hoursMore.any { it.window.startMinuteOfDay == it.window.endMinuteOfDay } ->
            "Blocked hours can't start and end at the same time"
        mode == BlockMode.LOCKDOWN && lockdownMinutes <= 0 -> "Set how long to block"
        mode == BlockMode.HABIT && unlockMinutes <= 0 -> "Set how long an unlock lasts"
        mode == BlockMode.HABIT && unlockMinutes > capMinutes ->
            "An unlock can't be longer than your daily unlock limit ($capMinutes min, in Settings)"
        else -> null
    }

    /** The first thing stopping a save, or null. */
    fun hint(capMinutes: Long): String? =
        targetsHint() ?: unlockHint() ?: whenHint(capMinutes) ?: if (existing != null && name.isBlank()) "Give the block a name" else null

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

    private fun builtHours(): Schedule? =
        if (hasHours) Schedule(hoursDays, if (hoursTimeOn) TimeWindow(hoursStart, hoursEnd) else null, hoursMore) else null

    /** The block this form describes: a new one with [id], or [existing] with its settings replaced. */
    fun toBlock(id: String, autoName: String): Block {
        val mode = mode
        val limited = limitOn && (mode == BlockMode.ALLOWANCE || mode == BlockMode.HABIT)
        val base = existing ?: Block(id = id, name = "", trigger = builtTrigger(), state = BlockState.ARMED)
        return base.copy(
            name = name.trim().ifEmpty { autoName },
            targetPackages = apps,
            targetSites = sites,
            exceptSites = exceptSites.filterTo(mutableSetOf()) { e -> sites.any { Sites.matches(e, it) } },
            devices = devices,
            trigger = builtTrigger(),
            reward = (existing?.reward ?: Reward()).copy(unlockDurationMs = unlockMinutes * 60_000),
            schedule = builtSchedule(),
            mode = mode,
            dailyAllowanceMs = if (limited && dailyOn) dailyMinutes * 60_000 else 0,
            sessionMs = if (limited && rowOn) rowMinutes * 60_000 else 0,
            sessionBreakMs = if (limited && rowOn) rowBreakMinutes * 60_000 else 0,
            opensPerDay = if (limited && opensOn) opensPerDay else 0,
            openWaitS = if (limited) openWaitS else 0,
            perDevice = limited && perDevice,
            habitFirst = limited && mode == BlockMode.HABIT && habitFirst,
            blockedHours = builtHours(),
            lockdownMs = if (mode == BlockMode.LOCKDOWN) lockdownMinutes * 60_000 else base.lockdownMs,
            selfPaced = mode == BlockMode.HABIT && selfPaced,
            enforcementTier = if (hardcore) EnforcementTier.HARDCORE else EnforcementTier.SOFT,
        )
    }

    /**
     * The block's checklist after this form: kept items keep their ticks and what they spent, new
     * ones start unticked with an id from [newId].
     */
    fun toItems(blockId: String, old: List<ChecklistItem>, newId: () -> String = { UUID.randomUUID().toString() }): List<ChecklistItem> {
        if (mode != BlockMode.HABIT) return if (existing?.mode == BlockMode.HABIT) emptyList() else old
        val byId = old.associateBy { it.id }
        val linkItems = if (loopOn) links else emptyList()
        val taskItems = if (loopOn) tasks.filter { it.text.isNotBlank() } else emptyList()
        val linkedIds = linkItems.mapNotNull { it.itemId }.toSet()
        // A habit taken off and picked again in one edit is still its old item, with what this
        // block spent of it today (I36): a new one would let the same tick open the block twice.
        val byHabit = old.filter { it.habitId != null && it.id !in linkedIds }.associateBy { it.habitId }
        val linked = linkItems.mapIndexed { i, l ->
            val day = if (l.yesterday) LinkedDay.YESTERDAY else LinkedDay.TODAY
            (byId[l.itemId] ?: byHabit[l.uuid])?.copy(order = i, habitId = l.uuid, externalHabitId = null, linkedDay = day, countsGoal = l.goal)
                ?: ChecklistItem(id = "$blockId-${newId()}", blockId = blockId, text = l.name, order = i, habitId = l.uuid, linkedDay = day, countsGoal = l.goal)
        }
        val typed = taskItems.mapIndexed { i, t ->
            byId[t.id]?.copy(text = t.text.trim(), order = linked.size + i)
                ?: ChecklistItem(id = "$blockId-${newId()}", blockId = blockId, text = t.text.trim(), order = linked.size + i)
        }
        val taken = linked.mapTo(mutableSetOf()) { it.id }
        val timers = (if (focusOn) timerIds else emptyList()).mapIndexed { i, id ->
            val name = habits.firstOrNull { it.id == id }?.name ?: ""
            byHabit[id]?.takeIf { it.id !in taken }?.copy(text = name, order = linked.size + typed.size + i)
                ?: ChecklistItem(id = "$blockId-${newId()}", blockId = blockId, text = name, order = linked.size + typed.size + i, habitId = id)
        }
        val plain = linked + typed + timers
        val tagItems = (if (tagOn) tags else emptyList()).mapIndexed { i, t ->
            val name = t.name.trim().ifEmpty { "the tag" }
            byId[t.id]?.copy(text = name, order = plain.size + i, tag = t.tag)
                ?: ChecklistItem(id = "$blockId-${newId()}", blockId = blockId, text = name, order = plain.size + i, tag = t.tag)
        }
        return plain + tagItems
    }

    /**
     * Whether saving this form over [existing] and its [oldItems] makes the block easier to get
     * past (§2.3): the block's own settings ([BlockEdits.loosens]) or its checklist ([itemsLoosen]).
     */
    fun saveLoosens(oldItems: List<ChecklistItem>): Boolean {
        val old = existing ?: return false
        return BlockEdits.loosens(old, toBlock(old.id, old.name)) || itemsLoosen(oldItems, toItems(old.id, oldItems))
    }

    /**
     * I39: this form saved over [stored], the block as it is at save time, rather than as it was
     * when the page opened: another device may have tightened or locked it meanwhile, and the
     * page's older values saved over that loosen it. What the page doesn't show (the lock, the
     * laptop's apps, the credit's lifetime) comes from [stored] too, so it isn't put back either.
     * Judge [saveLoosens] by [stored]'s checklist as it is then, too.
     */
    fun onto(stored: Block): BlockDraft = copy(existing = stored)

    companion object {
        /** The form for [existing] (null: a new block) and its checklist [items]. */
        fun of(existing: Block?, items: List<ChecklistItem>, habits: List<Habit>): BlockDraft {
            // A new block's unlock counts use, so leaving the app doesn't spend it. Not the field's
            // default: that would count for every block already made.
            if (existing == null) return BlockDraft(habits = habits, selfPaced = true)
            val trigger = existing.trigger
            val hadTimer = existing.mode == BlockMode.HABIT && (trigger.type == TriggerType.TIMER || trigger.type == TriggerType.COMBINED)
            val timerMinutes = trigger.requiredAmount / 60_000
            val appTimeOn = hadTimer && trigger.habitPackages.isNotEmpty()
            val timerOn = hadTimer && trigger.habitPackages.isEmpty()
            val tasks = items.filter { !it.derived && it.tag == null }.sortedBy { it.order }.map { TaskDraft(it.id, it.id, it.text) }
            val tags = items.filter { it.tag != null }.sortedBy { it.order }.map { TagDraft(it.id, it.id, it.text, it.tag!!) }
            fun habitOf(item: ChecklistItem) = (item.habitId ?: item.externalHabitId).let { id -> habits.firstOrNull { it.id == id } }
            // A timer is the Focus session card's, unless it counts another day: only a link shows that.
            fun isTimer(item: ChecklistItem) =
                habitOf(item)?.kind == HabitKind.TIMER && item.linkedDay != LinkedDay.YESTERDAY && !item.countsGoal
            val timerIds = items.filter { it.derived && isTimer(it) }.sortedBy { it.order }.map { it.habitId ?: it.externalHabitId!! }
            val links = items.filter { it.derived && !isTimer(it) }.sortedBy { it.order }.map {
                val id = it.habitId ?: it.externalHabitId!!
                val habit = habits.firstOrNull { h -> h.id == id }
                LinkDraft(it.id, id, habit?.name ?: it.text, it.linkedDay == LinkedDay.YESTERDAY, it.countsGoal, daily = habit?.let(::isDaily) ?: !it.countsGoal)
            }
            val limit = existing.takeIf { it.hasLimit }
            val hours = existing.blockedHours
            val schedule = existing.schedule
            val whenMode = when {
                existing.mode == BlockMode.LOCKDOWN -> WhenMode.FOR_A_WHILE
                schedule != Schedule.ALWAYS -> WhenMode.SCHEDULE
                else -> WhenMode.ALWAYS
            }
            val draft = BlockDraft(
                existing = existing,
                habits = habits,
                name = existing.name,
                apps = existing.targetPackages,
                sites = existing.targetSites,
                exceptSites = existing.exceptSites,
                devices = existing.devices,
                appTimeOn = appTimeOn,
                appTimeApps = trigger.habitPackages,
                appTimeMinutes = if (appTimeOn) timerMinutes else 15,
                timerOn = timerOn,
                timerMinutes = if (timerOn) timerMinutes else 30,
                tasks = tasks,
                timerIds = timerIds,
                links = links,
                loopOn = links.isNotEmpty() || tasks.isNotEmpty(),
                combineMode = trigger.combineMode,
                limitOn = limit != null,
                dailyOn = limit == null || limit.dailyAllowanceMs > 0,
                opensOn = limit != null && limit.opensPerDay > 0,
                opensPerDay = limit?.opensPerDay?.takeIf { it > 0 } ?: 3,
                openWaitS = limit?.openWaitS ?: 0,
                dailyMinutes = limit?.dailyAllowanceMs?.takeIf { it > 0 }?.div(60_000) ?: 30,
                rowOn = limit?.hasRowRule == true,
                rowMinutes = limit?.takeIf { it.hasRowRule }?.sessionMs?.div(60_000) ?: 15,
                rowBreakMinutes = limit?.takeIf { it.hasRowRule }?.sessionBreakMs?.div(60_000) ?: 15,
                perDevice = limit?.perDevice == true,
                habitFirst = existing.habitFirst,
                hoursOn = hours != null,
                hoursDays = hours?.activeDays ?: (1..7).toSet(),
                hoursTimeOn = hours == null || hours.activeWindow != null,
                hoursStart = hours?.activeWindow?.startMinuteOfDay ?: (9 * 60),
                hoursEnd = hours?.activeWindow?.endMinuteOfDay ?: (17 * 60),
                hoursMore = hours?.moreWindows.orEmpty(),
                whenMode = whenMode,
                scheduleDays = schedule.activeDays.takeIf { whenMode == WhenMode.SCHEDULE } ?: (1..7).toSet(),
                timeOn = schedule.activeWindow != null,
                startMinute = schedule.activeWindow?.startMinuteOfDay ?: (9 * 60),
                endMinute = schedule.activeWindow?.endMinuteOfDay ?: (17 * 60),
                moreWindows = schedule.moreWindows,
                lockdownMinutes = existing.lockdownMs.takeIf { it > 0 }?.div(60_000) ?: 60,
                unlockMinutes = existing.reward.unlockDurationMs / 60_000,
                selfPaced = existing.selfPaced,
                hardcore = existing.enforcementTier == EnforcementTier.HARDCORE,
            )
            return draft.copy(
                focusOn = draft.timerIds.isNotEmpty(),
                tagOn = tags.isNotEmpty(),
                tags = tags,
            )
        }
    }
}

/**
 * Whether going from [old] to [new] items makes the checklist easier: an item gone, a habit item
 * counting a different day or goal days, or a tag item wanting another tag (one that may be in
 * your pocket) or none (contract §2.3, I43). Adding items only tightens it.
 */
fun itemsLoosen(old: List<ChecklistItem>, new: List<ChecklistItem>): Boolean {
    val newById = new.associateBy { it.id }
    return old.any { o ->
        val n = newById[o.id] ?: return@any true
        (o.derived && (n.linkedDay != o.linkedDay || (n.countsGoal && !o.countsGoal))) ||
            (o.tag != null && n.tag != o.tag)
    }
}

/** A habit whose ticked days and goal days are the same: every day, or an amount. */
fun isDaily(habit: Habit): Boolean = habit.numerical || habit.freqNum == habit.freqDen

/** [block] and its checklist [items], added or replacing the block of that id. */
fun World.withBlock(block: Block, items: List<ChecklistItem>): World {
    val old = blocks.firstOrNull { it.id == block.id }
    val habit = block.mode == BlockMode.HABIT
    return copy(
        // An edit keeps the block's state: it moves on through events only, and stops being a
        // HABIT block's with its habit (I44). Nor does it touch the lock (I39), which only its own dialog sets.
        blocks = if (old != null) {
            blocks.map { if (it.id == block.id) block.copy(state = if (habit) it.state else BlockState.ARMED, lock = it.lock) else it }
        } else {
            blocks + block
        },
        checklist = if (items.isEmpty()) checklist - block.id else checklist + (block.id to items),
        credits = if (habit) credits else credits - block.id,
        sessions = if (habit) sessions else sessions - block.id,
        progress = if (old != null) progress.withModeChange(old, block) else progress,
    )
}

/** The progress map once [old] became [new]: its count moves with its mode (I44). */
fun Map<String, HabitProgress>.withModeChange(old: Block, new: Block): Map<String, HabitProgress> {
    val p = this[old.id] ?: return this
    return this + (old.id to p.forMode(old.mode, new.mode))
}

/** The world without the block [blockId] and everything that hangs off it. */
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
