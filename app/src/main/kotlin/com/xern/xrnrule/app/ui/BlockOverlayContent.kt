package com.xern.xrnrule.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xern.xrnrule.app.block.OverlayShieldPort
import com.xern.xrnrule.app.ui.theme.KindColors
import com.xern.xrnrule.app.ui.theme.LocalKinds
import com.xern.xrnrule.app.ui.theme.XrnruleTheme
import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.BlockReason
import com.xern.xrnrule.domain.BlockState
import com.xern.xrnrule.domain.LinkedDay
import com.xern.xrnrule.domain.OverrideRejection
import com.xern.xrnrule.domain.RejectReason
import com.xern.xrnrule.domain.SiteIcons
import kotlinx.coroutines.delay

private const val HOLD_MS = 1200
private const val SETTLE_MS = 5_000L

/**
 * The block screen. Full-bleed, one job: it does not go away until the habit is done and the
 * unlock is redeemed (or the user leaves the app). The app's icon, one line saying what it
 * takes ("To unlock X, finish your habits first!"), what's left in plain words, and the habits
 * themselves. Tasks are hold-to-complete so the list can't be tapped through on autopilot, and
 * the Open button is inert for a few seconds after the habit is met.
 */
@Composable
fun BlockOverlayContent(
    ui: OverlayShieldPort.OverlayUi?,
    onToggleItem: (String, Boolean) -> Unit,
    onSetTimer: (String, Boolean) -> Unit,
    /** Logs an amount of a habit or sets a day's check (contract I35), and starts a habit's focus timer in the app (I37). */
    onLogHabit: (String, Long) -> Unit = { _, _ -> },
    onSetHabitEntry: (String, Long, Long) -> Unit = { _, _, _ -> },
    onFocus: (String) -> Unit = {},
    onRedeem: (String) -> Unit,
    onLeave: () -> Unit,
    /** Buying minutes (contract I34): start the wait for these blocks, type the sentence, or drop it. */
    onBeginOverride: (Set<String>) -> Unit = {},
    onClaimOverride: (String) -> Unit = {},
    onCancelOverride: () -> Unit = {},
) {
    XrnruleTheme {
        val block = ui?.block
        // Blocked by time, not by a habit (contract §2.2, I44): no habit to do, just say when it lets go.
        val strict = block != null && (block.mode != BlockMode.HABIT || (ui.reason != null && ui.reason != BlockReason.HABIT))
        val earned = !strict && block?.state == BlockState.EARNED
        val subject = ui?.subject ?: "This app"
        val parts = if (block != null && !strict) {
            habitParts(block, ui.items, ui.timerMs, ui.timerRunning, ui.habitAppLabel, ui.goals, ui.focusHabitId)
        } else {
            emptyList()
        }
        rememberTicker() // keeps a lockdown countdown live

        // Surface, not a bare Column: it sets LocalContentColor so headline Text with no
        // explicit colour isn't left as the default black on our dark ground.
        Surface(
            Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    // The window covers the bars (OverlayShieldPort): stay above the gesture bar,
                    // and above the keyboard while the override sentence is typed.
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 20.dp),
            ) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Gap(24)
                    SubjectIcon(ui, block, strict)
                    Gap(24)
                    Text(
                        when {
                            earned -> "Nice work!\n$subject is ready to open"
                            block == null || strict -> "$subject is blocked"
                            else -> "To unlock $subject,\nfinish your habits first!"
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                    )
                    val sentence = when {
                        block == null -> null
                        strict -> strictStatus(block, ui!!)
                        earned -> null
                        else -> whatsLeft(block, parts)
                    }
                    if (!sentence.isNullOrEmpty()) {
                        Gap(10)
                        Text(
                            sentence,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Gap(28)
                    if (ui != null && strict) {
                        Column(Modifier.fillMaxWidth()) { OverrideSection(ui, onBeginOverride, onClaimOverride, onCancelOverride) }
                    }
                    if (block != null && !strict && parts.isNotEmpty()) {
                        HFCard(Modifier.fillMaxWidth()) {
                            joinTag(block, parts)?.let { Tag(it); Gap(8) }
                            parts.forEach { part ->
                                if (part is HabitPart.Goal) {
                                    GoalRow(
                                        part,
                                        onLog = onLogHabit,
                                        onSetEntry = onSetHabitEntry,
                                        onFocus = { id, _ -> onFocus(id) },
                                        onCount = null, // no dialogs over another app: "+" adds one
                                    )
                                } else {
                                    OverlayPartRow(block, part, armed = block.state == BlockState.ARMED, onToggleItem, onSetTimer)
                                }
                            }
                        }
                    }
                    Gap(16)
                }
                if (earned) {
                    EarnedActions(block!!.id, block.reward.unlockDurationMs, ui, onRedeem)
                    Gap(10)
                }
                PillButton(
                    if (ui?.isSite == true) "Leave this site" else "Close",
                    onClick = onLeave,
                    container = MaterialTheme.colorScheme.onSurface,
                    content = MaterialTheme.colorScheme.surface,
                )
            }
        }
    }
}

