package com.xern.xrnrule.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockEngine
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.BlockReason
import com.xern.xrnrule.domain.BlockState
import com.xern.xrnrule.domain.BlockTexts
import com.xern.xrnrule.domain.Clock
import com.xern.xrnrule.domain.Schedule
import com.xern.xrnrule.domain.World
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * How a block is doing right now, in words — shared by the block cards, the detail screen and
 * the block screen. [tone] reuses the state palette: ARMED = blocking (clay red), UNLOCKED =
 * free (green), EARNED = ready (amber).
 */
data class BlockStatusUi(
    val label: String,
    val icon: ImageVector,
    val tone: BlockState,
    val line: String,
    val fraction: Float?,
)

fun blockStatus(block: Block, world: World, engine: BlockEngine, clock: Clock): BlockStatusUi {
    val blocking = engine.isBlocking(world, block, clock)
    val reason = engine.blockReason(world, block, clock)
    return when (block.mode) {
        BlockMode.HABIT -> if (reason == BlockReason.LIMIT || reason == BlockReason.BREAK || reason == BlockReason.WAIT) limitStatus(block, world, engine, clock) else {
            val parts = world.habitParts(block, appLabel = null, engine, clock)
            BlockStatusUi(
                label = stateVerbose(block.state),
                icon = stateIcon(block.state),
                tone = block.state,
                line = when (block.state) {
                    BlockState.UNLOCKED -> sessionLeftMs(world, block.id, clock).let { left ->
                        if (world.sessions[block.id]?.selfPaced == true) "${formatRemaining(left)} of use left today" // I41
                        else "Open for ${formatRemaining(left)} more"
                    }
                    BlockState.EARNED -> "${formatDuration(block.reward.unlockDurationMs)} unlock ready"
                    BlockState.ARMED -> whatsLeft(block, parts) ?: stateVerbose(block.state)
                },
                fraction = if (block.state == BlockState.UNLOCKED) null else partsFraction(block, parts),
            )
        }

        BlockMode.ALLOWANCE -> limitStatus(block, world, engine, clock)

        BlockMode.SCHEDULE -> {
            val until = if (blocking) freeAtWall(block, world, engine, clock) else null
            val bought = engine.overrideRemainingMs(world, block.id, clock)
            BlockStatusUi(
                label = if (blocking) "Blocking now" else if (bought > 0) "Bought time" else "Off now",
                icon = if (blocking) Icons.Filled.Lock else Icons.Filled.Schedule,
                tone = if (blocking) BlockState.ARMED else BlockState.UNLOCKED,
                line = describeSchedule(block.schedule) +
                    (until?.let { " · until ${formatClockTime(it, clock.zone())}" } ?: "") +
                    (if (bought > 0) " · ${formatRemaining(bought)} of bought time left" else ""),
                fraction = null,
            )
        }

        BlockMode.LOCKDOWN -> {
            val remaining = engine.lockdownRemainingMs(world, block.id, clock)
            val duration = world.lockdowns[block.id]?.durationMs ?: block.lockdownMs
            if (remaining > 0) {
                BlockStatusUi(
                    label = "Blocking",
                    icon = Icons.Filled.Lock,
                    tone = BlockState.ARMED,
                    line = "${formatRemaining(remaining)} left",
                    fraction = if (duration == 0L) 1f else 1f - remaining.toFloat() / duration,
                )
            } else {
                BlockStatusUi(
                    label = "Ended",
                    icon = Icons.Filled.LockOpen,
                    tone = BlockState.UNLOCKED,
                    line = "Not running",
                    fraction = null,
                )
            }
        }
    }
}

