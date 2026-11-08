package com.xern.xrnrule.app.ui

import androidx.compose.ui.text.drawText
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Edit
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xern.xrnrule.app.data.WorldStore
import com.xern.xrnrule.app.hardcore.HardcoreGuard
import com.xern.xrnrule.domain.BlockEdits
import com.xern.xrnrule.domain.BlockEvent
import com.xern.xrnrule.domain.EnforcementTier
import com.xern.xrnrule.domain.Habit
import com.xern.xrnrule.domain.HabitEntry
import com.xern.xrnrule.domain.HabitKind
import com.xern.xrnrule.domain.LoopRules
import com.xern.xrnrule.domain.TargetType
import com.xern.xrnrule.domain.World
import com.xern.xrnrule.domain.withHabit
import com.xern.xrnrule.domain.withoutHabit
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

// The Habits tab and each habit's page (xrnloops merge, stage 2): Loop's list and its habit
// screen, on xrnrule's habits (contract §1, I35). Every change goes through the engine's
// SetHabitEntry/LogHabit or World.withHabit, so it syncs like a tick on a block card.

/** One habit's days as Loop sees them: its entries, the days Loop fills in, and its scores. */
class HabitDays(val habit: Habit, val computed: Map<Long, Long>, private val scores: DoubleArray, val from: Long, val today: Long) {
    /** Loop's score today, 0..1. */
    val score: Double get() = scores.lastOrNull() ?: 0.0

    fun value(day: Long): Long = computed[day] ?: HabitEntry.UNKNOWN

    /** The score at the end of [day], or null before the habit's first entry. */
    fun scoreOn(day: Long): Double? = (day - from).toInt().takeIf { it in scores.indices }?.let { scores[it] }
}

/** Each habit's entries by day, from the one map of them all. */
fun entriesByHabit(entries: Map<String, HabitEntry>): Map<String, Map<Long, Long>> {
    val out = HashMap<String, HashMap<Long, Long>>()
    for ((key, entry) in entries) {
        val day = HabitEntry.day(key) ?: continue
        out.getOrPut(HabitEntry.habitId(key)) { HashMap() }[day] = entry.value
    }
    return out
}

fun habitDays(habit: Habit, original: Map<Long, Long>, today: Long): HabitDays {
    val computed = if (habit.numerical) original else LoopRules.computeEntries(habit, original)
    val from = LoopRules.range(computed, today).first
    return HabitDays(habit, computed, LoopRules.scores(habit, computed, from, today), from, today)
}

/** "Mon 3 Nov", or "today" / "yesterday". */
private fun dayLabel(day: Long, today: Long): String = when (day) {
    today -> "today"
    today - 1 -> "yesterday"
    else -> LocalDate.ofEpochDay(day).let { "${it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${it.dayOfMonth} ${it.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}" }
}

/** A numerical day's amount counts as done (Loop's colour): at least its share, or within the limit. */
private fun amountGood(habit: Habit, value: Long): Boolean {
    val share = habit.goal / habit.freqDen.coerceAtLeast(1)
    return when (habit.targetType) {
        TargetType.AT_LEAST -> value > 0 && value >= share
        TargetType.AT_MOST -> value >= 0 && value <= share
    }
}

