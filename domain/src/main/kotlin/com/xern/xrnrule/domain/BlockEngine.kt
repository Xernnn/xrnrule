package com.xern.xrnrule.domain

import java.time.Instant

/**
 * The state machine from `docs/domain-contract.md` §2. Pure and deterministic: [reduce] takes
 * the current [World], one [BlockEvent] and a [Clock], and returns an [Outcome]. It performs
 * no I/O and never mutates its inputs.
 *
 * Every invariant I1..I38 in the contract has a matching test in
 * `domain/src/test/.../InvariantTests.kt` (`i01_*` .. `i38_*`).
 */
class BlockEngine {

    fun reduce(world: World, event: BlockEvent, clock: Clock): Outcome {
        val effects = mutableListOf<Effect>()

        var w = when (event) {
            is BlockEvent.BootOrColdStart -> rebuild(world, clock, effects)
            is BlockEvent.ForegroundChanged -> onForegroundChanged(world, event, clock, effects)
            is BlockEvent.Tick -> accrueAndCheckTimers(world, clock, effects)
            is BlockEvent.ToggleChecklistItem -> onToggleItem(world, event, clock, effects)
            is BlockEvent.Redeem -> onRedeem(world, event.blockId, clock, effects)
            is BlockEvent.UserResetEarned -> onUserReset(world, event.blockId, clock)
            is BlockEvent.TimerControl -> onTimerControl(world, event, clock)
            is BlockEvent.StartLockdown -> world.withLockdownStarted(event.blockId, clock) // I21
            is BlockEvent.StartPause -> onStartPause(world, event, clock)
            is BlockEvent.EndPause -> world.copy(pause = null)
            is BlockEvent.SharedStateMerged ->
                adoptShared(world, SharedState.merge(event.base, SharedState.of(world), event.remote), clock) // I26
            is BlockEvent.BeginOverride -> onBeginOverride(world, event, clock)
            is BlockEvent.CancelOverride -> world.copy(pendingOverride = null)
            is BlockEvent.ClaimOverride -> onClaimOverride(world, event, clock, effects)
            is BlockEvent.LogHabit -> onLogHabit(world, event, clock)
            is BlockEvent.SetHabitEntry -> onSetHabitEntry(world, event, clock)
            is BlockEvent.HabitServerPulled -> world.mergeHabitRecords(event.habits, event.entries, event.full).copy( // I38
                habitSyncSeq = if (event.full) event.seq else maxOf(world.habitSyncSeq, event.seq),
                habitServerEpoch = event.epoch ?: world.habitServerEpoch,
                habitDayStartHour = event.dayStartHour?.coerceIn(0, 23) ?: world.habitDayStartHour,
            )
            is BlockEvent.HabitServerPushed ->
                world.afterHabitPush(event.sent, event.accepted, event.rejectedHabits, event.rejectedEntries) // I38
            is BlockEvent.StartFocus -> onStartFocus(world, event.habitId, clock)
            is BlockEvent.PauseFocus -> onPauseFocus(world, event.breakMs, clock)
            is BlockEvent.EndFocus -> if (world.focus == null) world else fold(world, clock).copy(focus = null)
        }

        // Housekeeping sweep after every event: day rollover, credit expiry (I1), tamper (I3),
        // unlock time-up + 30s warning (G3), lockdown end (I21), break end (I25). Keeping this out of the
        // per-event handlers means a plain Tick and a Redeem both re-evaluate expiry the same way.
        w = sweep(w, clock, effects)
        // I36: habit items follow the entries, whatever moved them (a log here, the focus timer,
        // the habit server); after the sweep, so a block it just re-armed can earn again.
        w = refreshHabitItems(w, clock, effects)

        val desired = shieldPlan(w, clock)
        val plan = if (desired != world.appliedShield || event is BlockEvent.BootOrColdStart) desired else null
        return Outcome(w.copy(appliedShield = desired), plan, effects)
    }

    // ---- shield projection -------------------------------------------------------------

    /**
     * Sites are evaluated per configured domain, like packages (I18); a domain's exceptions are
     * the hosts every block blocking it leaves open (I31).
     */
    fun shieldPlan(world: World, clock: Clock): ShieldPlan {
        val sites = world.blocks.flatMapTo(mutableSetOf()) { it.targetSites }
            .associateWith { site -> blockers(world, world.blocks.filter { site in it.targetSites }, clock) }
            .filterValues { it.isNotEmpty() }
        return ShieldPlan(
            blockedPackages = blockedPackages(world, clock),
            blockedSites = sites.keys,
            siteExceptions = sites
                .mapValues { (_, blocks) -> blocks.map { it.exceptSites }.reduce { a, b -> a intersect b } }
                .filterValues { it.isNotEmpty() },
        )
    }

    /** Blocked apps: phone packages and laptop apps alike (I29). */
    fun blockedPackages(world: World, clock: Clock): Set<String> {
        val allTargets = world.blocks.flatMapTo(mutableSetOf()) { it.targetApps }
        return allTargets.filterTo(mutableSetOf()) { pkg ->
            blockers(world, world.blocks.filter { pkg in it.targetApps }, clock).isNotEmpty()
        }
    }

    /** I18: target domains currently blocked, evaluated per configured domain like packages. */
    fun blockedSites(world: World, clock: Clock): Set<String> = shieldPlan(world, clock).blockedSites

    /**
     * I22: the blocks keeping a target blocked, out of [all] that cover it; empty when it is
     * free. Any engaged ALLOWANCE / SCHEDULE / LOCKDOWN block blocks outright; HABIT blocks block
     * unless *any* covering HABIT block is UNLOCKED (I6). During a break (I25) the blocks it
     * covers drop out first.
     */
    fun blockers(world: World, all: List<Block>, clock: Clock): List<Block> {
        val here = all.filter { world.appliesHere(it) } // I42
        val covering = if (pauseRemainingMs(world, clock) > 0) here.filterNot(::pausable) else here
        val strict = covering.filter { it.mode != BlockMode.HABIT && isBlocking(world, it, clock) }
        val habit = covering.filter { it.mode == BlockMode.HABIT }
        val habitBlocking = if (habit.any { it.state == BlockState.UNLOCKED }) emptyList() else habit.filter { it.enabled && scheduleActive(it, clock) }
        return strict + habitBlocking
    }

    /** The blocks keeping this app or site blocked right now (I22, I31); empty when it is open. */
    fun blockersOf(world: World, pkg: String?, site: String?, clock: Clock): List<Block> =
        blockers(world, world.blocks.filter { covers(it, pkg, site) }, clock)

    fun isAppUnlocked(world: World, pkg: String): Boolean =
        world.blocks.any { pkg in it.targetApps && it.state == BlockState.UNLOCKED }

    /** I5: the shield is on for a block in every state except UNLOCKED. */
    fun shieldOn(world: World, blockId: String, clock: Clock): Boolean {
        val b = world.block(blockId) ?: return false
        return isBlocking(world, b, clock)
    }