/** Where a block's limit stands (I19, I32, I44). */
private fun limitStatus(block: Block, world: World, engine: BlockEngine, clock: Clock): BlockStatusUi {
    val blocking = engine.isBlocking(world, block, clock)
    val reason = engine.blockReason(world, block, clock)
    val used = engine.allowanceUsedMs(world, block, clock)
    val allowance = block.dailyAllowanceMs
    val rowBreak = engine.rowBreakRemainingMs(world, block, clock)
    val bought = engine.overrideRemainingMs(world, block.id, clock)
    val row = if (block.hasRowRule) "${formatRemaining(block.sessionMs - engine.rowUsedMs(world, block, clock))} of ${formatDuration(block.sessionMs)} in a row left" else null
    val daily = if (allowance > 0) "${formatDuration(used)} of ${formatDuration(allowance)} used today" else null
    val opensLeft = (block.opensPerDay - engine.opensUsed(world, block, clock)).coerceAtLeast(0)
    val opens = if (block.opensPerDay > 0 && allowance > 0) "$opensLeft of ${block.opensPerDay} opens left" else null
    return BlockStatusUi(
        label = when {
            rowBreak > 0 -> "On a break"
            reason == BlockReason.WAIT -> "Opening"
            reason == BlockReason.LIMIT -> "Limit reached"
            blocking -> "Blocking now"
            bought > 0 -> "Bought time"
            allowance > 0 -> "${formatRemaining(allowance - used)} left"
            block.opensPerDay > 0 -> "$opensLeft of ${block.opensPerDay} opens left"
            else -> "Open"
        },
        icon = if (blocking) Icons.Filled.Lock else Icons.Filled.HourglassBottom,
        tone = if (blocking) BlockState.ARMED else BlockState.UNLOCKED,
        line = when {
            rowBreak > 0 -> "${formatRemaining(rowBreak)} of the break left · ${formatDuration(block.sessionMs)} in a row is the most"
            bought > 0 -> "${formatRemaining(bought)} of bought time left" + (daily?.let { " · $it" } ?: "")
            else -> listOfNotNull(daily?.let { if (reason == BlockReason.LIMIT) "$it · opens at midnight" else it }, opens, row).joinToString(" · ")
        },
        fraction = when {
            allowance > 0 -> used.toFloat() / allowance
            block.hasRowRule -> engine.rowUsedMs(world, block, clock).toFloat() / block.sessionMs
            block.opensPerDay > 0 -> engine.opensUsed(world, block, clock).toFloat() / block.opensPerDay
            else -> null
        },
    )
}

/** When the last of what holds a block lets go, if only time holds it. */
fun freeAtWall(block: Block, world: World, engine: BlockEngine, clock: Clock): Long? =
    BlockTexts.freeAtWall(engine, world, block, clock)

/** Why an earned unlock can't be opened: it would go past today's unlock limit (I9). */
/** [usedMs] counts open unlocks at their full length (I9); [othersOpen] says so when there are some. */
fun capReachedText(usedMs: Long, capMs: Long, unlockMs: Long, othersOpen: Boolean = false): String {
    val left = capMs - usedMs
    val open = if (othersOpen) ", counting the unlocks open now" else ""
    return if (left < 60_000) {
        "Today's unlock time is used up$open (${formatDuration(capMs)} a day). It resets at midnight."
    } else {
        "Only ${formatDuration(left)} of today's unlock time is left$open, and this unlock is ${formatDuration(unlockMs)}. It resets at midnight."
    }
}

/** "Weekdays 09:00–17:00 · Sat 10:00–12:00", "Every day 21:00–22:30". */
fun describeSchedule(schedule: Schedule): String = BlockTexts.schedule(schedule)

fun shortDayName(isoDay: Int): String = DayOfWeek.of(isoDay).getDisplayName(TextStyle.SHORT, Locale.getDefault())

fun minuteText(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

/** "17:00" today, "Mon 09:00" on another day. */
fun formatClockTime(wallMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val t = Instant.ofEpochMilli(wallMs).atZone(zone)
    val time = t.format(HourMinute)
    return if (t.toLocalDate() == LocalDate.now(zone)) time else "${shortDayName(t.dayOfWeek.value)} $time"
}

private val HourMinute = DateTimeFormatter.ofPattern("HH:mm")

/** "3 h 12 min" / "45 min" / "< 1 min" / "0 min". */
fun formatDuration(ms: Long): String {
    if (ms in 1 until 60_000) return "< 1 min"
    val h = ms / 3_600_000
    val m = (ms % 3_600_000) / 60_000
    return when {
        h == 0L -> "$m min"
        m == 0L -> "$h h"
        else -> "$h h $m min"
    }
}

/** Like [formatDuration] but rounds up, so a countdown never says "0 min" while time is left. */
fun formatRemaining(ms: Long): String =
    if (ms <= 0) "0 min" else formatDuration(((ms + 59_999) / 60_000) * 60_000)

/**
 * Recomposes the caller every [periodMs]. Countdowns need it: a lockdown or allowance changes by
 * time alone, so the World (and its StateFlow) often doesn't change at all.
 */
@Composable
fun rememberTicker(periodMs: Long = 1_000): Long {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs)
            tick++
        }
    }
    return tick
}
