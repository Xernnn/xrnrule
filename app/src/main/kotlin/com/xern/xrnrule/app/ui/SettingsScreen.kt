package com.xern.xrnrule.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockClock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xern.xrnrule.app.R
import com.xern.xrnrule.app.data.AppLock
import com.xern.xrnrule.app.data.AppPermission
import com.xern.xrnrule.app.data.BlocksBackups
import com.xern.xrnrule.app.data.ClockGuard
import com.xern.xrnrule.app.data.HabitSync
import com.xern.xrnrule.app.data.OemAutostart
import com.xern.xrnrule.app.data.SharedSync
import com.xern.xrnrule.app.data.SharedSyncStatus
import com.xern.xrnrule.app.data.WorldStore
import com.xern.xrnrule.app.data.isGranted
import com.xern.xrnrule.app.data.settingsIntent
import com.xern.xrnrule.app.hardcore.HardcoreGuard
import com.xern.xrnrule.app.loop.LoopHabits
import com.xern.xrnrule.app.loop.LoopStatus
import com.xern.xrnrule.app.ui.theme.Appearance
import com.xern.xrnrule.app.ui.theme.Brand
import com.xern.xrnrule.app.ui.theme.LocalKinds
import com.xern.xrnrule.app.ui.theme.ThemeMode
import com.xern.xrnrule.app.update.AppUpdates
import com.xern.xrnrule.app.web.WebsiteMonitor
import com.xern.xrnrule.app.web.WebsiteTracking
import com.xern.xrnrule.app.web.outOfDate
import com.xern.xrnrule.app.web.tracking
import com.xern.xrnrule.domain.BlockEdits
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.Defaults
import com.xern.xrnrule.domain.EnforcementTier
import com.xern.xrnrule.domain.Habit
import com.xern.xrnrule.domain.HabitKind
import com.xern.xrnrule.domain.World
import com.xern.xrnrule.domain.epochDay
import com.xern.xrnrule.domain.withHabit
import com.xern.xrnrule.domain.withoutHabit
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The pages under Settings, each its own route (`settings/<name>`). */
enum class SettingsPage(val title: String) {
    SYNC("Home server"),
    HABITS("Habits"),
    UNLOCKS("Unlocks"),
    SECURITY("Security & privacy"),
    APPEARANCE("Appearance"),
    WEBSITES("Browser extension"),
    UPDATES("Updates"),
    BACKUP("Backup"),
    ADVANCED("Advanced"),
}

/** Everything Settings reads, so the list and its pages take one argument. */
class SettingsDeps(
    val store: WorldStore,
    val guard: HardcoreGuard,
    val websites: WebsiteMonitor,
    val loop: LoopHabits,
    val clockGuard: ClockGuard,
    val shared: SharedSync,
    val habits: HabitSync,
    val updates: AppUpdates,
    val appLock: AppLock,
)

// =====================================================================================
//  The list
// =====================================================================================

@Composable
fun SettingsScreen(world: World, deps: SettingsDeps, onOpen: (SettingsPage) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        PageTitle("settings")
        Column(Modifier.padding(ScreenPadding)) { SettingsList(world, deps, onOpen) }
    }
}