    /** Whether this one block, on its own, is blocking its targets right now (contract §2.2). */
    fun isBlocking(world: World, block: Block, clock: Clock): Boolean {
        if (!block.enabled || !world.appliesHere(block)) return false // I42
        if (pausable(block) && pauseRemainingMs(world, clock) > 0) return false // I25
        return when (block.mode) {
            BlockMode.HABIT -> block.state != BlockState.UNLOCKED && scheduleActive(block, clock)
            BlockMode.ALLOWANCE -> scheduleActive(block, clock) && (
                rowBreakRemainingMs(world, block, clock) > 0 || // I32: a bought minute doesn't lift a forced break
                    (dailyLimitReached(world, block, clock) && overrideRemainingMs(world, block.id, clock) == 0L)
                )
            BlockMode.SCHEDULE -> scheduleActive(block, clock) && overrideRemainingMs(world, block.id, clock) == 0L // I34
            BlockMode.LOCKDOWN -> lockdownRemainingMs(world, block.id, clock) > 0
        }
    }

    /** I19: the daily limit is on and used up (0 means no daily limit, only the row rule). */
    fun dailyLimitReached(world: World, block: Block, clock: Clock): Boolean =
        block.dailyAllowanceMs > 0 && allowanceUsedMs(world, block, clock) >= block.dailyAllowanceMs

    /**
     * I19: today's used allowance, here plus on the other devices (§2.4) unless each device has
     * its own (I33); a progress window or a report from an earlier day counts as zero.
     */
    fun allowanceUsedMs(world: World, block: Block, clock: Clock): Long {
        val p = world.progress[block.id]
        val here = if (p == null || dayRolled(p, clock)) 0 else p.accumulatedMs
        if (block.perDevice) return here
        val today = clock.startOfLocalDayMs()
        val elsewhere = world.otherAllowance[block.id].orEmpty().values.sumOf { if (it.dayStartWall == today) it.ms else 0 }
        return here + elsewhere
    }

    /**
     * I32: use in a row so far: this device's run, plus other devices' (unless [Block.perDevice])
     * while they haven't rested [Block.sessionBreakMs] since.
     */
    fun rowUsedMs(world: World, block: Block, clock: Clock): Long {
        if (!block.hasRowRule) return 0
        val now = clock.nowWallMs()
        val here = world.runs[block.id]?.takeIf { live(it, block, now) }?.ms ?: 0
        return here + rowElsewhereMs(world, block, now)
    }

    private fun rowElsewhereMs(world: World, block: Block, now: Long): Long =
        if (block.perDevice) 0
        else world.otherAllowance[block.id].orEmpty().values.sumOf { use -> use.run?.takeIf { live(it, block, now) }?.ms ?: 0 }

    private fun live(run: RunUse, block: Block, now: Long): Boolean =
        run.lastActiveWall > 0 && now - run.lastActiveWall < block.sessionBreakMs

    /** I32: what's left of a forced break: this device's, or any device's on a shared block. */
    fun rowBreakRemainingMs(world: World, block: Block, clock: Clock): Long {
        if (!block.hasRowRule) return 0
        val here = world.runs[block.id]?.breakUntilWall ?: 0
        val elsewhere = if (block.perDevice) 0 else world.otherAllowance[block.id].orEmpty().values.maxOfOrNull { it.run?.breakUntilWall ?: 0 } ?: 0
        return (maxOf(here, elsewhere) - clock.nowWallMs()).coerceAtLeast(0)
    }

    /** I34: what's left of the minutes bought past this block; a per-device block's only count where they were bought (I33). */
    fun overrideRemainingMs(world: World, blockId: String, clock: Clock): Long {
        val g = world.overrides[blockId] ?: return 0
        if (world.block(blockId)?.perDevice == true && g.deviceId != world.deviceId) return 0
        return (g.durationMs - (clock.nowMonotonicMs() - g.startedAtMonotonic)).coerceAtLeast(0)
    }

    /** I34: overrides of this block still to be had today, across devices. */
    fun overridesLeftToday(world: World, blockId: String, clock: Clock): Int {
        val used = if (world.overrideEpochDay != clock.epochDay()) 0 else world.overrideLog.keys.count { it.substringBeforeLast('@') == blockId }
        return (world.settings.overridesPerDay - used).coerceAtLeast(0)
    }

    /**
     * I34: why minutes can't be bought past [blocks] — the blockers of one target
     * ([blockersOf]) — right now, or null if they can: every one has to be a SOFT daily limit or
     * schedule, not on a forced break, with overrides left today.
     */
    fun overrideRejection(world: World, blocks: List<Block>, clock: Clock): OverrideRejection? {
        if (world.settings.overridesPerDay <= 0) return OverrideRejection.OFF
        if (blocks.isEmpty() || blocks.any { !overridable(world, it, clock) }) return OverrideRejection.NOT_OVERRIDABLE
        if (blocks.any { overridesLeftToday(world, it.id, clock) <= 0 }) return OverrideRejection.NONE_LEFT
        return null
    }

    private fun overridable(world: World, block: Block, clock: Clock): Boolean =
        block.enforcementTier == EnforcementTier.SOFT && when (block.mode) {
            BlockMode.SCHEDULE -> true
            BlockMode.ALLOWANCE -> rowBreakRemainingMs(world, block, clock) == 0L
            BlockMode.HABIT, BlockMode.LOCKDOWN -> false
        }

    /**
     * What runs out first on an open app or site, for the browser's corner clock: today's limit,
     * the row rule, a habit unlock, bought minutes or the daily break. Null when nothing meters it.
     */
    fun timeLeft(world: World, pkg: String?, site: String?, clock: Clock): TimeLeft? {
        val covering = world.blocks.filter { it.enabled && world.appliesHere(it) && covers(it, pkg, site) }
        if (covering.isEmpty()) return null
        val left = mutableListOf<TimeLeft>()
        for (b in covering) {
            when (b.mode) {
                BlockMode.ALLOWANCE -> if (scheduleActive(b, clock)) {
                    val bought = overrideRemainingMs(world, b.id, clock)
                    if (b.dailyAllowanceMs > 0) {
                        left += if (bought > 0 && dailyLimitReached(world, b, clock)) TimeLeft(bought, ClockKind.OVERRIDE, b.id)
                        else TimeLeft((b.dailyAllowanceMs - allowanceUsedMs(world, b, clock)).coerceAtLeast(0), ClockKind.LIMIT, b.id)
                    }
                    if (b.hasRowRule) left += TimeLeft((b.sessionMs - rowUsedMs(world, b, clock)).coerceAtLeast(0), ClockKind.ROW, b.id)
                }
                BlockMode.SCHEDULE -> {
                    val bought = overrideRemainingMs(world, b.id, clock)
                    if (bought > 0 && scheduleActive(b, clock)) left += TimeLeft(bought, ClockKind.OVERRIDE, b.id)
                }
                BlockMode.HABIT -> world.sessions[b.id]?.takeIf { b.state == BlockState.UNLOCKED }?.let { s ->
                    left += TimeLeft(s.leftMs(clock), ClockKind.UNLOCK, b.id)
                }
                BlockMode.LOCKDOWN -> Unit
            }
        }
        val pause = pauseRemainingMs(world, clock)
        if (pause > 0 && blockers(world.copy(pause = null), covering, clock).any(::pausable)) left += TimeLeft(pause, ClockKind.BREAK)
        return left.minByOrNull { it.ms }
    }

    /** I21: monotonic countdown, so moving the wall clock can't shorten it. */
    fun lockdownRemainingMs(world: World, blockId: String, clock: Clock): Long {
        val l = world.lockdowns[blockId] ?: return 0
        return (l.durationMs - (clock.nowMonotonicMs() - l.startedAtMonotonic)).coerceAtLeast(0)
    }

