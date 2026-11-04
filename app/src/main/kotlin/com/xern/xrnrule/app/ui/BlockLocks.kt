package com.xern.xrnrule.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xern.xrnrule.app.data.WorldStore
import com.xern.xrnrule.app.hardcore.HardcoreGuard
import com.xern.xrnrule.app.ui.theme.LocalKinds
import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockEdits
import com.xern.xrnrule.domain.BlockLock
import com.xern.xrnrule.domain.Defaults
import com.xern.xrnrule.domain.EnforcementTier
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How long a block can be locked for (contract I39). */
private val LOCK_CHOICES: List<Pair<String, Long>> = listOf(
    "24 hours" to 24L * HOUR_MS,
    "1 week" to 7 * 24L * HOUR_MS,
    "1 month" to 30 * 24L * HOUR_MS,
    "3 months" to 91 * 24L * HOUR_MS,
    "1 year" to 365 * 24L * HOUR_MS,
)

private const val HOUR_MS = 60L * 60 * 1000

private val ThisYear = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")
private val OtherYear = DateTimeFormatter.ofPattern("d MMM yyyy")

/** When a lock ends: the day and time within a year, else the date. */
fun formatLockEnd(wallMs: Long, nowWall: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = Instant.ofEpochMilli(wallMs).atZone(zone)
    val now = Instant.ofEpochMilli(nowWall).atZone(zone)
    return (if (at.year == now.year) ThisYear else OtherYear).format(at)
}

/**
 * The small lock under a block card's switch, as in Habits First: grey while nothing holds the
 * block, in the strict colour while it is locked for a time or behind the hardcore password.
 */
