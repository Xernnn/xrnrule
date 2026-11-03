package com.xern.xrnrule.app.ui

import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.filled.Remove
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.xern.xrnrule.app.ui.theme.Kinds
import com.xern.xrnrule.app.ui.theme.KindColors
import com.xern.xrnrule.app.ui.theme.LocalKinds
import com.xern.xrnrule.domain.Habit
import com.xern.xrnrule.domain.HabitEntry
import com.xern.xrnrule.domain.HabitKind
import com.xern.xrnrule.domain.TargetType
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/** The icons a habit can pick, by the key stored in `Habit.icon`. */
val HabitIcons: List<Pair<String, ImageVector>> = listOf(
    "check" to Icons.Outlined.TaskAlt,
    "book" to Icons.Outlined.AutoStories,
    "study" to Icons.Outlined.School,
    "code" to Icons.Outlined.Code,
    "workout" to Icons.Outlined.FitnessCenter,
    "meditate" to Icons.Outlined.SelfImprovement,
    "water" to Icons.Outlined.WaterDrop,
    "sleep" to Icons.Outlined.Bedtime,
    "music" to Icons.Outlined.MusicNote,
    "write" to Icons.Outlined.EditNote,
    "language" to Icons.Outlined.Translate,
    "draw" to Icons.Outlined.Brush,
    "mind" to Icons.Outlined.Psychology,
    "calm" to Icons.Outlined.Spa,
    "timer" to Icons.Outlined.Timer,
)

/** A habit's icon: its own, else one for its kind. */
fun habitIcon(habit: Habit?): ImageVector =
    HabitIcons.firstOrNull { it.first == habit?.icon }?.second ?: when (habit?.kind) {
        HabitKind.TIMER -> Icons.Outlined.Timer
        HabitKind.COUNT -> Icons.Outlined.AddCircleOutline
        else -> Icons.Outlined.TaskAlt
    }

/** A habit's colours: its place in Loop's palette. */
@Composable
fun habitColors(habit: Habit?): KindColors {
    val kinds = LocalKinds.current
    return habit?.let { kinds.palette(it.paletteColor) } ?: kinds.habit
}

/** "2.5", "20": an amount kept in thousandths (contract §1), as Loop shows it. */
fun formatAmount(milli: Long): String =
    BigDecimal.valueOf(milli).movePointLeft(3).stripTrailingZeros().toPlainString()

/** Thousandths from what someone typed ("2.5" → 2500); null if it isn't a number from 0 up. */
fun parseAmount(text: String): Long? =
    text.trim().replace(',', '.').toBigDecimalOrNull()
        ?.takeIf { it.signum() >= 0 && it <= BigDecimal(1_000_000) }
        ?.movePointRight(3)?.setScale(0, RoundingMode.HALF_UP)?.toLong()

private fun unitSuffix(habit: Habit): String = habit.unit.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""

/** An amount of [habit] with its unit: "12 push-ups", "1 h 5 min". */
fun habitAmountText(habit: Habit, milli: Long): String = when (habit.kind) {
    HabitKind.TIMER -> formatDuration(milli * 60)
    else -> formatAmount(milli) + unitSuffix(habit)
}

/** Loop's frequency in words: "Every day", "3 times a week", "Every 2 days". */
fun frequencyText(num: Int, den: Int): String = when {
    num == den -> "Every day"
    num == 1 && den == 7 -> "Once a week"
    num == 1 && (den == 30 || den == 31) -> "Once a month"
    num == 1 -> "Every $den days"
    den == 7 -> "$num times a week"
    den == 30 || den == 31 -> "$num times a month"
    else -> "$num times in $den days"
}

/** "a day", "a week", "every 3 days": the period a numerical goal is for. */
fun periodText(den: Int): String = when (den) {
    1 -> "a day"
    7 -> "a week"
    30, 31 -> "a month"
    else -> "every $den days"
}

/** "Every day", "3 times a week", "20 push-ups a day", "At most 2 coffees a day". */
fun habitGoalText(habit: Habit): String = when {
    !habit.numerical -> frequencyText(habit.freqNum, habit.freqDen)
    habit.targetType == TargetType.AT_MOST -> "At most ${habitAmountText(habit, habit.goal)} ${periodText(habit.freqDen)}"
    else -> "${habitAmountText(habit, habit.goal)} ${periodText(habit.freqDen)}"
}

