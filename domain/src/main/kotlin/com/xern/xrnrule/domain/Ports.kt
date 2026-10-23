package com.xern.xrnrule.domain

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Ports the domain defines and `:app` implements. See `docs/domain-contract.md` §4.
 * The engine itself never calls [ShieldPort]; it returns an [Outcome] and the app layer
 * persists first, then applies the shield ([applyOutcome]).
 */

interface Clock {
    /** `SystemClock.elapsedRealtime()` on Android. Not affected by the user setting the clock. */
    fun nowMonotonicMs(): Long

    /** `System.currentTimeMillis()` on Android. User-settable. */
    fun nowWallMs(): Long

    fun zone(): ZoneId
}

fun Clock.localDate(): LocalDate = Instant.ofEpochMilli(nowWallMs()).atZone(zone()).toLocalDate()

fun Clock.epochDay(): Long = localDate().toEpochDay()

fun Clock.startOfLocalDayMs(): Long =
    localDate().atStartOfDay(zone()).toInstant().toEpochMilli()

interface PersistPort {
    fun loadWorld(): World

    /** Must be atomic and must return before the caller touches the shield (I7). */
    fun save(world: World)
}

interface ShieldPort {
    fun apply(plan: ShieldPlan)
}

interface Telemetry {
    fun onBlockedImpression(pkg: String, atWallMs: Long)
    fun onHabitCompleted(blockId: String, atWallMs: Long)
    /** [startedAtWall] names the session (I28): devices sharing it each log it, under one name. */
    fun onUnlockConsumed(blockId: String, ms: Long, atWallMs: Long, startedAtWall: Long)
}

/**
 * The full desired shield state. Applying it is idempotent. [blockedSites] holds target domains;
 * a host is covered when it [Sites.matches] any of them (I18) and none of that domain's
 * [siteExceptions] (I31).
 */
@Serializable
data class ShieldPlan(
    val blockedPackages: Set<String>,
    val blockedSites: Set<String> = emptySet(),
    /** Per blocked domain, the hosts under it that every block blocking it leaves open (I31). */
    val siteExceptions: Map<String, Set<String>> = emptyMap(),
) {
    fun covers(pkg: String?, site: String?): Boolean =
        (pkg != null && pkg in blockedPackages) || (site != null && siteBlocked(site))

    fun siteBlocked(host: String): Boolean = blockedSites.any { domain ->
        Sites.matches(host, domain) && siteExceptions[domain].orEmpty().none { Sites.matches(host, it) }
    }
}

enum class RejectReason { NOT_EARNED, CAP_REACHED }

/** Why minutes can't be bought past a block (I34). */
enum class OverrideRejection {
    /** Overrides are turned off (`overridesPerDay == 0`). */
    OFF,
    /** Something blocking the target can't be overridden: a habit, a lockdown, a hardcore block, a forced break. */
    NOT_OVERRIDABLE,
    /** Today's overrides for one of the blocks are used up. */
    NONE_LEFT,
    /** [BlockEvent.ClaimOverride] with no override started. */
    NOT_STARTED,
    /** Claimed before the wait was over. */
    TOO_EARLY,
    /** Claimed too long after the wait: start again. */
    TOO_LATE,
    WRONG_PHRASE,
}

/** What runs out first on an open target, for the browser's corner clock. */
@Serializable
enum class ClockKind {
    /** Today's limit; runs down while you use it. */
    LIMIT,
    /** The "in a row" rule (I32); runs down while you use it. */
    ROW,
    /** A habit unlock; runs down by the clock. */
    UNLOCK,
    /** Bought minutes (I34); run down by the clock. */
    OVERRIDE,
    /** The daily break (I25); runs down by the clock. */
    BREAK,
}

/** [blockId]: the block it runs out on (null for the daily break, which is everyone's). */
data class TimeLeft(val ms: Long, val kind: ClockKind, val blockId: String? = null)

sealed interface Effect {
    data class BlockedImpression(val pkg: String) : Effect
    data class HabitCompleted(val blockId: String) : Effect
    data class UnlockConsumed(val blockId: String, val ms: Long, val startedAtWall: Long = 0) : Effect
    data class UnlockEndingSoon(val blockId: String, val remainingMs: Long) : Effect
    data class TamperDetected(val blockId: String) : Effect
    data class RedeemRejected(val blockId: String, val reason: RejectReason) : Effect
    data class OverrideGranted(val blockIds: Set<String>, val ms: Long) : Effect
    data class OverrideRejected(val reason: OverrideRejection) : Effect
}

/**
 * Result of [BlockEngine.reduce]. [shieldPlan] is non-null only when the desired blocked
 * set changed (or on cold start) — see contract §4.
 */
data class Outcome(
    val world: World,
    val shieldPlan: ShieldPlan?,
    val effects: List<Effect>,
)

/**
 * Canonical order for applying an [Outcome]: persist, then shield, then side effects.
 * This ordering is invariant I7 and is exercised by `i07_persistHappensBeforeShield`.
 */
fun applyOutcome(
    outcome: Outcome,
    persist: PersistPort,
    shield: ShieldPort,
    telemetry: Telemetry,
    nowWallMs: Long,
) {
    persist.save(outcome.world)
    outcome.shieldPlan?.let(shield::apply)
    outcome.effects.forEach { effect ->
        when (effect) {
            is Effect.BlockedImpression -> telemetry.onBlockedImpression(effect.pkg, nowWallMs)
            is Effect.HabitCompleted -> telemetry.onHabitCompleted(effect.blockId, nowWallMs)
            is Effect.UnlockConsumed -> telemetry.onUnlockConsumed(effect.blockId, effect.ms, nowWallMs, effect.startedAtWall)
            is Effect.UnlockEndingSoon,
            is Effect.TamperDetected,
            is Effect.RedeemRejected,
            is Effect.OverrideGranted,
            is Effect.OverrideRejected -> Unit // routed to notifications / UI by the caller
        }
    }
}
