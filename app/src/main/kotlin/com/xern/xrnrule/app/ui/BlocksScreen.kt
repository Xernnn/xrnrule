@file:OptIn(ExperimentalFoundationApi::class)

package com.xern.xrnrule.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.FreeBreakfast
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockClock
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xern.xrnrule.app.data.AppPermission
import com.xern.xrnrule.app.data.InstalledApps
import com.xern.xrnrule.app.data.WorldStore
import com.xern.xrnrule.app.data.isGranted
import com.xern.xrnrule.app.data.settingsIntent
import com.xern.xrnrule.app.hardcore.HardcoreGuard
import com.xern.xrnrule.app.ui.theme.GeistMono
import com.xern.xrnrule.app.ui.theme.LocalKinds
import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockEdits
import com.xern.xrnrule.domain.BlockEvent
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.BlockState
import com.xern.xrnrule.domain.Defaults
import com.xern.xrnrule.domain.EnforcementTier
import com.xern.xrnrule.domain.Habit
import com.xern.xrnrule.domain.RejectReason
import com.xern.xrnrule.domain.Sites
import com.xern.xrnrule.domain.World

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
) {
    val context = LocalContext.current
    val appLabels = remember { InstalledApps.launchable(context).associate { it.packageName to it.label } }
    val resume = rememberResumeSignal()
    val missing = remember(resume) {
        listOf(AppPermission.USAGE_ACCESS, AppPermission.OVERLAY).filterNot { it.isGranted(context) }
    }
    var loosen by remember { mutableStateOf<LoosenRequest?>(null) }
    var confirmDelete by remember { mutableStateOf<Block?>(null) }
    var breakUsed by remember { mutableStateOf(false) }
    val pauseLeft = store.engine.pauseRemainingMs(world, store.clock)
    if (pauseLeft > 0) rememberTicker()

    fun guarded(block: Block, request: LoosenRequest) {
        if (BlockEdits.guardNeeded(store.engine, world, block, store.clock)) loosen = request else request.apply()
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PageTitle(
            title = "",
            leading = { Wordmark() },
            actions = {
                // A break only lifts soft blocks (I25); with none, there's nothing to offer.
                if (pauseLeft == 0L && world.blocks.any(store.engine::pausable)) {
                    RoundIconButton(Icons.Outlined.FreeBreakfast, "Take a break", onClick = {
                        if (store.engine.canPause(world, store.clock)) {
                            loosen = LoosenRequest(
                                title = "Take a ${formatDuration(Defaults.PAUSE_MS)} break?",
                                detail = "Soft blocks stop blocking for ${formatDuration(Defaults.PAUSE_MS)}. " +
                                    "You get one break a day. Hardcore blocks and lockdowns stay on.",
                                hardcore = false,
                            ) { store.dispatch(BlockEvent.StartPause()) }
                        } else {
                            breakUsed = true
                        }
                    })
                }
                if (world.blocks.isNotEmpty()) RoundIconButton(Icons.Filled.Add, "New block", onClick = onCreateBlock)
            },
        )

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = ScreenPadding,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (missing.isNotEmpty()) {
                item {
                    PermissionBanner(missing) {
                        runCatching { context.startActivity(missing.first().settingsIntent(context)) }
                    }
                }
            }
            if (pauseLeft > 0) {
                item { PauseBanner(leftMs = pauseLeft, onEnd = { store.dispatch(BlockEvent.EndPause) }) }
            }
            if (world.blocks.isEmpty()) {
                item { FirstBlock(onCreateBlock) }
            }
            items(world.blocks, key = { it.id }) { block ->
                val hardcore = block.enforcementTier == EnforcementTier.HARDCORE
                BlockCard(
                    block = block,
                    world = world,
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
            Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
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

/** No blocks yet: one big "+" in the middle, the way in. */
@Composable
private fun FirstBlock(onCreate: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        RoundIconButton(Icons.Filled.Add, "New block", onClick = onCreate, size = 72.dp)
        Gap(16)
        Text("create your first block!", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
) {
    if (block.mode != BlockMode.HABIT || block.state == BlockState.UNLOCKED) rememberTicker() // countdowns move with time
    var menu by remember { mutableStateOf(false) }
    val hardcore = block.enforcementTier == EnforcementTier.HARDCORE

    Box {
        HFCard(
            Modifier
                .clip(CardShape)
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true }),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppTile(
                    packages = block.targetPackages.sortedBy { appLabels[it]?.lowercase() ?: it },
                    fallback = Icons.Filled.Shield,
                    sites = block.targetSites.sorted(),
                    modifier = Modifier.alpha(if (block.enabled) 1f else 0.5f),
                )
                WGap(14)
                Column(Modifier.weight(1f)) {
                    Text(block.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        targetLine(block, appLabels),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val now = store.clock.nowWallMs()
                    if (BlockEdits.locked(block, now)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.LockClock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                            WGap(4)
                            Text(
                                "Locked until ${formatLockEnd(block.lock!!.untilWall, now)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
                WGap(8)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    XSwitch(checked = block.enabled, onCheckedChange = onEnabledChange)
                    if (hardcore) {
                        Gap(4)
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = "Hardcore",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(6.dp),
                        )
                    }
                }
            }
            if (!block.enabled) {
                Gap(12)
                Text(
                    "Off · turn it on to block again",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@HFCard
            }
            Gap(14)
            when (block.mode) {
                BlockMode.HABIT -> HabitBody(block, world, store, appLabels, onFocus)
                else -> StrictBody(block, world, store)
            }
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

/** "Instagram + youtube.com", "+ 2 laptop apps" when the laptop has some too. */
private fun targetLine(block: Block, appLabels: Map<String, String>): String {
    val names = block.targetPackages.map { appLabels[it] ?: it }.sortedBy { it.lowercase() } + Sites.shown(block.targetSites.sorted())
    val laptop = block.targetDesktopApps.size
    val phone = summarizeNames(names, empty = if (laptop > 0) "" else "Nothing yet")
    val laptopText = when (laptop) {
        0 -> ""
        1 -> "1 laptop app"
        else -> "$laptop laptop apps"
    }
    return listOf(phone, laptopText).filter { it.isNotEmpty() }.joinToString(" · ")
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
            onDismiss = { counting = null },
            dayLabel = if (goal.yesterday) "yesterday" else "today",
        )
    }
    if (block.state == BlockState.UNLOCKED) {
        val left = sessionLeftMs(world, block.id, store.clock)
        val duration = world.sessions[block.id]?.durationMs ?: block.reward.unlockDurationMs
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LockOpen, contentDescription = null, tint = kinds.habit.color, modifier = Modifier.size(16.dp))
            WGap(6)
            Text(
                "unlocked · blocks again at ${formatClockTime(store.clock.nowWallMs() + left, store.clock.zone())}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(compactDuration(left), style = MaterialTheme.typography.titleMedium, color = kinds.habit.color, fontFamily = GeistMono)
        }
        Gap(8)
        MeterBar(if (duration <= 0) 0f else left.toFloat() / duration, color = kinds.habit.color, track = kinds.habit.fill, height = 5.dp)
        return
    }

    val habitApps = block.trigger.habitPackages.takeIf { it.isNotEmpty() }?.joinToString(" or ") { appLabels[it] ?: it }
    val parts = world.habitParts(block, habitApps, store.engine, store.clock)
    val armed = block.state == BlockState.ARMED
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
                action = {
                    RowAction(
                        Icons.Filled.Check,
                        if (part.done) "Untick ${part.item.text}" else "Tick ${part.item.text}",
                        kinds.habit.color,
                        filled = part.done,
                        onClick = { if (armed) store.dispatch(BlockEvent.ToggleChecklistItem(part.item.id, !part.done)) },
                    )
                },
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
    if (block.state == BlockState.EARNED) {
        Gap(12)
        if (store.engine.redeemRejection(world, block.id, store.clock) == RejectReason.CAP_REACHED) {
            Text(
                capReachedText(store.engine.unlockUsedTodayMs(world, store.clock), world.settings.dailyCapMs, block.reward.unlockDurationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            PillButton(
                "Open for ${formatDuration(block.reward.unlockDurationMs)}",
                onClick = { store.dispatch(BlockEvent.Redeem(block.id)) },
                icon = Icons.Filled.LockOpen,
            )
        }
    }
}

/** Daily limit, schedule, lockdown: no habit to do, so the card says where the rule stands. */
@Composable
private fun StrictBody(block: Block, world: World, store: WorldStore) {
    val kinds = LocalKinds.current
    val engine = store.engine
    val clock = store.clock
    val status = blockStatus(block, world, engine, clock)
    val blocking = engine.isBlocking(world, block, clock)
    when (block.mode) {
        BlockMode.ALLOWANCE -> {
            if (block.dailyAllowanceMs > 0) {
                val used = engine.allowanceUsedMs(world, block, clock)
                HabitRow(
                    icon = Icons.Outlined.HourglassBottom,
                    kind = kinds.limit,
                    label = if (block.perDevice) "A day, on this phone" else "A day",
                    value = "${formatDuration(used)} / ${formatDuration(block.dailyAllowanceMs)}",
                    fraction = used.toFloat() / block.dailyAllowanceMs,
                )
            }
            if (block.hasRowRule) {
                HabitRow(
                    icon = Icons.Outlined.Timer,
                    kind = kinds.limit,
                    label = "In a row",
                    value = "${formatDuration(engine.rowUsedMs(world, block, clock))} / ${formatDuration(block.sessionMs)}",
                    fraction = engine.rowUsedMs(world, block, clock).toFloat() / block.sessionMs,
                )
            }
            if (blocking || engine.overrideRemainingMs(world, block.id, clock) > 0) StatusLine(status.line, blocking)
        }
        BlockMode.SCHEDULE -> {
            val bought = engine.overrideRemainingMs(world, block.id, clock)
            HabitRow(
                icon = Icons.Outlined.CalendarMonth,
                kind = kinds.strict,
                // What it's doing now is the line; the schedule, which can be long, wraps under it.
                label = when {
                    bought > 0 -> "Bought time · ${formatRemaining(bought)} left"
                    blocking -> freeAtWall(block, world, engine, clock)
                        ?.let { "Blocking until ${formatClockTime(it, clock.zone())}" } ?: "Blocking now"
                    else -> "Off now"
                },
                dim = !blocking && bought <= 0,
                note = describeSchedule(block.schedule),
            )
        }
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

@Composable
private fun StatusLine(text: String, alarm: Boolean) {
    Gap(4)
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        color = if (alarm) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Start,
    )
}
