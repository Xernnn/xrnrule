package com.xern.xrnrule.app.data

import com.xern.xrnrule.domain.BlockEdits
import com.xern.xrnrule.domain.BlockEngine
import com.xern.xrnrule.domain.BlockEvent
import com.xern.xrnrule.domain.Clock
import com.xern.xrnrule.domain.Effect
import com.xern.xrnrule.domain.PersistPort
import com.xern.xrnrule.domain.ShieldPlan
import com.xern.xrnrule.domain.ShieldPort
import com.xern.xrnrule.domain.Telemetry
import com.xern.xrnrule.domain.World
import com.xern.xrnrule.domain.applyOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes every change to the [World] through one mutex so the engine's ordering guarantees
 * (persist-before-shield, I7) hold no matter who calls — the UI or the service.
 */
class WorldStore(
    val engine: BlockEngine,
    private val persist: PersistPort,
    private val telemetry: Telemetry,
    val clock: Clock,
    private val scope: CoroutineScope,
) {
    /** Set by [com.xern.xrnrule.app.block.BlockerForegroundService] once it is running. */
    @Volatile
    var shield: ShieldPort = NoopShield

    private val _world = MutableStateFlow(persist.loadWorld())
    val world: StateFlow<World> = _world.asStateFlow()

    private val mutex = Mutex()

    private val _effects = MutableSharedFlow<Effect>(extraBufferCapacity = 32)

    /**
     * The engine's side effects, after [applyOutcome] has handled the telemetry ones. The service
     * turns the rest (unlock ending soon, tamper, a refused redeem) into notifications.
     */
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    fun dispatch(event: BlockEvent) {
        scope.launch { dispatchAndGet(event) }
    }

    /**
     * I8 at a cold start, done before [feed] starts what feeds the engine (the foreground, the
     * sites playing out of sight, the tick). The world as loaded holds the monotonic stamps of
     * before the process died: the first of those to reach the engine would otherwise charge all
     * the time it was dead to whatever was in front then, to a limit or to a habit app's timer.
     */
    suspend fun coldStart(feed: () -> Unit) {
        dispatchAndGet(BlockEvent.BootOrColdStart)
        feed()
    }

    /** [dispatch], waiting for it: the world right after this event, before anything else ran. */
    suspend fun dispatchAndGet(event: BlockEvent): World = mutex.withLock {
        val outcome = engine.reduce(_world.value, event, clock)
        applyOutcome(outcome, persist, shield, telemetry, clock.nowWallMs())
        _world.value = outcome.world
        outcome.effects.forEach { _effects.tryEmit(it) }
        outcome.world
    }

    /**
     * Structural edits (create a block, add a to-do item) that are not [BlockEvent]s. A block
     * whose settings or checklist change is stamped with the time, so the later edit wins when two
     * devices change it at once and a lock can refuse one made without it (contract §2.4,
     * [BlockEdits.stamped]).
     */
    fun edit(transform: (World) -> World) {
        scope.launch {
            mutex.withLock {
                val edited = BlockEdits.stamped(_world.value, transform(_world.value), clock.nowWallMs())
                val plan = engine.shieldPlan(edited, clock)
                val next = edited.copy(appliedShield = plan)
                persist.save(next)
                shield.apply(plan)
                _world.value = next
            }
        }
    }
}

object NoopShield : ShieldPort {
    override fun apply(plan: ShieldPlan) = Unit
}