/**
 * "7 / 20 push-ups", "12 / 30 min": a block's way to its next unlock (contract I36), which stops at
 * one unlock's worth. Null for a check, which is done or not, and for an "at most" habit.
 */
fun habitProgressText(habit: Habit, amount: Long): String? {
    val unit = habit.unlockUnit?.takeIf { habit.numerical } ?: return null
    val shown = amount.coerceAtMost(unit)
    return when (habit.kind) {
        HabitKind.TIMER -> "${shown / 1000} / ${unit / 1000} min"
        else -> "${formatAmount(shown)} / ${formatAmount(unit)}${unitSuffix(habit)}"
    }
}

/** A day's entry in words: "12 push-ups", "45 min", "done", "skipped", "not yet". */
fun dayEntryText(habit: Habit, value: Long?): String = when {
    habit.numerical -> habitAmountText(habit, (value ?: 0).coerceAtLeast(0))
    value == HabitEntry.YES_MANUAL || value == HabitEntry.YES_AUTO -> "done"
    value == HabitEntry.SKIP -> "skipped"
    else -> "not yet"
}

/** "8 more push-ups", "12 min more", for the block screen's sentence. */
fun habitLeftText(habit: Habit, amount: Long): String {
    val left = ((habit.unlockUnit ?: 0) - amount).coerceAtLeast(0)
    return when (habit.kind) {
        HabitKind.CHECK -> habit.name
        HabitKind.COUNT -> "${formatAmount(left)} more" + (habit.unit.takeIf { it.isNotBlank() }?.let { " $it" } ?: " of ${habit.name}")
        HabitKind.TIMER -> formatRemaining(left * 60) + " more"
    }
}

/** The habit's icon on a rounded tile of its colour. */
@Composable
fun HabitTile(habit: Habit?, modifier: Modifier = Modifier, size: Int = 40) {
    val colors = habitColors(habit)
    Box(
        modifier.size(size.dp).clip(RoundedCornerShape((size * 0.3f).dp)).background(colors.fill),
        contentAlignment = Alignment.Center,
    ) {
        Icon(habitIcon(habit), contentDescription = null, tint = colors.color, modifier = Modifier.size((size * 0.55f).dp))
    }
}

/** Loop's ways to repeat a check: (times, in days). */
private enum class Repeat(val label: String) { DAILY("Every day"), WEEK("A week"), MONTH("A month"), DAYS("Every N days") }

private fun repeatOf(num: Int, den: Int): Repeat = when {
    num >= den -> Repeat.DAILY
    den == 7 -> Repeat.WEEK
    den == 30 || den == 31 -> Repeat.MONTH
    num == 1 -> Repeat.DAYS
    else -> Repeat.WEEK
}