@Composable
fun LockPill(name: String, active: Boolean, onClick: () -> Unit) {
    val strict = LocalKinds.current.strict
    Box(
        Modifier
            .size(width = 30.dp, height = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) strict.fill else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = if (active) "$name is locked" else "Lock $name",
            tint = if (active) strict.color else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * "Lock Zen?": until the hardcore password says otherwise (sideload builds), or for a time
 * (I39), only lengths that end later than the lock it has. A length is confirmed before it
 * locks, since only an emergency unlock (I40) ends it early. A block behind the password offers
 * [onUnhardcore] instead, which asks for the password like any loosening.
 */
@Composable
fun LockDialog(block: Block, store: WorldStore, guard: HardcoreGuard, onUnhardcore: () -> Unit, onDismiss: () -> Unit) {
    val world by store.world.collectAsStateWithLifecycle()
    val current = world.block(block.id) ?: block
    val now = store.clock.nowWallMs()
    val until = current.lock?.untilWall ?: 0
    val choices = LOCK_CHOICES.filter { (_, ms) -> now + ms > until }
    val left = BlockEdits.emergencyLeft(world)
    val hardcore = current.enforcementTier == EnforcementTier.HARDCORE
    var picked by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var settingPassword by remember { mutableStateOf(false) }

    fun makeHardcore() {
        store.edit { w -> w.copy(blocks = w.blocks.map { if (it.id == block.id) it.copy(enforcementTier = EnforcementTier.HARDCORE) else it }) }
        onDismiss()
    }

    if (settingPassword) {
        SetPasswordDialog(guard, onDone = { makeHardcore() }, onDismiss = onDismiss)
        return
    }
    val choice = picked
    if (choice != null) {
        val end = now + choice.second
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Lock until ${formatLockEnd(end, now)}?") },
            text = {
                Text(
                    "Until then nothing that makes ${current.name} easier to get past can change: not its apps, habits or " +
                        "times, not turning it off or deleting it, whatever you wait or type. Making it stricter still works. " +
                        "Only an emergency unlock ends it early: you have $left of ${Defaults.EMERGENCY_UNLOCKS} left, " +
                        "and used ones never come back.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val at = store.clock.nowWallMs()
                    store.edit { w ->
                        w.copy(blocks = w.blocks.map { b ->
                            // Never shorter than a lock set meanwhile, on this phone or another device.
                            if (b.id == block.id && (b.lock?.untilWall ?: 0) < end) b.copy(lock = BlockLock(end, at)) else b
                        })
                    }
                    onDismiss()
                }) { Text("Lock it") }
            },
            dismissButton = { TextButton(onClick = { picked = null }) { Text("Back") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Lock ${current.name}?") },
        text = {
            Column {
                Text(
                    if (until > now) {
                        "Locked until ${formatLockEnd(until, now)}. A longer lock replaces it; only emergency unlocks " +
                            "($left left) end one early."
                    } else {
                        "A timed lock can't be opened early, not even with your password. Only emergency unlocks " +
                            "($left left) break it."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Gap(12)
                if (guard.isSupported() && !hardcore) {
                    LockChoice(if (guard.hasPassword()) "Until I unlock it (password)" else "Until I unlock it (set password)") {
                        if (guard.hasPassword()) makeHardcore() else settingPassword = true
                    }
                }
                if (hardcore) {
                    LockChoice("Unlock it with the password") {
                        onDismiss()
                        onUnhardcore()
                    }
                }
                choices.forEach { c -> LockChoice(c.first) { picked = c } }
                if (choices.isEmpty()) {
                    Text("It's already locked for longer than a year.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = onDismiss,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) { Text("Cancel") }
        },
    )
}

/** One of the lock dialog's lengths: a centred line, the whole width tappable. */
@Composable
private fun LockChoice(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
    }
}

/** The hardcore password, typed twice, set before a block is first locked behind it. */
@Composable
private fun SetPasswordDialog(guard: HardcoreGuard, onDone: () -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val hidden = PasswordVisualTransformation()
    val keyboard = KeyboardOptions(keyboardType = KeyboardType.Password)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Set a password") },
        text = {
            Column {
                Text(
                    "Changes that make a hardcore block easier to get past will ask for it. It can be changed in " +
                        "Settings, never removed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Gap(12)
                FieldBox(first, { first = it }, "Password", container = MaterialTheme.colorScheme.surfaceVariant, keyboard = keyboard, visual = hidden)
                Gap(8)
                FieldBox(again, { again = it }, "Again", container = MaterialTheme.colorScheme.surfaceVariant, keyboard = keyboard, visual = hidden)
                if (again.isNotEmpty() && again != first) {
                    Gap(6)
                    Text("The two don't match.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = first.isNotBlank() && first == again,
                onClick = {
                    guard.setPassword(first)
                    onDone()
                },
            ) { Text("Set and lock") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * What the loosen guard shows instead of its wait while [locked] is locked (I39): until when,
 * and an emergency unlock (I40) if any are left. Using one ends the lock; the change it was for
 * then waits like any other.
 */
@Composable
fun LockedGuard(locked: Block, store: WorldStore, onDismiss: () -> Unit) {
    val world by store.world.collectAsStateWithLifecycle()
    val now = store.clock.nowWallMs()
    val left = BlockEdits.emergencyLeft(world)
    var asking by remember { mutableStateOf(false) }
    val end = formatLockEnd(locked.lock!!.untilWall, now)
    if (!asking) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("${locked.name} is locked") },
            text = {
                Text(
                    "Until $end, nothing can make it easier to get past. " +
                        if (left > 0) "An emergency unlock ends the lock now ($left of ${Defaults.EMERGENCY_UNLOCKS} left)."
                        else "All ${Defaults.EMERGENCY_UNLOCKS} emergency unlocks are used.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Keep it") } },
            dismissButton = if (left > 0) ({ TextButton(onClick = { asking = true }) { Text("Emergency unlock") } }) else null,
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Use an emergency unlock?") },
        text = {
            Text(
                "It ends the lock on ${locked.name}, which would have lasted until $end. You'd have ${left - 1} left, " +
                    "on every device, and they never come back. The change still waits like any other.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Keep the lock") } },
        dismissButton = {
            TextButton(onClick = {
                val at = store.clock.nowWallMs()
                store.edit { w -> BlockEdits.emergencyUnlock(w, locked.id, at) ?: w }
                asking = false
            }) { Text("Use one", color = MaterialTheme.colorScheme.error) }
        },
    )
}
