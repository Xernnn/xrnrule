package com.xern.xrnrule.app.block

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.xern.xrnrule.app.XrnruleApp
import com.xern.xrnrule.app.data.AppContainer
import com.xern.xrnrule.app.data.OemAutostart
import com.xern.xrnrule.app.data.AppPermission
import com.xern.xrnrule.app.data.isGranted
import android.os.SystemClock
import com.xern.xrnrule.app.web.Browsers
import com.xern.xrnrule.app.web.SiteSightings
import com.xern.xrnrule.domain.BlockEvent
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.BlockState
import com.xern.xrnrule.domain.Effect
import com.xern.xrnrule.domain.ExtensionApi
import com.xern.xrnrule.domain.OverrideRejection
import com.xern.xrnrule.domain.RejectReason
import com.xern.xrnrule.domain.Sites
import com.xern.xrnrule.domain.World
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the Phase A core loop: detect the foreground app -> feed the engine -> (re)render the
 * block overlay. Runs as a `specialUse` foreground service so it can hold the overlay and be
 * restarted after death (survival layer, brainstorm §4.4 — the watchdog/boot pieces are
 * Phase B). Also keeps the status notification current and raises alerts (see [Notifications]).
 */
class BlockerForegroundService : LifecycleService() {

    /** What the user is looking at: an app, plus the site when that app is a browser. */
    private data class Foreground(val pkg: String?, val site: String?)

    private data class Render(val world: World, val fg: Foreground, val shown: Foreground, val other: String?)

    private val foreground = MutableStateFlow(Foreground(null, null))

    /**
     * What the block screen goes by: [foreground], except that while the screen is off it stays
     * what was in front when it went off (S5). The block screen stays up behind the lock screen,
     * so unlocking onto a blocked app doesn't show it until the next look finds it again.
     */
    private val shieldFront = MutableStateFlow(Foreground(null, null))
    private val held = HeldWhileAsleep(Foreground(null, null))

    /** I49: the browser in front, if it's one that can't block sites and has to stay closed. */
    private val otherBrowser = MutableStateFlow<String?>(null)
    private var usageDetector: UsageStatsDetector? = null
    private val awake = MutableStateFlow(true)
    private lateinit var overlay: OverlayShieldPort
    private lateinit var settingsGuard: SettingsGuardOverlay
    private lateinit var cornerClock: CornerClockOverlay
    private lateinit var notifications: Notifications

    /** The unlock session already warned about, so the 30 s warning fires once per unlock. */
    private var warnedSession: Pair<String, Long>? = null
    private var lastHealth: String? = null

    /** The cold-start rebuild is done: what accrues time may reach the engine (see onCreate). */
    @Volatile
    private var rebuilt = false

    /** A browser in front, and since when (`elapsedRealtime`): the extension should be speaking. */
    @Volatile
    private var browserInFront: Pair<String, Long>? = null