@Composable
private fun SettingsList(world: World, deps: SettingsDeps, onOpen: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    val resume = rememberResumeSignal()
    val granted = remember(resume) { AppPermission.entries.associateWith { it.isGranted(context) } }
    val automaticTime = remember(resume) { deps.clockGuard.automaticTime() }
    val clockSkew = remember(resume) { deps.clockGuard.check(); deps.clockGuard.skewMs }
    val websiteTracking = remember(resume) { deps.websites.tracking(context) }
    val loopStatus = remember(resume) { deps.loop.status() }
    val loopUnreachable by deps.loop.unreachable.collectAsState()
    run {
        SyncSummary(deps.shared) { onOpen(SettingsPage.SYNC) }

        SectionLabel("Permissions")
        GroupCard {
            AppPermission.entries.forEachIndexed { i, perm ->
                PermissionRow(perm.label, permissionIcon(perm), granted[perm] == true, first = i == 0) {
                    runCatching { context.startActivity(perm.settingsIntent(context)) }
                }
            }
            if (OemAutostart.isKnownOem()) {
                PermissionRow("Autostart (${Build.MANUFACTURER})", Icons.Outlined.RocketLaunch, granted = null) { OemAutostart.open(context) }
            }
            PermissionRow("Automatic date & time", Icons.Outlined.AccessTime, granted = automaticTime) {
                runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_DATE_SETTINGS)) }
            }
        }
        if (clockSkew != 0L) {
            FootNote(
                "The phone's clock was set ${formatDuration(kotlin.math.abs(clockSkew))} " +
                    "${if (clockSkew > 0) "ahead" else "back"} by hand. Blocks keep using the real time.",
            )
        }

        SectionLabel("Integrations")
        GroupCard {
            val kinds = LocalKinds.current
            rememberTicker(30_000)
            GroupRow(
                title = "xrnloops",
                icon = Icons.Outlined.TaskAlt,
                iconTint = kinds.loop.color,
                subtitle = when {
                    loopStatus == LoopStatus.NOT_INSTALLED -> "Not installed: it's where habits are kept and ticked"
                    loopStatus == LoopStatus.NO_ACCESS -> "Signed with another key: it can't tell xrnrule about ticks"
                    loopUnreachable -> "Not answering: turn on its Autostart in the phone's settings"
                    else -> "Same habits, through your home server"
                },
                subtitleColor = if (loopStatus != LoopStatus.NO_ACCESS && !loopUnreachable) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
                first = true,
                onClick = { deps.loop.launchIntent()?.let { runCatching { context.startActivity(it) } } },
            )
            if (deps.websites.isSupported()) {
                GroupRow(
                    title = "Browser extension",
                    icon = Icons.Outlined.Extension,
                    iconTint = kinds.appTime.color,
                    subtitle = when {
                        websiteTracking == WebsiteTracking.OFF -> "Not set up: sites aren't blocked"
                        websiteTracking == WebsiteTracking.NOT_RUNNING -> "Not answering"
                        deps.websites.outOfDate -> "Out of date: ${deps.websites.version}, ${deps.websites.latestVersion} is out"
                        else -> "Blocking sites in your browser"
                    },
                    subtitleColor = if (websiteTracking == WebsiteTracking.ON && !deps.websites.outOfDate) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                    onClick = { onOpen(SettingsPage.WEBSITES) },
                )
            }
        }

        Gap(20)
        GroupCard {
            GroupRow(title = "Advanced", icon = Icons.Outlined.Tune, first = true, onClick = { onOpen(SettingsPage.ADVANCED) })
        }
        Gap(36)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(painterResource(R.drawable.ic_stat_xrnrule), contentDescription = null, tint = Brand, modifier = Modifier.size(18.dp))
                WGap(8)
                Wordmark(size = 20)
            }
            Gap(8)
            Text(
                appVersion(context),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Gap(24)
    }
}

private fun permissionIcon(perm: AppPermission): ImageVector = when (perm) {
    AppPermission.USAGE_ACCESS -> Icons.Outlined.QueryStats
    AppPermission.OVERLAY -> Icons.Outlined.Layers
    AppPermission.NOTIFICATIONS -> Icons.Outlined.Notifications
    AppPermission.BATTERY_OPTIMIZATION -> Icons.Outlined.BatteryChargingFull
}

