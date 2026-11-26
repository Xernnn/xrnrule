package com.xern.xrnrule.app.block

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.xern.xrnrule.app.data.AppIcons
import com.xern.xrnrule.app.data.AppLabels
import com.xern.xrnrule.app.data.HabitSync
import com.xern.xrnrule.app.data.OemAutostart
import com.xern.xrnrule.app.data.SharedSync
import com.xern.xrnrule.app.data.WorldStore
import com.xern.xrnrule.app.ui.AppLinks
import com.xern.xrnrule.app.ui.BlockOverlayContent
import com.xern.xrnrule.app.ui.GoalInfo
import com.xern.xrnrule.app.ui.goalInfo
import com.xern.xrnrule.app.web.ExtensionWebsiteMonitor
import com.xern.xrnrule.app.web.SiteSightings
import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockEvent
import com.xern.xrnrule.domain.BlockReason
import com.xern.xrnrule.domain.BlockState
import com.xern.xrnrule.domain.BlockTexts
import com.xern.xrnrule.domain.ChecklistItem
import com.xern.xrnrule.domain.OverrideRejection
import com.xern.xrnrule.domain.RejectReason
import com.xern.xrnrule.domain.ShieldPlan
import com.xern.xrnrule.domain.ShieldPort
import com.xern.xrnrule.domain.Sites
import com.xern.xrnrule.domain.World
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The enforcement surface: a `TYPE_APPLICATION_OVERLAY` window rather than a
 * launched Activity, because a foreground service holding the overlay permission can show it
 * reliably where background Activity starts are restricted.
 *
 * [apply] only records the desired blocked set; [render] is called by the service whenever the
 * foreground app or the [World] changes and decides whether the overlay is visible. [render]
 * also re-adds the window if the OS silently tore it down (some OEM task killers do), so the
 * block cannot be dismissed by anything short of finishing the habit or leaving the app.
 *
 * A `ComposeView` added straight to a [WindowManager] has no Activity to supply a
 * ViewModelStoreOwner / SavedStateRegistryOwner, so [OverlayWindowOwner] provides a minimal one
 * scoped to the lifetime of the window itself.
 */
/** How often each blocked thing was tried today, by phone-local day. Stays on this phone. */
private class TriesToday(context: Context) {
    private val prefs = context.getSharedPreferences("tries", Context.MODE_PRIVATE)
    private var last: Pair<String, Long>? = null

    /** The count so far, without counting a try: a block screen held up while the screen is off. */
    fun peek(subject: String): Int =
        if (prefs.getString(KEY_DAY, null) != LocalDate.now().toString()) 0 else prefs.getInt(subject, 0)

    /** The count, with this try in it. Called on every redraw, so one visit counts once. */
    fun count(subject: String): Int {
        val now = SystemClock.elapsedRealtime()
        val same = last?.takeIf { (who, at) -> who == subject && now - at < VISIT_MS } != null
        last = subject to now
        val day = LocalDate.now().toString()
        if (prefs.getString(KEY_DAY, null) != day) prefs.edit().clear().putString(KEY_DAY, day).apply()
        val seen = prefs.getInt(subject, 0)
        if (same) return seen
        prefs.edit().putInt(subject, seen + 1).apply()
        return seen + 1
    }

    private companion object {
        const val KEY_DAY = " day"
        const val VISIT_MS = 60_000L
    }
}