    /**
     * Screen off counts as "nothing in use". UsageStats keeps reporting the last app while the
     * phone is locked, which would let timers run and daily limits drain in a pocket overnight.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val container = (application as XrnruleApp).container
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    awake.value = false
                    container.persist.flush()
                }
                Intent.ACTION_USER_PRESENT -> {
                    awake.value = true
                    // Catch up with the home server before the user can reach a blocked app.
                    container.habits.request()
                    container.shared.request()
                }
                Intent.ACTION_SCREEN_ON -> if (!keyguardLocked()) awake.value = true
                // A hand-set clock change: book it before anything reads the time again (the
                // rebuild reads it too, so nothing is lost by waiting for it, see onCreate).
                Intent.ACTION_TIME_CHANGED -> {
                    container.checkClock()
                    if (rebuilt) container.store.dispatch(BlockEvent.Tick)
                }
                // A zone the phone set itself is taken; one picked by hand isn't (ZoneGuard).
                Intent.ACTION_TIMEZONE_CHANGED -> {
                    container.checkClock()
                    if (rebuilt) container.store.dispatch(BlockEvent.Tick)
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        val container = (application as XrnruleApp).container
        notifications = Notifications(this).also { it.createChannels() }

        overlay = OverlayShieldPort(
            context = this,
            store = container.store,
            habits = container.habits,
            shared = container.shared,
            websites = container.websites,
            onEvent = container.store::dispatch,
        )
        container.store.shield = overlay
        settingsGuard = SettingsGuardOverlay(this, container.hardcoreGuard)
        cornerClock = CornerClockOverlay(this)

        startAsForeground()
        overlay.prepare() // made and composed now, so the first block screen only has to show

        awake.value = getSystemService(PowerManager::class.java).isInteractive && !keyguardLocked()
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val detector = UsageStatsDetector(this) {
            container.store.world.value.blocks.any { it.enabled }
        }
        usageDetector = detector

        // 1. cold-start rebuild (I8), done before anything feeds the engine (see WorldStore.coldStart);
        // 2. then what does: the app in front (+ the site the browser extension reports), the sites
        //    playing out of sight (I47), and the tick;
        // 3. then catch up on what the home server got while we were down.
        lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                container.store.coldStart {
                    rebuilt = true
                    lifecycleScope.launch { followForeground(container, detector) }
                    lifecycleScope.launch {
                        SiteSightings.playing.collect { container.store.dispatch(BlockEvent.BackgroundChanged(it)) }
                    }
                    lifecycleScope.launch { tick(container) }
                }
            }
            container.habits.request()
            container.shared.request()
        }
        lifecycleScope.launch(Dispatchers.IO) { container.telemetry.prune(container.clock.nowWallMs()) }
        container.unreadableWorldCopy?.let { copy ->
            notifications.alert(
                Notifications.ALERT_WORLD_UNREADABLE,
                "Couldn't read your blocks",
                "xrnrule started empty. The unreadable file was kept as ${copy.name}.",
            )
        }

        // 4. re-render the overlay (and the hardcore Settings guard) whenever the world or the
        //    foreground changes; keep the status notification in step
        lifecycleScope.launch {
            combine(container.store.world, foreground, shieldFront, otherBrowser) { world, fg, shown, other -> Render(world, fg, shown, other) }
                .collect { (world, fg, shown, other) ->
                    overlay.render(world, shown.pkg, shown.site, other, awake = awake.value)
                    settingsGuard.render(fg.pkg, world)
                    updateCornerClock()
                    // The app in front with no block screen over it: what runs out on it leads the line.
                    val front = fg.pkg?.takeIf { !overlay.visible.value }
                    notifications.updateStatus(notifications.status(world, container.engine, container.clock, front))
                }
        }

        // 5. engine effects that deserve the user's attention right now
        lifecycleScope.launch {
            container.store.effects.collect { effect -> onEffect(effect, container.store.world.value) }
        }

        // 6. while a block screen is up, hear about the laptop and the web app within a second or
        //    two: long-polls on the shared state and on the habit server
        lifecycleScope.launch {
            // Not while the screen is off: the block screen held up behind the lock screen isn't being looked at.
            combine(overlay.visible, awake) { up, on -> up && on }.distinctUntilChanged().collectLatest { up ->
                if (!up) return@collectLatest
                // Scoped to this showing: both stop when the shield goes (collectLatest cancels).
                coroutineScope {
                    launch { container.shared.watch() }
                    container.habits.watch { overlay.showsHabitItem }
                }
            }
        }
    }

    /** The app in front, and the site when it's a browser, to the engine and the site-time log. The detector only polls while the screen is on and unlocked. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun followForeground(container: AppContainer, detector: UsageStatsDetector) {
        val detected = awake.flatMapLatest { isAwake -> if (isAwake) detector.foregroundPackage else flowOf(null) }
        combine(detected, SiteSightings.current, awake) { pkg, host, isAwake ->
            val browser = pkg?.takeIf { Browsers.isBrowser(this@BlockerForegroundService, it) }
            if (!isAwake) Foreground(null, null) else Foreground(pkg, host?.takeIf { browser != null })
        }.distinctUntilChanged().collect { fg ->
            foreground.value = fg
            shieldFront.value = held.of(awake.value, fg)
            val browser = fg.pkg?.takeIf { Browsers.isBrowser(this@BlockerForegroundService, it) }
            if (browser == null) browserInFront = null
            else if (browserInFront?.first != browser) browserInFront = browser to SystemClock.elapsedRealtime()
            container.siteUsage.onSite(fg.site?.let(Sites::hostOf))
            container.store.dispatch(BlockEvent.ForegroundChanged(fg.pkg, fg.site))
        }
    }

    /**
     * ~1s tick while anything time-based is live and the screen is on (nothing is shown
     * while it's off, and the first event after waking catches up); book website time
     * every 30 s; work habit items out again when a new habit day starts (I23); check every
     * 5 s that blocking still works.
     */
    private suspend fun tick(container: AppContainer) {
        var seconds = 0
        var habitDay = container.store.world.value.habitDay(container.clock)
        while (true) {
            // Asleep unless a page is playing: its time still counts (I47).
            if (!awake.value) combine(awake, SiteSightings.playing) { on, sites -> on || sites.isNotEmpty() }.first { it }
            if (needsTicking(container.store.world.value)) {
                container.store.dispatch(BlockEvent.Tick)
            }
            if (seconds % 30 == 0) {
                container.siteUsage.flush()
                container.checkClock()
            }
            if (seconds % 5 == 0) checkHealth(container.store.world.value)
            updateCornerClock()
            seconds++
            val today = container.store.world.value.habitDay(container.clock)
            if (today != habitDay) {
                habitDay = today
                container.store.dispatch(BlockEvent.Tick)
                container.habits.request()
            }
            delay(1_000)
        }
    }