    /** I25: what's left of the running break, on the monotonic clock like a lockdown. */
    fun pauseRemainingMs(world: World, clock: Clock): Long {
        val p = world.pause ?: return 0
        return (p.durationMs - (clock.nowMonotonicMs() - p.startedAtMonotonic)).coerceAtLeast(0)
    }

    /** I25: a break may be started when none is running and none was taken today. */
    fun canPause(world: World, clock: Clock): Boolean =
        pauseRemainingMs(world, clock) == 0L && world.lastPauseEpochDay != clock.epochDay()

    /** I25: a break lifts SOFT blocks only, and never a lockdown the user started on purpose. */
    fun pausable(block: Block): Boolean =
        block.enforcementTier == EnforcementTier.SOFT && block.mode != BlockMode.LOCKDOWN

    /** I9/I28: unlock time spent today; counters from an earlier day count as zero. */
    fun unlockUsedTodayMs(world: World, clock: Clock): Long =
        if (clock.epochDay() != world.dailyUsageEpochDay) 0 else world.dailyUnlockUsedMs + world.unlockLog.values.sum()

    /**
     * I9: what a new unlock is measured against: today's ended unlocks plus every open one at its
     * full length, so opening several blocks at once can't add up past the daily limit.
     */
    fun unlockCommittedMs(world: World, clock: Clock): Long =
        unlockUsedTodayMs(world, clock) + world.sessions.values.sumOf { it.durationMs }

    /** Why [BlockEvent.Redeem] would be refused right now, or null if it would go through (I9). */
    fun redeemRejection(world: World, blockId: String, clock: Clock): RejectReason? {
        val block = world.block(blockId)
        val credit = world.credits[blockId]
        if (block?.state != BlockState.EARNED || credit == null) return RejectReason.NOT_EARNED
        if (unlockCommittedMs(world, clock) + credit.amountMs > world.settings.dailyCapMs) return RejectReason.CAP_REACHED
        return null
    }

    /** I18: in a target app, or on a target site (or a subdomain of one) the block doesn't except (I31). */
    fun covers(block: Block, pkg: String?, site: String?): Boolean =
        (pkg != null && pkg in block.targetApps) || // I29: a laptop app is a target like a package
            (site != null && block.coversSite(site))

    // ---- habits (I23, I24, I35-I37) ---------------------------------------------------

    /** I23: the habit day a habit item counts: today's, or yesterday's. */
    fun itemDay(world: World, item: ChecklistItem, clock: Clock): Long {
        val today = world.habitDay(clock)
        return if (item.linkedDay == LinkedDay.YESTERDAY) today - 1 else today
    }

    /** I37: the running focus timer's ms not yet in an entry; 0 while paused or when there is none. */
    fun focusLiveMs(world: World, clock: Clock): Long {
        val run = world.focus?.takeIf { it.running } ?: return 0
        return (clock.nowMonotonicMs() - run.startedAtMonotonic).coerceAtLeast(0)
    }

    /**
     * I23, I37: an amount habit's amount on habit day [epochDay], in thousandths: its entry plus
     * the focus timer running here for that day.
     */
    fun habitAmount(world: World, habitId: String, epochDay: Long, clock: Clock): Long {
        val logged = (world.entry(habitId, epochDay)?.value ?: 0).coerceAtLeast(0)
        val live = world.focus?.takeIf { it.habitId == habitId && it.epochDay == epochDay }?.let { focusLiveMs(world, clock) / 60 } ?: 0
        return logged + live
    }

    /**
     * I23: Loop's value for a check on [epochDay], counting the days it fills in itself for a
     * check that isn't daily ([HabitEntry.YES_AUTO]).
     */
    fun checkValue(world: World, habit: Habit, epochDay: Long): Long {
        val own = world.entry(habit.id, epochDay)?.value ?: HabitEntry.UNKNOWN
        if (habit.frequency >= 1.0 || own == HabitEntry.YES_MANUAL || own == HabitEntry.SKIP) return own
        // An interval covering the day starts at most a period before it (LoopRules.buildIntervals).
        val span = habit.freqDen.coerceAtLeast(1) + 31L
        val upTo = epochDay + span
        val original = (epochDay - span..upTo).mapNotNull { d -> world.entry(habit.id, d)?.let { d to it.value } }.toMap()
        return LoopRules.computeEntries(habit, original)[epochDay] ?: own
    }

    /** I23: how much of its habit a habit item has on its day; a check counts as 1. */
    fun itemAmount(world: World, item: ChecklistItem, clock: Clock): Long {
        val habit = item.habitId?.let(world::habit) ?: return 0
        val day = itemDay(world, item, clock)
        if (habit.numerical) return habitAmount(world, habit.id, day, clock)
        val value = if (item.countsGoal) checkValue(world, habit, day) else world.entry(habit.id, day)?.value
        return when (value) {
            HabitEntry.YES_MANUAL -> 1
            HabitEntry.YES_AUTO -> if (item.countsGoal) 1 else 0
            else -> 0
        }
    }

    /** I36: how much of its habit a habit item has toward this block's next unlock. */
    fun habitAvailable(world: World, item: ChecklistItem, clock: Clock): Long =
        (itemAmount(world, item, clock) - item.spentOn(itemDay(world, item, clock))).coerceAtLeast(0)

    /** I36: whether a habit item has an unlock's worth of its habit; never for an "at most" habit. */
    fun itemReady(world: World, item: ChecklistItem, clock: Clock): Boolean {
        val unit = item.habitId?.let(world::habit)?.unlockUnit ?: return false
        return habitAvailable(world, item, clock) >= unit
    }

    // ---- trigger evaluation ----------------------------------------------------------

    /** I11 / I14 / I15. */
    fun triggerMet(world: World, block: Block): Boolean {
        val p = world.progress[block.id]
        return when (block.trigger.type) {
            TriggerType.TIMER -> timerMet(p, block)
            TriggerType.CHECKLIST -> checklistThresholdMet(world, block)
            TriggerType.COMBINED -> {
                val timer = timerMet(p, block)
                val checklist = checklistAllDone(world, block)
                when (block.trigger.combineMode) { // I15
                    CombineMode.AND -> timer && checklist
                    CombineMode.OR -> timer || checklist
                }
            }
        }
    }

    private fun timerMet(progress: HabitProgress?, block: Block): Boolean =
        (progress?.accumulatedMs ?: 0) >= block.trigger.requiredAmount

    private fun checklistThresholdMet(world: World, block: Block): Boolean {
        val items = world.items(block.id)
        if (items.isEmpty()) return false // I14
        val need =
            if (block.trigger.requiredAmount == Defaults.ALL_ITEMS) items.size.toLong()
            else block.trigger.requiredAmount
        return items.count { it.done } >= need
    }

    /** COMBINED's checklist half always means "every item" — no partial threshold (I15). */
    private fun checklistAllDone(world: World, block: Block): Boolean {
        val items = world.items(block.id)
        return items.isNotEmpty() && items.all { it.done } // I14-equivalent: empty never satisfies
    }

    // ---- event handlers ------------------------------------------------------------

