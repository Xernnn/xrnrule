package com.xern.xrnrule.desktop

import com.xern.xrnrule.domain.BlockEngine
import com.xern.xrnrule.domain.BlockEvent
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.BlockState
import com.xern.xrnrule.domain.DeviceKind
import com.xern.xrnrule.domain.Effect
import com.xern.xrnrule.domain.World
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * xrnrule on the laptop: the same engine as the phone, enforcing the same shared blocks on
 * desktop apps (KWin) and websites (the browser extension). See `desktop/README.md`.
 */
fun main() = runBlocking {
    val config = Config.load()
    config.dataDir.mkdirs()
    // The habit history, blocks and event log are this user's alone: owner-only, like ~/.ssh.
    runCatching { Files.setPosixFilePermissions(config.dataDir.toPath(), PosixFilePermissions.fromString("rwx------")) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clock = DesktopClock()
    val persist = FilePersist(File(config.dataDir, "world.json"), clock)
    val telemetry = FileTelemetry(File(config.dataDir, "telemetry.log")).also { it.prune(clock.nowWallMs()) }
    val store = Store(BlockEngine(), persist, clock, scope, telemetry)
    if (store.world.value.deviceId.isEmpty()) {
        store.edit { it.copy(deviceId = "laptop-" + UUID.randomUUID().toString().take(8)) }
    }
    // Which blocks hold here (I42): those for the laptop, and those for both.
    if (store.world.value.deviceKind != DeviceKind.LAPTOP) store.edit { it.copy(deviceKind = DeviceKind.LAPTOP) }

    val notifier = Notifier(config)
    val enforcer = Enforcer(store, notifier, config)
    val foreground = Foreground(store)
    enforcer.foreground = foreground
    store.shield = enforcer
    val shared = SharedSyncClient(config, store, telemetry, scope)
    val habits = HabitClient(config, store, scope)

    if (KWin.export(enforcer)) KWin.reloadScript() // our script lists the open windows again
    LocalServer(config, store, foreground, enforcer, shared, habits, SiteIconCache(File(config.dataDir, "site-icons"), scope)).start()
    store.dispatch(BlockEvent.BootOrColdStart) // I8, and the first shield plan
    shared.start()
    habits.start()
    Runtime.getRuntime().addShutdownHook(Thread { persist.flush() })

    // Engine effects -> desktop notifications.
    scope.launch {
        store.effects.collect { effect ->
            val world = store.world.value
            fun name(id: String) = world.block(id)?.name ?: "A block"
            when (effect) {
                is Effect.UnlockEndingSoon -> notifier.notify("${name(effect.blockId)} locks again soon", "In ${effect.remainingMs / 1000} seconds.")
                is Effect.TamperDetected -> notifier.notify("Unlock ended", "The clock was changed, so ${name(effect.blockId)} is blocked again.", urgent = true)
                is Effect.RedeemRejected -> notifier.notify("Can't open ${name(effect.blockId)}", "Today's unlock time is used up.")
                else -> Unit
            }
        }
    }

    System.err.println("xrnrule-desktop on ${config.localUrl}, data in ${config.dataDir}" + if (config.syncConfigured) ", syncing with ${config.ruleUrl} and ${config.loopUrl}" else ", not syncing")

    // The tick: ~1 s while anything time-based is live; screen lock means nothing in use; a gap
    // (the laptop slept) re-anchors timers rather than crediting the sleep to whatever was in front.
    var lastMono = clock.nowMonotonicMs()
    var seconds = 0
    var extensionAlerted = false
    var syncFailingSince: Long? = null
    var syncAlerted = false
    while (true) {
        val now = clock.nowMonotonicMs()
        if (now - lastMono > 10_000) store.dispatch(BlockEvent.BootOrColdStart)
        lastMono = now
        if (seconds % 3 == 0) {
            val locked = withContext(Dispatchers.IO) { KWin.screenLocked() }
            if (locked != foreground.locked) {
                foreground.locked = locked
                if (locked) persist.flush()
            }
        }
        foreground.update()
        val world = store.world.value
        if (needsTicking(world)) store.dispatch(BlockEvent.Tick)
        if (seconds % 30 == 0) {
            val missing = foreground.extensionMissing() && world.blocks.any { it.targetSites.isNotEmpty() }
            if (missing && !extensionAlerted) {
                notifier.notify("Website blocking is off", "The xrnrule extension isn't answering in this browser. Turn it on in the extensions page.", urgent = true)
            }
            extensionAlerted = missing

            // Sync failing for an hour: the phone's unlocks and ticks stop arriving, silently.
            val failing = listOfNotNull(shared.status.value, habits.status.value).firstOrNull { !it.ok }
            val wall = System.currentTimeMillis()
            syncFailingSince = if (failing == null) null else syncFailingSince ?: wall
            val stuck = syncFailingSince?.let { wall - it >= SYNC_ALERT_MS } == true
            if (stuck && !syncAlerted) {
                notifier.notify(
                    "Not syncing with your home server",
                    "Nothing has synced for over an hour (${failing?.message}), so unlocks and ticks from the phone aren't reaching this laptop.",
                    urgent = true,
                )
            }
            syncAlerted = stuck
        }
        seconds++
        delay(1_000)
    }
}

/** How long sync may fail before the "not syncing" notification. */
private const val SYNC_ALERT_MS = 60 * 60 * 1000L

private fun needsTicking(world: World): Boolean =
    world.sessions.isNotEmpty() ||
        world.pause != null ||
        world.blocks.any { it.state == BlockState.EARNED || it.mode != BlockMode.HABIT || it.hasLimit || it.blockedHours != null } ||
        world.progress.values.any { it.timerRunning } ||
        world.focus?.let { it.running || it.resumeAtWall != null } == true // I37
