@file:OptIn(ExperimentalFoundationApi::class)

package com.xern.xrnrule.app.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.FreeBreakfast
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xern.xrnrule.app.R
import com.xern.xrnrule.app.data.AppPermission
import com.xern.xrnrule.app.data.InstalledApps
import com.xern.xrnrule.app.data.WorldStore
import com.xern.xrnrule.app.data.isGranted
import com.xern.xrnrule.app.data.settingsIntent
import com.xern.xrnrule.app.hardcore.HardcoreGuard
import com.xern.xrnrule.app.ui.theme.Brand
import com.xern.xrnrule.app.ui.theme.GeistMono
import com.xern.xrnrule.app.ui.theme.LocalKinds
import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockEdits
import com.xern.xrnrule.domain.BlockEvent
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.BlockReason
import com.xern.xrnrule.domain.BlockState
import com.xern.xrnrule.domain.BlockTexts
import com.xern.xrnrule.domain.Companions
import com.xern.xrnrule.domain.Defaults
import com.xern.xrnrule.domain.Devices
import com.xern.xrnrule.domain.EnforcementTier
import com.xern.xrnrule.domain.RejectReason
import com.xern.xrnrule.domain.Schedule
import com.xern.xrnrule.domain.World
import com.xern.xrnrule.domain.appliesHere
import com.xern.xrnrule.domain.deleteBlock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * The Blocks tab: a card per block that shows what it asks for and lets you do it right there
 * (tick a task, start the timer, open an earned unlock). Tapping a card edits it; holding it
 * offers delete.
 */
