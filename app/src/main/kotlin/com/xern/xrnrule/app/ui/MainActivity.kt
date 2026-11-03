package com.xern.xrnrule.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.xern.xrnrule.app.XrnruleApp
import com.xern.xrnrule.app.block.BlockerForegroundService
import com.xern.xrnrule.app.data.AppContainer
import com.xern.xrnrule.app.data.AppLock
import com.xern.xrnrule.app.ui.theme.XrnruleTheme
import com.xern.xrnrule.domain.BlockEvent
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The app's one activity. A [FragmentActivity] because [BiometricPrompt] needs one: while
 * [AppLock] says so, it shows [LockScreen] instead of the app, and the prompt opens by itself
 * each time the app comes back to the front.
 */
class MainActivity : FragmentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private lateinit var lock: AppLock
    private lateinit var prompt: BiometricPrompt
    private val promptState: PromptState by viewModels()
    private var lockMessage by mutableStateOf<String?>(null)

    /** Set when the app comes back to the front locked: the prompt opens once it's resumed. */
    private var promptOnResume = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as XrnruleApp).container
        lock = container.appLock
        // Made here, before the activity starts: a prompt that outlives a recreation reports to it.
        prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                promptState.answered()
                lockMessage = null
                lock.unlock()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val stale = promptState.answered()
                lockMessage = when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON -> {
                        // Android closed the prompt when the app went out of sight, and says so only
                        // now that it's back: this visit gets a prompt of its own. Posted, so the
                        // library has let go of the old one first.
                        if (stale) window.decorView.post { authenticate() }
                        null
                    }
                    // Nothing left to check against: the lock can't work, so it steps aside.
                    BiometricPrompt.ERROR_NO_BIOMETRICS, BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL, BiometricPrompt.ERROR_HW_NOT_PRESENT -> {
                        lock.unlock()
                        null
                    }
                    else -> errString.toString()
                }
            }
            // onAuthenticationFailed (a finger it didn't know): the prompt stays up for another try.
        })
        // The process may have come up in the background, where Android refused this start;
        // being on screen is always an allowed moment, so retry here.
        BlockerForegroundService.start(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        AppLinks.handle(intent)
        setContent {
            XrnruleTheme {
                val locked by lock.showing.collectAsStateWithLifecycle()
                // The button can't be reached under a prompt, so a tap means none is up.
                if (locked) LockScreen(lockMessage, onUnlock = { authenticate(force = true) }) else XrnruleScreens(container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AppLinks.handle(intent)
    }

    override fun onStart() {
        super.onStart()
        lock.onShown()
        promptOnResume = lock.showing.value
        // Recents keeps no picture of the app while it's locked away.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(!lock.enabled.value)
    }

    override fun onResume() {
        super.onResume()
        (application as XrnruleApp).container.habits.request()
        (application as XrnruleApp).container.shared.request()
        if (promptOnResume) {
            promptOnResume = false
            authenticate()
        }
    }

    override fun onStop() {
        super.onStop()
        lock.onHidden()
        // From Android 11 the prompt is a system window, so stopping means the app was left with it
        // open; Android closes it and says so when the app is back (onAuthenticationError).
        // Before that, the PIN screen it opens stops the app too, and its answer is the real one.
        if (promptState.open && !isChangingConfigurations && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            promptState.reopen = true
        }
    }

    /** Opens the prompt unless one is open already, or, with [force], even if it seems to be. */
    private fun authenticate(force: Boolean = false) {
        // After onSaveInstanceState the library drops the request without a word.
        if ((promptState.open && !force) || !lock.showing.value || supportFragmentManager.isStateSaved) return
        promptState.open = true
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock xrnrule")
                .setAllowedAuthenticators(lock.authenticators())
                .build(),
        )
    }
}

/**
 * Whether a prompt is open. A ViewModel, as the biometric library keeps its own state: it lasts
 * through a recreation, when the prompt still answers, and goes with the activity, when it never
 * will. (Kept in the process instead, a prompt lost with its activity would leave the next one
 * waiting for an answer that never comes.)
 */
class PromptState : ViewModel() {
    /** A prompt was opened and hasn't answered yet. */
    var open = false

    /** The app was left while it was open: its answer, when it comes, is stale. */
    var reopen = false

    /** The prompt answered. True when that answer was a stale one. */
    fun answered(): Boolean {
        val stale = reopen
        open = false
        reopen = false
        return stale
    }
}

/** The tabs and the screens under them: the app itself, once past the lock. */
@Composable
private fun XrnruleScreens(container: AppContainer) {
    val store = container.store
    val hardcoreGuard = container.hardcoreGuard
    val world by store.world.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val current by nav.currentBackStackEntryAsState()
    val route = current?.destination?.route
    val settingsDeps = remember {
        SettingsDeps(
            store, hardcoreGuard, container.websites, container.loop, container.clockGuard, container.shared,
            container.habits, container.updates, container.appLock,
        )
    }

    // A link from the block screen or the notification: open the focus timer once past the lock.
    val focusLink by AppLinks.focus.collectAsStateWithLifecycle()
    LaunchedEffect(focusLink) {
        focusLink?.let { id ->
            AppLinks.focus.value = null
            nav.navigate("focus/$id") { launchSingleTop = true }
        }
    }
    val openFocus: (String, Boolean) -> Unit = { id, start ->
        if (start) store.dispatch(BlockEvent.StartFocus(id))
        nav.navigate("focus/$id") { launchSingleTop = true }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // Hide the tab bar on full-screen sub-pages (new block, edit, settings pages).
            if (route == null || route in Tabs.map { it.route }) {
                Column {
                    Hairline()
                    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                        Tabs.forEach { tab ->
                            val selected = current?.destination?.hierarchy?.any { it.route == tab.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    nav.navigate(tab.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                                label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    indicatorColor = Color.Transparent,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Tab.Blocks.route,
            // The pages draw no bars of their own: what the Scaffold padded for is used up here.
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
        ) {
            composable(Tab.Blocks.route) {
                BlocksScreen(
                    world,
                    store,
                    guard = hardcoreGuard,
                    onOpenBlock = { nav.navigate("block/$it") },
                    onCreateBlock = { nav.navigate("createBlock") },
                    onFocus = openFocus,
                )
            }
            composable("focus/{habitId}") { entry ->
                FocusScreen(store, entry.arguments?.getString("habitId").orEmpty(), onClose = { nav.popBackStack() })
            }
            composable(Tab.Insights.route) {
                InsightsScreen(world, container.websites, container.siteUsage, container.telemetry)
            }
            composable(Tab.Settings.route) {
                SettingsScreen(world, settingsDeps, onOpen = { nav.navigate("settings/${it.name}") })
            }
            composable("settings/{page}") { entry ->
                val page = SettingsPage.entries.firstOrNull { it.name == entry.arguments?.getString("page") }
                if (page == null) {
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    SettingsPageScreen(page, world, settingsDeps, onBack = { nav.popBackStack() })
                }
            }
            composable("createBlock") {
                NewBlockScreen(
                    store = store,
                    websites = container.websites,
                    onDone = { nav.popBackStack() },
                )
            }
            composable("block/{id}") { entry ->
                EditBlockScreen(
                    store = store,
                    websites = container.websites,
                    guard = hardcoreGuard,
                    blockId = entry.arguments?.getString("id").orEmpty(),
                    onDone = { nav.popBackStack() },
                )
            }
        }
    }
}

/** Links into a screen of the app from outside it: the block screen, the notification. */
object AppLinks {
    private const val EXTRA_FOCUS = "com.xern.xrnrule.app.FOCUS"

    /** A habit whose focus timer should open, until the screens pick it up. */
    val focus = MutableStateFlow<String?>(null)

    fun focusIntent(context: Context, habitId: String): Intent =
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_FOCUS, habitId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun handle(intent: Intent?) {
        intent?.getStringExtra(EXTRA_FOCUS)?.let { focus.value = it }
    }
}

sealed class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    data object Blocks : Tab("blocks", "Blocks", Icons.Outlined.Shield, Icons.Filled.Shield)
    data object Insights : Tab("insights", "Insights", Icons.Outlined.BarChart, Icons.Filled.BarChart)
    data object Settings : Tab("settings", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings)
}

private val Tabs = listOf(Tab.Blocks, Tab.Insights, Tab.Settings)
