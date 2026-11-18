@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.xern.xrnrule.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xern.xrnrule.app.ui.theme.KindColors
import com.xern.xrnrule.app.ui.theme.LocalKinds
import com.xern.xrnrule.domain.Schedule

/** The pieces every section of the block form is built from: buttons, chips, days and times. */

/** A wide grey button with its icon and label in the middle: "⊕ Specify time". */
@Composable
internal fun GreyButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RowShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
        WGap(10)
        Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp))
    }
}

// =====================================================================================
//  Small pieces
// =====================================================================================

/**
 * One kind of unlock in step 2. Off: a grey row with a ⊕. On: tinted in the kind's colour, with a
 * check, a one-line summary, and its settings ([content]) underneath. [onToggle] null: always on.
 */
@Composable
internal fun KindCard(
    icon: ImageVector,
    title: String,
    /** What it is, while it's off. */
    about: String? = null,
    kind: KindColors,
    selected: Boolean,
    summary: String,
    onToggle: ((Boolean) -> Unit)?,
    /** In place of [icon]: another xrn app's tile, when the card is about that app. */
    tile: (@Composable (Dp) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(if (selected) kind.fill else MaterialTheme.colorScheme.surfaceVariant)
            .then(if (selected) Modifier.border(1.5.dp, kind.border, RowShape) else Modifier),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onToggle != null) Modifier.clickable { onToggle(!selected) } else Modifier)
                .padding(start = if (selected) 14.dp else 17.dp, end = 12.dp, top = if (selected) 12.dp else 13.dp, bottom = if (selected) 12.dp else 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tile != null) {
                Box(Modifier.size(if (selected) 32.dp else 20.dp), contentAlignment = Alignment.Center) { tile(if (selected) 30.dp else 20.dp) }
                WGap(if (selected) 14 else 13)
            } else if (selected) {
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, contentDescription = null, tint = kind.color, modifier = Modifier.size(18.dp)) }
                WGap(14)
            } else {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                WGap(17)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp))
                if (selected && summary.isNotEmpty()) {
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = kind.color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!selected && about != null) {
                    Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (onToggle != null) {
                Icon(
                    if (selected) Icons.Filled.CheckCircle else Icons.Filled.AddCircleOutline,
                    contentDescription = if (selected) "Remove $title" else "Add $title",
                    tint = if (selected) kind.color else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        AnimatedVisibility(selected) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), content = content)
        }
    }
    Gap(12)
}

@Composable
fun KindNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

/** A grey line with an (i): something worth knowing about the choice above. */
@Composable
fun Note(text: String) {
    Row(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp).padding(top = 2.dp))
        WGap(8)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A tinted row that opens something: "Tap to select apps ›". */
@Composable
internal fun PickRow(label: String, packages: List<String>, kind: KindColors?, onClick: () -> Unit) {
    val color = kind?.color ?: MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (kind != null) Color.Black.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant)
            .then(if (kind != null) Modifier.border(1.dp, kind.border, RoundedCornerShape(12.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (packages.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) { packages.take(3).forEach { AppIcon(it, size = 24.dp) } }
            WGap(10)
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = color)
    }
}