    private fun onForegroundChanged(
        world: World,
        event: BlockEvent.ForegroundChanged,
        clock: Clock,
        effects: MutableList<Effect>,
    ): World {
        // Accrue time for the *previous* foreground app/site up to now, then switch.
        var w = accrueTimers(world, clock)
        val pkg = event.pkg
        if (pkg != null && pkg in blockedPackages(w, clock)) {
            effects += Effect.BlockedImpression(pkg)
        }
        w = w.copy(foregroundPkg = pkg, foregroundSite = event.site)
        return checkTimerTriggers(w, clock, effects)
    }

    private fun accrueAndCheckTimers(world: World, clock: Clock, effects: MutableList<Effect>): World {
        val w = accrueTimers(world, clock)
        return checkTimerTriggers(w, clock, effects)
    }

    /**
     * Advances every live progress marker to now, crediting the time since the last marker to
     * whatever was foreground over that span.
     *
     * HABIT timers (I4): only while running and the counted app/site is foreground. "Counted" is
     * [Trigger.habitPackages] when set, else the block's own targets (I16, I18) — a set test,
     * so any of several targets counts (I17).
     *
     * ALLOWANCE (I19): while a target is foreground *and not shielded* — time spent looking at
     * the block screen isn't use — capped at the allowance; the same time runs the row rule (I32).
     */
    private fun accrueTimers(before: World, clock: Clock): World {
        val world = accrueUnlockUse(before, clock)
        val nowMono = clock.nowMonotonicMs()
        val nowWall = clock.nowWallMs()
        val fgPkg = world.foregroundPkg
        val fgSite = world.foregroundSite

        var progress = world.progress
        for (b in world.blocks) {
            if (b.mode == BlockMode.ALLOWANCE && b.id !in progress) {
                progress = progress + (b.id to freshProgress(b.id, clock))
            }
        }
        if (progress.isEmpty()) return world

        val fgShielded = shieldPlan(world, clock).covers(fgPkg, fgSite)
        val runs = world.runs.toMutableMap()
        progress = progress.mapValues { (blockId, p) ->
            val block = world.block(blockId) ?: return@mapValues p
            val delta = (nowMono - p.lastUpdated).coerceAtLeast(0)
            val credit = when (block.mode) {
                BlockMode.ALLOWANCE -> !fgShielded && world.appliesHere(block) && covers(block, fgPkg, fgSite) // I42
                BlockMode.HABIT -> {
                    val hasTimer = block.trigger.type == TriggerType.TIMER || block.trigger.type == TriggerType.COMBINED
                    val counted = if (block.trigger.habitPackages.isNotEmpty()) {
                        fgPkg != null && fgPkg in block.trigger.habitPackages // I16, on any device (I42)
                    } else {
                        world.appliesHere(block) && covers(block, fgPkg, fgSite)
                    }
                    hasTimer && p.timerRunning && block.state == BlockState.ARMED && counted
                }
                BlockMode.SCHEDULE, BlockMode.LOCKDOWN -> false
            }
            if (!credit) return@mapValues p.copy(lastUpdated = nowMono)
            if (block.hasRowRule) runs[blockId] = advanceRun(world, block, runs[blockId], delta, nowWall)
            val accumulated = p.accumulatedMs + delta
            p.copy(
                accumulatedMs = if (block.mode == BlockMode.ALLOWANCE && block.dailyAllowanceMs > 0) accumulated.coerceAtMost(block.dailyAllowanceMs) else accumulated,
                lastUpdated = nowMono,
            )
        }
        return world.copy(progress = progress, runs = runs)
    }

    /**
     * I41: a self-paced unlock's use on this device: the time since its mark, while one of the
     * block's targets is in front and no other block shields it. A session without a mark only
     * gets one (after a reboot, or one adopted from another device).
     */
    private fun accrueUnlockUse(world: World, clock: Clock): World {
        if (world.sessions.values.none { it.selfPaced }) return world
        val nowMono = clock.nowMonotonicMs()
        val fgPkg = world.foregroundPkg
        val fgSite = world.foregroundSite
        val fgShielded by lazy { shieldPlan(world, clock).covers(fgPkg, fgSite) }
        val sessions = world.sessions.mapValues { (blockId, s) ->
            val block = world.block(blockId)
            if (!s.selfPaced || block?.state != BlockState.UNLOCKED) return@mapValues s
            val delta = s.markMonotonic?.let { (nowMono - it).coerceAtLeast(0) } ?: 0
            val inUse = delta > 0 && world.appliesHere(block) && covers(block, fgPkg, fgSite) && !fgShielded // I42
            val used = if (inUse) s.usedMs + (world.deviceId to (s.usedMs[world.deviceId] ?: 0) + delta) else s.usedMs
            s.copy(usedMs = used, markMonotonic = nowMono)
        }
        return world.copy(sessions = sessions)
    }

    /**
     * I32: [delta] more use in a row. Resting [Block.sessionBreakMs] since the last use starts a
     * fresh run; reaching [Block.sessionMs], counting other devices' live runs, starts the break.
     */
    private fun advanceRun(world: World, block: Block, run: RunUse?, delta: Long, nowWall: Long): RunUse {
        val r = run ?: RunUse()
        val rested = nowWall - r.lastActiveWall >= block.sessionBreakMs
        val ms = (if (rested) 0 else r.ms) + delta
        val reached = ms + rowElsewhereMs(world, block, nowWall) >= block.sessionMs
        val breakUntil = if (reached && r.breakUntilWall <= nowWall) nowWall + block.sessionBreakMs else r.breakUntilWall
        return RunUse(ms = ms, lastActiveWall = nowWall, breakUntilWall = breakUntil)
    }

    private fun checkTimerTriggers(world: World, clock: Clock, effects: MutableList<Effect>): World {
        var w = world
        w.blocks
            .filter {
                it.mode == BlockMode.HABIT && it.state == BlockState.ARMED &&
                    (it.trigger.type == TriggerType.TIMER || it.trigger.type == TriggerType.COMBINED)
            }
            .forEach { b -> if (triggerMet(w, b)) w = armedToEarned(w, b, clock, effects) }
        return w
    }

    private fun onToggleItem(
        world: World,
        event: BlockEvent.ToggleChecklistItem,
        clock: Clock,
        effects: MutableList<Effect>,
    ): World {
        val owner = world.checklist.entries.firstOrNull { (_, items) ->
            items.any { it.id == event.itemId }
        }?.key ?: return world
        val block = world.block(owner) ?: return world
        if (block.mode != BlockMode.HABIT || block.state != BlockState.ARMED) return world // I12, §2.2
        if (world.items(owner).any { it.id == event.itemId && it.derived }) return world // I23, I36

        val updated = world.items(owner).map { item ->
            if (item.id != event.itemId) item
            else item.copy(
                done = event.done,
                doneAtMonotonic = if (event.done) clock.nowMonotonicMs() else null,
            )
        }
        var w = world.copy(checklist = world.checklist + (owner to updated))
        if (hasChecklist(block) && triggerMet(w, block)) {
            w = armedToEarned(w, block, clock, effects)
        }
        return w
    }

    private fun hasChecklist(block: Block): Boolean =
        block.trigger.type == TriggerType.CHECKLIST || block.trigger.type == TriggerType.COMBINED