/** The blocked app's or site's icon, big; a rule with no app a lock. */
@Composable
private fun SubjectIcon(ui: OverlayShieldPort.OverlayUi?, block: Block?, strict: Boolean) {
    val pkg = ui?.pkg
    if (pkg != null) {
        AppIcon(pkg, size = 84.dp)
        return
    }
    if (ui?.isSite == true && SiteIcons.fetchable(ui.subject)) {
        SiteIcon(ui.subject, size = 84.dp, fallback = { KindIcon(ui, block, strict) })
        return
    }
    KindIcon(ui, block, strict)
}

/** A globe, an hourglass, a clock or a lock on the block's colour. */
@Composable
private fun KindIcon(ui: OverlayShieldPort.OverlayUi?, block: Block?, strict: Boolean) {
    val kinds = LocalKinds.current
    val kind = if (strict) kinds.strict else kinds.habit
    Box(
        Modifier.size(84.dp).clip(RoundedCornerShape(22.dp)).background(kind.fill),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            when {
                ui?.isSite == true -> Icons.Outlined.Language
                ui?.reason == BlockReason.LIMIT || ui?.reason == BlockReason.BREAK -> Icons.Outlined.HourglassBottom
                ui?.reason == BlockReason.HOURS -> Icons.Outlined.Schedule
                else -> Icons.Outlined.Lock
            },
            contentDescription = null,
            tint = kind.color,
            modifier = Modifier.size(40.dp),
        )
    }
}

/** When a daily limit / schedule / lockdown lets go — status, not instructions. */
private fun strictStatus(block: Block, ui: OverlayShieldPort.OverlayUi): String {
    val freeAt = ui.freeAtWall
    return when (ui.reason) {
        BlockReason.BREAK -> "A break after ${formatDuration(block.sessionMs)} in a row · ${formatRemaining(ui.rowBreakRemainingMs)} left"
        BlockReason.LIMIT -> "Today's ${formatDuration(ui.allowanceMs ?: block.dailyAllowanceMs)} is used up. It opens again at midnight."
        BlockReason.HOURS -> if (freeAt != null) "Blocked until ${formatClockTime(freeAt)}." else ""
        BlockReason.LOCKDOWN -> ui.freeInMs?.let { "Locked down for ${formatRemaining(it)} more." }.orEmpty()
        BlockReason.HABIT, null -> ""
    }
}

/** One habit in the block screen's card. */
@Composable
private fun OverlayPartRow(
    block: Block,
    part: HabitPart,
    armed: Boolean,
    onToggleItem: (String, Boolean) -> Unit,
    onSetTimer: (String, Boolean) -> Unit,
) {
    val kinds = LocalKinds.current
    when (part) {
        is HabitPart.Goal -> Unit // drawn by GoalRow
        is HabitPart.Task -> HoldToCheck(
            text = part.item.text.ifBlank { "Task" },
            done = part.done,
            kind = kinds.habit,
            enabled = armed,
            onToggle = { onToggleItem(part.item.id, it) },
        )
        // I43: nothing to press; the tag read anywhere ticks it, and this screen moves on.
        is HabitPart.Tag -> HabitRow(
            icon = Icons.Outlined.Nfc,
            kind = kinds.blue,
            label = part.name.replaceFirstChar { it.uppercase() },
            dim = part.done,
            note = if (part.done) null else "Hold your phone to it: this screen opens up once it's read",
            action = if (part.done) ({ RowAction(Icons.Filled.Check, "Scanned", kinds.blue.color, filled = true, onClick = {}) }) else null,
        )
        is HabitPart.Timer -> {
            val kind = if (part.appLabel != null) kinds.appTime else kinds.timer
            HabitRow(
                icon = Icons.Outlined.Timer,
                kind = kind,
                label = part.appLabel?.let { "Time in $it" } ?: "Timer",
                value = "${formatDuration(part.doneMs.coerceAtMost(part.requiredMs))} / ${formatDuration(part.requiredMs)}",
                fraction = part.fraction,
                note = when {
                    part.done -> null
                    part.appLabel != null && part.running -> "Counting while you use ${part.appLabel}"
                    part.appLabel != null -> "Start it, then use ${part.appLabel}"
                    part.running -> "Counting while you wait here"
                    else -> "Start it and wait here"
                },
                action = if (armed && !part.done) ({
                    RowAction(
                        if (part.running) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (part.running) "Pause timer" else "Start timer",
                        kind.color,
                        filled = true,
                        onClick = { onSetTimer(block.id, !part.running) },
                    )
                }) else null,
            )
        }
    }
}