/** "+ Add another task", drawn as a dashed outline. */
@Composable
fun DashedButton(label: String, kind: KindColors?, onClick: () -> Unit) {
    val color = kind?.color ?: MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .drawBehind {
                drawRoundRect(
                    color = color.copy(alpha = 0.7f),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                    cornerRadius = CornerRadius(12.dp.toPx()),
                )
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.AddCircleOutline, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        WGap(12)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

/** A text box on a flat fill, no outline. */
@Composable
fun FieldBox(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surface,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    visual: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = keyboard,
            visualTransformation = visual,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A big choice tile for step 3. */
@Composable
internal fun OptionButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    centred: Boolean = false,
    onClick: () -> Unit,
) {
    val green = LocalKinds.current.habit
    val shape = RoundedCornerShape(12.dp)
    val tint = if (selected) green.color else MaterialTheme.colorScheme.onSurfaceVariant
    val look = modifier
        .heightIn(min = 46.dp)
        .clip(shape)
        .background(if (selected) green.fill else MaterialTheme.colorScheme.surfaceVariant)
        .then(if (selected) Modifier.border(1.5.dp, green.border, shape) else Modifier)
        .clickable(onClick = onClick)
    val text = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium)
    if (centred) {
        // Side by side these are narrow: the label gets the whole width, under its icon.
        Column(look.padding(horizontal = 6.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Gap(4)
            Text(label, style = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        Row(look.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            WGap(12)
            Text(label, style = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Minute presets as equal pills, plus "•••" for any other number. A custom value shows in that
 * last pill. [max] caps what can be typed.
 */
@Composable
fun MinuteChips(
    options: List<Long>,
    selected: Long,
    kind: KindColors,
    format: (Long) -> String = { it.toString() },
    max: Long = 24 * 60,
    onSelect: (Long) -> Unit,
) {
    var custom by remember { mutableStateOf(false) }
    val isCustom = selected !in options
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { m -> MinutePill(format(m), selected == m, kind, Modifier.weight(1f)) { onSelect(m) } }
        MinutePill(if (isCustom) format(selected) else "•••", isCustom, kind, Modifier.weight(1f)) { custom = true }
    }
    if (custom) {
        MinutesDialog(initial = selected, max = max, onDismiss = { custom = false }, onConfirm = { onSelect(it); custom = false })
    }
}

/** A few set numbers to pick from; one set on the laptop stays until another is picked. */
@Composable
fun NumberChips(options: List<Int>, selected: Int, kind: KindColors, suffix: String = "", onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { n -> MinutePill("$n$suffix", selected == n, kind, Modifier.weight(1f)) { onSelect(n) } }
    }
}

@Composable
internal fun MinutePill(label: String, selected: Boolean, kind: KindColors, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(if (selected) kind.color else Color.Black.copy(alpha = 0.18f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) onKind(kind.color) else kind.color, maxLines = 1)
    }
}

/** "15", "1h", "1h 30" for the pills. */
fun shortMinutes(m: Long): String = when {
    m < 60 -> "${m}m"
    m % 60 == 0L -> "${m / 60}h"
    else -> "${m / 60}h ${m % 60}"
}

@Composable
internal fun MinutesDialog(initial: Long, max: Long, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    var text by remember { mutableStateOf(initial.toString()) }
    val value = text.toLongOrNull()
    val ok = value != null && value in 1..max
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How many minutes?") },
        text = {
            Column {
                FieldBox(text, onChange = { v -> text = v.filter(Char::isDigit).take(5) }, placeholder = "Minutes", container = MaterialTheme.colorScheme.surfaceVariant)
                if (value != null && value > max) {
                    Text("At most $max.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onConfirm(value!!) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "Every day", "Weekdays", "Mon, Wed". */
internal fun describeDays(days: Set<Int>): String = describeSchedule(Schedule(days, null)).substringBefore(" · ")

@Composable
internal fun DayCircles(days: Set<Int>, onChange: (Set<Int>) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        (1..7).forEach { day ->
            val on = day in days
            val name = java.time.DayOfWeek.of(day).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
            Box(
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (on) DayBlue else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(role = Role.Checkbox) { onChange(if (on) days - day else days + day) }
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    name.take(1),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Habits First's blue for the days a schedule blocks. */
internal val DayBlue = Color(0xFF3B82F6)

@Composable
internal fun TimeRange(start: Int, end: Int, onStart: (Int) -> Unit, onEnd: (Int) -> Unit) {
    var editing by remember { mutableStateOf<String?>(null) } // "start" | "end"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TimeTile("From", start, Modifier.weight(1f)) { editing = "start" }
        TimeTile("To", end, Modifier.weight(1f)) { editing = "end" }
    }
    if (end <= start && end != start) {
        Text("Runs past midnight.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
    editing?.let { which ->
        TimeInputDialog(
            initialMinute = if (which == "start") start else end,
            onDismiss = { editing = null },
            onConfirm = { m ->
                if (which == "start") onStart(m) else onEnd(m)
                editing = null
            },
        )
    }
}

@Composable
internal fun TimeTile(label: String, minute: Int, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(minuteText(minute), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
internal fun TimeInputDialog(initialMinute: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val state = rememberTimePickerState(
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimeInput(state = state) },
    )
}