/** "−  3  +" with what the number means beside it. */
@Composable
private fun CountStepper(value: Int, range: IntRange, label: String, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundIconButton(Icons.Filled.Remove, "Fewer", onClick = { onChange((value - 1).coerceIn(range)) }, size = 40.dp)
        Text(value.toString(), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.width(48.dp))
        RoundIconButton(Icons.Filled.Add, "More", onClick = { onChange((value + 1).coerceIn(range)) }, size = 40.dp)
        WGap(12)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The sheet for making or changing a habit: a name, what it measures, how often or how much, a
 * colour and an icon. [kinds] are the kinds on offer (a timer card offers timers only). What it
 * doesn't show (reminder, question, notes) is kept as it was: xrnloops edits those.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HabitEditorSheet(
    existing: Habit?,
    kinds: List<HabitKind>,
    onSave: (Habit) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var kind by remember { mutableStateOf(existing?.kind?.takeIf { it in kinds } ?: kinds.first()) }
    var count by remember { mutableStateOf(existing?.takeIf { it.kind == HabitKind.COUNT }?.goal?.let(::formatAmount) ?: "10") }
    var unit by remember { mutableStateOf(existing?.takeIf { it.kind == HabitKind.COUNT }?.unit ?: "") }
    var minutes by remember { mutableLongStateOf(existing?.takeIf { it.kind == HabitKind.TIMER }?.goal?.div(1000) ?: 30L) }
    var color by remember { mutableIntStateOf(existing?.paletteColor ?: if (kind == HabitKind.TIMER) 0 else 7) }
    var icon by remember { mutableStateOf(existing?.icon ?: "") }
    val kindColors = LocalKinds.current.palette(color)
    val goal = when (kind) {
        HabitKind.CHECK -> 0L
        HabitKind.COUNT -> parseAmount(count)?.takeIf { it > 0 }
        HabitKind.TIMER -> minutes.takeIf { it > 0 }?.times(1000)
    }
    // A check: freqNum times in freqDen days. A count or timer: its goal per freqDen days (Loop's way).
    var repeat by remember { mutableStateOf(existing?.takeIf { !it.numerical }?.let { repeatOf(it.freqNum, it.freqDen) } ?: Repeat.DAILY) }
    var times by remember { mutableIntStateOf(existing?.takeIf { !it.numerical && it.freqDen in setOf(7, 30, 31) }?.freqNum ?: 3) }
    var everyDays by remember { mutableIntStateOf(existing?.takeIf { !it.numerical && it.freqNum == 1 && it.freqDen !in setOf(1, 7, 30, 31) }?.freqDen ?: 2) }
    var period by remember { mutableIntStateOf(existing?.takeIf { it.numerical }?.freqDen?.takeIf { it in setOf(1, 7, 30) } ?: 1) }
    var atMost by remember { mutableStateOf(existing?.targetType == TargetType.AT_MOST) }
    val (freqNum, freqDen) = when {
        kind != HabitKind.CHECK -> 1 to period
        repeat == Repeat.DAILY -> 1 to 1
        repeat == Repeat.WEEK -> times.coerceIn(1, 6) to 7
        repeat == Repeat.MONTH -> times.coerceIn(1, 29) to 30
        else -> 1 to everyDays
    }
    val ready = name.isNotBlank() && goal != null
    val den = freqDen

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(if (existing == null) "New habit" else "Edit habit", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Box(
                    Modifier
                        .padding(end = 8.dp)
                        .clip(CircleShape)
                        .background(if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = ready) {
                            val base = existing ?: Habit(id = UUID.randomUUID().toString(), name = "")
                            onSave(
                                base.copy(
                                    name = name.trim(),
                                    kind = kind,
                                    goal = goal!!,
                                    unit = when (kind) {
                                        HabitKind.COUNT -> unit.trim()
                                        HabitKind.TIMER -> "min"
                                        HabitKind.CHECK -> ""
                                    },
                                    paletteColor = color,
                                    icon = icon,
                                    freqNum = freqNum,
                                    freqDen = freqDen,
                                    targetType = if (kind != HabitKind.CHECK && atMost) TargetType.AT_MOST else TargetType.AT_LEAST,
                                ),
                            )
                        }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) {
                    Text(
                        "Done",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (ready) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HabitTile(Habit("", name, kind, paletteColor = color, icon = icon), size = 50)
                    WGap(12)
                    FieldBox(name, onChange = { name = it.take(40) }, placeholder = "Name, e.g. Read", container = MaterialTheme.colorScheme.surfaceVariant)
                }
                if (kinds.size > 1) {
                    SectionLabel("What it measures")
                    SegmentSwitch(
                        options = kinds.map { kindLabel(it) },
                        selected = kinds.indexOf(kind),
                        onSelect = { kind = kinds[it] },
                    )
                }
                if (kind != HabitKind.CHECK) {
                    SectionLabel("Goal")
                    SegmentSwitch(options = listOf("At least", "At most"), selected = if (atMost) 1 else 0, onSelect = { atMost = it == 1 })
                    Gap(10)
                    SegmentSwitch(options = listOf("A day", "A week", "A month"), selected = listOf(1, 7, 30).indexOf(period).coerceAtLeast(0), onSelect = { period = listOf(1, 7, 30)[it] })
                    Gap(10)
                } else {
                    SectionLabel("How often")
                    SegmentSwitch(options = Repeat.entries.map { it.label }, selected = repeat.ordinal, onSelect = { repeat = Repeat.entries[it] })
                    when (repeat) {
                        Repeat.DAILY -> {}
                        Repeat.WEEK -> CountStepper(times.coerceIn(1, 6), 1..6, if (times == 1) "time a week" else "times a week") { times = it }
                        Repeat.MONTH -> CountStepper(times.coerceIn(1, 29), 1..29, if (times == 1) "time a month" else "times a month") { times = it }
                        Repeat.DAYS -> CountStepper(everyDays, 2..365, "days apart") { everyDays = it }
                    }
                    Gap(6)
                    Note("${frequencyText(freqNum, freqDen)}. Done once you tick it." + if (freqNum < freqDen) " Days in between fill in on their own, as in Loop." else "")
                }
                when (kind) {
                    HabitKind.CHECK -> {}
                    HabitKind.COUNT -> Row(verticalAlignment = Alignment.CenterVertically) {
                        FieldBox(
                            count,
                            onChange = { v -> count = v.filter { it.isDigit() || it == '.' || it == ',' }.take(8) },
                            placeholder = "10",
                            modifier = Modifier.weight(0.4f),
                            container = MaterialTheme.colorScheme.surfaceVariant,
                            keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        WGap(10)
                        FieldBox(unit, onChange = { unit = it.take(20) }, placeholder = "push-ups, pages…", modifier = Modifier.weight(0.6f), container = MaterialTheme.colorScheme.surfaceVariant)
                    }
                    HabitKind.TIMER -> MinuteChips(listOf(15, 25, 30, 45, 60), minutes, kindColors, format = ::shortMinutes) { minutes = it }
                }
                if (kind != HabitKind.CHECK) {
                    KindNote(
                        when {
                            atMost -> "An \"at most\" habit never opens a block."
                            kind == HabitKind.TIMER -> "Counted with the focus timer. Each block that asks for it opens once per day's share of the goal."
                            else -> "Add to it as you go. Each block that asks for it opens once per day's share of the goal."
                        },
                    )
                }
                SectionLabel("Colour")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val all = LocalKinds.current
                    Kinds.PALETTE_LIGHT.indices.forEach { index ->
                        val c = all.palette(index)
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(c.color)
                                .then(if (index == color) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .clickable { color = index },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (index == color) Icon(Icons.Filled.Check, contentDescription = "Colour ${index + 1}", tint = onKind(c.color), modifier = Modifier.size(18.dp))
                        }
                    }
                }
                SectionLabel("Icon")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HabitIcons.forEach { (key, vector) ->
                        val on = key == icon || (icon.isEmpty() && vector == habitIcon(Habit("", "", kind)))
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (on) kindColors.fill else MaterialTheme.colorScheme.surfaceVariant)
                                .then(if (on) Modifier.border(1.5.dp, kindColors.border, RoundedCornerShape(14.dp)) else Modifier)
                                .clickable { icon = key },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(vector, contentDescription = key, tint = if (on) kindColors.color else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (onDelete != null) {
                    Gap(24)
                    TextButton(onClick = onDelete) { Text("Delete habit", color = MaterialTheme.colorScheme.error) }
                }
                Gap(24)
            }
        }
    }
}