@Composable
fun BlocksScreen(
    world: World,
    store: WorldStore,
    guard: HardcoreGuard,
    onOpenBlock: (String) -> Unit,
    onCreateBlock: () -> Unit,
    onFocus: (habitId: String, start: Boolean) -> Unit,
    updateReady: String?,
    onOpenUpdates: () -> Unit,
) {
    val context = LocalContext.current
    val appLabels = remember { InstalledApps.launchable(context).associate { it.packageName to it.label } }
    val resume = rememberResumeSignal()
    val missing = remember(resume) {
        listOf(AppPermission.USAGE_ACCESS, AppPermission.OVERLAY).filterNot { it.isGranted(context) }
    }
    // With nothing to block yet, the first-run page lists these calmly instead.
    val bannerShown = missing.isNotEmpty() && world.blocks.isNotEmpty()
    var loosen by remember { mutableStateOf<LoosenRequest?>(null) }
    var confirmDelete by remember { mutableStateOf<Block?>(null) }
    var breakUsed by remember { mutableStateOf(false) }
    val pauseLeft = store.engine.pauseRemainingMs(world, store.clock)
    if (pauseLeft > 0) rememberTicker()

    fun guarded(block: Block, request: LoosenRequest) {
        if (BlockEdits.guardNeeded(store.engine, world, block, store.clock)) loosen = request else request.apply()
    }

    // A link to one block (xrnrule://open?block=…): scroll to its card and outline it a moment.
    val list = rememberLazyListState()
    val linked by AppLinks.block.collectAsStateWithLifecycle()
    var outlined by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(linked) {
        val id = linked ?: return@LaunchedEffect
        val index = world.blocks.indexOfFirst { it.id == id }
        if (index >= 0) {
            val at = index + 1 + listOf(bannerShown, pauseLeft > 0, updateReady != null).count { it }
            // Just opened, the list hasn't been laid out yet, and a scroll then goes nowhere.
            snapshotFlow { list.layoutInfo.totalItemsCount }.first { it > at }
            list.animateScrollToItem(at)
            outlined = id
        }
        // Taken only once shown: if this screen goes meanwhile, the next one shows it.
        AppLinks.block.value = null
    }
    LaunchedEffect(outlined) {
        if (outlined != null) {
            delay(2_500)
            outlined = null
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PageTitle(
            title = "",
            leading = {
                // Habits First's header: a small mark in colour before the name.
                Icon(painterResource(R.drawable.ic_stat_xrnrule), contentDescription = null, tint = Brand, modifier = Modifier.size(20.dp))
                WGap(8)
                Wordmark()
            },
            actions = {
                // A break only lifts soft blocks that aren't locked (I25, I50); with none, there's nothing to offer.
                if (pauseLeft == 0L && world.blocks.any { store.engine.pausable(it, store.clock) }) {
                    RoundIconButton(Icons.Outlined.FreeBreakfast, "Take a break", outlined = true, onClick = {
                        if (store.engine.canPause(world, store.clock)) {
                            loosen = LoosenRequest(
                                title = "Take a ${formatDuration(Defaults.PAUSE_MS)} break?",
                                detail = "Soft blocks stop blocking for ${formatDuration(Defaults.PAUSE_MS)}. " +
                                    "You get one break a day. Hardcore blocks, locked blocks and lockdowns stay on.",
                                hardcore = false,
                            ) { store.dispatch(BlockEvent.StartPause()) }
                        } else {
                            breakUsed = true
                        }
                    })
                }
                if (world.blocks.isNotEmpty()) RoundIconButton(Icons.Filled.Add, "New block", outlined = true, onClick = onCreateBlock)
            },
        )

        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            contentPadding = ScreenPadding,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                BlockTexts.summary(store.engine, world, store.clock)?.let { summary ->
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (bannerShown) {
                item {
                    PermissionBanner(missing) {
                        runCatching { context.startActivity(missing.first().settingsIntent(context)) }
                    }
                }
            }
            if (pauseLeft > 0) {
                item { PauseBanner(leftMs = pauseLeft, onEnd = { store.dispatch(BlockEvent.EndPause) }) }
            }
            updateReady?.let { version -> item { UpdateBanner(version, onOpenUpdates) } }
            if (world.blocks.isEmpty()) {
                item { FirstRun(onCreateBlock) }
            }
            items(world.blocks, key = { it.id }) { block ->
                val hardcore = block.enforcementTier == EnforcementTier.HARDCORE
                BlockCard(
                    block = block,
                    // A block for the laptop only shows how it stands there, not "not blocking" (I42).
                    world = if (world.appliesHere(block)) world else world.copy(deviceKind = null),
                    store = store,
                    appLabels = appLabels,
                    onOpen = { onOpenBlock(block.id) },
                    onFocus = onFocus,
                    onEnabledChange = { on ->
                        val apply = { store.edit { w -> w.copy(blocks = w.blocks.map { if (it.id == block.id) it.copy(enabled = on) else it }) } }
                        if (on) {
                            apply()
                        } else {
                            guarded(block, LoosenRequest("Turn ${block.name} off?", "It stops blocking until you turn it back on.", hardcore, listOf(block.id)) { apply() })
                        }
                    },
                    onDelete = {
                        if (BlockEdits.guardNeeded(store.engine, world, block, store.clock)) {
                            loosen = LoosenRequest("Delete ${block.name}?", "It stops blocking for good.", hardcore, listOf(block.id)) {
                                store.edit { it.deleteBlock(block.id) }
                            }
                        } else {
                            confirmDelete = block
                        }
                    },
                    guard = guard,
                    outlined = outlined == block.id,
                    onUnhardcore = {
                        guarded(
                            block,
                            LoosenRequest("Take ${block.name} off the password?", "Making it easier to get past won't ask for the password any more.", true, listOf(block.id)) {
                                store.edit { w -> w.copy(blocks = w.blocks.map { if (it.id == block.id) it.copy(enforcementTier = EnforcementTier.SOFT) else it }) }
                            },
                        )
                    },
                    onLoosen = { loosen = it },
                )
            }
        }
    }

    loosen?.let { request -> LoosenGuardDialog(request, guard, store, onDismiss = { loosen = null }) }
    confirmDelete?.let { block ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${block.name}?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    store.edit { it.deleteBlock(block.id) }
                }) { Text("Delete block", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
    if (breakUsed) {
        AlertDialog(
            onDismissRequest = { breakUsed = false },
            title = { Text("No more breaks today") },
            text = { Text("You've had today's break. The next one is available tomorrow.") },
            confirmButton = { TextButton(onClick = { breakUsed = false }) { Text("OK") } },
        )
    }
}

/** Red, at the top: blocking can't work until this is fixed. */
@Composable
private fun PermissionBanner(missing: List<AppPermission>, onClick: () -> Unit) {
    val title = when {
        missing.size > 1 -> "Blocking is off"
        missing[0] == AppPermission.USAGE_ACCESS -> "Usage access is off"
        else -> "Drawing over other apps is off"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.error)
            .clickable(onClick = onClick)
            .padding(18.dp),
    ) {
        Icon(Icons.Outlined.GppBad, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        WGap(12)
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = Color.White)
            Text(
                "Your blocks can't block anything until it's back on. Tap to fix.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.88f),
            )
        }
    }
}

