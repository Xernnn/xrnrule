package com.xern.xrnrule.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FreeBreakfast
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xern.xrnrule.app.data.WorldStore
import com.xern.xrnrule.app.ui.theme.TABULAR
import com.xern.xrnrule.domain.BlockEvent
import com.xern.xrnrule.domain.Defaults
import com.xern.xrnrule.domain.HabitKind

/**
 * The full-screen focus timer for a timer habit (contract I37): a ring filling toward today's
 * goal, the time left (then the time past it), when the goal will be reached, and Pause, a
 * five-minute break and End. It runs on in the background when this screen is left; the time
 * is logged on pause and end, and counts toward blocks as it goes.
 */
@Composable
fun FocusScreen(store: WorldStore, habitId: String, onClose: () -> Unit) {
    val world by store.world.collectAsStateWithLifecycle()
    val habit = world.habit(habitId)?.takeIf { it.kind == HabitKind.TIMER }
    if (habit == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    rememberTicker(500)
    BackHandler(onBack = onClose)
    val engine = store.engine
    val clock = store.clock
    val run = world.focus?.takeIf { it.habitId == habitId }
    val running = run?.running == true
    val breakEnds = run?.takeIf { !it.running }?.resumeAtWall
    // In ms: amounts are thousandths of a minute (contract §1). The goal is the day's share.
    val total = engine.habitAmount(world, habitId, run?.epochDay ?: world.habitDay(clock), clock) * 60
    val goal = ((habit.unlockUnit ?: habit.goal) * 60).coerceAtLeast(60_000)
    val left = goal - total
    val colors = habitColors(habit)

    // The screen stays on while the timer runs, like a kitchen timer on the table.
    val view = LocalView.current
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Leave the timer running")
            }
            Text(
                habit.name,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Spacer(Modifier.size(48.dp))
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(290.dp), contentAlignment = Alignment.Center) {
            val fraction = (total.toFloat() / goal).coerceIn(0f, 1f)
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 16.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(colors.fill, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                drawArc(colors.color, -90f, 360f * fraction, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when {
                        // A break counts itself down; the timer starts again when it ends (I37).
                        breakEnds != null -> clockText((breakEnds - clock.nowWallMs()).coerceAtLeast(0))
                        left > 0 -> clockText(left)
                        else -> "+" + clockText(-left)
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 54.sp,
                    style = LocalTextStyle.current.copy(fontFeatureSettings = TABULAR),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    when {
                        breakEnds != null -> "left of your break"
                        left <= 0 && run != null && !running -> "past today's goal · paused"
                        left <= 0 -> "past today's goal"
                        run != null && !running -> "paused"
                        else -> "left of ${formatDuration(goal)}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Gap(24)
        Text(
            when {
                breakEnds != null -> "Back to it at ${formatClockTime(breakEnds, clock.zone())} · ${formatDuration(total)} today"
                left <= 0 -> "Goal reached · ${formatDuration(total)} today"
                running -> "Goal at ${formatClockTime(clock.nowWallMs() + left, clock.zone())}"
                else -> "${formatDuration(total)} of ${formatDuration(goal)} today"
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (left <= 0 && breakEnds == null) colors.color else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        if (running) {
            PillButton(
                "Pause",
                onClick = { store.dispatch(BlockEvent.PauseFocus()) },
                icon = Icons.Filled.Pause,
                container = colors.color,
                content = onKind(colors.color),
            )
            Gap(10)
            OutlinePill(
                "Take a ${formatDuration(Defaults.FOCUS_BREAK_MS)} break",
                onClick = { store.dispatch(BlockEvent.PauseFocus(Defaults.FOCUS_BREAK_MS)) },
                icon = Icons.Outlined.FreeBreakfast,
            )
        } else {
            PillButton(
                if (run == null) "Start" else "Resume",
                onClick = { store.dispatch(BlockEvent.StartFocus(habitId)) },
                icon = Icons.Filled.PlayArrow,
                container = colors.color,
                content = onKind(colors.color),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
            if (run != null) {
                TextButton(onClick = {
                    store.dispatch(BlockEvent.EndFocus)
                    onClose()
                }) { Text("End session", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/** "24:59", "1:02:03". */
private fun clockText(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}