    private fun onRedeem(
        world: World,
        blockId: String,
        clock: Clock,
        effects: MutableList<Effect>,
    ): World {
        val block = world.block(blockId) ?: return world
        redeemRejection(world, blockId, clock)?.let { reason -> // I9
            effects += Effect.RedeemRejected(blockId, reason)
            return world
        }
        val credit = world.credits.getValue(blockId)
        val epochDay = clock.epochDay()
        val sameDay = world.dailyUsageEpochDay == epochDay
        val session = UnlockSession(
            blockId = blockId,
            startedAtMonotonic = clock.nowMonotonicMs(),
            startedAtWall = clock.nowWallMs(),
            durationMs = credit.amountMs,
            selfPaced = block.selfPaced, // I41
            markMonotonic = if (block.selfPaced) clock.nowMonotonicMs() else null,
        )
        // I24: one unlock's worth of each habit item's habit pays for this unlock. Stamped here
        // rather than on earn, so a credit that expires unused (I1) spends nothing.
        val items = world.items(blockId)
        val spent = items.map { spendHabit(world, it, clock) }
        val checklist = if (spent == items) world.checklist else world.checklist + (blockId to spent)
        return world.copy(
            blocks = world.blocks.replace(blockId) { it.copy(state = BlockState.UNLOCKED) },
            credits = world.credits - blockId,
            sessions = world.sessions + (blockId to session),
            checklist = checklist,
            dailyUnlockUsedMs = if (sameDay) world.dailyUnlockUsedMs else 0,
            unlockLog = if (sameDay) world.unlockLog else emptyMap(),
            dailyUsageEpochDay = epochDay,
        )
    }

    /** I34: the wait starts, unless one for the same blocks is already running. */
    private fun onBeginOverride(world: World, event: BlockEvent.BeginOverride, clock: Clock): World {
        val blocks = event.blockIds.mapNotNull(world::block)
        if (blocks.isEmpty() || blocks.size != event.blockIds.size) return world
        if (overrideRejection(world, blocks, clock) != null || blocks.none { isBlocking(world, it, clock) }) return world
        if (world.pendingOverride?.blockIds == event.blockIds) return world
        return world.copy(pendingOverride = PendingOverride(event.blockIds, clock.nowMonotonicMs(), clock.nowWallMs()))
    }

    /** I34: after the wait, with the phrase, and still allowed: the minutes, for every block at once. */
    private fun onClaimOverride(world: World, event: BlockEvent.ClaimOverride, clock: Clock, effects: MutableList<Effect>): World {
        fun reject(reason: OverrideRejection, keepWaiting: Boolean): World {
            effects += Effect.OverrideRejected(reason)
            return if (keepWaiting) world else world.copy(pendingOverride = null)
        }
        val pending = world.pendingOverride ?: return reject(OverrideRejection.NOT_STARTED, keepWaiting = true)
        val settings = world.settings
        val waited = clock.nowMonotonicMs() - pending.sinceMonotonic
        if (waited < settings.overrideWaitMs) return reject(OverrideRejection.TOO_EARLY, keepWaiting = true)
        if (waited > settings.overrideWaitMs + Defaults.OVERRIDE_CLAIM_WINDOW_MS) return reject(OverrideRejection.TOO_LATE, keepWaiting = false)
        if (Sites.normalizePhrase(event.phrase) != Sites.normalizePhrase(settings.overridePhrase)) {
            return reject(OverrideRejection.WRONG_PHRASE, keepWaiting = true)
        }
        val blocks = pending.blockIds.mapNotNull(world::block)
        overrideRejection(world, blocks, clock)?.let { return reject(it, keepWaiting = false) }
        val grants = blocks.associate { b ->
            b.id to OverrideGrant(b.id, clock.nowMonotonicMs(), clock.nowWallMs(), settings.overrideGrantMs, world.deviceId)
        }
        val today = clock.epochDay()
        val log = (if (world.overrideEpochDay == today) world.overrideLog else emptyMap()) + grants.values.associate { it.key to it.durationMs }
        effects += Effect.OverrideGranted(grants.keys, settings.overrideGrantMs)
        return world.copy(overrides = world.overrides + grants, overrideLog = log, overrideEpochDay = today, pendingOverride = null)
    }

    /** I35: more (or less) of a habit today; a check ticks or unticks. */
    private fun onLogHabit(world: World, event: BlockEvent.LogHabit, clock: Clock): World {
        val habit = world.habit(event.habitId) ?: return world
        val day = world.habitDay(clock)
        val old = world.entry(habit.id, day)
        val value = when {
            habit.numerical -> ((old?.value ?: 0).coerceAtLeast(0) + event.amount).coerceAtLeast(0)
            event.amount > 0 -> HabitEntry.YES_MANUAL
            event.amount < 0 -> HabitEntry.NO
            else -> return world
        }
        return world.writeEntry(habit.id, day, value, old?.notes ?: "", clock)
    }

    /** I35: a day's entry set outright, today or before. */
    private fun onSetHabitEntry(world: World, event: BlockEvent.SetHabitEntry, clock: Clock): World {
        val habit = world.habit(event.habitId) ?: return world
        if (event.epochDay > world.habitDay(clock)) return world
        val valid = if (habit.numerical) event.value >= 0 else event.value in setOf(HabitEntry.UNKNOWN, HabitEntry.NO, HabitEntry.YES_MANUAL, HabitEntry.SKIP)
        if (!valid) return world
        return world.writeEntry(habit.id, event.epochDay, event.value, event.notes, clock)
    }

    /** I37: starts the focus timer, or resumes it; another habit's run is logged and ended first. */
    private fun onStartFocus(world: World, habitId: String, clock: Clock): World {
        world.habit(habitId)?.takeIf { it.kind == HabitKind.TIMER } ?: return world
        val run = world.focus
        if (run?.habitId == habitId && run.running) return world
        val w = if (run != null && run.habitId != habitId) fold(world, clock) else world
        return w.copy(focus = FocusRun(habitId, world.habitDay(clock), clock.nowMonotonicMs(), clock.nowWallMs()))
    }

    /** I37: logs the focus timer's time and holds it; a break makes it go on by itself later. */
    private fun onPauseFocus(world: World, breakMs: Long, clock: Clock): World {
        world.focus ?: return world
        val w = fold(world, clock)
        val run = w.focus ?: return w
        return w.copy(focus = run.copy(running = false, resumeAtWall = if (breakMs > 0) clock.nowWallMs() + breakMs else null))
    }

    /**
     * I37: moves a running focus timer's time into the entry of the run's own day, in whole
     * thousandths of a minute, and carries on (on today) with what is left over. A paused run is
     * left as it is.
     */
    private fun fold(world: World, clock: Clock): World {
        val run = world.focus?.takeIf { it.running } ?: return world
        val ms = focusLiveMs(world, clock)
        val amount = ms / 60
        val rest = ms - amount * 60
        var w = world
        if (amount > 0 && world.habit(run.habitId) != null) {
            val old = world.entry(run.habitId, run.epochDay)
            w = world.writeEntry(run.habitId, run.epochDay, (old?.value ?: 0).coerceAtLeast(0) + amount, old?.notes ?: "", clock)
        }
        return w.copy(
            focus = run.copy(
                epochDay = world.habitDay(clock),
                startedAtMonotonic = clock.nowMonotonicMs() - rest,
                startedAtWall = clock.nowWallMs() - rest,
            ),
        )
    }