class OverlayShieldPort(
    private val context: Context,
    private val store: WorldStore,
    private val habits: HabitSync,
    private val shared: SharedSync,
    private val websites: ExtensionWebsiteMonitor,
    private val onEvent: (BlockEvent) -> Unit,
) : ShieldPort {

    private val wm = context.getSystemService(WindowManager::class.java)

    @Volatile
    private var plan = ShieldPlan(emptySet())

    /** One block screen's window, its layout and its lifecycle; [detached] once the OS took it away. */
    private class Screen(val host: FrameLayout, val params: WindowManager.LayoutParams, val owner: OverlayWindowOwner) {
        @Volatile
        var detached = false
    }

    private val window = ShieldWindow(
        make = ::makeScreen,
        add = { s -> wm.addView(s.host, s.params) },
        remove = { s -> wm.removeView(s.host) },
        release = { s -> s.owner.onDestroy() },
        reveal = { s, on ->
            // Started before it shows, so what it draws first is this showing's, not the last one's.
            if (on) s.owner.onStart()
            s.host.visibility = if (on) View.VISIBLE else View.GONE
            if (!on) s.owner.onStop() // nothing recomposes or ticks behind a hidden screen
        },
        lost = { s -> s.detached },
    )
    private var audioFocus: AudioFocusRequest? = null

    @Volatile
    private var popupsOff = false
    private val tries = TriesToday(context)

    private val _visible = MutableStateFlow(false)

    /** Whether a block screen is up; the service syncs faster while it is. */
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    /** Whether the block screen shows a habit item: a tick anywhere can lift it (I36). */
    val showsHabitItem: Boolean get() = uiState.value?.items?.any { it.derived } == true
    private val uiState = mutableStateOf<OverlayUi?>(null)

    override fun apply(plan: ShieldPlan) {
        this.plan = plan
    }

    /** What [show] puts up: the screen, and whether it's a block's (one a sync can lift). */
    data class Screenful(val ui: OverlayUi, val ofABlock: Boolean)

    /**
     * What the block screen shows for this front, or null for none. Worked out off the main thread
     * (N5): it asks the engine which blocks hold and until when, and PackageManager for names.
     * [otherBrowser]: a browser in front that can't block sites (I49). [awake]: false while the
     * screen is off and the block screen is only held up for when it comes back on, which isn't a try.
     */
    fun model(world: World, foregroundPkg: String?, foregroundSite: String?, otherBrowser: String? = null, awake: Boolean = true): Screenful? {
        val current = plan
        val appBlocked = foregroundPkg != null && foregroundPkg in current.blockedPackages
        val siteBlocked = !appBlocked && current.covers(null, foregroundSite)
        val browser = otherBrowser?.takeIf { it == foregroundPkg && !appBlocked && !siteBlocked }
        if (!appBlocked && !siteBlocked && browser == null) return null
        if (browser != null) {
            AppIcons.load(context, browser)
            // The extension's own browser, the extension silent there (I49): nowhere else to send you.
            val use = homeBrowser()?.takeIf { it.pkg != browser }
            val ui = OverlayUi(subject = appLabel(browser), pkg = browser, isSite = false, block = null, items = emptyList(), useBrowser = use, extensionOff = use == null)
            return Screenful(ui, ofABlock = false)
        }
        val engine = store.engine
        val clock = store.clock
        // Read once a showing: Close and Focus can't open anything while HyperOS's switch is off (S4).
        if (!visible.value) popupsOff = OemAutostart.backgroundPopups(context) == false
        // Drawn now, off the main thread, so the screen has it the moment it shows (N6).
        if (appBlocked) AppIcons.load(context, foregroundPkg)
        val blockers = engine.blockersOf(world, foregroundPkg.takeIf { appBlocked }, foregroundSite.takeIf { !appBlocked }, clock)
        val blockerIds = blockers.map { it.id }.toSet()
        val block = BlockTexts.leading(engine, world, blockers, clock)
        val pending = world.pendingOverride?.takeIf { it.blockIds == blockerIds }
        val subject = if (appBlocked) appLabel(foregroundPkg) else Sites.hostOf(foregroundSite!!)
        val reason = block?.let { engine.blockReason(world, it, clock) }
        val ui = OverlayUi(
            subject = subject,
            // I53: the pause before an app opens isn't a try at getting past a block.
            triesToday = when {
                reason == BlockReason.WAIT -> 0
                awake -> tries.count(subject)
                else -> tries.peek(subject)
            },
            pkg = foregroundPkg.takeIf { appBlocked },
            isSite = siteBlocked,
            block = block,
            reason = reason,
            usedToday = block?.let { engine.usedToday(world, it, clock) } == true,
            items = block?.let { world.items(it.id) }.orEmpty(),
            habitAppLabel = block?.trigger?.habitPackages
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(" or ") { appLabel(it) },
            back = block?.let { held -> { BlockTexts.back(engine, world, held, clock) } },
            openWaitLeftMs = block?.let { held -> { engine.openWaitRemainingMs(world, held.id, clock) } },
            timerMs = block?.let { world.progress[it.id]?.accumulatedMs } ?: 0,
            timerRunning = block?.let { world.progress[it.id]?.timerRunning } == true,
            redeemRejection = block?.takeIf { it.state == BlockState.EARNED }?.let { engine.redeemRejection(world, it.id, clock) },
            unlockUsedTodayMs = engine.unlockCommittedMs(world, clock), // I9: open unlocks included
            othersOpen = world.sessions.isNotEmpty(),
            dailyCapMs = world.settings.dailyCapMs,
            goals = block?.let { world.goalInfo(it.id, engine, clock) }.orEmpty(),
            focusHabitId = world.focus?.takeIf { it.running }?.habitId,
            blockerIds = blockerIds,
            overrideRejection = if (blockers.isEmpty()) OverrideRejection.NOT_OVERRIDABLE else engine.overrideRejection(world, blockers, clock),
            overrideReadyAtElapsed = pending?.let { SystemClock.elapsedRealtime() + (it.sinceWall + world.settings.overrideWaitMs - clock.nowWallMs()) },
            overridePhrase = world.settings.overridePhrase,
            overrideGrantMs = world.settings.overrideGrantMs,
            overrideWaitMs = world.settings.overrideWaitMs,
            popupsOff = popupsOff,
        )
        return Screenful(ui, ofABlock = true)
    }

    /** On the main thread: puts [screen] up, or takes the block screen down for null. */
    fun show(screen: Screenful?) {
        if (screen == null) {
            hide()
            return
        }
        val ui = screen.ui
        uiState.value = ui
        if (screen.ofABlock) {
            // Hitting the shield asks the habit server, so a tick made elsewhere is caught now rather
            // than at the next minute's check.
            if (!window.shown && showsHabitItem) habits.request()
            // Likewise the laptop: an unlock redeemed there lifts this shield a moment later.
            if (!window.shown) shared.request()
        }
        show()
        keepScreenOn(ui.timerRunning || ui.overrideReadyAtElapsed != null)
    }

    /** [model] and [show] in one, on the main thread. */
    fun render(world: World, foregroundPkg: String?, foregroundSite: String?, otherBrowser: String? = null, awake: Boolean = true) =
        show(model(world, foregroundPkg, foregroundSite, otherBrowser, awake))

    /**
     * The screen stays on only while something is counting on it: a wait timer stops with the
     * screen, and the wait before buying minutes is shorter than most screen timeouts' patience.
     * Otherwise a block screen left on a table would keep the phone awake until the battery ran out.
     */
    private fun keepScreenOn(on: Boolean) {
        val screen = window.window ?: return
        val host = screen.host
        val params = screen.params
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if ((params.flags and flag != 0) == on) return
        params.flags = if (on) params.flags or flag else params.flags and flag.inv()
        runCatching { wm.updateViewLayout(host, params) }
    }

    /**
     * Leaving a blocked app goes home. Leaving a blocked *site* (the button or Back) keeps you in
     * the browser: the screen goes at once and the extension steps the tab back, or closes it,
     * so the browser isn't stuck behind this screen. Going home instead would leave the blocked
     * tab in front, and this screen would be back the moment the browser was.
     */
    private fun leave() {
        val ui = uiState.value
        if (ui?.isSite == true) {
            SiteSightings.leave(ui.subject)
            return
        }
        context.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun openApp(pkg: String) {
        context.packageManager.getLaunchIntentForPackage(pkg)?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    private fun homeBrowser(): UseBrowser? = websites.extensionBrowser?.let { UseBrowser(it, appLabel(it)) }

    /** Puts the block screen up: a window already made is only shown, so nothing has to be made or composed first (S5). */
    private fun show() {
        val wasShown = window.shown
        if (!window.show()) return
        if (wasShown) return
        if (uiState.value?.pkg != null) takeAudio() // a blocked app's sound stops with it
        _visible.value = true
    }

    /**
     * Makes and adds the block screen's window, hidden, before it's first needed: the first showing
     * then only draws. Without "Display over other apps" it can't go up; the next showing asks again.
     */
    fun prepare() {
        window.prepare()
    }

    /** Stops for good with the service. */
    fun dispose() {
        releaseAudio()
        window.dispose()
        _visible.value = false
    }

    private fun makeScreen(): Screen {
        val owner = OverlayWindowOwner().also { it.onCreate() }
        val composeView = ComposeView(context).apply {
            setContent {
                BlockOverlayContent(
                    ui = uiState.value,
                    onToggleItem = { id, done -> onEvent(BlockEvent.ToggleChecklistItem(id, done)) },
                    onLogHabit = { id, amount -> onEvent(BlockEvent.LogHabit(id, amount)) },
                    onSetHabitEntry = { id, day, value -> onEvent(BlockEvent.SetHabitEntry(id, day, value)) },
                    // The focus timer is a screen of the app: it comes up over the blocked one.
                    onFocus = { id ->
                        onEvent(BlockEvent.StartFocus(id))
                        runCatching { context.startActivity(AppLinks.focusIntent(context, id)) }
                    },
                    onSetTimer = { blockId, running -> onEvent(BlockEvent.TimerControl(blockId, running)) },
                    onRedeem = { blockId -> onEvent(BlockEvent.Redeem(blockId)) },
                    onLeave = ::leave,
                    onOpenApp = ::openApp,
                    onBeginOverride = { ids -> onEvent(BlockEvent.BeginOverride(ids)) },
                    onClaimOverride = { phrase -> onEvent(BlockEvent.ClaimOverride(phrase)) },
                    onCancelOverride = { onEvent(BlockEvent.CancelOverride) },
                )
            }
        }
        // BACK can't dismiss an app's block screen; on a site's, it leaves the site.
        val host = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
                if (event.action == KeyEvent.ACTION_UP && uiState.value?.isSite == true) leave()
                return true
            }
        }
        // Compose resolves its recomposer from the *root* view attached to the window, so the
        // ViewTree owners must live on `host`, not on the child ComposeView.
        host.setViewTreeLifecycleOwner(owner)
        host.setViewTreeViewModelStoreOwner(owner)
        host.setViewTreeSavedStateRegistryOwner(owner)
        host.addView(composeView)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            // Focusable: Back comes here, and the override sentence is typed here.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.CENTER
            // Overlay windows don't inherit an Activity's manifest orientation lock; without
            // this, rotating the phone while a landscape-capable app (e.g. a game) is blocked
            // rotates the shield with it, scrambling the portrait-only layout.
            screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            // The override sentence is typed here: keep the field above the keyboard.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            // From Android 11 an overlay stops short of the status and navigation bars, leaving
            // the blocked app's own bar colour showing above the shield. Cover the whole screen;
            // the content keeps clear of the bars itself.
            // The status bar holds the camera cutout, which a window keeps out of by default.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        host.visibility = View.GONE // shown by ShieldWindow's reveal
        val screen = Screen(host, params, owner)
        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                screen.detached = true // some OEM task killers drop it: the next showing makes another
            }
        })
        return screen
    }

    /**
     * A window on top doesn't pause what's under it, so a blocked app would keep playing behind
     * the block screen. Holding the audio focus tells a media app to stop, and it stays stopped
     * when the focus is let go: a loss that isn't marked transient doesn't resume by itself.
     */
    private fun takeAudio() {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener { }
            .build()
        runCatching { manager.requestAudioFocus(request) }
        audioFocus = request
    }

    private fun releaseAudio() {
        val request = audioFocus ?: return
        audioFocus = null
        runCatching { context.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request) }
    }

    private fun hide() {
        if (!window.shown) return
        releaseAudio()
        keepScreenOn(false)
        window.hide()
        _visible.value = false
    }

    private fun appLabel(pkg: String): String = AppLabels.of(context, pkg)

    private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    /** The browser that has the extension, to go to instead (I49). */
    data class UseBrowser(val pkg: String, val label: String)

    data class OverlayUi(
        /** The blocked app's name, or the blocked site's host. */
        val subject: String,
        /** How many times today this has been tried, this time included. */
        val triesToday: Int = 0,
        /** [subject] is a browser without the extension (I49): use this one. */
        val useBrowser: UseBrowser? = null,
        /** [subject] is the extension's own browser, the extension silent there too long (I49). */
        val extensionOff: Boolean = false,
        /** The blocked app, for its icon; null for a site. */
        val pkg: String? = null,
        val isSite: Boolean,
        val block: Block?,
        /** Why [block] is blocking: a habit shows its habits, the rest say when it lets go (I44). */
        val reason: BlockReason? = null,
        /** Its habits are done and already opened it on their day (I24): nothing to do till the next. */
        val usedToday: Boolean = false,
        val items: List<ChecklistItem>,
        /** Set when [Block.trigger] has `habitPackages` — the app(s) the timer actually counts in. */
        val habitAppLabel: String? = null,
        /** Held by time: when it's back and why, what the screen leads with. Asked each second, since time alone moves it. */
        val back: (() -> BlockTexts.Back?)? = null,
        /** [BlockReason.WAIT]: what's left of the pause before the open (I53), for its ring. */
        val openWaitLeftMs: (() -> Long)? = null,
        /** TIMER / COMBINED: time counted so far, and whether the timer is running. */
        val timerMs: Long = 0,
        val timerRunning: Boolean = false,
        /** EARNED blocks: why "Open" wouldn't go through right now, if it wouldn't (I9). */
        val redeemRejection: RejectReason? = null,
        /** Today's unlock time so far, open unlocks at their full length (I9). */
        val unlockUsedTodayMs: Long = 0,
        val othersOpen: Boolean = false,
        val dailyCapMs: Long = 0,
        /** The blocks keeping this blocked; buying minutes is for all of them at once (I34). */
        val blockerIds: Set<String> = emptySet(),
        /** Why minutes can't be bought past it, or null if they can. */
        val overrideRejection: OverrideRejection? = null,
        /** A wait under way for these blocks: when it's over, on `elapsedRealtime`. */
        val overrideReadyAtElapsed: Long? = null,
        val overridePhrase: String = "",
        val overrideGrantMs: Long = 0,
        val overrideWaitMs: Long = 0,
        /** The block's habit items, by item id (contract I36). */
        val goals: Map<String, GoalInfo> = emptyMap(),
        /** The habit the focus timer is running for, if any (I37). */
        val focusHabitId: String? = null,
        /** HyperOS refuses screens started from here (Close going home, Focus): its pop-up switch is off. */
        val popupsOff: Boolean = false,
    )
}

/**
 * Minimal Lifecycle / ViewModelStore / SavedStateRegistry owner for a `ComposeView` attached
 * directly to a [WindowManager] window, which has no Activity to supply these. Scoped to one
 * overlay window: [onCreate] before the view is attached, [onStart] while it shows, [onStop]
 * while it's hidden, [onDestroy] when the window is removed.
 */
internal class OverlayWindowOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    fun onCreate() {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun onStart() {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /** Hidden: Compose stops drawing frames for it until [onStart] again. */
    fun onStop() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