private fun appVersion(context: android.content.Context): String = runCatching {
    "Version " + context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrDefault("")

/** The home-server card at the top, where Habits First keeps its account. */
@Composable
private fun SyncSummary(shared: SharedSync, onClick: () -> Unit) {
    val status by shared.status.collectAsStateWithLifecycle()
    val configured = shared.settings.isConfigured
    val green = LocalKinds.current.habit
    rememberTicker(30_000)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CardShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
            if (configured) Icons.Outlined.CloudSync else Icons.Outlined.CloudOff,
            contentDescription = null,
            tint = if (configured) green.color else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        WGap(18)
        Column(Modifier.weight(1f)) {
            Text(if (configured) "Home server" else "Sync is off", style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp))
            Text(
                when {
                    !configured -> "Share blocks and unlocks with your laptop"
                    status == null -> shared.settings.url.removePrefix("https://")
                    status!!.ok -> "Synced ${formatAgo(status!!.atWallMs)}"
                    else -> status!!.message
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (status?.ok == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        androidx.compose.material3.Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A permission: a green tick once granted, else an "Allow" pill that opens the system screen. */
@Composable
private fun PermissionRow(title: String, icon: ImageVector, granted: Boolean?, first: Boolean = false, onClick: () -> Unit) {
    GroupRow(
        title = title,
        icon = icon,
        first = first,
        chevron = false,
        onClick = onClick,
        trailing = {
            if (granted == true) {
                androidx.compose.material3.Icon(Icons.Filled.CheckCircle, contentDescription = "Allowed", tint = MaterialTheme.colorScheme.primary)
            } else {
                val red = MaterialTheme.colorScheme.error
                Text(
                    if (granted == null) "Open" else "Allow",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (granted == null) MaterialTheme.colorScheme.onSurface else red,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (granted == null) MaterialTheme.colorScheme.surfaceVariant else red.copy(alpha = 0.16f))
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                )
            }
        },
    )
}

/** What Habits First keeps under Advanced: habits, unlocks, security, look, updates, backup. */
@Composable
private fun AdvancedPage(world: World, deps: SettingsDeps, onOpen: (SettingsPage) -> Unit) {
    val theme by Appearance.themeMode.collectAsState()
    val lockOn by deps.appLock.enabled.collectAsStateWithLifecycle()
    Gap(8)
    GroupCard {
        GroupRow(
            title = "Habits",
            icon = Icons.Outlined.TaskAlt,
            subtitle = when (world.habits.size) {
                0 -> "None yet: make one when a block asks for it"
                1 -> "1 habit, shared with your laptop"
                else -> "${world.habits.size} habits, shared with your laptop"
            },
            first = true,
            onClick = { onOpen(SettingsPage.HABITS) },
        )
        GroupRow(
            title = "Unlocks",
            icon = Icons.Outlined.LockOpen,
            subtitle = "Up to ${formatDuration(world.settings.dailyCapMs)} a day" +
                if (world.settings.overridesPerDay > 0) " · buying minutes on" else "",
            onClick = { onOpen(SettingsPage.UNLOCKS) },
        )
        GroupRow(
            title = "Security & privacy",
            icon = Icons.Outlined.Security,
            subtitle = listOfNotNull(
                if (lockOn) "App lock on" else "App lock off",
                if (deps.guard.isSupported() && deps.guard.hasPassword()) "hardcore password set" else null,
            ).joinToString(" · "),
            onClick = { onOpen(SettingsPage.SECURITY) },
        )
        GroupRow(
            title = "Appearance",
            icon = Icons.Outlined.DarkMode,
            subtitle = theme.label,
            onClick = { onOpen(SettingsPage.APPEARANCE) },
        )
        if (deps.updates.isSupported) {
            GroupRow(
                title = "Updates",
                icon = Icons.Outlined.SystemUpdate,
                subtitle = "Version ${deps.updates.installedVersion}",
                onClick = { onOpen(SettingsPage.UPDATES) },
            )
        }
        GroupRow(
            title = "Backup",
            icon = Icons.Outlined.SaveAlt,
            subtitle = "Export or import your blocks",
            onClick = { onOpen(SettingsPage.BACKUP) },
        )
    }
}

/** "just now", "5 min ago", "2 h ago". */
fun formatAgo(wallMs: Long): String {
    val ago = System.currentTimeMillis() - wallMs
    return if (ago < 60_000) "just now" else "${formatDuration(ago)} ago"
}

// =====================================================================================
//  The pages
// =====================================================================================

@Composable
fun SettingsPageScreen(page: SettingsPage, world: World, deps: SettingsDeps, onBack: () -> Unit, onOpen: (SettingsPage) -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        // Habits First's sub-page bar: a bare back arrow and the title beside it.
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                androidx.compose.material3.Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            WGap(4)
            Text(
                page.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal, fontSize = 18.sp),
                modifier = Modifier.weight(1f),
            )
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            when (page) {
                SettingsPage.SYNC -> SyncPage(deps.shared, deps.habits)
                SettingsPage.HABITS -> HabitsPage(world, deps)
                SettingsPage.UNLOCKS -> UnlocksPage(world, deps)
                SettingsPage.SECURITY -> SecurityPage(deps)
                SettingsPage.APPEARANCE -> AppearancePage()
                SettingsPage.WEBSITES -> WebsitesPage(deps)
                SettingsPage.UPDATES -> UpdatesPage(deps.updates)
                SettingsPage.BACKUP -> BackupPage(world, deps.store)
                SettingsPage.ADVANCED -> AdvancedPage(world, deps, onOpen)
            }
            Gap(32)
        }
    }
}

/** Every habit, today's amount of each, and their editor (contract I35/I36). */
@Composable
private fun HabitsPage(world: World, deps: SettingsDeps) {
    val store = deps.store
    var editing by remember { mutableStateOf<Habit?>(null) }
    var making by remember { mutableStateOf(false) }
    var loosen by remember { mutableStateOf<LoosenRequest?>(null) }
    var confirmDelete by remember { mutableStateOf<Habit?>(null) }
    if (world.focus?.running == true) rememberTicker()

    PageIntro(
        "Blocks ask for these. They're the same habits as in xrnloops and the web app, kept on " +
            "your home server. A day's share of a goal opens each block that asks for it once.",
    )
    Gap(16)
    if (world.habits.isNotEmpty()) {
        GroupCard {
            world.habits.filterNot { it.archived }.sortedBy { it.position }.forEachIndexed { i, habit ->
                if (i > 0) Hairline(Modifier.padding(start = 72.dp))
                val day = world.habitDay(store.clock)
                val today = if (habit.numerical) store.engine.habitAmount(world, habit.id, day, store.clock) else store.engine.checkValue(world, habit, day)
                val used = world.blocksUsing(habit.id)
                Row(
                    Modifier.fillMaxWidth().clickable { editing = habit }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HabitTile(habit)
                    WGap(14)
                    Column(Modifier.weight(1f)) {
                        Text(habit.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            listOfNotNull(
                                habitGoalText(habit),
                                "today " + dayEntryText(habit, today),
                                when (used.size) { 0 -> null; 1 -> used.first().name; else -> "${used.size} blocks" },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Gap(14)
    }
    OutlinePill("New habit", onClick = { making = true }, icon = Icons.Filled.Add)

    fun guarded(habit: Habit, title: String, detail: String, apply: () -> Unit) {
        val blocks = world.blocksUsing(habit.id)
        if (blocks.any { BlockEdits.guardNeeded(store.engine, world, it, store.clock) }) {
            loosen = LoosenRequest(title, detail, hardcore = blocks.any { it.enforcementTier == EnforcementTier.HARDCORE }, blockIds = blocks.map { it.id }, apply = apply)
        } else {
            apply()
        }
    }

    if (making) {
        HabitEditorSheet(
            existing = null,
            kinds = HabitKind.entries,
            onSave = { h -> store.edit { it.withHabit(h, store.clock.nowWallMs()) }; making = false },
            onDismiss = { making = false },
        )
    }
    editing?.let { old ->
        HabitEditorSheet(
            existing = old,
            kinds = HabitKind.entries,
            onSave = { h ->
                editing = null
                val apply = { store.edit { it.withHabit(h, store.clock.nowWallMs()) } }
                if (BlockEdits.habitLoosens(old, h)) {
                    guarded(old, "Make ${old.name} easier?", "Blocks that ask for it open with less.") { apply() }
                } else {
                    apply()
                }
            },
            onDismiss = { editing = null },
            onDelete = {
                editing = null
                confirmDelete = old
            },
        )
    }
    loosen?.let { LoosenGuardDialog(it, deps.guard, deps.store, onDismiss = { loosen = null }) }
    confirmDelete?.let { habit ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${habit.name}?") },
            text = {
                val used = world.blocksUsing(habit.id)
                Text(
                    "It goes from xrnloops and the web app too, with its history." +
                        if (used.isEmpty()) "" else " ${andList(used.map { it.name })} can't open until you edit ${if (used.size == 1) "it" else "them"}.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    store.edit { it.withoutHabit(habit.id, store.clock.nowWallMs()) }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PageIntro(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StatusLine(text: String, ok: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/**
 * The home-server link that shares unlocks, breaks and blocks with the laptop (contract §2.4), and
 * habits with xrnloops, the web app and the laptop (§2.5): one login for both.
 */
@Composable
private fun SyncPage(shared: SharedSync, habits: HabitSync) {
    val settings = shared.settings
    val status by shared.status.collectAsStateWithLifecycle()
    val habitStatus by habits.status.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(!settings.isConfigured) }
    var url by remember { mutableStateOf(settings.url) }
    var user by remember { mutableStateOf(settings.user.ifEmpty { "sonn" }) }
    var password by remember { mutableStateOf(settings.password) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    PageIntro(
        "Shares blocks, unlocks, breaks and lockdowns with xrnrule on your laptop, and your habits with xrnloops and the " +
            "web app, through your home server. Off until you set it; nothing else leaves the phone.",
    )
    Gap(16)
    if (editing) {
        FieldLabel("Server address")
        FieldBox(url, { url = it }, "https://your-server/xrnrule", keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri))
        FieldLabel("User")
        FieldBox(user, { user = it }, "User")
        FieldLabel("Password")
        FieldBox(
            password,
            { password = it },
            "Password",
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Password),
            visual = PasswordVisualTransformation(),
        )
        Gap(20)
        PillButton(
            "Save and sync",
            enabled = url.trim().startsWith("http") && user.isNotBlank() && password.isNotEmpty() && !busy,
            onClick = {
                settings.save(url, user, password)
                editing = false
                busy = true
                scope.launch { shared.syncNow(); habits.syncNow(); busy = false }
            },
        )
        if (settings.isConfigured) {
            Gap(8)
            OutlinePill("Cancel", onClick = { editing = false })
        }
    } else {
        rememberTicker(30_000)
        GroupCard {
            GroupRow(title = "Server", subtitle = settings.url, first = true, chevron = false)
            GroupRow(title = "Habits", subtitle = habits.url, chevron = false)
            GroupRow(title = "User", subtitle = settings.user, chevron = false)
        }
        fun line(what: String, s: SharedSyncStatus?) = what + ": " + when {
            busy -> "syncing…"
            s == null -> "not synced yet"
            s.ok -> "synced ${formatAgo(s.atWallMs)}"
            else -> "${s.message} (${formatAgo(s.atWallMs)})"
        }
        StatusLine(line("Blocks", status), ok = busy || status?.ok != false)
        StatusLine(line("Habits", habitStatus), ok = busy || habitStatus?.ok != false)
        PillButton("Sync now", enabled = !busy, onClick = { busy = true; scope.launch { shared.syncNow(); habits.syncNow(); busy = false } })
        Gap(8)
        OutlinePill("Change server or login", onClick = { editing = true })
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp),
    )
}

private val DAILY_CAP_CHOICES_MIN = listOf(15L, 30L, 60L, 90L, 120L, 180L)

/** The daily unlock limit (I9) and buying minutes (I34), both shared with the laptop. */
@Composable
private fun UnlocksPage(world: World, deps: SettingsDeps) {
    val store = deps.store
    var loosen by remember { mutableStateOf<LoosenRequest?>(null) }
    val cap = world.settings.dailyCapMs

    SectionLabel("Daily unlock limit")
    PageIntro("The most time all unlocks together can give you in a day.")
    Gap(10)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DAILY_CAP_CHOICES_MIN.forEach { minutes ->
            val ms = minutes * 60_000
            ChoicePill(formatDuration(ms), cap == ms) {
                val apply = { store.edit { w -> w.copy(settings = w.settings.copy(dailyCapMs = ms)) } }
                if (ms > cap && world.blocks.any { BlockEdits.guardNeeded(store.engine, world, it, store.clock) }) {
                    loosen = LoosenRequest(
                        title = "Raise the limit to ${formatDuration(ms)}?",
                        detail = "Unlocks could give you more time each day.",
                        hardcore = deps.guard.hasPassword() && world.blocks.any { it.enforcementTier == EnforcementTier.HARDCORE },
                        apply = { apply() },
                    )
                } else {
                    apply()
                }
            }
        }
    }
    val tooLong = world.blocks.filter { it.mode == BlockMode.HABIT && it.reward.unlockDurationMs > cap }
    if (tooLong.isNotEmpty()) {
        StatusLine("Can't open at this limit (their unlock is longer): ${tooLong.joinToString { it.name }}", ok = false)
    }

    SectionLabel("Buying minutes")
    BuyingMinutes(world, onSave = { new ->
        val save = {
            store.edit { w ->
                w.copy(
                    settings = w.settings.copy(
                        overridePhrase = new.overridePhrase,
                        overrideWaitMs = new.overrideWaitMs,
                        overrideGrantMs = new.overrideGrantMs,
                        overridesPerDay = new.overridesPerDay,
                        showClock = new.showClock,
                    ),
                )
            }
        }
        if (BlockEdits.settingsLoosen(world.settings, new)) {
            loosen = LoosenRequest("Save these settings?", "They make buying minutes past a block easier.", hardcore = false, apply = save)
        } else {
            save()
        }
    })

    loosen?.let { request -> LoosenGuardDialog(request, deps.guard, deps.store, onDismiss = { loosen = null }) }
}

/** Buying minutes past a daily limit or schedule (contract I34), and the browser's corner clock. */
@Composable
private fun BuyingMinutes(world: World, onSave: (com.xern.xrnrule.domain.Settings) -> Unit) {
    val settings = world.settings
    var editing by remember { mutableStateOf(false) }
    if (!editing) {
        GroupCard {
            GroupRow(
                title = if (settings.overridesPerDay > 0) "${settings.overridesPerDay} a day per block" else "Off",
                icon = Icons.Outlined.Timer,
                subtitle = if (settings.overridesPerDay > 0) {
                    "On a daily limit or a schedule: wait ${settings.overrideWaitMs / 1000} s, type “${settings.overridePhrase}”, " +
                        "get ${formatDuration(settings.overrideGrantMs)}. Phone and laptop together."
                } else {
                    "Nothing can be bought past a block."
                },
                first = true,
                chevron = false,
            )
            GroupRow(
                title = "Corner clock on limited sites",
                icon = Icons.Outlined.AccessTime,
                subtitle = if (settings.showClock) "On" else "Off",
                chevron = false,
            )
        }
        Gap(12)
        OutlinePill("Change", onClick = { editing = true })
        return
    }
    var phrase by remember { mutableStateOf(settings.overridePhrase) }
    var waitSec by remember { mutableStateOf((settings.overrideWaitMs / 1000).toString()) }
    var grantMin by remember { mutableStateOf((settings.overrideGrantMs / 60_000).toString()) }
    var perDay by remember { mutableStateOf(settings.overridesPerDay.toString()) }
    var showClock by remember { mutableStateOf(settings.showClock) }
    val number = KeyboardOptions(keyboardType = KeyboardType.Number)
    FieldLabel("Sentence to type (accents, case and punctuation don't matter)")
    FieldBox(phrase, { phrase = it }, "Sentence")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            FieldLabel("Wait (s)")
            FieldBox(waitSec, { waitSec = it.filter(Char::isDigit) }, "45", keyboard = number)
        }
        Column(Modifier.weight(1f)) {
            FieldLabel("Minutes")
            FieldBox(grantMin, { grantMin = it.filter(Char::isDigit) }, "10", keyboard = number)
        }
        Column(Modifier.weight(1f)) {
            FieldLabel("A day")
            FieldBox(perDay, { perDay = it.filter(Char::isDigit) }, "2", keyboard = number)
        }
    }
    Gap(12)
    GroupCard {
        SwitchRow("Corner clock on limited sites", showClock, { showClock = it }, icon = Icons.Outlined.AccessTime, first = true)
    }
    Gap(16)
    PillButton("Save", onClick = {
        onSave(
            settings.copy(
                overridePhrase = phrase.trim(),
                overrideWaitMs = (waitSec.toLongOrNull() ?: 0).coerceIn(0, 3600) * 1000,
                overrideGrantMs = (grantMin.toLongOrNull() ?: 1).coerceIn(1, 120) * 60_000,
                overridesPerDay = (perDay.toIntOrNull() ?: 0).coerceIn(0, 20),
                showClock = showClock,
            ),
        )
        editing = false
    })
    Gap(8)
    OutlinePill("Cancel", onClick = { editing = false })
}

/** The app lock (fingerprint) and hardcore mode (password, anti-uninstall). */
@Composable
private fun SecurityPage(deps: SettingsDeps) {
    val context = LocalContext.current
    val resume = rememberResumeSignal()
    val lock = deps.appLock
    val guard = deps.guard
    val lockOn by lock.enabled.collectAsStateWithLifecycle()
    val method = remember(resume) { lock.method() }
    val deviceAdminActive = remember(resume) { guard.isDeviceAdminActive() }
    var hasPassword by remember { mutableStateOf(guard.hasPassword()) }

    SectionLabel("App lock")
    GroupCard {
        SwitchRow(
            "Ask for my fingerprint to open xrnrule",
            lockOn,
            { lock.setEnabled(it) },
            icon = Icons.Outlined.Fingerprint,
            first = true,
        )
    }
    FootNote(
        when (method) {
            AppLock.Method.FINGERPRINT ->
                "Asked when you open the app, and again after 30 seconds away. The phone's screen-lock PIN works too. " +
                    "The block screen isn't locked: it's how you get through a block."
            AppLock.Method.PIN ->
                "No fingerprint is set up, so the phone's screen-lock PIN is asked instead. Add one in Android's settings."
            AppLock.Method.NONE ->
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) "Needs Android 9 or later."
                else "The phone has no screen lock, so there's nothing to check: set one in Android's settings first."
        },
    )

    LockedBlocks(deps.store)

    if (!guard.isSupported()) return
    SectionLabel("Hardcore mode")
    PageIntro(
        "A hardcore block can only be loosened with this password, and while one is on, Android's Settings and " +
            "uninstalling xrnrule ask for it too. It can be changed, never removed.",
    )
    Gap(12)
    when {
        !hasPassword -> PasswordForm(askCurrent = false, submitLabel = "Set password") { _, new ->
            guard.setPassword(new)
            hasPassword = true
            null
        }
        else -> {
            GroupCard {
                GroupRow(
                    title = "Anti-uninstall",
                    icon = Icons.Outlined.Security,
                    subtitle = if (deviceAdminActive) "On" else "Off: tap to turn it on",
                    first = true,
                    chevron = !deviceAdminActive,
                    onClick = if (deviceAdminActive) null else ({ (context as? Activity)?.let(guard::requestDeviceAdmin) }),
                )
            }
            Gap(12)
            var changing by remember { mutableStateOf(false) }
            if (changing) {
                PasswordForm(askCurrent = true, submitLabel = "Change password") { current, new ->
                    if (guard.changePassword(current, new)) {
                        changing = false
                        null
                    } else {
                        "The current password is wrong."
                    }
                }
            } else {
                OutlinePill("Change password", onClick = { changing = true })
            }
        }
    }
}

/** I39/I40: which blocks are locked, and how many emergency unlocks are left. */
@Composable
private fun LockedBlocks(store: WorldStore) {
    val world by store.world.collectAsStateWithLifecycle()
    val now = store.clock.nowWallMs()
    val locked = world.blocks.filter { BlockEdits.locked(it, now) }.sortedBy { it.lock!!.untilWall }
    SectionLabel("Locked blocks")
    GroupCard {
        GroupRow(
            title = "Emergency unlocks",
            icon = Icons.Outlined.LockClock,
            subtitle = "${BlockEdits.emergencyLeft(world)} of ${Defaults.EMERGENCY_UNLOCKS} left, on every device together",
            first = true,
        )
        locked.forEach { b -> GroupRow(title = b.name, subtitle = "Locked until ${formatLockEnd(b.lock!!.untilWall, now)}", icon = Icons.Outlined.Lock) }
    }
    FootNote(
        "Lock a block from its edit page. Until the lock ends nothing that makes it easier to get past can change, " +
            "not even with the wait or the password; an emergency unlock ends a lock early. Used ones never come back.",
    )
}

/**
 * A new password typed twice, so a typo can't lock Settings for good; plus the current one when
 * changing it. [onSubmit] returns an error to show, or null when done.
 */
@Composable
private fun PasswordForm(askCurrent: Boolean, submitLabel: String, onSubmit: (current: String, new: String) -> String?) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val mismatch = repeat.isNotEmpty() && new != repeat
    val hidden = PasswordVisualTransformation()
    val keyboard = KeyboardOptions(keyboardType = KeyboardType.Password)
    if (askCurrent) {
        FieldLabel("Current password")
        FieldBox(current, { current = it; error = null }, "Current password", keyboard = keyboard, visual = hidden)
    }
    FieldLabel("New password")
    FieldBox(new, { new = it; error = null }, "New password", keyboard = keyboard, visual = hidden)
    FieldLabel("New password again")
    FieldBox(repeat, { repeat = it; error = null }, "New password again", keyboard = keyboard, visual = hidden)
    if (mismatch) StatusLine("The two don't match.", ok = false)
    error?.let { StatusLine(it, ok = false) }
    Gap(16)
    PillButton(
        submitLabel,
        enabled = new.isNotBlank() && new == repeat && (!askCurrent || current.isNotEmpty()),
        onClick = { error = onSubmit(current, new) },
    )
}

@Composable
private fun AppearancePage() {
    val theme by Appearance.themeMode.collectAsState()
    SectionLabel("Theme")
    SegmentSwitch(ThemeMode.entries.map { it.label }, theme.ordinal, onSelect = { Appearance.setThemeMode(ThemeMode.entries[it]) })
    FootNote("Auto follows the phone's dark mode. The block screen follows this too.")
}

@Composable
private fun WebsitesPage(deps: SettingsDeps) {
    val context = LocalContext.current
    val url = deps.shared.settings.url.ifEmpty { "<your server>/xrnrule" }
    PageIntro(
        "Sites are blocked by the xrnrule extension in Quetta; nothing reads your screen. Install it once by opening " +
            "$url/update/xrnrule.crx in Quetta; it keeps itself up to date from there.",
    )
    deps.websites.version?.let { version ->
        // Quetta checks the home server every few hours; its options page has "Check now".
        if (deps.websites.outOfDate) {
            StatusLine("Version $version, out of date: ${deps.websites.latestVersion} is out. It updates itself within a few hours, or now from its options page (Check now).", ok = false)
        } else {
            StatusLine("Version $version", ok = true)
        }
    }
    Gap(16)
    WebsiteTrackingWarning(deps.websites)
    Gap(8)
    PillButton("Open the browser", onClick = { runCatching { context.startActivity(deps.websites.settingsIntent()) } })
}

/** New versions of the app from the home server (sideload builds; see AppUpdates). */
@Composable
private fun UpdatesPage(updates: AppUpdates) {
    val context = LocalContext.current
    val resume = rememberResumeSignal()
    val canInstall = remember(resume) { updates.canInstall() }
    val status by updates.status.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    PageIntro(
        "Version ${updates.installedVersion}. New versions come from your home server and install themselves " +
            "the next time the screen goes off.",
    )
    Gap(16)
    GroupCard {
        PermissionRow("Allow installing updates", Icons.Outlined.SystemUpdate, canInstall, first = true) {
            runCatching { context.startActivity(updates.installPermissionIntent()) }
        }
    }
    status?.let { s ->
        rememberTicker(30_000)
        StatusLine("${s.message} (${formatAgo(s.atWallMs)})", ok = s.ok)
    }
    Gap(12)
    PillButton(if (busy) "Checking…" else "Check now", enabled = !busy, onClick = { busy = true; scope.launch { updates.checkNow(); busy = false } })
}

@Composable
private fun BackupPage(world: World, store: WorldStore) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = BlocksBackups.export(store.world.value)
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.encodeToByteArray()) } }.isSuccess
            }
            message = if (ok) "Exported ${store.world.value.blocks.size} blocks." else "Couldn't write that file."
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            }
            val parsed = text?.let { runCatching { BlocksBackups.import(store.world.value, it) }.getOrNull() }
            message = if (parsed == null) {
                "That file isn't an xrnrule backup."
            } else {
                store.edit { w -> BlocksBackups.import(w, text).first }
                if (parsed.second == 0) "Nothing new: every block in it is already here." else "Added ${parsed.second} blocks."
            }
        }
    }
    PageIntro("Save your blocks to a file, or add blocks from one. Importing only adds blocks; it never changes or removes the ones here.")
    Gap(16)
    PillButton("Export blocks", enabled = world.blocks.isNotEmpty(), onClick = { exportLauncher.launch("xrnrule-blocks-${LocalDate.now()}.json") })
    Gap(8)
    OutlinePill("Import blocks", onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) })
    message?.let { StatusLine(it, ok = true) }
}