@Composable
private fun PauseBanner(leftMs: Long, onEnd: () -> Unit) {
    val green = LocalKinds.current.habit
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(green.fill)
            .padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.FreeBreakfast, contentDescription = null, tint = green.color)
        WGap(12)
        Column(Modifier.weight(1f)) {
            Text("On a break", style = MaterialTheme.typography.titleMedium)
            Text(
                "Soft blocks are back in ${formatRemaining(leftMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onEnd) { Text("End now") }
    }
}

/** A new version waits for a tap. Calm on purpose: nothing's wrong, and it can wait. */
@Composable
private fun UpdateBanner(version: String, onClick: () -> Unit) {
    val blue = LocalKinds.current.blue
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(blue.fill)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.SystemUpdate, contentDescription = null, tint = blue.color)
        WGap(12)
        Column {
            Text("Update ready", style = MaterialTheme.typography.titleMedium)
            Text(
                "Version $version. Tap to install it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** No blocks yet: what this is, and what it'll need, with nothing to alarm anyone. */
@Composable
private fun FirstRun(onCreate: () -> Unit) {
    val context = LocalContext.current
    val resume = rememberResumeSignal()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val needs = remember(resume) {
        listOf(
            Triple(AppPermission.USAGE_ACCESS, "See which app is open", "So a blocked app can be caught. Nothing leaves your phone."),
            Triple(AppPermission.OVERLAY, "Show the block screen", "It goes on top of a blocked app or site."),
            Triple(AppPermission.NOTIFICATIONS, "Notifications", "For time running out, and if blocking ever stops working."),
        )
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Column(Modifier.fillMaxWidth().padding(top = 28.dp)) {
        Text("Do the thing first.", style = MaterialTheme.typography.displaySmall)
        Gap(10)
        Text(
            "Pick the apps and sites that eat your time. xrnrule keeps them closed until you've done what you " +
                "set: a habit, a timer, or just waiting for the right hour.",
            style = MaterialTheme.typography.bodyLarge,
            color = muted,
        )
        Gap(24)
        HFCard {
            Text("It needs", style = MaterialTheme.typography.titleSmall, color = muted)
            needs.forEach { (permission, title, why) ->
                val granted = remember(resume) { permission.isGranted(context) }
                Gap(12)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = !granted) {
                            if (permission == AppPermission.NOTIFICATIONS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                runCatching { context.startActivity(permission.settingsIntent(context)) }
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (granted) Icons.Filled.CheckCircle else Icons.Filled.AddCircleOutline,
                        contentDescription = if (granted) "Allowed" else "Not allowed yet",
                        tint = if (granted) LocalKinds.current.habit.color else muted,
                        modifier = Modifier.size(24.dp),
                    )
                    WGap(12)
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.bodyLarge)
                        Text(why, style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
            }
        }
        Gap(20)
        PillButton("Make your first block", onClick = onCreate)
    }
}

@Composable
private fun BlockCard(
    block: Block,
    world: World,
    store: WorldStore,
    appLabels: Map<String, String>,
    onOpen: () -> Unit,
    onFocus: (String, Boolean) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    guard: HardcoreGuard,
    onUnhardcore: () -> Unit,
    /** A change from the card that has to wait for a guard first (§2.3). */
    onLoosen: (LoosenRequest) -> Unit,
    /** Shown from a link: outlined for a moment. */
    outlined: Boolean = false,
) {
    if (block.mode != BlockMode.HABIT || block.state == BlockState.UNLOCKED || block.hasLimit || block.blockedHours != null) rememberTicker() // countdowns move with time
    var menu by remember { mutableStateOf(false) }
    var locking by remember { mutableStateOf(false) }
    val hardcore = block.enforcementTier == EnforcementTier.HARDCORE
    if (locking) LockDialog(block, store, guard, onUnhardcore = onUnhardcore, onDismiss = { locking = false })

    Box {
        HFCard(
            Modifier
                .clip(CardShape)
                .then(if (outlined) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CardShape) else Modifier)
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true }),
        ) {
            // How it stands comes first: that's what the card is looked at for.
            val now = store.clock.nowWallMs()
            val locked = BlockEdits.locked(block, now)
            val headline = BlockTexts.headline(store.engine, world, block, store.clock)
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppTile(
                    packages = block.targetPackages.sortedBy { appLabels[it]?.lowercase() ?: it },
                    fallback = Icons.Filled.Shield,
                    sites = Companions.withoutPaired(block.targetSites.sorted(), block.targetPackages),
                    size = 46.dp,
                    modifier = Modifier.alpha(if (block.enabled) 1f else 0.5f),
                )
                WGap(12)
                Column(Modifier.weight(1f)) {
                    Text(block.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    HeadlineText(headline)
                    // I42: a block that holds on one device says which.
                    val where = when (block.devices) {
                        Devices.BOTH -> null
                        Devices.PHONE -> "Phone only"
                        Devices.LAPTOP -> "Laptop only"
                    }
                    val lockText = if (locked) "Locked until ${formatLockEnd(block.lock!!.untilWall, now)}" else null
                    listOfNotNull(where, lockText).takeIf { it.isNotEmpty() }?.let { parts ->
                        Text(
                            parts.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                WGap(8)
                LockPill(block.name, active = locked || hardcore, onClick = { locking = true })
                WGap(8)
                XSwitch(checked = block.enabled, onCheckedChange = onEnabledChange)
            }
            if (!block.enabled) return@HFCard
            Gap(8)
            when (block.mode) {
                BlockMode.HABIT -> {
                    RuleRows(block, world, store)
                    HabitBody(block, world, store, appLabels, onFocus)
                }
                else -> StrictBody(block, world, store)
            }
            MissingCompanion(block, appLabels, store, onLoosen)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onOpen() })
            if (block.state == BlockState.EARNED) {
                DropdownMenuItem(
                    text = { Text("Give up this unlock") },
                    onClick = { menu = false; store.dispatch(BlockEvent.UserResetEarned(block.id)) },
                )
            }
            DropdownMenuItem(
                text = { Text("Delete block", color = MaterialTheme.colorScheme.error) },
                onClick = { menu = false; onDelete() },
            )
        }
    }
}

@Composable
private fun HabitBody(
    block: Block,
    world: World,
    store: WorldStore,
    appLabels: Map<String, String>,
    onFocus: (String, Boolean) -> Unit,
) {
    val kinds = LocalKinds.current
    var counting by remember { mutableStateOf<HabitPart.Goal?>(null) }
    if (world.focus?.running == true) rememberTicker() // the focus timer's minutes move on the card
    counting?.let { goal ->
        val h = goal.habit ?: return@let
        // A "yesterday" item's dialog adds to yesterday (I23); otherwise to today.
        val total = store.engine.habitAmount(world, h.id, goal.day, store.clock)
        LogAmountDialog(
            habit = h,
            todayTotal = total,
            onLog = { amount ->
                store.dispatch(
                    if (goal.yesterday) BlockEvent.SetHabitEntry(h.id, goal.day, (total + amount).coerceAtLeast(0))
                    else BlockEvent.LogHabit(h.id, amount),
                )
            },
            onSetTotal = { value -> store.dispatch(BlockEvent.SetHabitEntry(h.id, goal.day, value)) },
            onDismiss = { counting = null },
            dayLabel = if (goal.yesterday) "yesterday" else "today",
        )
    }
    if (block.state == BlockState.UNLOCKED) return // the headline says how long

    val habitApps = block.trigger.habitPackages.takeIf { it.isNotEmpty() }?.joinToString(" or ") { appLabels[it] ?: it }
    val parts = world.habitParts(block, habitApps, store.engine, store.clock)
    val armed = block.state == BlockState.ARMED
    if (block.state == BlockState.EARNED) {
        when (store.engine.redeemRejection(world, block.id, store.clock)) {
            RejectReason.HELD -> StatusPill("Done · it can open once it's no longer blocked")
            RejectReason.CAP_REACHED -> StatusPill(
                capReachedText(store.engine.unlockCommittedMs(world, store.clock), world.settings.dailyCapMs, block.reward.unlockDurationMs),
            )
            else -> {
                // The same moment to change your mind as on the block screen.
                val settle = rememberSettleLeft(block.id)
                StatusPill(
                    if (settle > 0) "Steady… ${settle / 1000 + 1}" else "Open for ${formatDuration(block.reward.unlockDurationMs)}",
                    container = if (settle > 0) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary,
                    content = if (settle > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary,
                    onClick = if (settle > 0) null else ({ store.dispatch(BlockEvent.Redeem(block.id)) }),
                )
            }
        }
        Gap(6)
    }
    joinTag(block, parts)?.let { Tag(it); Gap(6) }
    if (parts.isEmpty()) {
        Text("Nothing unlocks it yet. Tap to add a habit.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    parts.forEach { part ->
        when (part) {
            is HabitPart.Goal -> GoalRow(
                part,
                onLog = { id, amount -> store.dispatch(BlockEvent.LogHabit(id, amount)) },
                onSetEntry = { id, day, value -> store.dispatch(BlockEvent.SetHabitEntry(id, day, value)) },
                onFocus = onFocus,
                onCount = { counting = it },
            )
            is HabitPart.Task -> HabitRow(
                icon = Icons.Outlined.TaskAlt,
                kind = kinds.habit,
                label = part.item.text.ifBlank { "Task" },
                dim = part.done,
                note = if (!part.done && armed) "Hold the circle to tick" else null,
                action = {
                    if (part.done || !armed) {
                        RowAction(Icons.Filled.Check, "Untick ${part.item.text}", kinds.habit.color, filled = part.done) {
                            if (armed) store.dispatch(BlockEvent.ToggleChecklistItem(part.item.id, false))
                        }
                    } else {
                        HoldRowAction(Icons.Filled.Check, "Hold to tick ${part.item.text}", kinds.habit.color, onHeld = {
                            store.dispatch(BlockEvent.ToggleChecklistItem(part.item.id, true))
                        })
                    }
                },
            )
            is HabitPart.Tag -> HabitRow(
                icon = Icons.Outlined.Nfc,
                kind = kinds.blue,
                label = part.name.replaceFirstChar { it.uppercase() },
                dim = part.done,
                note = if (!part.done && armed) "Hold your phone to it" else null,
                // Only reading the tag ticks it (I43); unticking is always fine.
                action = if (part.done && armed) ({
                    RowAction(
                        Icons.Filled.Check,
                        "Untick ${part.name}",
                        kinds.blue.color,
                        filled = true,
                        onClick = { store.dispatch(BlockEvent.ToggleChecklistItem(part.item.id, false)) },
                    )
                }) else null,
            )
            is HabitPart.Timer -> {
                val kind = if (part.appLabel != null) kinds.appTime else kinds.timer
                HabitRow(
                    icon = Icons.Outlined.Timer,
                    kind = kind,
                    label = part.appLabel?.let { "Time in $it" } ?: "Timer",
                    value = "${part.doneMs.coerceAtMost(part.requiredMs) / 60_000} / ${part.requiredMs / 60_000} min",
                    fraction = part.fraction,
                    note = if (part.running && part.appLabel == null && armed) "Counts while the block screen is up" else null,
                    action = if (armed && !part.done) ({
                        RowAction(
                            if (part.running) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (part.running) "Pause timer" else "Start timer",
                            kind.color,
                            filled = true,
                            onClick = { store.dispatch(BlockEvent.TimerControl(block.id, !part.running)) },
                        )
                    }) else null,
                )
            }
        }
    }
}

/**
 * The wide pill under a block card's header, as in Habits First: "no unlocks available" on the
 * raised grey, the green "Open for 15 min" once it can be opened, the time left while open.
 */
@Composable
private fun StatusPill(
    text: String,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailing: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = content,
            textAlign = TextAlign.Center,
            modifier = if (trailing != null) Modifier.weight(1f) else Modifier,
        )
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.titleMedium, color = content, fontFamily = GeistMono)
    }
}

