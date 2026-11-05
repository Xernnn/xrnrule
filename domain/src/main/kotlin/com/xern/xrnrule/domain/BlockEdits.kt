package com.xern.xrnrule.domain

/**
 * Which edits make a block easier to get past (contract §2.3). The app asks for the guard —
 * a wait, or the hardcore password — before applying one; edits that only make a block
 * stricter go through at once.
 */
object BlockEdits {

    /** Whether replacing [old] with [new] loosens the block in any way. */
    fun loosens(old: Block, new: Block): Boolean {
        return !new.targetPackages.containsAll(old.targetPackages) ||
            !new.targetSites.containsAll(old.targetSites) ||
            !new.targetDesktopApps.containsAll(old.targetDesktopApps) ||
            (old.enabled && !new.enabled) ||
            (old.enforcementTier == EnforcementTier.HARDCORE && new.enforcementTier == EnforcementTier.SOFT) ||
            rulesLoosen(old, new) ||
            allowanceLoosens(old, new) ||
            !old.exceptSites.containsAll(new.exceptSites) || // I31: a new exception opens a host
            (!old.perDevice && new.perDevice) || // I33: each device gets the whole limit
            (!old.selfPaced && new.selfPaced) || // I41: an unlock spread over the day
            !new.devices.covers(old.devices) || // I42: a device it no longer holds on
            new.lockdownMs < old.lockdownMs ||
            (new.lock?.untilWall ?: 0) < (old.lock?.untilWall ?: 0) || // I39: a shorter lock
            hoursLoosen(old, new) ||
            // A SCHEDULE block's schedule is its hours, just compared; elsewhere it's when the rules hold.
            (old.mode != BlockMode.SCHEDULE && new.mode != BlockMode.SCHEDULE && schedulesLoosen(old.schedule, new.schedule))
    }

    /**
     * I44: going from one mix of rules to another. Rules may be added, but a habit is a way past a
     * limit that comes first, a limit put first is free minutes, and a habit block's own unlock
     * settings only count while it stays one.
     */
    private fun rulesLoosen(old: Block, new: Block): Boolean {
        val oldHabit = old.mode == BlockMode.HABIT
        val newHabit = new.mode == BlockMode.HABIT
        if (old.mode == BlockMode.LOCKDOWN || new.mode == BlockMode.LOCKDOWN) return new.mode != old.mode
        if (oldHabit && !newHabit) return true
        if (!oldHabit && newHabit) return old.hasLimit && new.limitComesFirst
        if (!oldHabit) return false
        val oldTimer = old.trigger.type == TriggerType.TIMER || old.trigger.type == TriggerType.COMBINED
        return new.trigger.type != old.trigger.type ||
            (oldTimer && new.trigger.requiredAmount < old.trigger.requiredAmount) ||
            (old.trigger.combineMode == CombineMode.AND && new.trigger.combineMode == CombineMode.OR) ||
            new.trigger.habitPackages != old.trigger.habitPackages ||
            new.reward.unlockDurationMs > old.reward.unlockDurationMs ||
            new.reward.creditTtlMs > old.reward.creditTtlMs ||
            (new.limitComesFirst && !old.limitComesFirst) // free minutes it didn't have
    }

    /** I44: the hours a block blocks outright: its [Block.blockedHours], or a SCHEDULE block's schedule. */
    private fun hours(block: Block): Schedule? =
        if (block.mode == BlockMode.SCHEDULE) block.schedule else block.blockedHours

    private fun hoursLoosen(old: Block, new: Block): Boolean {
        val before = hours(old) ?: return false
        val after = hours(new) ?: return true
        return schedulesLoosen(before, after)
    }

    /** I39: whether [block]'s settings are locked at [nowWall]. */
    fun locked(block: Block, nowWall: Long): Boolean = block.lock.let { it != null && nowWall < it.untilWall }

    /** I39: the longest lock still running among [blockIds] in [world], or null if none is. */
    fun lockedBy(world: World, blockIds: Collection<String>, nowWall: Long): Block? =
        blockIds.mapNotNull { world.block(it) }.filter { locked(it, nowWall) }.maxByOrNull { it.lock!!.untilWall }

    /** I40: emergency unlocks still to use, on every device together. */
    fun emergencyLeft(world: World): Int = (Defaults.EMERGENCY_UNLOCKS - world.emergencyLog.size).coerceAtLeast(0)

    /**
     * I40: ends [blockId]'s lock now and logs it, or null when the block isn't locked or all
     * [Defaults.EMERGENCY_UNLOCKS] are used. The edit it was for still goes through the guard.
     */
    fun emergencyUnlock(world: World, blockId: String, nowWall: Long): World? {
        val block = world.block(blockId)?.takeIf { locked(it, nowWall) } ?: return null
        if (emergencyLeft(world) == 0) return null
        return world.copy(
            blocks = world.blocks.map { if (it.id == block.id) it.copy(lock = BlockLock(nowWall, nowWall)) else it },
            emergencyLog = world.emergencyLog + ("${block.id}@$nowWall" to nowWall),
        )
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
     * Whether loosening a block's own settings needs the guard right now. Only a lockdown that
     * isn't running is inert; everything else is either blocking or about to. A locked block
     * (I39) always does: the guard then refuses until the lock ends.
     */
    fun guardNeeded(engine: BlockEngine, world: World, block: Block, clock: Clock): Boolean {
        val current = world.block(block.id) ?: block // a caller may hold a copy from before a lock
        return locked(current, clock.nowWallMs()) ||
            !(current.mode == BlockMode.LOCKDOWN && engine.lockdownRemainingMs(world, current.id, clock) == 0L)
    }
}