/**
 * Buying a few minutes past a daily limit or a schedule (contract I34): start, wait out the
 * delay, type the sentence. The engine checks all of it; this only shows it.
 */
@Composable
private fun OverrideSection(
    ui: OverlayShieldPort.OverlayUi,
    onBegin: (Set<String>) -> Unit,
    onClaim: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val minutes = formatDuration(ui.overrideGrantMs)
    when (ui.overrideRejection) {
        null -> Unit
        OverrideRejection.NONE_LEFT -> {
            Text(
                "Today's bought minutes for this are used up.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            return
        }
        else -> return
    }
    val readyAt = ui.overrideReadyAtElapsed
    if (readyAt == null) {
        OutlinePill("Buy $minutes", onClick = { onBegin(ui.blockerIds) })
        Gap(6)
        Text(
            "Wait ${ui.overrideWaitMs / 1000} s, then type a sentence.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(readyAt) {
        while (true) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    var typed by remember(readyAt) { mutableStateOf("") }
    val waitLeft = readyAt - now
    HFCard(Modifier.fillMaxWidth()) {
        Text("Type: “${ui.overridePhrase}”", style = MaterialTheme.typography.bodyLarge)
        Gap(10)
        SearchField(
            value = typed,
            onChange = { typed = it },
            placeholder = "Type it here",
            icon = Icons.Outlined.Lock,
            // A password field keeps keyboards like Laban Key from turning "oo" into "ô" as you type.
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            actions = KeyboardActions(onDone = { if (waitLeft <= 0) onClaim(typed) }),
        )
        Gap(12)
        PillButton(
            if (waitLeft > 0) "Wait ${(waitLeft + 999) / 1000} s" else "Open for $minutes",
            onClick = { onClaim(typed) },
            enabled = waitLeft <= 0,
        )
        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Never mind") }
    }
}

@Composable
private fun EarnedActions(blockId: String, unlockMs: Long, ui: OverlayShieldPort.OverlayUi, onRedeem: (String) -> Unit) {
    if (ui.redeemRejection == RejectReason.CAP_REACHED) {
        Text(
            capReachedText(ui.unlockUsedTodayMs, ui.dailyCapMs, unlockMs, ui.othersOpen),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    var shownAt by remember(blockId) { mutableLongStateOf(System.currentTimeMillis()) }
    var now by remember(blockId) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(blockId) {
        shownAt = System.currentTimeMillis()
        while (true) {
            now = System.currentTimeMillis()
            delay(200)
        }
    }
    val remainMs = (SETTLE_MS - (now - shownAt)).coerceAtLeast(0L)
    val ready = remainMs <= 0L
    PillButton(
        if (ready) "Open for ${formatDuration(unlockMs)}" else "Steady… ${remainMs / 1000 + 1}",
        onClick = { onRedeem(blockId) },
        enabled = ready,
        icon = Icons.Filled.LockOpen,
    )
}

/**
 * A task that only toggles after a sustained press (~[HOLD_MS] ms). Releasing early springs the
 * ring back to empty. Prevents the "tap tap tap, done" speed-run.
 */
@Composable
private fun HoldToCheck(
    text: String,
    done: Boolean,
    kind: KindColors,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    var holding by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (holding) 1f else 0f,
        animationSpec = if (holding) tween(HOLD_MS, easing = LinearEasing) else tween(160),
        label = "hold",
    )
    LaunchedEffect(progress, holding) {
        if (holding && progress >= 0.999f) {
            holding = false
            onToggle(!done)
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (!enabled) Modifier
                else Modifier.pointerInput(done) {
                    detectTapGestures(onPress = {
                        holding = true
                        tryAwaitRelease()
                        holding = false
                    })
                },
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.TaskAlt, contentDescription = null, tint = kind.color, modifier = Modifier.size(20.dp))
        WGap(14)
        Column(Modifier.weight(1f)) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (!done && enabled) {
                Text("Hold to tick", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        WGap(10)
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { if (done) 1f else progress },
                modifier = Modifier.size(34.dp),
                strokeWidth = 3.dp,
                color = kind.color,
                trackColor = kind.fill,
            )
            if (done) Icon(Icons.Filled.Check, contentDescription = "Done", tint = kind.color, modifier = Modifier.size(18.dp))
        }
    }
}
