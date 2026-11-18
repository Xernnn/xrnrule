package com.xern.xrnrule.domain

/** Inputs to [BlockEngine.reduce]. See `docs/domain-contract.md` §5. */
sealed interface BlockEvent {

    /**
     * Foreground app changed (from `ForegroundAppDetector`). `pkg == null` means nothing is in
     * use (home screen, screen off). [site] is the host a foreground browser is showing (I18).
     */
    data class ForegroundChanged(val pkg: String?, val site: String? = null) : BlockEvent

    /** I47: the pages playing sound on this device without being in front; empty when none are. */
    data class BackgroundChanged(val sites: Set<String>) : BlockEvent

    /** Periodic wake (~1s) while any timer, session, or non-HABIT block is live. */
    data object Tick : BlockEvent

    /** Start a [BlockMode.LOCKDOWN] block's lockdown; no-op while one is running (I21). */
    data class StartLockdown(val blockId: String) : BlockEvent

    /** User ticked/unticked a to-do item on the block detail or the overlay. No-op unless ARMED (I12). */
    data class ToggleChecklistItem(val itemId: String, val done: Boolean) : BlockEvent

    /** The phone read an NFC tag ([Tags.id]): it ticks every tag item waiting for it (I43). */
    data class TagScanned(val tag: String) : BlockEvent

    /** User pressed "Redeem" on an EARNED block. */
    data class Redeem(val blockId: String) : BlockEvent

    /** User discarded an EARNED credit without spending it. */
    data class UserResetEarned(val blockId: String) : BlockEvent

    /** User pressed "Start" / "Pause" on a TIMER habit. */
    data class TimerControl(val blockId: String, val running: Boolean) : BlockEvent

    /** Starts a break from SOFT blocks, at most once per local day (I25). */
    data class StartPause(val durationMs: Long = Defaults.PAUSE_MS) : BlockEvent

    /** Ends the running break early (I25). */
    data object EndPause : BlockEvent

    /**
     * The shared state as the sync server has it ([remote]) and as this device last agreed with
     * it ([base], null the first time). The engine merges it three ways with this device's own
     * and adopts the result (contract §2.4, I26, I27).
     */
    data class SharedStateMerged(val base: SharedState?, val remote: SharedState) : BlockEvent

    /**
     * Starts buying minutes past [blockIds], the blocks keeping one target blocked
     * ([BlockEngine.blockersOf]); the wait starts now. Ignored unless all of them can be
     * overridden ([BlockEngine.overrideRejection]). A wait already running for them goes on (I34).
     */
    data class BeginOverride(val blockIds: Set<String>) : BlockEvent

    /** Drops the override waiting here, if any (I34). */
    data object CancelOverride : BlockEvent

    /** Finishes the override waiting here: grants it if the wait is over and [phrase] matches (I34). */
    data class ClaimOverride(val phrase: String) : BlockEvent

    /**
     * Logs [amount] more of a habit on today's habit day (I35), in thousandths of its unit;
     * negative takes some back, never below zero. For a check, positive ticks it and negative
     * unticks it.
     */
    data class LogHabit(val habitId: String, val amount: Long) : BlockEvent

    /**
     * Sets a habit's entry on any habit day up to today: Loop's value and the day's notes (I35).
     * Null [notes] keeps the day's note as it is: a tick or an amount never says anything about it.
     */
    data class SetHabitEntry(val habitId: String, val epochDay: Long, val value: Long, val notes: String? = null) : BlockEvent

    /**
     * Records read from the habit server, after [seq] (§2.5): merged into this device's habits and
     * entries (I38). [dayStartHour] is the server's habit-day start, when it says. [full]: these
     * are all the server has, read again from the start because it went back (§2.5). [epoch] is
     * the server's, when it says.
     */
    data class HabitServerPulled(
        val habits: List<HabitRecord>,
        val entries: List<EntryRecord>,
        val seq: Long,
        val dayStartHour: Int? = null,
        val full: Boolean = false,
        val epoch: Long? = null,
    ) : BlockEvent

    /** The habit server's answer to a push of [sent] (I38). */
    data class HabitServerPushed(
        val sent: HabitPush,
        val accepted: HabitAcceptance,
        val rejectedHabits: List<HabitRecord> = emptyList(),
        val rejectedEntries: List<EntryRecord> = emptyList(),
    ) : BlockEvent

    /** Starts or resumes the focus timer for a TIMER habit, ending another habit's first (I37). */
    data class StartFocus(val habitId: String) : BlockEvent

    /** Pauses the focus timer, logging its time; with [breakMs] it goes on by itself after that long (I37). */
    data class PauseFocus(val breakMs: Long = 0) : BlockEvent

    /** Stops the focus timer, logging its time (I37). */
    data object EndFocus : BlockEvent

    /** Process cold start or BOOT_COMPLETED — rebuild state and re-apply the shield (I8). */
    data object BootOrColdStart : BlockEvent
}