    private fun onEffect(effect: Effect, world: World) {
        when (effect) {
            is Effect.UnlockEndingSoon -> {
                val session = world.sessions[effect.blockId] ?: return
                val key = effect.blockId to session.startedAtWall
                if (warnedSession == key) return
                warnedSession = key
                val name = world.block(effect.blockId)?.name ?: "Your unlock"
                notifications.alert(Notifications.ALERT_ENDING, "$name locks in ${(effect.remainingMs + 999) / 1000} s", "Finish up.", gone = ALERT_GONE_MS)
            }
            is Effect.TamperDetected -> notifications.alert(
                Notifications.ALERT_TAMPER,
                "Unlock ended early",
                "The phone's clock was turned back, so the unlock was closed.",
            )
            is Effect.RedeemRejected -> if (effect.reason == RejectReason.CAP_REACHED) {
                Toast.makeText(this, "Today's unlock time is used up", Toast.LENGTH_LONG).show()
            }
            is Effect.OverrideRejected -> Toast.makeText(this, overrideText(effect.reason), Toast.LENGTH_LONG).show()
            else -> Unit
        }
    }

    /**
     * Turning off "Usage access" or "Draw over other apps" silently stops all blocking (and MIUI
     * resets the overlay one on every reinstall), so say so while it lasts.
     */
    private fun checkHealth(world: World) {
        val container = (application as XrnruleApp).container
        val missing = if (world.blocks.isEmpty()) {
            emptyList()
        } else {
            listOf(AppPermission.USAGE_ACCESS, AppPermission.OVERLAY).filterNot { it.isGranted(this) }
        }
        // A browser in front for a while and not a word from the extension: sites aren't being blocked there.
        val inFront = browserInFront
        val silentBrowser = inFront?.takeIf { (_, since) ->
            val now = SystemClock.elapsedRealtime()
            now - since >= EXTENSION_GRACE_MS && SiteSightings.silent(now)
        }?.first
        container.websites.quiet = silentBrowser != null
        // The extension speaks from whichever browser is in front when it does: that's the one it
        // lives in. Only a report from well after that browser came in front counts: one from
        // the moment of switching is the last browser's. And only the extension's own: the block
        // page speaks from any browser that opens it.
        inFront?.takeIf { (_, since) -> SiteSightings.voice.namedSiteSince(since + BROWSER_SETTLE_MS) }
            ?.let { (pkg, _) -> container.websites.extensionBrowser = pkg }
        // I49: a browser that can't block sites, once one that can is known. Never that one itself:
        // it's where the extension gets fixed.
        val other = inFront?.takeIf { (pkg, since) ->
            SystemClock.elapsedRealtime() - since >= EXTENSION_GRACE_MS && !inCustomTab() &&
                container.websites.lacksExtension(pkg) && Browsers.isFullBrowser(this, pkg) &&
                ExtensionApi.otherBrowsersMatter(world)
        }?.first
        otherBrowser.value = other
        val sitesOff = other == null && silentBrowser != null && world.blocks.any { it.enabled && it.targetSites.isNotEmpty() }
        // Sync failing for an hour: unlocks and ticks from the laptop stop arriving, silently.
        val now = System.currentTimeMillis()
        val blocksStuck = container.shared.failingSince.value?.let { now - it >= SYNC_ALERT_MS } == true
        val habitsStuck = container.habits.failingSince.value?.let { now - it >= SYNC_ALERT_MS } == true
        container.loop.check()
        val loopStuck = container.loop.serverFailingSince.value?.let { now - it >= SYNC_ALERT_MS } == true
        val loopUnreachable = loopStuck && container.loop.unreachable.value
        // The phone turns this off at every install, and nothing else shows until a kill goes unrepaired.
        val autostartOff = world.blocks.isNotEmpty() && OemAutostart.enabled(this) == false
        val health = "${missing.joinToString()}|$sitesOff|$silentBrowser|$blocksStuck|$habitsStuck|$loopStuck|$loopUnreachable|$autostartOff"
        if (health == lastHealth) return
        lastHealth = health

        if (blocksStuck || habitsStuck || loopStuck) {
            val what = buildList {
                if (blocksStuck) add("Blocks and unlocks (${container.shared.status.value?.message ?: "failing"}): xrnrule → Settings → Sync with laptop.")
                if (habitsStuck) add("Habits (${container.habits.status.value?.message ?: "failing"}): xrnrule → Settings → Sync with laptop.")
                if (loopUnreachable) {
                    add("xrnloops doesn't answer: turn on its Autostart (phone Settings → Apps → xrnloops).")
                } else if (loopStuck) {
                    add("xrnloops: its Settings → Sync with home server.")
                }
            }
            notifications.alert(
                Notifications.ALERT_SYNC_OFF,
                "Not syncing with your home server",
                "Nothing has synced for over an hour, so the laptop and this phone have drifted apart. " + what.joinToString(" "),
            )
        } else {
            notifications.cancel(Notifications.ALERT_SYNC_OFF)
        }

        if (autostartOff) {
            notifications.alert(
                Notifications.ALERT_AUTOSTART_OFF,
                "Autostart is off",
                "The phone turned it off, so xrnrule may not come back if it's stopped. Tap to turn it on.",
                tap = OemAutostart.fixIt(this),
            )
        } else {
            notifications.cancel(Notifications.ALERT_AUTOSTART_OFF)
        }

        if (missing.isEmpty()) {
            notifications.cancel(Notifications.ALERT_BLOCKING_OFF)
        } else {
            notifications.alert(
                Notifications.ALERT_BLOCKING_OFF,
                "Blocking is off",
                "Turn on ${missing.joinToString(" and ") { "\"${it.label}\"" }} for xrnrule. Tap to fix.",
            )
        }
        if (sitesOff && silentBrowser != null) {
            val name = appLabel(silentBrowser)
            val home = container.websites.extensionBrowser
            notifications.alert(
                Notifications.ALERT_WEBSITES_OFF,
                "Sites aren't blocked in $name",
                if (home == null || home == silentBrowser) {
                    "The xrnrule extension in $name isn't answering. Turn it on in $name's extensions page."
                } else {
                    "Only ${appLabel(home)} has the xrnrule extension. Use it for the sites you block."
                },
            )
        } else {
            notifications.cancel(Notifications.ALERT_WEBSITES_OFF)
        }
    }

    /** The time left on the app in front, unless the block screen is over it. Sites have the extension's own clock. */
    private fun updateCornerClock() {
        val container = (application as XrnruleApp).container
        val look = CornerClockLook.look.value
        val pkg = foreground.value.pkg?.takeIf { look.on && !overlay.visible.value }
        val left = pkg?.let { container.engine.timeLeft(container.store.world.value, it, null, container.clock) }
        cornerClock.show(left?.ms?.takeIf { it > 0 }, look)
    }

    override fun onDestroy() {
        cornerClock.hide()
        overlay.dispose()
        unregisterReceiver(screenReceiver)
        val container = (application as XrnruleApp).container
        container.siteUsage.onSite(null)
        container.persist.flush()
        super.onDestroy()
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun overrideText(reason: OverrideRejection): String = when (reason) {
        OverrideRejection.WRONG_PHRASE -> "That isn't the sentence. Type it exactly."
        OverrideRejection.TOO_EARLY -> "Not yet: wait for the countdown."
        OverrideRejection.TOO_LATE -> "That took too long. Start again."
        OverrideRejection.NONE_LEFT -> "Today's bought minutes for this are used up."
        OverrideRejection.OFF -> "Buying minutes is turned off."
        OverrideRejection.NOT_OVERRIDABLE -> "This can't be bought off."
        OverrideRejection.NOT_STARTED -> "Start again."
    }

    private fun keyguardLocked(): Boolean =
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    /**
     * Some OEMs kill the whole process when the task is swiped from recents despite
     * `START_STICKY`. Ask WorkManager to bring the service back up immediately rather than
     * waiting for [ServiceWatchdogWorker]'s next periodic tick (brainstorm §4.4).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val request = OneTimeWorkRequestBuilder<ServiceWatchdogWorker>().build()
        WorkManager.getInstance(applicationContext).enqueue(request)
    }

    /** Daily limits, schedules, lockdowns and breaks change what's blocked by time alone, so they always tick. */
    private fun inCustomTab() = usageDetector?.customTab == true

    private fun needsTicking(world: World): Boolean =
        world.sessions.isNotEmpty() ||
            world.pause != null ||
            world.backgroundSites.isNotEmpty() ||
            world.blocks.any { it.state == BlockState.EARNED || it.mode != BlockMode.HABIT || it.hasLimit || it.blockedHours != null } ||
            world.progress.values.any { it.timerRunning } ||
            world.focus?.let { it.running || it.resumeAtWall != null } == true // I37: goals reached mid-run, breaks ending

    // Lint false positive (sideload variant only): the merged manifest does declare
    // foregroundServiceType="specialUse" on this service.
    @SuppressLint("ForegroundServiceType")
    private fun startAsForeground() {
        val container = (application as XrnruleApp).container
        val notification = notifications.buildStatus(
            notifications.status(container.store.world.value, container.engine, container.clock),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Notifications.STATUS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifications.STATUS_ID, notification)
        }
    }

    companion object {
        /** A warning about the next 30 s is out of date soon: it doesn't stay around. */
        private const val ALERT_GONE_MS = 10_000L
        /** How long sync may fail before the "not syncing" alert. */
        private const val SYNC_ALERT_MS = 60 * 60 * 1000L

        /** How long a browser may be in front without a word from the extension. */
        private const val BROWSER_SETTLE_MS = 3_000L
        private const val EXTENSION_GRACE_MS = 10_000L

        /**
         * Android 12+ refuses foreground-service starts from a process that came up in the
         * background (a watchdog job) by throwing
         * `ForegroundServiceStartNotAllowedException`, an [IllegalStateException]. Called from
         * `Application.onCreate`, that used to kill the process before anything ran. Now the
         * start is simply skipped; the next allowed caller (opening the app, boot) brings the
         * service up.
         */
        fun start(context: Context): Boolean {
            val intent = Intent(context, BlockerForegroundService::class.java)
            return try {
                context.startForegroundService(intent)
                true
            } catch (e: IllegalStateException) {
                false
            }
        }
    }
}
