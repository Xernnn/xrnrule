package com.xern.xrnrule.domain

import java.time.Instant

/**
 * The state machine from `docs/domain-contract.md` §2. Pure and deterministic: [reduce] takes
 * the current [World], one [BlockEvent] and a [Clock], and returns an [Outcome]. It performs
 * no I/O and never mutates its inputs.
 *
 * Every invariant I1..I34 in the contract has a matching test in
 * `domain/src/test/.../InvariantTests.kt` (`i01_*` .. `i34_*`).
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
            is BlockEvent.ExternalHabitsSynced -> onExternalHabitsSynced(world, event, clock, effects)
            is BlockEvent.StartPause -> onStartPause(world, event, clock)
            is BlockEvent.EndPause -> world.copy(pause = null)
            is BlockEvent.SharedStateMerged ->
                adoptShared(world, SharedState.merge(event.base, SharedState.of(world), event.remote), clock) // I26
            is BlockEvent.BeginOverride -> onBeginOverride(world, event, clock)
            is BlockEvent.CancelOverride -> world.copy(pendingOverride = null)
            is BlockEvent.ClaimOverride -> onClaimOverride(world, event, clock, effects)
        }

        // Housekeeping sweep after every event: day rollover, credit expiry (I1), tamper (I3),
        // unlock time-up + 30s warning (G3), lockdown end (I21), break end (I25). Keeping this out of the
        // per-event handlers means a plain Tick and a Redeem both re-evaluate expiry the same way.
        w = sweep(w, clock, effects)

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
        val covering = if (pauseRemainingMs(world, clock) > 0) all.filterNot(::pausable) else all
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
        if (!block.enabled) return false
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
        val covering = world.blocks.filter { it.enabled && covers(it, pkg, site) }
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
                    left += TimeLeft((s.durationMs - (clock.nowMonotonicMs() - s.startedAtMonotonic)).coerceAtLeast(0), ClockKind.UNLOCK, b.id)
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

    /** Why [BlockEvent.Redeem] would be refused right now, or null if it would go through (I9). */
    fun redeemRejection(world: World, blockId: String, clock: Clock): RejectReason? {
        val block = world.block(blockId)
        val credit = world.credits[blockId]
        if (block?.state != BlockState.EARNED || credit == null) return RejectReason.NOT_EARNED
        if (unlockUsedTodayMs(world, clock) + credit.amountMs > world.settings.dailyCapMs) return RejectReason.CAP_REACHED
        return null
    }

    /** I18: in a target app, or on a target site (or a subdomain of one) the block doesn't except (I31). */
    fun covers(block: Block, pkg: String?, site: String?): Boolean =
        (pkg != null && pkg in block.targetApps) || // I29: a laptop app is a target like a package
            (site != null && block.coversSite(site))

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
    private fun accrueTimers(world: World, clock: Clock): World {
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
                BlockMode.ALLOWANCE -> !fgShielded && covers(block, fgPkg, fgSite)
                BlockMode.HABIT -> {
                    val hasTimer = block.trigger.type == TriggerType.TIMER || block.trigger.type == TriggerType.COMBINED
                    val counted = if (block.trigger.habitPackages.isNotEmpty()) {
                        fgPkg != null && fgPkg in block.trigger.habitPackages // I16
                    } else {
                        covers(block, fgPkg, fgSite)
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
        if (world.items(owner).any { it.id == event.itemId && it.externalHabitId != null }) return world // I23

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

    /**
     * I23: linked items mirror the external tracker — ticked iff that habit is done on the item's
     * [LinkedDay] (by the tracker's own day) and that day's tick hasn't already paid for an
     * unlock (I24). Only ARMED HABIT blocks listen, as with a manual toggle (I12). A snapshot for
     * any other day raced midnight and is dropped.
     */
    private fun onExternalHabitsSynced(
        world: World,
        event: BlockEvent.ExternalHabitsSynced,
        clock: Clock,
        effects: MutableList<Effect>,
    ): World {
        val today = clock.epochDay()
        if (event.epochDay != today) return world
        var w = world
        for (block in world.blocks) {
            if (block.mode != BlockMode.HABIT || block.state != BlockState.ARMED) continue
            val items = w.items(block.id)
            if (items.none { it.externalHabitId != null }) continue
            val updated = items.map { item ->
                val habitId = item.externalHabitId ?: return@map item
                val (ticked, day) = when (item.linkedDay) {
                    LinkedDay.TODAY -> (if (item.countsGoal) event.goalMetHabitIds else event.doneHabitIds) to event.trackerEpochDay
                    LinkedDay.YESTERDAY -> (if (item.countsGoal) event.goalMetYesterdayHabitIds else event.doneYesterdayHabitIds) to event.trackerEpochDay - 1
                }
                val tickDay = day.takeIf { habitId in ticked }
                val done = tickDay != null && item.spentEpochDay != tickDay
                when {
                    done == item.done && tickDay == item.tickEpochDay -> item
                    done -> item.copy(done = true, doneAtMonotonic = item.doneAtMonotonic ?: clock.nowMonotonicMs(), tickEpochDay = tickDay)
                    else -> item.copy(done = false, doneAtMonotonic = null, tickEpochDay = tickDay)
                }
            }
            w = w.copy(checklist = w.checklist + (block.id to updated))
            if (hasChecklist(block) && triggerMet(w, block)) {
                w = armedToEarned(w, block, clock, effects)
            }
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
        world.block(blockId) ?: return world
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
        )
        // I24: each external tick that is done now pays for this unlock. Stamped here rather than
        // on earn, so a credit that expires unused (I1) doesn't use the tick up.
        val items = world.items(blockId)
        val checklist = if (items.none { it.externalHabitId != null && it.done }) {
            world.checklist
        } else {
            world.checklist + (blockId to items.map { spendTick(it, epochDay) })
        }
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
     * UNLOCKED this way resets like I10, and entering UNLOCKED spends local linked ticks (I24).
     */
    private fun adoptShared(world: World, shared: SharedState, clock: Clock): World {
        val nowMono = clock.nowMonotonicMs()
        val nowWall = clock.nowWallMs()
        fun anchor(wall: Long) = nowMono - (nowWall - wall).coerceAtLeast(0)
        val blockIds = shared.blocks.keys

        val sessions = shared.sessions.filterKeys { it in blockIds }.mapValues { (id, s) ->
            world.sessions[id]?.takeIf { it.copy(startedAtMonotonic = 0) == s } ?: s.copy(startedAtMonotonic = anchor(s.startedAtWall))
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
                        // A linked item's tick comes from this device's own tracker read (§2.4).
                        item.externalHabitId != null ->
                            item.copy(done = local?.done == true, doneAtMonotonic = local?.doneAtMonotonic, tickEpochDay = local?.tickEpochDay)
                        local != null && local.done == item.done -> item.copy(doneAtMonotonic = local.doneAtMonotonic)
                        else -> item.copy(doneAtMonotonic = if (item.done) nowMono else null)
                    }
                }
            }
            .toMutableMap()

        val today = clock.epochDay()
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
            } else if (before != BlockState.UNLOCKED && state == BlockState.UNLOCKED) { // I24, as onRedeem
                checklist[b.id] = items.map { spendTick(it, today) }
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
            s.copy(startedAtMonotonic = nowMono - wallElapsed)
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
        return sweep(
            world.copy(
                credits = credits, sessions = sessions, lockdowns = lockdowns, pause = pause, progress = progress,
                overrides = overrides, pendingOverride = pending,
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

    /** I10. A linked item keeps [ChecklistItem.tickEpochDay]: the tracker still has that tick. */
    private fun resetItems(items: List<ChecklistItem>): List<ChecklistItem> =
        items.map { it.copy(done = false, doneAtMonotonic = null) }

    /**
     * I24: a linked item's tick that is done now pays for an unlock; it's marked by its own day
     * ([today] for an item ticked before items knew their tick's day).
     */
    private fun spendTick(item: ChecklistItem, today: Long): ChecklistItem =
        if (item.externalHabitId != null && item.done) item.copy(spentEpochDay = item.tickEpochDay ?: today) else item

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