    /**
     * I36: every ARMED HABIT block's habit items are done while their habit has an unlock's worth
     * on the item's day that the block hasn't spent; a block whose trigger that meets earns.
     */
    private fun refreshHabitItems(world: World, clock: Clock, effects: MutableList<Effect>): World {
        if (world.checklist.values.none { items -> items.any { it.habitId != null } }) return world
        var w = world
        for (block in world.blocks) {
            if (block.mode != BlockMode.HABIT || (block.state != BlockState.ARMED && block.state != BlockState.EARNED)) continue
            if (w.items(block.id).none { it.habitId != null }) continue
            val undone = updateHabitItems(w, block.id, clock).let { (next, undone) -> w = next; undone }
            when {
                block.state == BlockState.ARMED ->
                    if (hasChecklist(block) && triggerMet(w, block)) w = armedToEarned(w, block, clock, effects)
                // I36: a habit done here was taken back before the unlock was used, so is the earn.
                undone && !triggerMet(w, block) -> w = updateHabitItems(returnToArmed(w, block, clock), block.id, clock).first
            }
        }
        return w
    }

    /** I36: a block's habit items worked out from the entries; true if one done here is no longer. */
    private fun updateHabitItems(world: World, blockId: String, clock: Clock): Pair<World, Boolean> {
        val items = world.items(blockId)
        var undone = false
        val updated = items.map { item ->
            if (item.habitId == null) return@map item
            val done = itemReady(world, item, clock)
            if (item.done && !done) undone = true
            if (done == item.done) item else item.copy(done = done, doneAtMonotonic = if (done) clock.nowMonotonicMs() else null)
        }
        return (if (updated == items) world else world.copy(checklist = world.checklist + (blockId to updated))) to undone
    }

    private fun onStartPause(world: World, event: BlockEvent.StartPause, clock: Clock): World {
        if (!canPause(world, clock) || event.durationMs <= 0) return world // I25: one a day
        val pause = Pause(clock.nowMonotonicMs(), clock.nowWallMs(), event.durationMs)
        return world.copy(pause = pause, lastPauseEpochDay = clock.epochDay())
    }

    private fun onUserReset(world: World, blockId: String, clock: Clock): World {
        val block = world.block(blockId) ?: return world
        if (block.state != BlockState.EARNED) return world
        return returnToArmed(world, block, clock)
    }

    private fun onTimerControl(world: World, event: BlockEvent.TimerControl, clock: Clock): World {
        if (world.block(event.blockId)?.let { it.mode != BlockMode.HABIT } == true) return world
        val existing = world.progress[event.blockId]
            ?: freshProgress(event.blockId, clock)
        return world.copy(
            progress = world.progress + (event.blockId to existing.copy(
                timerRunning = event.running,
                lastUpdated = clock.nowMonotonicMs(),
            )),
        )
    }

    // ---- transitions ------------------------------------------------------------------

    private fun armedToEarned(
        world: World,
        block: Block,
        clock: Clock,
        effects: MutableList<Effect>,
    ): World {
        val nowMono = clock.nowMonotonicMs()
        val nowWall = clock.nowWallMs()
        val credit = EarnedCredit(
            blockId = block.id,
            earnedAtMonotonic = nowMono,
            earnedAtWall = nowWall,
            expiresAtWall = nowWall + block.reward.creditTtlMs, // I1 (wall clock, survives reboot)
            amountMs = block.reward.unlockDurationMs,
        )
        effects += Effect.HabitCompleted(block.id)
        return world.copy(
            blocks = world.blocks.replace(block.id) { it.copy(state = BlockState.EARNED) },
            credits = world.credits + (block.id to credit),
        )
    }

    /** Every path back to ARMED resets the habit window and the checklist (I10). */
    private fun returnToArmed(world: World, block: Block, clock: Clock): World =
        world.copy(
            blocks = world.blocks.replace(block.id) { it.copy(state = BlockState.ARMED) },
            credits = world.credits - block.id,
            sessions = world.sessions - block.id,
            progress = world.progress + (block.id to freshProgress(block.id, clock)),
            checklist = world.checklist + (block.id to resetItems(world.items(block.id))),
        )

    // ---- housekeeping sweep --------------------------------------------------------

    private fun sweep(world: World, clock: Clock, effects: MutableList<Effect>): World {
        var w = world
        val today = clock.epochDay()
        if (today != w.dailyUsageEpochDay) {
            w = w.copy(dailyUnlockUsedMs = 0, unlockLog = emptyMap(), dailyUsageEpochDay = today)
        }

        // I21: drop lockdowns that have run out (or whose block is gone / no longer LOCKDOWN).
        val liveLockdowns = w.lockdowns.filter { (id, _) ->
            w.block(id)?.mode == BlockMode.LOCKDOWN && lockdownRemainingMs(w, id, clock) > 0
        }
        if (liveLockdowns.size != w.lockdowns.size) w = w.copy(lockdowns = liveLockdowns)
        if (w.pause != null && pauseRemainingMs(w, clock) == 0L) w = w.copy(pause = null) // I25

        // I34: bought minutes that ran out (or whose block is gone), yesterday's log, a wait left too long.
        val liveOverrides = w.overrides.filter { (id, g) ->
            w.block(id) != null && g.durationMs - (clock.nowMonotonicMs() - g.startedAtMonotonic) > 0
        }
        if (liveOverrides.size != w.overrides.size) w = w.copy(overrides = liveOverrides)
        if (w.overrideEpochDay != today && w.overrideLog.isNotEmpty()) w = w.copy(overrideLog = emptyMap(), overrideEpochDay = today)
        w.pendingOverride?.let { p ->
            val waited = clock.nowMonotonicMs() - p.sinceMonotonic
            if (waited > w.settings.overrideWaitMs + Defaults.OVERRIDE_CLAIM_WINDOW_MS || p.blockIds.any { w.block(it) == null }) {
                w = w.copy(pendingOverride = null)
            }
        }
        val liveRuns = w.runs.filterKeys { w.block(it)?.hasRowRule == true }
        if (liveRuns.size != w.runs.size) w = w.copy(runs = liveRuns)

        // I23: linked items from before the habit list moved here are habit items now.
        if (w.checklist.values.any { items -> items.any { it.externalHabitId != null } }) {
            w = w.copy(checklist = w.checklist.mapValues { (_, items) -> items.map(::migrateLinked) })
        }
        // I37: a run whose habit is gone (or no longer a timer) goes; a break that is over goes on;
        // a run that crossed into a new habit day logs into its own day and carries on into today's.
        val habitDay = w.habitDay(clock)
        w.focus?.let { run ->
            when {
                w.habit(run.habitId)?.kind != HabitKind.TIMER -> w = w.copy(focus = null)
                !run.running && run.resumeAtWall != null && clock.nowWallMs() >= run.resumeAtWall ->
                    w = w.copy(focus = run.copy(running = true, resumeAtWall = null, epochDay = habitDay, startedAtMonotonic = clock.nowMonotonicMs(), startedAtWall = clock.nowWallMs()))
                run.running && run.epochDay != habitDay -> w = fold(w, clock)
            }
        }

        for (block in w.blocks) {
            w = when (block.state) {
                BlockState.ARMED -> {
                    if (dayRolled(w.progress[block.id], clock)) returnToArmed(w, block, clock) else w
                }

                BlockState.EARNED -> {
                    val credit = w.credits[block.id]
                    val expiredByWall = credit != null && clock.nowWallMs() >= credit.expiresAtWall
                    val clockWoundBack = credit != null && run {
                        val monoElapsed = clock.nowMonotonicMs() - credit.earnedAtMonotonic
                        val wallElapsed = clock.nowWallMs() - credit.earnedAtWall
                        wallElapsed < monoElapsed - w.settings.skewToleranceMs
                    }
                    if (expiredByWall || clockWoundBack || dayRolled(w.progress[block.id], clock)) {
                        returnToArmed(w, block, clock)
                    } else {
                        w
                    }
                }

                BlockState.UNLOCKED -> {
                    val s = w.sessions[block.id]
                    if (s == null) {
                        returnToArmed(w, block, clock)
                    } else if (s.selfPaced) {
                        // I41: used up, or the day it was for is over (no tamper check: wall time doesn't count here).
                        val used = s.totalUsedMs
                        val dayOver = s.startedAtWall < clock.startOfLocalDayMs()
                        if (used >= s.durationMs || dayOver) {
                            val consumed = used.coerceIn(0, s.durationMs)
                            effects += Effect.UnlockConsumed(block.id, consumed, s.startedAtWall)
                            val reset = returnToArmed(w, block, clock)
                            // I28; yesterday's unlock doesn't count against today's cap.
                            if (dayOver) reset else reset.copy(unlockLog = reset.unlockLog + (s.key to consumed), dailyUsageEpochDay = today)
                        } else {
                            val remaining = s.durationMs - used
                            if (remaining in 1..w.settings.unlockWarningMs) effects += Effect.UnlockEndingSoon(block.id, remaining)
                            w
                        }
                    } else {
                        val monoElapsed = clock.nowMonotonicMs() - s.startedAtMonotonic
                        val wallElapsed = clock.nowWallMs() - s.startedAtWall
                        val tamper = wallElapsed < monoElapsed - w.settings.skewToleranceMs // I2/I3
                        val timeUp = monoElapsed >= s.durationMs
                        if (tamper || timeUp) {
                            val consumed = monoElapsed.coerceIn(0, s.durationMs)
                            effects += Effect.UnlockConsumed(block.id, consumed, s.startedAtWall)
                            if (tamper) effects += Effect.TamperDetected(block.id)
                            val reset = returnToArmed(w, block, clock)
                            // I28: keyed by session, so another device ending the same one counts it once.
                            reset.copy(unlockLog = reset.unlockLog + (s.key to consumed), dailyUsageEpochDay = today)
                        } else {
                            val remaining = s.durationMs - monoElapsed
                            if (remaining in 1..w.settings.unlockWarningMs) {
                                effects += Effect.UnlockEndingSoon(block.id, remaining)
                            }
                            w
                        }
                    }
                }
            }
        }
        return w
    }