fun kindLabel(kind: HabitKind): String = when (kind) {
    HabitKind.CHECK -> "Check"
    HabitKind.COUNT -> "Count"
    HabitKind.TIMER -> "Timer"
}

/** The sheet for choosing which of the habits a block asks for. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitPickerSheet(
    title: String,
    habits: List<Habit>,
    initial: Set<String>,
    onDone: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var picked by remember { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.7f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Box(
                    Modifier
                        .padding(end = 8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { onDone(picked) }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) { Text("Done", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary) }
            }
            Gap(8)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                habits.forEach { habit ->
                    val on = habit.id in picked
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RowShape)
                            .clickable { picked = if (on) picked - habit.id else picked + habit.id }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HabitTile(habit)
                        WGap(14)
                        Column(Modifier.weight(1f)) {
                            Text(habit.name, style = MaterialTheme.typography.bodyLarge)
                            Text(habitGoalText(habit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        CheckCircle(on)
                    }
                }
            }
        }
    }
}

/** Adding to a counted habit today (I35): quick amounts, any amount, or taking some back. */
@Composable
fun LogAmountDialog(habit: Habit, todayTotal: Long, onLog: (Long) -> Unit, onDismiss: () -> Unit, dayLabel: String = "today") {
    val left = ((habit.unlockUnit ?: habit.goal) - todayTotal).coerceAtLeast(0)
    var text by remember { mutableStateOf(formatAmount(if (left > 0) left else 1000)) }
    val n = parseAmount(text)?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(habit.name) },
        text = {
            Column {
                Text(
                    "${habitAmountText(habit, todayTotal)} $dayLabel · ${habitGoalText(habit)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Gap(12)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1L, 5L, 10L).forEach { q -> ChoicePill("+$q", selected = false) { onLog(q * 1000); onDismiss() } }
                }
                Gap(12)
                FieldBox(
                    text,
                    onChange = { v -> text = v.filter { it.isDigit() || it == '.' || it == ',' }.take(8) },
                    placeholder = "How many",
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = { TextButton(enabled = n != null, onClick = { onLog(n!!); onDismiss() }) { Text("Add") } },
        dismissButton = {
            Row {
                if (todayTotal > 0) {
                    TextButton(enabled = n != null, onClick = { onLog(-minOf(n!!, todayTotal)); onDismiss() }) {
                        Text("Take back", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/**
 * One habit in a block's card or block screen (contract I36): tick a check (on the item's day:
 * yesterday's for a "yesterday" item), add to a count ("+" adds one; [onCount] asks how many), or
 * start the focus timer for a timer.
 */
@Composable
fun GoalRow(
    part: HabitPart.Goal,
    onLog: (String, Long) -> Unit,
    onSetEntry: (habitId: String, epochDay: Long, value: Long) -> Unit,
    onFocus: (habitId: String, start: Boolean) -> Unit,
    onCount: ((HabitPart.Goal) -> Unit)?,
) {
    val habit = part.habit
    val colors = habitColors(habit)
    HabitRow(
        icon = habitIcon(habit),
        kind = colors,
        label = part.name,
        value = habit?.let { habitProgressText(it, part.amount) },
        fraction = if (habit == null || !habit.numerical) null else part.fraction,
        dim = part.done,
        note = when {
            habit == null -> "This habit was deleted. Edit the block to take it out."
            habit.unlockUnit == null -> "An \"at most\" habit can't open a block"
            part.focusing -> "Focus timer running"
            !part.done && part.spent > 0 && !habit.numerical -> if (part.yesterday) "Done yesterday · that already opened this block" else "Done today · that already opened this block"
            !part.done && part.spent > 0 -> "Counting toward the next unlock"
            part.yesterday -> "Yesterday's"
            else -> null
        },
        onClick = when (habit?.kind) {
            HabitKind.COUNT -> onCount?.let { { it(part) } }
            HabitKind.TIMER -> if (part.yesterday) null else ({ onFocus(habit.id, false) })
            else -> null
        },
        action = habit?.let { h ->
            {
                when (h.kind) {
                    HabitKind.CHECK -> {
                        val ticked = part.dayValue == HabitEntry.YES_MANUAL
                        RowAction(
                            Icons.Filled.Check,
                            if (ticked) "Untick ${h.name}" else "Tick ${h.name}",
                            colors.color,
                            filled = ticked || part.dayValue == HabitEntry.YES_AUTO,
                            onClick = { onSetEntry(h.id, part.day, if (ticked) HabitEntry.NO else HabitEntry.YES_MANUAL) },
                        )
                    }
                    // A "yesterday" item counts yesterday's amount, so "+" adds to that day (I23).
                    HabitKind.COUNT -> RowAction(Icons.Filled.Add, "Add one to ${h.name}", colors.color, filled = false) {
                        if (part.yesterday) onSetEntry(h.id, part.day, part.dayValue.coerceAtLeast(0) + 1000) else onLog(h.id, 1000)
                    }
                    // The focus timer only counts today; yesterday's minutes are set in the habit's history.
                    HabitKind.TIMER -> if (part.yesterday) null else RowAction(
                        if (part.focusing) Icons.Filled.Timer else Icons.Filled.PlayArrow,
                        if (part.focusing) "Open the focus timer" else "Start the focus timer",
                        colors.color,
                        filled = true,
                    ) { onFocus(h.id, !part.focusing) }
                }
            }
        },
    )
}