// ---- the list ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HabitsScreen(world: World, store: WorldStore, onOpenHabit: (String) -> Unit) {
    var making by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    var amountFor by remember { mutableStateOf<Pair<Habit, Long>?>(null) }
    var pickFor by remember { mutableStateOf<Pair<Habit, Long>?>(null) }
    if (world.focus?.running == true) rememberTicker()
    val today = world.habitDay(store.clock)
    val byHabit = remember(world.habitEntries) { entriesByHabit(world.habitEntries) }
    val habits = world.habits.sortedBy { it.position }
    val days = remember(world.habits, byHabit, today) {
        habits.associate { it.id to habitDays(it, byHabit[it.id].orEmpty(), today) }
    }
    val active = habits.filterNot { it.archived }
    val archived = habits.filter { it.archived }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PageTitle(title = "habits", actions = { RoundIconButton(Icons.Filled.Add, "New habit", onClick = { making = true }) })
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Today first, as in Loop; as many days as fit beside a readable name.
            val columns = ((maxWidth - 40.dp - 150.dp) / CELL).toInt().coerceIn(3, 7)
            val shown = (0 until columns).map { today - it }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = ScreenPadding) {
                if (habits.isEmpty()) {
                    item {
                        Gap(24)
                        Text("No habits yet", style = MaterialTheme.typography.titleMedium)
                        Gap(6)
                        Text(
                            "Habits you track here are the same ones blocks ask for, and they sync with the web app through your home server.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Gap(16)
                        OutlinePill("New habit", onClick = { making = true }, icon = Icons.Filled.Add)
                    }
                } else {
                    // Pinned, as in Loop: further down you still see which column is which day.
                    stickyHeader { DayHeader(shown, today) }
                    items(active, key = { it.id }) { habit ->
                        HabitListRow(world, store, days.getValue(habit.id), shown, onOpen = { onOpenHabit(habit.id) },
                            onAmount = { day -> amountFor = habit to day }, onPick = { day -> pickFor = habit to day })
                    }
                    if (archived.isNotEmpty()) {
                        item {
                            Text(
                                (if (showArchived) "Hide archived" else "Archived") + " · ${archived.size}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().clickable { showArchived = !showArchived }.padding(vertical = 16.dp),
                            )
                        }
                        if (showArchived) {
                            items(archived, key = { it.id }) { habit ->
                                Box(Modifier.alpha(0.6f)) {
                                    HabitListRow(world, store, days.getValue(habit.id), shown, onOpen = { onOpenHabit(habit.id) },
                                        onAmount = { day -> amountFor = habit to day }, onPick = { day -> pickFor = habit to day })
                                }
                            }
                        }
                    }
                }
            }
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
    amountFor?.let { (habit, day) ->
        SetAmountDialog(habit, dayLabel(day, today), store.engine.habitAmount(world, habit.id, day, store.clock),
            onSet = { store.dispatch(BlockEvent.SetHabitEntry(habit.id, day, it)) }, onDismiss = { amountFor = null })
    }
    pickFor?.let { (habit, day) ->
        CheckPickDialog(habit, dayLabel(day, today), onPick = { store.dispatch(BlockEvent.SetHabitEntry(habit.id, day, it)) }, onDismiss = { pickFor = null })
    }
}

private val CELL = 44.dp

