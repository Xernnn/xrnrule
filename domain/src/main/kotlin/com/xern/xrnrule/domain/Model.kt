package com.xern.xrnrule.domain

import kotlinx.serialization.Serializable

/**
 * Pure domain model for xrnrule. No Android imports here — everything in `:domain`
 * must be unit-testable on a plain JVM. See `docs/domain-contract.md`.
 *
 * Every persisted type is [Serializable] so `:app` can store the whole [World] as one atomic
 * JSON document (see `FilePersistPort`). Days of week are ISO-8601 ints (1 = Monday .. 7 =
 * Sunday) to keep the model free of `java.time` in its serialized shape.
 */

enum class BlockState { ARMED, EARNED, UNLOCKED }

enum class TriggerType { TIMER, CHECKLIST, COMBINED }

enum class TriggerUnit { MS, ITEMS }

enum class EnforcementTier { SOFT, HARDCORE }

/** How a [TriggerType.COMBINED] trigger's two conditions resolve to `triggerMet` (I15). */
enum class CombineMode { AND, OR }

/**
 * What a block is, from the rules it has (contract §2.2, I44): a habit makes it [HABIT], else a
 * limit [ALLOWANCE], else its hours alone [SCHEDULE]. Only [HABIT] runs the ARMED → EARNED →
 * UNLOCKED machine; the others have no unlock path and stay ARMED.
 */
enum class BlockMode { HABIT, ALLOWANCE, SCHEDULE, LOCKDOWN }

/** Why a block is blocking right now, the firmest first (I44). */
enum class BlockReason { LOCKDOWN, HOURS, BREAK, LIMIT, HABIT }

/** What a device running the engine is (I42). */
enum class DeviceKind { PHONE, LAPTOP }

/** The devices a block holds on (I42). */
enum class Devices {
    BOTH, PHONE, LAPTOP;

    /** Whether a block with these devices holds on a [kind] device; any when the kind is unknown. */
    fun include(kind: DeviceKind?): Boolean = when (this) {
        BOTH -> true
        PHONE -> kind == null || kind == DeviceKind.PHONE
        LAPTOP -> kind == null || kind == DeviceKind.LAPTOP
    }

    /** Whether these devices hold on every device [other] does: going from [other] to these doesn't loosen. */
    fun covers(other: Devices): Boolean = this == BOTH || this == other
}

/** NFC tags (I43): what a tag item stores of a tag. */
object Tags {
    /**
     * A tag's id from its UID: hashed, so the shared state and the home server never hold the
     * UID itself. Some cards (bank cards, passports) show a new random UID on every read; the
     * phone reads a tag twice before it saves one, and such a card never matches itself.
     */
    fun id(uid: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest("xrnrule-tag:".toByteArray() + uid)
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}

/** I42: whether [block] holds on this device. */
fun World.appliesHere(block: Block): Boolean = block.devices.include(deviceKind)

/**
 * Which habit day a habit item counts (I23). [YESTERDAY] is for habits ticked at the end of the
 * day: last night's tick pays for today's unlock.
 */
enum class LinkedDay { TODAY, YESTERDAY }

/** What a [Habit] measures: Loop's yes/no and numerical habits, plus timed ones (contract §1). */
enum class HabitKind {
    /** Done or not (Loop: yes/no). */
    CHECK,

    /** An amount with a unit, e.g. 20 push-ups (Loop: numerical). */
    COUNT,