/** A block's limit and its blocked hours, on any kind of block that has them (I44). */
@Composable
private fun RuleRows(block: Block, world: World, store: WorldStore) {
    val kinds = LocalKinds.current
    val engine = store.engine
    val clock = store.clock
    if (block.hasLimit) {
        if (block.dailyAllowanceMs > 0) {
            val used = engine.allowanceUsedMs(world, block, clock)
            HabitRow(
                icon = Icons.Outlined.HourglassBottom,
                kind = kinds.limit,
                label = if (block.perDevice) "Daily limit, this phone" else "Daily limit",
                value = "${formatDuration(used)} / ${formatDuration(block.dailyAllowanceMs)}",
                fraction = used.toFloat() / block.dailyAllowanceMs,
            )
        }
        if (block.hasRowRule) {
            HabitRow(
                icon = Icons.Outlined.Timer,
                kind = kinds.limit,
                label = "Time in a row",
                value = "${formatDuration(engine.rowUsedMs(world, block, clock))} / ${formatDuration(block.sessionMs)}",
                fraction = engine.rowUsedMs(world, block, clock).toFloat() / block.sessionMs,
            )
        }
        if (block.opensPerDay > 0) {
            val used = engine.opensUsed(world, block, clock)
            HabitRow(
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                kind = kinds.limit,
                label = if (block.perDevice) "Opens today, this phone" else "Opens today",
                value = "$used / ${block.opensPerDay}",
                fraction = used.toFloat() / block.opensPerDay,
            )
        }
    }
    block.blockedHours?.let { HoursRow(block, it, world, store) }
}