/** The days above the columns: "MON" over "3", today in bold. */
@Composable
private fun DayHeader(shown: List<Long>, today: Long) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f))
        shown.forEach { day ->
            val date = LocalDate.ofEpochDay(day)
            Column(Modifier.width(CELL), horizontalAlignment = Alignment.CenterHorizontally) {
                val weight = if (day == today) FontWeight.Bold else FontWeight.Normal
                Text(date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase(), fontSize = 10.sp, fontWeight = weight, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(date.dayOfMonth.toString(), fontSize = 12.sp, fontWeight = weight, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun HabitListRow(
    world: World,
    store: WorldStore,
    days: HabitDays,
    shown: List<Long>,
    onOpen: () -> Unit,
    onAmount: (Long) -> Unit,
    onPick: (Long) -> Unit,
) {
    val habit = days.habit
    val colors = habitColors(habit)
    Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpen).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(days.score, colors.color, Modifier.size(28.dp))
            WGap(12)
            Column(Modifier.weight(1f)) {
                Text(habit.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(habitGoalText(habit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        shown.forEach { day ->
            DayCell(habit, day, days.today,
                value = if (habit.numerical) store.engine.habitAmount(world, habit.id, day, store.clock) else days.value(day),
                onToggle = { store.dispatch(BlockEvent.SetHabitEntry(habit.id, day, LoopRules.nextToggleValue(days.value(day)))) },
                onAmount = { onAmount(day) },
                onPick = { onPick(day) },
            )
        }
    }
}

/** One day of one habit: a tap cycles a check (Loop's order) or opens a count's amount; a long press picks. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayCell(habit: Habit, day: Long, today: Long, value: Long, onToggle: () -> Unit, onAmount: () -> Unit, onPick: () -> Unit) {
    val colors = habitColors(habit)
    val haptics = LocalHapticFeedback.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val label = "${habit.name}, ${dayLabel(day, today)}: ${if (habit.numerical) habitAmountText(habit, value.coerceAtLeast(0)) else when (value) { HabitEntry.NO -> "not done"; HabitEntry.UNKNOWN -> "blank"; else -> dayEntryText(habit, value) }}"
    Box(
        Modifier
            .size(CELL)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                onClickLabel = label,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (habit.numerical) onAmount() else onToggle()
                },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (habit.numerical) onAmount() else onPick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (habit.numerical) {
            val v = value.coerceAtLeast(0)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (habit.kind == HabitKind.TIMER) "${v / 1000}" else formatAmount(v),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (amountGood(habit, v) && (v > 0 || habit.targetType == TargetType.AT_MOST)) colors.color else muted,
                    maxLines = 1,
                )
                Text(if (habit.kind == HabitKind.TIMER) "min" else habit.unit.take(6), fontSize = 9.sp, color = muted, maxLines = 1)
            }
        } else {
            when (value) {
                HabitEntry.YES_MANUAL -> Icon(Icons.Filled.Check, label, tint = colors.color, modifier = Modifier.size(24.dp))
                HabitEntry.YES_AUTO -> Icon(Icons.Filled.Check, label, tint = colors.color.copy(alpha = 0.45f), modifier = Modifier.size(24.dp))
                HabitEntry.SKIP -> Icon(Icons.Filled.KeyboardDoubleArrowRight, label, tint = colors.color, modifier = Modifier.size(22.dp))
                HabitEntry.NO -> Icon(Icons.Filled.Close, label, tint = muted.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                else -> Icon(Icons.Filled.Close, label, tint = muted.copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Loop's score as a ring in the habit's colour. */
@Composable
fun ScoreRing(score: Double, color: Color, modifier: Modifier = Modifier, width: Float = 3.5f) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    Canvas(modifier) {
        val stroke = width * density
        val inset = stroke / 2
        val arc = Size(size.width - stroke, size.height - stroke)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
        drawArc(color, -90f, (score.coerceIn(0.0, 1.0) * 360).toFloat(), false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** A count's or timer's amount on a day, set outright. */
@Composable
private fun SetAmountDialog(habit: Habit, day: String, current: Long, onSet: (Long) -> Unit, onDismiss: () -> Unit) {
    val timer = habit.kind == HabitKind.TIMER
    var text by remember { mutableStateOf(if (current <= 0) "" else if (timer) "${current / 1000}" else formatAmount(current)) }
    val parsed = if (text.isBlank()) 0L else if (timer) text.toLongOrNull()?.times(1000) else parseAmount(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${habit.name}, $day") },
        text = {
            Column {
                Text(habitGoalText(habit), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Gap(12)
                FieldBox(
                    text,
                    onChange = { v -> text = v.filter { it.isDigit() || (!timer && (it == '.' || it == ',')) }.take(8) },
                    placeholder = if (timer) "Minutes" else habit.unit.ifBlank { "How many" },
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    keyboard = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                )
            }
        },
        confirmButton = { TextButton(enabled = parsed != null, onClick = { onSet(parsed!!); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A check's day, picked rather than cycled (a long press). */
@Composable
private fun CheckPickDialog(habit: Habit, day: String, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${habit.name}, $day") },
        text = {
            Column {
                listOf(
                    "Done" to HabitEntry.YES_MANUAL,
                    "Skipped" to HabitEntry.SKIP,
                    "Not done" to HabitEntry.NO,
                    "Clear" to HabitEntry.UNKNOWN,
                ).forEach { (label, value) ->
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(value); onDismiss() }.padding(vertical = 14.dp, horizontal = 8.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- one habit -----------------------------------------------------------------------------

@Composable
fun HabitScreen(world: World, store: WorldStore, guard: HardcoreGuard, habitId: String, askAmountOn: Long? = null, onBack: () -> Unit) {
    val habit = world.habit(habitId)
    if (habit == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var editing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var loosen by remember { mutableStateOf<LoosenRequest?>(null) }
    // A reminder's "Enter" opens the page asking for that day's amount.
    var amountOn by remember { mutableStateOf(askAmountOn.takeIf { habit.numerical }) }
    var pickOn by remember { mutableStateOf<Long?>(null) }
    val today = world.habitDay(store.clock)
    val original = remember(world.habitEntries, habitId) { entriesByHabit(world.habitEntries)[habitId].orEmpty() }
    val days = remember(habit, original, today) { habitDays(habit, original, today) }
    val colors = habitColors(habit)
    val blocks = world.blocksUsing(habit.id)

    fun guarded(title: String, detail: String, apply: () -> Unit) {
        if (blocks.any { BlockEdits.guardNeeded(store.engine, world, it, store.clock) }) {
            loosen = LoosenRequest(title, detail, hardcore = blocks.any { it.enforcementTier == EnforcementTier.HARDCORE }, apply = apply)
        } else {
            apply()
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = onBack)
            Text(habit.name, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            RoundIconButton(Icons.Outlined.Edit, "Edit", onClick = { editing = true })
            WGap(8)
            Box {
                RoundIconButton(Icons.Filled.MoreVert, "More", onClick = { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (habit.archived) "Unarchive" else "Archive") },
                        onClick = {
                            menu = false
                            val apply = { store.edit { it.withHabit(habit.copy(archived = !habit.archived), store.clock.nowWallMs()) } }
                            // An archived habit still opens blocks; archiving only tidies the list.
                            apply()
                        },
                    )
                    DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HabitTile(habit, size = 44)
                WGap(12)
                Column {
                    Text(habitGoalText(habit), style = MaterialTheme.typography.bodyLarge)
                    habit.reminder?.let { r ->
                        Text("Reminder at %d:%02d".format(r.hour, r.minute), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Gap(16)
            Overview(days, colors.color)
            Gap(14)
            SectionLabel("Calendar")
            HFCard(Modifier.fillMaxWidth()) {
                HabitCalendar(days, colors.color, onDay = { day ->
                    if (habit.numerical) amountOn = day
                    else store.dispatch(BlockEvent.SetHabitEntry(habit.id, day, LoopRules.nextToggleValue(days.value(day))))
                }, onLongDay = { day -> if (habit.numerical) amountOn = day else pickOn = day })
                Gap(6)
                Text("Tap a day to change it; hold for more.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Gap(14)
            var scoreBucket by remember { mutableStateOf(Bucket.WEEK) }
            var historyBucket by remember { mutableStateOf(Bucket.WEEK) }
            SectionLabel("Score")
            HFCard(Modifier.fillMaxWidth()) {
                SegmentSwitch(options = Bucket.entries.map { it.label }, selected = scoreBucket.ordinal, onSelect = { scoreBucket = Bucket.entries[it] })
                Gap(12)
                BucketChart(days, colors.color, scoreBucket, score = true)
            }
            Gap(14)
            SectionLabel(if (habit.numerical) "Amount" else "Days done")
            HFCard(Modifier.fillMaxWidth()) {
                SegmentSwitch(options = Bucket.entries.map { it.label }, selected = historyBucket.ordinal, onSelect = { historyBucket = Bucket.entries[it] })
                Gap(12)
                BucketChart(days, colors.color, historyBucket, score = false)
            }
            Gap(14)
            SectionLabel("Best streaks")
            HFCard(Modifier.fillMaxWidth()) { Streaks(days, colors.color) }
            Gap(14)
            SectionLabel("Opens")
            HFCard(Modifier.fillMaxWidth()) {
                Text(
                    when {
                        habit.unlockUnit == null -> "An \"at most\" habit doesn't open blocks."
                        blocks.isEmpty() -> "No block asks for it yet."
                        else -> andList(blocks.map { it.name })
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Gap(24)
        }
    }

    if (editing) {
        HabitEditorSheet(
            existing = habit,
            kinds = HabitKind.entries,
            onSave = { h ->
                editing = false
                val apply = { store.edit { it.withHabit(h, store.clock.nowWallMs()) } }
                if (BlockEdits.habitLoosens(habit, h)) guarded("Make ${habit.name} easier?", "Blocks that ask for it open with less.") { apply() } else apply()
            },
            onDismiss = { editing = false },
            onDelete = { editing = false; confirmDelete = true },
        )
    }
    loosen?.let { LoosenGuardDialog(it, guard, onDismiss = { loosen = null }) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${habit.name}?") },
            text = {
                Text(
                    "It goes from the web app and every device too, with its history. Archive it instead to keep the history." +
                        if (blocks.isEmpty()) "" else " ${andList(blocks.map { it.name })} can't open until you edit ${if (blocks.size == 1) "it" else "them"}.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    // Deleting takes a way to open a block away, so it isn't a loosening; it needs no wait.
                    store.edit { it.withoutHabit(habit.id, store.clock.nowWallMs()) }
                    onBack()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    amountOn?.let { day ->
        SetAmountDialog(habit, dayLabel(day, today), store.engine.habitAmount(world, habit.id, day, store.clock),
            onSet = { store.dispatch(BlockEvent.SetHabitEntry(habit.id, day, it)) }, onDismiss = { amountOn = null })
    }
    pickOn?.let { day ->
        CheckPickDialog(habit, dayLabel(day, today), onPick = { store.dispatch(BlockEvent.SetHabitEntry(habit.id, day, it)) }, onDismiss = { pickOn = null })
    }
}

/** Loop's overview: the score now, how it moved this month and this year, and days done. */
@Composable
private fun Overview(days: HabitDays, color: Color) {
    val today = LocalDate.ofEpochDay(days.today)
    val monthStart = today.withDayOfMonth(1).toEpochDay() - 1
    val yearStart = today.withDayOfYear(1).toEpochDay() - 1
    fun change(since: Long): String? = (days.scoreOn(since) ?: 0.0.takeIf { since < days.from })?.let { before ->
        val d = ((days.score - before) * 100).roundToInt()
        (if (d >= 0) "+" else "−") + "${kotlin.math.abs(d)}%"
    }
    val habit = days.habit
    val total = if (habit.numerical) {
        habitAmountText(habit, (days.from..days.today).sumOf { days.value(it).coerceAtLeast(0) })
    } else {
        "${(days.from..days.today).count { days.value(it) == HabitEntry.YES_MANUAL }}"
    }
    HFCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(days.score, color, Modifier.size(56.dp), width = 6f)
            WGap(16)
            Stat("${(days.score * 100).roundToInt()}%", "Score", Modifier.weight(1f))
            Stat(change(monthStart) ?: "–", "Month", Modifier.weight(1f))
            Stat(change(yearStart) ?: "–", "Year", Modifier.weight(1f))
            Stat(total, if (habit.numerical) "Total" else "Days done", Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/**
 * Loop's calendar: a column per week (Monday on top), newest on the right, 26 weeks back,
 * scrolled to today. A done day is filled with the habit's colour, one Loop filled in or a
 * partial amount lighter, a skipped day with a ring; days after today are left out.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HabitCalendar(days: HabitDays, color: Color, onDay: (Long) -> Unit, onLongDay: (Long) -> Unit) {
    val weeks = 26
    val todayDate = LocalDate.ofEpochDay(days.today)
    val lastMonday = days.today - (todayDate.dayOfWeek.value - 1)
    val firstMonday = lastMonday - 7L * (weeks - 1)
    val scroll = rememberScrollState()
    LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val habit = days.habit
    Row(Modifier.horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (w in 0 until weeks) {
            val monday = firstMonday + 7L * w
            Column(verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val date = LocalDate.ofEpochDay(monday)
                Text(
                    if (date.dayOfMonth <= 7) date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()) else "",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.height(16.dp),
                )
                for (d in 0 until 7) {
                    val day = monday + d
                    if (day > days.today) {
                        Box(Modifier.size(20.dp))
                        continue
                    }
                    val v = days.value(day)
                    val fill = when {
                        habit.numerical -> {
                            val share = (habit.goal / habit.freqDen.coerceAtLeast(1)).coerceAtLeast(1)
                            when {
                                v <= 0 -> empty
                                habit.targetType == TargetType.AT_MOST -> if (v <= share) color else color.copy(alpha = 0.3f)
                                else -> color.copy(alpha = (0.3f + 0.7f * (v.toFloat() / share)).coerceAtMost(1f))
                            }
                        }
                        v == HabitEntry.YES_MANUAL -> color
                        v == HabitEntry.YES_AUTO -> color.copy(alpha = 0.45f)
                        else -> empty
                    }
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(fill)
                            .then(if (v == HabitEntry.SKIP && !habit.numerical) Modifier.background(color.copy(alpha = 0.2f)) else Modifier)
                            .combinedClickable(
                                onClickLabel = "${habit.name}, ${dayLabel(day, days.today)}",
                                onClick = { onDay(day) },
                                onLongClick = { onLongDay(day) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (v == HabitEntry.SKIP && !habit.numerical) {
                            Icon(Icons.Filled.KeyboardDoubleArrowRight, null, tint = color, modifier = Modifier.size(12.dp))
                        } else if (day == days.today) {
                            Box(Modifier.size(4.dp).clip(RoundedCornerShape(2.dp)).background(if (fill == empty) color else MaterialTheme.colorScheme.surface))
                        }
                    }
                }
            }
        }
    }
}

/** Loop's best streaks: the five longest, newest first, as bars against the longest. */
@Composable
private fun Streaks(days: HabitDays, color: Color) {
    val best = remember(days) { LoopRules.bestStreaks(LoopRules.streaks(days.habit, days.computed, days.from, days.today), 5) }
    if (best.isEmpty()) {
        Text("No streak yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val longest = best.maxOf { it.length }.toFloat()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        best.forEach { s ->
            val start = LocalDate.ofEpochDay(s.start)
            val end = LocalDate.ofEpochDay(s.end)
            fun short(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}" +
                if (d.year != LocalDate.ofEpochDay(days.today).year) " ${d.year}" else ""
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(22.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth(s.length / longest)
                            .height(22.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (s.end == days.today || s.end == days.today - 1) color else color.copy(alpha = 0.55f)),
                    )
                }
                WGap(10)
                Text(
                    "${s.length} ${if (s.length == 1) "day" else "days"} · ${short(start)}–${short(end)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(150.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

private enum class Bucket(val label: String, val count: Int) { WEEK("Weeks", 26), MONTH("Months", 18) }

/** The first day of each [bucket] up to today's, oldest first. */
private fun bucketStarts(today: Long, bucket: Bucket): List<Long> {
    val date = LocalDate.ofEpochDay(today)
    return when (bucket) {
        Bucket.WEEK -> (bucket.count - 1 downTo 0).map { date.minusDays((date.dayOfWeek.value - 1).toLong()).minusWeeks(it.toLong()).toEpochDay() }
        Bucket.MONTH -> (bucket.count - 1 downTo 0).map { date.withDayOfMonth(1).minusMonths(it.toLong()).toEpochDay() }
    }
}

private fun bucketName(start: Long, bucket: Bucket): String {
    val d = LocalDate.ofEpochDay(start)
    val month = d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    return when (bucket) {
        Bucket.WEEK -> "Week of ${d.dayOfMonth} $month"
        Bucket.MONTH -> "$month ${d.year}"
    }
}

/**
 * Loop's score card ([score]: the score at the end of each week or month, a line) or history card
 * (days done, or the amount, in each, as bars). One series in the habit's colour, so no legend;
 * the latest value is labelled, and a tap shows any other above the chart.
 */
@Composable
private fun BucketChart(days: HabitDays, color: Color, bucket: Bucket, score: Boolean) {
    val starts = remember(days, bucket) { bucketStarts(days.today, bucket) }
    val values: List<Double?> = remember(days, bucket, score) {
        starts.mapIndexed { i, start ->
            val end = ((starts.getOrNull(i + 1) ?: (days.today + 1)) - 1).coerceAtMost(days.today)
            if (end < days.from) return@mapIndexed null
            if (score) {
                days.scoreOn(end)?.times(100)
            } else if (days.habit.numerical) {
                (start..end).sumOf { days.value(it).coerceAtLeast(0) } / 1000.0
            } else {
                (start..end).count { days.value(it) == HabitEntry.YES_MANUAL }.toDouble()
            }
        }
    }
    var picked by remember(bucket, score) { mutableStateOf<Int?>(null) }
    val top = if (score) 100.0 else (values.filterNotNull().maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    fun text(v: Double) = if (score) "${v.roundToInt()}%" else if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)
    val shownIndex = picked ?: values.indexOfLast { it != null }.takeIf { it >= 0 }
    Text(
        shownIndex?.let { i -> values[i]?.let { "${bucketName(starts[i], bucket)}: ${text(it)}" } } ?: "No days yet",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Gap(6)
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val surface = MaterialTheme.colorScheme.surface
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(140.dp)
            .pointerInput(values) {
                detectTapGestures { offset ->
                    val left = 28.dp.toPx()
                    val step = (size.width - left) / values.size
                    picked = ((offset.x - left) / step).toInt().coerceIn(0, values.size - 1).takeIf { values[it] != null }
                }
            },
    ) {
        val left = 28.dp.toPx()
        val bottom = size.height - 14.dp.toPx()
        val plotTop = 6.dp.toPx()
        val step = (size.width - left) / values.size
        fun y(v: Double) = (bottom - (v / top) * (bottom - plotTop)).toFloat()
        // Recessive guides at 0, half and the top, labelled on the left.
        listOf(0.0, top / 2, top).filter { score || it == kotlin.math.floor(it) }.forEach { g ->
            drawLine(grid, Offset(left, y(g)), Offset(size.width, y(g)), strokeWidth = 1.dp.toPx())
            val label = measurer.measure(text(g), labelStyle)
            drawText(label, topLeft = Offset(left - label.size.width - 4.dp.toPx(), y(g) - label.size.height / 2))
        }
        // Month names under the first bucket of each month (weeks), or every third month.
        starts.forEachIndexed { i, start ->
            val d = LocalDate.ofEpochDay(start)
            val show = if (bucket == Bucket.WEEK) d.dayOfMonth <= 7 else d.monthValue % 3 == 1
            if (show) {
                val label = measurer.measure(d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()), labelStyle)
                drawText(label, topLeft = Offset(left + step * i, bottom + 2.dp.toPx()))
            }
        }
        val pickedIndex = picked
        if (pickedIndex != null) {
            val x = left + step * pickedIndex + step / 2
            drawLine(grid.copy(alpha = 0.3f), Offset(x, plotTop), Offset(x, bottom), strokeWidth = 1.dp.toPx())
        }
        if (score) {
            val points = values.mapIndexedNotNull { i, v -> v?.let { Offset(left + step * i + step / 2, y(it)) } }
            for (i in 1 until points.size) {
                drawLine(color, points[i - 1], points[i], strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
            val mark = shownIndex?.let { i -> values[i]?.let { Offset(left + step * i + step / 2, y(it)) } }
            if (mark != null) {
                drawCircle(surface, radius = 6.dp.toPx(), center = mark)
                drawCircle(color, radius = 4.dp.toPx(), center = mark)
            }
        } else {
            val gap = 2.dp.toPx()
            val radius = 4.dp.toPx()
            values.forEachIndexed { i, v ->
                if (v == null || v <= 0) return@forEachIndexed
                val x = left + step * i + gap / 2
                val w = (step - gap).coerceAtLeast(1f)
                val yTop = y(v)
                val fill = if (pickedIndex == null || pickedIndex == i) color else color.copy(alpha = 0.45f)
                // Rounded at the data end, square on the baseline.
                drawRoundRect(fill, Offset(x, yTop), Size(w, bottom - yTop), androidx.compose.ui.geometry.CornerRadius(radius.coerceAtMost(w / 2)))
                if (bottom - yTop > radius) drawRect(fill, Offset(x, bottom - radius), Size(w, radius))
            }
        }
    }
}