    /** Minutes, counted by the focus timer (I37); Loop sees a numerical habit in minutes. */
    TIMER,
}

/** For an amount habit: at least its goal (do this much), or at most (keep under it). */
enum class TargetType { AT_LEAST, AT_MOST }

/** Contract defaults (§3). Settings can override per install; these are the fallback. */
object Defaults {
    const val CREDIT_TTL_MS = 2L * 60 * 60 * 1000        // I1
    const val DAILY_CAP_MS = 60L * 60 * 1000             // I9
    const val SKEW_TOLERANCE_MS = 5_000L                 // I3
    const val UNLOCK_WARNING_MS = 30_000L                // G3
    const val DEFAULT_UNLOCK_MS = 5L * 60 * 1000
    const val PAUSE_MS = 15L * 60 * 1000                 // I25
    const val EMERGENCY_UNLOCKS = 5                      // I40: in all, never given back
    const val ALL_ITEMS = -1L                            // trigger.requiredAmount sentinel (I11)
    const val OVERRIDE_PHRASE = "I choose to keep scrolling" // I34
    const val OVERRIDE_WAIT_MS = 45_000L                 // I34
    const val OVERRIDE_GRANT_MS = 10L * 60 * 1000        // I34
    const val OVERRIDES_PER_DAY = 2                      // I34, per block
    /** I34: a started override must be claimed within this long after its wait. */
    const val OVERRIDE_CLAIM_WINDOW_MS = 10L * 60 * 1000
    /** I37: the focus timer's "Break" button. */
    const val FOCUS_BREAK_MS = 5L * 60 * 1000
}

/** When a habit reminds: [days] packs Monday..Sunday into bits 0..6, as Loop does (127 = every day). */
@Serializable
data class Reminder(val hour: Int, val minute: Int, val days: Int = 127)

/**
 * One habit of the one habit list, shared through the habit server (contract §1, §2.5); any block
 * can ask for it through a habit item (I36). The fields are Loop's, plus [icon] and timers.
 * Amounts ([goal], entry values) are thousandths of [unit] (minutes for a timer).
 */
@Serializable
data class Habit(
    val id: String,
    val name: String,
    val kind: HabitKind = HabitKind.CHECK,
    /** Amount habits: the goal per [freqDen] days, in thousandths. Unused for a check. */
    val goal: Long = 0,
    val targetType: TargetType = TargetType.AT_LEAST,
    /** A check: [freqNum] times in [freqDen] days. An amount habit: [goal] per [freqDen] days. */
    val freqNum: Int = 1,
    val freqDen: Int = 1,
    /** What a count counts, e.g. "push-ups"; a timer's is always minutes. */
    val unit: String = "",
    /** Loop's palette index, 0..19. */
    val paletteColor: Int = 8,
    /** An icon name the apps know ("book", "run", ...); unknown names fall back to a default. */
    val icon: String = "",
    val question: String = "",
    val description: String = "",
    val archived: Boolean = false,
    val position: Int = 0,
    val reminder: Reminder? = null,
    /** Wall-clock ms of the last edit here or the server's mtime; the later one wins (I38). */
    val editedAtWall: Long = 0,
    /** The habit server's version this one is based on, 0 before it has one (I38). */
    val seq: Long = 0,
    /** Changed here since the server last took it (I38). */
    val dirty: Boolean = false,
) {
    val numerical: Boolean get() = kind != HabitKind.CHECK

    /** How often a check is due, as a fraction of days (1 = daily). */
    val frequency: Double get() = freqNum.toDouble() / freqDen.coerceAtLeast(1)

    /**
     * I36: how much of it opens a block once: a check, or the goal's share of one day; null for
     * an "at most" habit, which never opens anything.
     */
    val unlockUnit: Long?
        get() = when {
            !numerical -> 1
            targetType == TargetType.AT_MOST -> null
            else -> ((goal + freqDen - 1) / freqDen.coerceAtLeast(1)).coerceAtLeast(1)
        }
}

/**
 * A habit's value on one habit day (I35). [value] is Loop's: for a check [UNKNOWN] .. [SKIP], for
 * an amount habit the amount in thousandths. [seq], [dirty] and [baseValue] keep it in step with
 * the habit server (I38).
 */
/** A habit deleted here: the server version it was deleted from, and when (I38). */
@Serializable
data class HabitTombstone(val seq: Long, val atWall: Long)

@Serializable
data class HabitEntry(
    val value: Long,
    val notes: String = "",
    /** Wall-clock ms of the last write, here or on the server. */
    val mtime: Long = 0,
    val seq: Long = 0,
    val dirty: Boolean = false,
    /** While [dirty]: the value the server last had, which this device's change started from. */
    val baseValue: Long? = null,
) {
    companion object {
        const val UNKNOWN = -1L
        const val NO = 0L
        const val YES_AUTO = 1L
        const val YES_MANUAL = 2L
        const val SKIP = 3L

        /** Keys of [World.habitEntries]: `habitId@epochDay`, the habit day (§1). */
        fun key(habitId: String, epochDay: Long): String = "$habitId@$epochDay"

        fun habitId(key: String): String = key.substringBeforeLast('@')

        fun day(key: String): Long? = key.substringAfterLast('@').toLongOrNull()
    }
}

/**
 * The focus timer for one [HabitKind.TIMER] habit, on this device only (I37). While [running],
 * the time since [startedAtMonotonic] counts for habit day [epochDay] and goes into its entry
 * when folded.
 */
@Serializable
data class FocusRun(
    val habitId: String,
    val epochDay: Long,
    val startedAtMonotonic: Long,
    val startedAtWall: Long,
    val running: Boolean = true,
    /** A break ends here (wall ms) and the run goes on by itself; null when it isn't on a break. */
    val resumeAtWall: Long? = null,
)

@Serializable
data class Trigger(
    val type: TriggerType,
    /**
     * For CHECKLIST: number of items to complete, or [Defaults.ALL_ITEMS] for "all". For TIMER
     * and COMBINED: milliseconds required. COMBINED's checklist half always requires *all*
     * items (see I15) — there is no separate partial-checklist threshold for it.
     */
    val requiredAmount: Long,
    val unit: TriggerUnit,
    /** Only meaningful when [type] is COMBINED (I15). */
    val combineMode: CombineMode = CombineMode.AND,
    /**
     * Packages whose foreground time counts toward the timer half of a TIMER or COMBINED
     * trigger. Empty (the default) means "count time in the blocked [Block.targetPackages]
     * instead" — the original behavior. Non-empty lets the habit be done in a *different* app
     * than the one being blocked (I16).
     */
    val habitPackages: Set<String> = emptySet(),
)

@Serializable
data class Reward(
    val unlockDurationMs: Long = Defaults.DEFAULT_UNLOCK_MS,
    val creditTtlMs: Long = Defaults.CREDIT_TTL_MS,
)

@Serializable
data class TimeWindow(val startMinuteOfDay: Int, val endMinuteOfDay: Int)

/** One more window of a [Schedule], with days of its own (I30). */
@Serializable
data class DayWindow(
    /** ISO-8601 day numbers, 1 = Monday .. 7 = Sunday. */
    val days: Set<Int>,
    val window: TimeWindow,
)

@Serializable
data class Schedule(
    /** ISO-8601 day numbers, 1 = Monday .. 7 = Sunday. */
    val activeDays: Set<Int>,
    val activeWindow: TimeWindow? = null,
    /** More windows, each with its own days; the schedule is active while any window is (I30). */
    val moreWindows: List<DayWindow> = emptyList(),
) {
    companion object {
        val ALWAYS = Schedule((1..7).toSet(), null)
    }
}

@Serializable
data class Block(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val targetPackages: Set<String> = emptySet(),
    val targetCategories: Set<String> = emptySet(),
    /** Normalized domains (see [Sites.normalize]); each also covers its subdomains (I18). */
    val targetSites: Set<String> = emptySet(),
    /** Hosts under [targetSites] this block leaves alone, e.g. `music.youtube.com` (I31). */
    val exceptSites: Set<String> = emptySet(),
    /**
     * Laptop apps, by the `.desktop` id KWin reports (e.g. `steam`). Targets like
     * [targetPackages] (I29); the phone never sees them in the foreground.
     */
    val targetDesktopApps: Set<String> = emptySet(),
    val trigger: Trigger,
    val reward: Reward = Reward(),
    val schedule: Schedule = Schedule.ALWAYS,
    val enforcementTier: EnforcementTier = EnforcementTier.SOFT,
    val state: BlockState = BlockState.ARMED,
    val mode: BlockMode = BlockMode.HABIT,
    /**
     * [BlockMode.ALLOWANCE]: free foreground time per local day before the block engages (I19);
     * 0 means no daily limit, only the "in a row" rule ([sessionMs]).
     */
    val dailyAllowanceMs: Long = 0,
    /** [BlockMode.ALLOWANCE]: at most this much use in a row, then a forced break; 0 = no such rule (I32). */
    val sessionMs: Long = 0,
    /** [BlockMode.ALLOWANCE]: the forced break after [sessionMs] in a row; resting this long also starts a fresh row (I32). */
    val sessionBreakMs: Long = 0,
    /** [BlockMode.ALLOWANCE]: each device gets the whole limit to itself instead of sharing one (I33). */
    val perDevice: Boolean = false,
    /** [BlockMode.LOCKDOWN]: length of one lockdown, reused when it is started again (I21). */
    val lockdownMs: Long = 0,
    /**
     * [BlockMode.HABIT]: an unlock is so much *use* rather than so much time: it runs only while a
     * target is in front and lasts until it is used up or the day ends (I41).
     */
    val selfPaced: Boolean = false,
    /** I42: the phone, the laptop, or both; elsewhere the block blocks nothing and counts no time. */
    val devices: Devices = Devices.BOTH,
    /**
     * I44: hours this block blocks outright, on top of its other rules and whatever their state;
     * null = none. A SCHEDULE block has none: its [schedule] is its hours.
     */
    val blockedHours: Schedule? = null,
    /**
     * I44: a HABIT block with a daily limit. False: the free minutes come first, and once they're
     * used up the habit opens it again. True: blocked until the habit is done, and the limit holds on top.
     */
    val habitFirst: Boolean = false,
    /** Wall-clock ms of the last edit; the later edit wins when two devices change a block at once (§2.4). */
    val editedAtWall: Long = 0,
    /** I39: nothing that loosens this block goes through before [BlockLock.untilWall]; never set back to null. */
    val lock: BlockLock? = null,
) {
    /** Everything this block blocks that can be "the foreground app": phone packages and laptop apps. */
    val targetApps: Set<String> get() = targetPackages + targetDesktopApps

    /** A target site covers [host] unless one of [exceptSites] does too (I18, I31). */
    fun coversSite(host: String): Boolean =
        targetSites.any { Sites.matches(host, it) } && exceptSites.none { Sites.matches(host, it) }

    /** Whether the "in a row" rule applies (I32, I44). */
    val hasRowRule: Boolean
        get() = (mode == BlockMode.ALLOWANCE || mode == BlockMode.HABIT) && sessionMs > 0 && sessionBreakMs > 0

    /** Whether it has a limit: an ALLOWANCE block always, a HABIT block if it was given one (I44). */
    val hasLimit: Boolean
        get() = mode == BlockMode.ALLOWANCE || (mode == BlockMode.HABIT && (dailyAllowanceMs > 0 || hasRowRule))

    /** I44: a HABIT block that only asks for its habit once the day's free minutes are gone. */
    val limitComesFirst: Boolean get() = mode == BlockMode.HABIT && dailyAllowanceMs > 0 && !habitFirst
}

/**
 * I39: a block's settings can't be loosened before [untilWall], by any wait or password; only an
 * emergency unlock (I40) ends it early. [setAtWall] settles two devices' locks (§2.4).
 */
@Serializable
data class BlockLock(val untilWall: Long, val setAtWall: Long)

/**
 * A short break from every SOFT block that isn't a lockdown (I25). Counted down on the monotonic
 * clock, like [Lockdown].
 */
@Serializable
data class Pause(
    val startedAtMonotonic: Long,
    val startedAtWall: Long,
    val durationMs: Long,
)

/** A running [BlockMode.LOCKDOWN]. Counted down on the monotonic clock, like [UnlockSession] (I21). */
@Serializable
data class Lockdown(
    val blockId: String,
    val startedAtMonotonic: Long,
    val startedAtWall: Long,
    val durationMs: Long,
)

@Serializable
data class ChecklistItem(
    val id: String,
    val blockId: String,
    val text: String,
    val order: Int,
    val done: Boolean = false,
    val doneAtMonotonic: Long? = null,
    /**
     * A *linked item* from before the habit list moved into xrnrule: Loop's habit `uuid`, which
     * is now that habit's [Habit.id]. The sweep turns it into a habit item (I23).
     */
    val externalHabitId: String? = null,
    /** Habit items: whether today's or yesterday's habit day counts (I23). */
    val linkedDay: LinkedDay = LinkedDay.TODAY,
    /**
     * Habit items on a check that isn't daily ("3 times a week"): count the days Loop fills in
     * because the goal is met, not only the ticked ones (I23).
     */
    val countsGoal: Boolean = false,
    /** Habit items: the habit day of the last unlock this item paid for; kept across resets (I24). */
    val spentEpochDay: Long? = null,
    /**
     * Set on a *habit item*: the [Habit] it asks for. Done while that habit has an unlock's worth
     * on the item's day that this block hasn't spent yet; never ticked by hand (I23, I36).
     */
    val habitId: String? = null,
    /** Habit items: how much of the habit this block spent on [spentEpochDay] (I24). */
    val spentAmount: Long = 0,
    /**
     * A *tag item* (I43): the NFC tag ([Tags.id]) the phone must read to tick it. Tapping it
     * never ticks it; a read of the tag does, in every block waiting for it.
     */
    val tag: String? = null,
) {
    /** Ticked from outside the block rather than by hand: a habit item (I23), or a linked one still to convert. */
    val derived: Boolean get() = externalHabitId != null || habitId != null

    /** Habit items: how much of the habit this block has already spent on habit day [epochDay] (I24). */
    fun spentOn(epochDay: Long): Long = if (spentEpochDay == epochDay) spentAmount else 0
}

@Serializable
data class HabitProgress(
    val blockId: String,
    val accumulatedMs: Long = 0,
    /** Wall-clock ms of local start-of-day this progress window belongs to (I13). */
    val windowStart: Long,
    /** Monotonic ms marker of the last timer accrual (I4). */
    val lastUpdated: Long,
    val timerRunning: Boolean = false,
    /** I44: a HABIT block's use of its limit; its [accumulatedMs] is the habit's timer. */
    val limitUsedMs: Long = 0,
) {
    /** I19, I44: this window's use of [block]'s limit. */
    fun limitUsed(block: Block): Long = if (block.mode == BlockMode.HABIT) limitUsedMs else accumulatedMs

    fun withLimitUsed(block: Block, ms: Long): HabitProgress =
        if (block.mode == BlockMode.HABIT) copy(limitUsedMs = ms) else copy(accumulatedMs = ms)

    /** This progress once its block goes from [old] to [new]: only a HABIT block has a timer (I44). */
    fun forMode(old: BlockMode, new: BlockMode): HabitProgress = when {
        old == new -> this
        new == BlockMode.HABIT -> copy(accumulatedMs = 0, limitUsedMs = accumulatedMs)
        old == BlockMode.HABIT -> copy(accumulatedMs = limitUsedMs, limitUsedMs = 0, timerRunning = false)
        else -> this
    }
}

/**
 * TTL is enforced on the wall clock ([expiresAtWall]) so it survives reboot; the monotonic
 * stamp is a live cross-check against the user winding the clock backward (I1/I3).
 */
@Serializable
data class EarnedCredit(
    val blockId: String,
    val earnedAtMonotonic: Long,
    val earnedAtWall: Long,
    val expiresAtWall: Long,
    val amountMs: Long,
)

@Serializable
data class UnlockSession(
    val blockId: String,
    val startedAtMonotonic: Long,
    val startedAtWall: Long,
    val durationMs: Long,
    /** I41: [durationMs] is use, counted in [usedMs], not time since [startedAtMonotonic]. */
    val selfPaced: Boolean = false,
    /** I41: each device's use of this session (device id → ms); the devices' use adds up. */
    val usedMs: Map<String, Long> = emptyMap(),
    /** I41: this device's monotonic mark for counting use; null = not marking yet. Never shared. */
    val markMonotonic: Long? = null,
) {
    /** Names this session on every device, for the [World.unlockLog] (I28). */
    val key: String get() = "$blockId@$startedAtWall"

    /** I41: use so far on every device. */
    val totalUsedMs: Long get() = usedMs.values.sum()

    /** What is left: its time (I2), or for a self-paced one its use (I41). */
    fun leftMs(clock: Clock): Long =
        if (selfPaced) (durationMs - totalUsedMs).coerceAtLeast(0)
        else (durationMs - (clock.nowMonotonicMs() - startedAtMonotonic)).coerceAtLeast(0)
}

/**
 * One device's run of use "in a row" of an ALLOWANCE block with a row rule (I32): [ms] of use,
 * the last of it at [lastActiveWall]; resting [Block.sessionBreakMs] starts a fresh run. A run
 * that reached [Block.sessionMs] set [breakUntilWall].
 */
@Serializable
data class RunUse(val ms: Long = 0, val lastActiveWall: Long = 0, val breakUntilWall: Long = 0)

/**
 * One device's ALLOWANCE use of a block on the local day starting at [dayStartWall] (I19, §2.4),
 * with its current run (I32). [atWall]: when that device last counted any of it.
 */
@Serializable
data class AllowanceUse(val dayStartWall: Long, val ms: Long, val run: RunUse? = null, val atWall: Long = 0)

/** Minutes bought past a block (I34). Counted down on the monotonic clock, like [Lockdown]. */
@Serializable
data class OverrideGrant(
    val blockId: String,
    val startedAtMonotonic: Long,
    val startedAtWall: Long,
    val durationMs: Long,
    /** The device that bought it; a [Block.perDevice] block opens there only (I33). */
    val deviceId: String = "",
) {
    /** Names this grant on every device, for the [World.overrideLog] (I34). */
    val key: String get() = "$blockId@$startedAtWall"
}

/** An override waiting out its delay on this device (I34); never shared. */
@Serializable
data class PendingOverride(val blockIds: Set<String>, val sinceMonotonic: Long, val sinceWall: Long)

@Serializable
data class Settings(
    val dailyCapMs: Long = Defaults.DAILY_CAP_MS,
    val skewToleranceMs: Long = Defaults.SKEW_TOLERANCE_MS,
    val unlockWarningMs: Long = Defaults.UNLOCK_WARNING_MS,
    /** I34: what has to be typed to buy minutes past a block. */
    val overridePhrase: String = Defaults.OVERRIDE_PHRASE,
    val overrideWaitMs: Long = Defaults.OVERRIDE_WAIT_MS,
    val overrideGrantMs: Long = Defaults.OVERRIDE_GRANT_MS,
    /** I34: per block per day, across devices; 0 turns overrides off. */
    val overridesPerDay: Int = Defaults.OVERRIDES_PER_DAY,
    /** The browser extension's corner clock on limited sites. */
    val showClock: Boolean = true,
    /** I49: browsers without the extension are blocked while any block has websites. */
    val blockOtherBrowsers: Boolean = true,
)

/**
 * The whole persisted world. `:app` loads it on boot and saves it back after every [Outcome]
 * (save must complete before the shield is touched — I7).
 */
@Serializable
data class World(
    val blocks: List<Block> = emptyList(),
    val progress: Map<String, HabitProgress> = emptyMap(),
    val credits: Map<String, EarnedCredit> = emptyMap(),
    val sessions: Map<String, UnlockSession> = emptyMap(),
    val checklist: Map<String, List<ChecklistItem>> = emptyMap(),
    /** Unlock time used today before [unlockLog] existed; only old data has it (I9, I28). */
    val dailyUnlockUsedMs: Long = 0,
    /** Epoch-day number [dailyUnlockUsedMs] and [unlockLog] apply to. */
    val dailyUsageEpochDay: Long = 0,
    /** Consumed unlock time per ended session today, keyed by [UnlockSession.key] (I28). */
    val unlockLog: Map<String, Long> = emptyMap(),
    val foregroundPkg: String? = null,
    /** Host shown in [foregroundPkg] when that is a browser and its address bar could be read (I18). */
    val foregroundSite: String? = null,
    /** I47: pages playing sound here without being in front. Never shared. */
    val backgroundSites: Set<String> = emptySet(),
    val lockdowns: Map<String, Lockdown> = emptyMap(),
    /** The running break, if any (I25). */
    val pause: Pause? = null,
    /** Local epoch day the last break was taken; one a day (I25). */
    val lastPauseEpochDay: Long? = null,
    val settings: Settings = Settings(),
    /** This install's id in the shared state (§2.4); empty until the app assigns one. */
    val deviceId: String = "",
    /** I42: whether this install is the phone or the laptop; null (old data) means every block holds here. Never shared. */
    val deviceKind: DeviceKind? = null,
    /** Other devices' ALLOWANCE use, block id -> device id -> use (I19, §2.4). */
    val otherAllowance: Map<String, Map<String, AllowanceUse>> = emptyMap(),
    /** This device's run of use in a row, by block (I32). */
    val runs: Map<String, RunUse> = emptyMap(),
    /** Minutes bought past blocks, by block (I34). */
    val overrides: Map<String, OverrideGrant> = emptyMap(),
    /** Overrides bought on [overrideEpochDay], keyed by [OverrideGrant.key] (I34). */
    val overrideLog: Map<String, Long> = emptyMap(),
    val overrideEpochDay: Long = 0,
    /** I40: every emergency unlock ever, "blockId@wall" -> wall; shared, only ever added to. */
    val emergencyLog: Map<String, Long> = emptyMap(),
    /** An override waiting out its delay here (I34). */
    val pendingOverride: PendingOverride? = null,
    /**
     * The last plan handed to the shield. A new plan is emitted when the projection differs
     * from this, not from the previous world's projection: schedules and lockdowns change the
     * projection by time alone, so "before" and "after" evaluated now would always agree.
     */
    val appliedShield: ShieldPlan = ShieldPlan(emptySet()),
    /** The habit list (§2.5), archived ones included; [Habit.position] orders it. */
    val habits: List<Habit> = emptyList(),
    /** Every habit's entries by [HabitEntry.key], the whole history (I35). */
    val habitEntries: Map<String, HabitEntry> = emptyMap(),
    /** Habits deleted here and not yet deleted on the habit server, by id (I38). */
    val habitTombstones: Map<String, HabitTombstone> = emptyMap(),
    /** The habit server's seq up to which this device has read (§2.5). */
    val habitSyncSeq: Long = 0,
    /** The habit server's epoch that [habitSyncSeq] belongs to; another one means it went back (§2.5). */
    val habitServerEpoch: Long? = null,
    /** The hour a habit day starts, 0..23 (§1; from the habit server). */
    val habitDayStartHour: Int = 0,
    /** The focus timer, if one is running or paused here (I37). */
    val focus: FocusRun? = null,
) {
    fun block(id: String): Block? = blocks.firstOrNull { it.id == id }
    fun habit(id: String): Habit? = habits.firstOrNull { it.id == id }

    /** The entry of [habitId] on habit day [epochDay], if any (I35). */
    fun entry(habitId: String, epochDay: Long): HabitEntry? = habitEntries[HabitEntry.key(habitId, epochDay)]

    /** The habit day it is now (§1): the local date of now minus [habitDayStartHour] hours. */
    fun habitDay(clock: Clock): Long = clock.habitEpochDay(habitDayStartHour)

    /** Blocks with a habit item asking for [habitId]. */
    fun blocksUsing(habitId: String): List<Block> =
        blocks.filter { b -> items(b.id).any { it.habitId == habitId } }
    fun items(blockId: String): List<ChecklistItem> = checklist[blockId].orEmpty()

    /** Starts (or restarts, once ended) a [BlockMode.LOCKDOWN] block's lockdown (I21). */
    fun withLockdownStarted(blockId: String, clock: Clock): World {
        val block = block(blockId) ?: return this
        if (block.mode != BlockMode.LOCKDOWN || block.lockdownMs <= 0) return this
        val running = lockdowns[blockId]?.let { clock.nowMonotonicMs() - it.startedAtMonotonic < it.durationMs } == true
        if (running) return this
        val lockdown = Lockdown(blockId, clock.nowMonotonicMs(), clock.nowWallMs(), block.lockdownMs)
        return copy(lockdowns = lockdowns + (blockId to lockdown))
    }
}