/** The hours a block blocks outright; whether it's in them now is the headline's to say. */
@Composable
private fun HoursRow(block: Block, hours: Schedule, world: World, store: WorldStore) {
    HabitRow(
        icon = Icons.Outlined.CalendarMonth,
        kind = LocalKinds.current.strict,
        label = "Blocked hours",
        dim = store.engine.blockReason(world, block, store.clock) != BlockReason.HOURS,
        note = describeSchedule(hours),
    )
}

/** Daily limit, schedule, lockdown: no habit to do, so the card says where the rule stands. */
@Composable
private fun StrictBody(block: Block, world: World, store: WorldStore) {
    val kinds = LocalKinds.current
    val engine = store.engine
    val clock = store.clock
    when (block.mode) {
        BlockMode.ALLOWANCE -> RuleRows(block, world, store)
        BlockMode.SCHEDULE -> if (block.schedule != Schedule.ALWAYS) HoursRow(block, block.schedule, world, store)
        BlockMode.LOCKDOWN -> {
            val left = engine.lockdownRemainingMs(world, block.id, clock)
            if (left > 0) {
                val duration = world.lockdowns[block.id]?.durationMs ?: block.lockdownMs
                HabitRow(
                    icon = Icons.Outlined.Lock,
                    kind = kinds.strict,
                    label = "Locked down",
                    value = "${formatRemaining(left)} left",
                    fraction = if (duration <= 0) 1f else 1f - left.toFloat() / duration,
                )
            } else {
                HabitRow(icon = Icons.Outlined.Lock, kind = kinds.strict, label = "Lockdown over", dim = true)
                Gap(8)
                OutlinePill(
                    "Block again for ${formatDuration(block.lockdownMs)}",
                    onClick = { store.dispatch(BlockEvent.StartLockdown(block.id)) },
                    icon = Icons.Outlined.Lock,
                )
            }
        }
        BlockMode.HABIT -> Unit
    }
}