    // ---- shared state (§2.4) -------------------------------------------------------

    /**
     * I27: takes [shared] (already merged, I26) into this device's world. Entities this device
     * already has keep their monotonic stamps; new ones are anchored from wall time, as
     * [rebuild] does (I8). Block states follow from sessions and credits; leaving EARNED or
     * UNLOCKED this way resets like I10; entering UNLOCKED keeps what the redeeming device spent
     * and spends here only what it didn't (I24).
     */
    private fun adoptShared(world: World, shared: SharedState, clock: Clock): World {
        val nowMono = clock.nowMonotonicMs()
        val nowWall = clock.nowWallMs()
        fun anchor(wall: Long) = nowMono - (nowWall - wall).coerceAtLeast(0)
        val blockIds = shared.blocks.keys

        val sessions = shared.sessions.filterKeys { it in blockIds }.mapValues { (id, s) ->
            val mine = world.sessions[id]?.takeIf { it.key == s.key }
            when {
                mine == null -> s.copy(startedAtMonotonic = anchor(s.startedAtWall), markMonotonic = if (s.selfPaced) clock.nowMonotonicMs() else null)
                mine.copy(startedAtMonotonic = 0, markMonotonic = null) == s -> mine
                // I41: the same unlock with others' use merged in; this device keeps its marks.
                else -> s.copy(startedAtMonotonic = mine.startedAtMonotonic, markMonotonic = mine.markMonotonic)
            }
        }
        // An open block holds no credit: redeeming spent it (§2.1).
        val credits = shared.credits.filterKeys { it in blockIds && it !in sessions }.mapValues { (id, c) ->
            world.credits[id]?.takeIf { it.copy(earnedAtMonotonic = 0) == c } ?: c.copy(earnedAtMonotonic = anchor(c.earnedAtWall))
        }
        val lockdowns = shared.lockdowns.filterKeys { it in blockIds }.mapValues { (id, l) ->
            world.lockdowns[id]?.takeIf { it.copy(startedAtMonotonic = 0) == l } ?: l.copy(startedAtMonotonic = anchor(l.startedAtWall))
        }
        val pause = shared.pause?.let { p ->
            world.pause?.takeIf { it.copy(startedAtMonotonic = 0) == p } ?: p.copy(startedAtMonotonic = anchor(p.startedAtWall))
        }
        val overrides = shared.overrides.filterKeys { it in blockIds }.mapValues { (id, g) ->
            world.overrides[id]?.takeIf { it.copy(startedAtMonotonic = 0) == g } ?: g.copy(startedAtMonotonic = anchor(g.startedAtWall))
        }

        val mine = world.checklist.values.flatten().associateBy { it.id }
        val checklist = shared.items.values
            .filter { it.blockId in blockIds }
            .groupBy { it.blockId }
            .mapValues { (_, items) ->
                items.sortedBy { it.order }.map { item ->
                    val local = mine[item.id]
                    when {
                        // A habit item's tick is worked out here from this device's entries (I36).
                        item.derived -> item.copy(done = local?.done == true, doneAtMonotonic = local?.doneAtMonotonic)
                        local != null && local.done == item.done -> item.copy(doneAtMonotonic = local.doneAtMonotonic)
                        else -> item.copy(doneAtMonotonic = if (item.done) nowMono else null)
                    }
                }
            }
            .toMutableMap()

        val progress = world.progress.filterKeys { it in blockIds }.toMutableMap()
        val blocks = shared.blocks.values.map { b ->
            val state = when {
                b.mode != BlockMode.HABIT -> BlockState.ARMED
                b.id in sessions -> BlockState.UNLOCKED
                b.id in credits -> BlockState.EARNED
                else -> BlockState.ARMED
            }
            val before = world.block(b.id)?.state ?: BlockState.ARMED
            val items = checklist[b.id].orEmpty()
            if (before != BlockState.ARMED && state == BlockState.ARMED) { // I10, as returnToArmed
                progress[b.id] = freshProgress(b.id, clock)
                checklist[b.id] = resetItems(items)
            } else if (before != BlockState.UNLOCKED && state == BlockState.UNLOCKED) { // I24, I27
                // What the redeeming device spent stands; an item it didn't spend (its entries may
                // not have had the tick yet) pays here, so one tick never pays for two unlocks.
                checklist[b.id] = items.map { item ->
                    val was = mine[item.id]
                    val untouched = was != null && was.spentEpochDay == item.spentEpochDay && was.spentAmount == item.spentAmount
                    if (item.habitId != null && untouched) spendHabit(world, item, clock) else item
                }
            }
            b.copy(state = state)
        }

        val newerDay = shared.unlockEpochDay > world.dailyUsageEpochDay
        return world.copy(
            blocks = blocks,
            progress = progress,
            credits = credits,
            sessions = sessions,
            checklist = checklist.filterValues { it.isNotEmpty() },
            lockdowns = lockdowns,
            pause = pause,
            overrides = overrides,
            overrideEpochDay = maxOf(shared.overrideEpochDay, world.overrideEpochDay),
            overrideLog = if (shared.overrideEpochDay >= world.overrideEpochDay) shared.overrideLog else world.overrideLog,
            emergencyLog = world.emergencyLog + shared.emergencyLog,
            lastPauseEpochDay = shared.lastPauseEpochDay,
            settings = shared.settings,
            dailyUnlockUsedMs = if (newerDay) 0 else world.dailyUnlockUsedMs,
            dailyUsageEpochDay = maxOf(shared.unlockEpochDay, world.dailyUsageEpochDay),
            unlockLog = if (shared.unlockEpochDay >= world.dailyUsageEpochDay) shared.unlockLog else world.unlockLog,
            otherAllowance = shared.allowance
                .mapValues { (_, byDevice) -> byDevice - world.deviceId }
                .filterValues { it.isNotEmpty() },
        )
    }

