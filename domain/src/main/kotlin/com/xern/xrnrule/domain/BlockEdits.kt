package com.xern.xrnrule.domain

/**
 * Which edits make a block easier to get past (contract §2.3). The app asks for the guard —
 * a wait, or the hardcore password — before applying one; edits that only make a block
 * stricter go through at once.
 */
object BlockEdits {

    /** Whether replacing [old] with [new] loosens the block in any way. */
    fun loosens(old: Block, new: Block): Boolean {
        val oldTimer = old.trigger.type == TriggerType.TIMER || old.trigger.type == TriggerType.COMBINED
        return !new.targetPackages.containsAll(old.targetPackages) ||
            !new.targetSites.containsAll(old.targetSites) ||
            !new.targetDesktopApps.containsAll(old.targetDesktopApps) ||
            (old.enabled && !new.enabled) ||
            (old.enforcementTier == EnforcementTier.HARDCORE && new.enforcementTier == EnforcementTier.SOFT) ||
            new.mode != old.mode ||
            new.trigger.type != old.trigger.type ||
            (oldTimer && new.trigger.requiredAmount < old.trigger.requiredAmount) ||
            (old.trigger.combineMode == CombineMode.AND && new.trigger.combineMode == CombineMode.OR) ||
            new.trigger.habitPackages != old.trigger.habitPackages ||
            new.reward.unlockDurationMs > old.reward.unlockDurationMs ||
            new.reward.creditTtlMs > old.reward.creditTtlMs ||
            allowanceLoosens(old, new) ||
            !old.exceptSites.containsAll(new.exceptSites) || // I31: a new exception opens a host
            (!old.perDevice && new.perDevice) || // I33: each device gets the whole limit
            new.lockdownMs < old.lockdownMs ||
            schedulesLoosen(old.schedule, new.schedule)
    }

    /** I19/I32: a bigger daily limit (0 is none at all), a longer row, or a shorter break. */
    private fun allowanceLoosens(old: Block, new: Block): Boolean {
        fun bigger(o: Long, n: Long) = o > 0 && (n == 0L || n > o)
        return bigger(old.dailyAllowanceMs, new.dailyAllowanceMs) ||
            (old.hasRowRule && (!new.hasRowRule || new.sessionMs > old.sessionMs || new.sessionBreakMs < old.sessionBreakMs))
    }

    /**
     * Fewer days, or any change to the main window, can free a moment that used to be blocked;
     * so can dropping or changing one of the other windows (I30). Adding windows only tightens.
     */
    private fun schedulesLoosen(old: Schedule, new: Schedule): Boolean =
        !new.activeDays.containsAll(old.activeDays) || new.activeWindow != old.activeWindow ||
            !new.moreWindows.containsAll(old.moreWindows)

    /**
     * §2.3: a habit some block asks for ([World.blocksUsing]) gets easier: a smaller unlock's worth
     * (I36), another kind, or a check due less often (Loop fills in more days by itself). Deleting
     * one doesn't loosen: its items stay and can't be done any more.
     */
    fun habitLoosens(old: Habit, new: Habit): Boolean {
        if (new.kind != old.kind) return true
        val before = old.unlockUnit
        val after = new.unlockUnit
        if (after != null && (before == null || after < before)) return true
        return !new.numerical && new.frequency < old.frequency
    }

    /** I34: changes to the override settings that make buying minutes easier. */
    fun settingsLoosen(old: Settings, new: Settings): Boolean =
        Sites.normalizePhrase(new.overridePhrase).length < Sites.normalizePhrase(old.overridePhrase).length ||
            new.overrideWaitMs < old.overrideWaitMs ||
            new.overrideGrantMs > old.overrideGrantMs ||
            new.overridesPerDay > old.overridesPerDay ||
            new.dailyCapMs > old.dailyCapMs

    /**
     * Whether a block's own settings may change freely right now. Only a lockdown that isn't
     * running is inert; everything else is either blocking or about to.
     */
    fun guardNeeded(engine: BlockEngine, world: World, block: Block, clock: Clock): Boolean =
        !(block.mode == BlockMode.LOCKDOWN && engine.lockdownRemainingMs(world, block.id, clock) == 0L)
}