/** A block's state in its colour: red holding, green open. */
@Composable
private fun HeadlineText(headline: BlockTexts.Headline) {
    val color = when (headline.tone) {
        BlockTexts.Tone.BLOCKED -> MaterialTheme.colorScheme.error
        BlockTexts.Tone.READY -> MaterialTheme.colorScheme.primary
        BlockTexts.Tone.OPEN -> LocalKinds.current.habit.color
        BlockTexts.Tone.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        headline.text,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * A block on a site whose app is on this phone and isn't in the block: the usual way around one,
 * usually without meaning to. "Not now" stays, per block and app.
 */
@Composable
private fun MissingCompanion(block: Block, appLabels: Map<String, String>, store: WorldStore, onLoosen: (LoosenRequest) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hints", Context.MODE_PRIVATE) }
    var dismissed by remember { mutableStateOf(prefs.getStringSet(KEY_NOT_NOW, null).orEmpty()) }
    val missing = Companions.appsFor(block.targetSites, block.targetPackages, appLabels.keys)
        .firstOrNull { "${block.id}:$it" !in dismissed } ?: return
    Gap(8)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "The ${appLabels[missing] ?: missing} app isn't in this block",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
            dismissed = dismissed + "${block.id}:$missing"
            prefs.edit().putStringSet(KEY_NOT_NOW, dismissed).apply()
        }) { Text("Not now") }
        TextButton(onClick = {
            val add = { store.edit { w -> w.copy(blocks = w.blocks.map { if (it.id == block.id) it.copy(targetPackages = it.targetPackages + missing) else it }) } }
            // I6: another habit block on that app is one this block's unlock would open too.
            val opened = BlockEdits.opens(store.world.value, block, block.copy(targetPackages = block.targetPackages + missing))
            openingRequest(store, opened, "Add ${appLabels[missing] ?: missing} to ${block.name}?") { add() }?.let(onLoosen) ?: add()
        }) { Text("Add") }
    }
}

private const val KEY_NOT_NOW = "companionNotNow"
