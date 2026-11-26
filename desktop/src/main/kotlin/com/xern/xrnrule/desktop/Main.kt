package com.xern.xrnrule.desktop

import com.xern.xrnrule.domain.BlockEngine
import com.xern.xrnrule.domain.BlockEvent
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.BlockState
import com.xern.xrnrule.domain.DeviceKind
import com.xern.xrnrule.domain.Effect
import com.xern.xrnrule.domain.ExtensionApi
import com.xern.xrnrule.domain.World
import com.xern.xrnrule.domain.sharedZone
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
 * desktop apps (KWin or Hyprland) and websites (the browser extension). See `desktop/README.md`.
 */
fun main() = runBlocking {
    val config = Config.load()
    config.dataDir.mkdirs()
    // The habit history, blocks and event log are this user's alone: owner-only, like ~/.ssh.
    runCatching { Files.setPosixFilePermissions(config.dataDir.toPath(), PosixFilePermissions.fromString("rwx------")) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Hand-set time and zone changes taken back out (ClockGuard, I54).
    val clock = ClockGuard(DesktopClock(), File(config.dataDir, "clock.json"))
    val persist = FilePersist(File(config.dataDir, "world.json"), clock, File(config.dataDir, SharedSyncClient.BASE_FILE))
    val telemetry = FileTelemetry(File(config.dataDir, "telemetry.log")).also { it.prune(clock.nowWallMs()) }
    val store = Store(BlockEngine(), persist, clock, scope, telemetry)
    clock.sharedZone = { store.world.value.sharedZone() }
    if (store.world.value.deviceId.isEmpty()) {
        store.edit { it.copy(deviceId = "laptop-" + UUID.randomUUID().toString().take(8)) }
    }
    // Which blocks hold here (I42): those for the laptop, and those for both.
    if (store.world.value.deviceKind != DeviceKind.LAPTOP) store.edit { it.copy(deviceKind = DeviceKind.LAPTOP) }

    val notifier = Notifier(config)
    val enforcer = Enforcer(store, notifier, config)
    // Screen time (docs/laptop-screen-time.md): statistics only, never read by the engine.
    val usage = UsageLog(File(config.dataDir, "usage.json"), clock)
    val foreground = Foreground(store, File(config.dataDir, "browsers.txt"), usage)
    enforcer.foreground = foreground
    val windows = WindowSystem.detect()
    enforcer.windowSystem = windows
    store.shield = enforcer
    val shared = SharedSyncClient(config, store, telemetry, scope, usage)
    val habits = HabitClient(config, store, scope)

    // The blocked apps' and sites' logos: the laptop's own, and the phone's through the server.
    val siteIcons = SiteIconCache(File(config.dataDir, "site-icons"), scope)
    val laptopIcons = LaptopAppIcons()
    val serverIcons = ServerIcons(
        File(config.dataDir, "server-icons"), config.ruleUrl, Http(config.user, config.password).takeIf { config.syncConfigured },
        store.world, { world -> ServerIcons.owned(world, siteIcons, laptopIcons) }, scope,
    )

    store.coldStart {
        windows.start(enforcer, enforcer::reported) // lists the open windows (again), then follows them
        LocalServer(config, store, foreground, enforcer, shared, habits, siteIcons, telemetry, laptopIcons = laptopIcons, serverIcons = serverIcons, usage = usage).start()
    }
    shared.start()
    habits.start()
    IdleWatch({ IdleWatch.socketOf() }, clock, usage::idle, usage::active).start()
    serverIcons.start(shared.status)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            persist.flush()
            usage.stop()
        },
    )

    // Engine effects -> desktop notifications.
    scope.launch {
        store.effects.collect { effect ->
            val world = store.world.value
            fun name(id: String) = world.block(id)?.name ?: "A block"
            when (effect) {
                is Effect.UnlockEndingSoon -> world.block(effect.blockId)?.takeIf(world::locksOnLaptop)?.let {
                    notifier.notify("${it.name} locks again soon", "In ${effect.remainingMs / 1000} seconds.")
                }
                is Effect.TamperDetected -> notifier.notify("Unlock ended", "The clock was changed, so ${name(effect.blockId)} is blocked again.", urgent = true)
                is Effect.RedeemRejected -> notifier.notify("Can't open ${name(effect.blockId)}", "Today's unlock time is used up.")
                else -> Unit
            }
        }
    }

    System.err.println(
        "xrnrule-desktop on ${config.localUrl}, data in ${config.dataDir}" +
            if (config.syncConfigured) ", syncing with ${config.ruleUrl} and ${config.loopUrl}" else ", not syncing" + config.syncRefusal?.let { ": $it" }.orEmpty(),
    )
    for (file in config.exposedFiles(Config.file())) System.err.println("xrnrule-desktop: other users can read $file, which holds the sync password (chmod 600 it)")

    // The tick: ~1 s while anything time-based is live; screen lock means nothing in use; a gap
    // (the laptop slept) re-anchors timers rather than crediting the sleep to whatever was in front.
    var lastMono = clock.nowMonotonicMs()
    var seconds = 0
    var extensionAlerted = false
    var syncFailingSince: Long? = null
    var syncAlerted = false
    var phoneAlerted = false
    while (true) {
        val now = clock.nowMonotonicMs()
        if (now - lastMono > 10_000) {
            withContext(Dispatchers.IO) { clock.check() } // the clock may have been set while it slept
            store.dispatch(BlockEvent.BootOrColdStart)
            foreground.heardExtension() // a sleeping laptop isn't a silent extension (I49)
        }
        lastMono = now
        usage.tick() // a sleep isn't screen time either
        if (seconds % 3 == 0) {
            val locked = withContext(Dispatchers.IO) { windows.screenLocked() }
            if (locked != foreground.locked) {
                foreground.locked = locked
                if (locked) persist.flush()
            }
        }
        foreground.update()
        if (seconds % 60 == 0) withContext(Dispatchers.IO) { usage.flush() }
        val world = store.world.value
        if (needsTicking(world)) store.dispatch(BlockEvent.Tick)
        if (seconds % 5 == 0 && ExtensionApi.otherBrowsersMatter(world)) {
            foreground.browsers.toClose(now)?.let { app ->
                // I49: a browser that can't block sites, or the extension turned off in this one.
                withContext(Dispatchers.IO) { windows.closeWindowsOf(setOf(app)) }
                notifier.notify("${DesktopApps.name(app)} closed", "Your site blocks only work in a browser with the xrnrule extension.", urgent = true)
            }
        }
        if (seconds % 30 == 0) {
            withContext(Dispatchers.IO) { clock.check() }
            val missing = foreground.extensionMissing() && world.blocks.any { it.targetSites.isNotEmpty() }
            if (missing && !extensionAlerted) {
                notifier.notify("Website blocking is off", "The xrnrule extension isn't answering for this window. Turn it on in the extensions page, allow it in private windows, or use a profile that has it.", urgent = true)
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

            // The phone can't report that it has stopped, but the laptop can see it go quiet.
            val quiet = shared.phoneQuietMs(wall)
            if (quiet != null && !phoneAlerted) notifier.notify("The phone has gone quiet", PhoneWatch.text(quiet))
            phoneAlerted = quiet != null
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
        world.backgroundSites.isNotEmpty() ||
        world.blocks.any { it.state == BlockState.EARNED || it.mode != BlockMode.HABIT || it.hasLimit || it.blockedHours != null } ||
        world.progress.values.any { it.timerRunning } ||
        world.focus?.let { it.running || it.resumeAtWall != null } == true // I37