    // ---- rebuild (I8) ---------------------------------------------------------------

    private fun rebuild(world: World, clock: Clock, effects: MutableList<Effect>): World {
        // Monotonic time resets across reboot, so rebase live monotonic references from the
        // wall-clock delta before letting the normal sweep logic take over.
        val nowMono = clock.nowMonotonicMs()
        val nowWall = clock.nowWallMs()
        val credits = world.credits.mapValues { (_, c) ->
            val wallElapsed = (nowWall - c.earnedAtWall).coerceAtLeast(0)
            c.copy(earnedAtMonotonic = nowMono - wallElapsed)
        }
        val sessions = world.sessions.mapValues { (_, s) ->
            val wallElapsed = (nowWall - s.startedAtWall).coerceAtLeast(0)
            s.copy(startedAtMonotonic = nowMono - wallElapsed, markMonotonic = null) // I41: a fresh mark
        }
        val lockdowns = world.lockdowns.mapValues { (_, l) ->
            val wallElapsed = (nowWall - l.startedAtWall).coerceAtLeast(0)
            l.copy(startedAtMonotonic = nowMono - wallElapsed)
        }
        val pause = world.pause?.let { p ->
            val wallElapsed = (nowWall - p.startedAtWall).coerceAtLeast(0)
            p.copy(startedAtMonotonic = nowMono - wallElapsed)
        }
        val overrides = world.overrides.mapValues { (_, g) ->
            g.copy(startedAtMonotonic = nowMono - (nowWall - g.startedAtWall).coerceAtLeast(0))
        }
        val pending = world.pendingOverride?.let { p ->
            p.copy(sinceMonotonic = nowMono - (nowWall - p.sinceWall).coerceAtLeast(0))
        }
        // Timer accrual markers are monotonic and meaningless after a reboot; re-anchor them.
        val progress = world.progress.mapValues { (_, p) -> p.copy(lastUpdated = nowMono) }
        val focus = world.focus?.let { f -> f.copy(startedAtMonotonic = nowMono - (nowWall - f.startedAtWall).coerceAtLeast(0)) } // I37
        return sweep(
            world.copy(
                credits = credits, sessions = sessions, lockdowns = lockdowns, pause = pause, progress = progress,
                overrides = overrides, pendingOverride = pending, focus = focus,
            ),
            clock,
            effects,
        )
    }

    // ---- helpers -----------------------------------------------------------------------

    private fun freshProgress(blockId: String, clock: Clock) = HabitProgress(
        blockId = blockId,
        accumulatedMs = 0,
        windowStart = clock.startOfLocalDayMs(),
        lastUpdated = clock.nowMonotonicMs(),
        timerRunning = false,
    )

    /** I10. A habit item keeps what it spent (I24). */
    private fun resetItems(items: List<ChecklistItem>): List<ChecklistItem> =
        items.map { it.copy(done = false, doneAtMonotonic = null) }

    /**
     * I24: a habit item with an unlock's worth available pays one unlock's worth of its habit;
     * the rest counts toward the next. Judged from the entries, not `done`: a block that came
     * here EARNED from another device never had its items worked out on this one.
     */
    private fun spendHabit(world: World, item: ChecklistItem, clock: Clock): ChecklistItem {
        val unit = item.habitId?.let(world::habit)?.unlockUnit ?: return item
        if (habitAvailable(world, item, clock) < unit) return item
        val day = itemDay(world, item, clock)
        return item.copy(spentEpochDay = day, spentAmount = item.spentOn(day) + unit)
    }

    /** I23: a linked item (a Loop habit, before the habit list moved here) as a habit item on the same habit. */
    private fun migrateLinked(item: ChecklistItem): ChecklistItem {
        val id = item.externalHabitId ?: return item
        return item.copy(
            habitId = item.habitId ?: id,
            externalHabitId = null,
            spentAmount = if (item.spentEpochDay != null && item.spentAmount == 0L) 1 else item.spentAmount,
        )
    }

    private fun dayRolled(progress: HabitProgress?, clock: Clock): Boolean {
        progress ?: return false
        val started = Instant.ofEpochMilli(progress.windowStart).atZone(clock.zone()).toLocalDate()
        return started != clock.localDate()
    }

    /** I20, I30: any of the schedule's windows is on. */
    fun scheduleActive(block: Block, clock: Clock): Boolean {
        val s = block.schedule
        val zdt = Instant.ofEpochMilli(clock.nowWallMs()).atZone(clock.zone())
        return windowActive(s.activeDays, s.activeWindow, zdt) ||
            s.moreWindows.any { windowActive(it.days, it.window, zdt) }
    }

    /** I20. A window that wraps midnight belongs to the day it starts on; no window means whole days. */
    private fun windowActive(days: Set<Int>, w: TimeWindow?, zdt: java.time.ZonedDateTime): Boolean {
        val today = zdt.dayOfWeek
        w ?: return today.value in days
        val minute = zdt.hour * 60 + zdt.minute
        return when {
            w.startMinuteOfDay <= w.endMinuteOfDay ->
                today.value in days && minute in w.startMinuteOfDay until w.endMinuteOfDay
            minute >= w.startMinuteOfDay -> today.value in days
            minute < w.endMinuteOfDay -> today.minus(1).value in days // last night's window
            else -> false
        }
    }
}

private inline fun List<Block>.replace(id: String, transform: (Block) -> Block): List<Block> =
    map { if (it.id == id) transform(it) else it }

/**
 * I35: a habit's entry on [epochDay] written here: stamped now and dirty until the habit server
 * takes it (I38), remembering the value it started from. Writing what is there already does nothing.
 */
internal fun World.writeEntry(habitId: String, epochDay: Long, value: Long, notes: String, clock: Clock): World {
    val key = HabitEntry.key(habitId, epochDay)
    val old = habitEntries[key]
    if (old != null && old.value == value && old.notes == notes) return this
    if (old == null && value == HabitEntry.UNKNOWN && notes.isEmpty()) return this
    val entry = HabitEntry(
        value = value,
        notes = notes,
        mtime = clock.nowWallMs(),
        seq = old?.seq ?: 0,
        dirty = true,
        baseValue = if (old?.dirty == true) old.baseValue else old?.value,
    )
    return copy(habitEntries = habitEntries + (key to entry))
}
