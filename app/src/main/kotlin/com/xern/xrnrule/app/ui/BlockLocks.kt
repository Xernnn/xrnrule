package com.xern.xrnrule.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LockClock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xern.xrnrule.app.data.WorldStore
import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockEdits
import com.xern.xrnrule.domain.BlockLock
import com.xern.xrnrule.domain.Defaults
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

/** The edit page's row: whether [block] is locked, and a way to lock it (or for longer). */
@Composable
fun LockRow(block: Block, store: WorldStore) {
    val world by store.world.collectAsStateWithLifecycle()
    val current = world.block(block.id) ?: block
    val now = store.clock.nowWallMs()
    val locked = BlockEdits.locked(current, now)
    var choosing by remember { mutableStateOf(false) }
    GroupRow(
        title = if (locked) "Locked" else "Lock this block",
        icon = Icons.Outlined.LockClock,
        subtitle = if (locked) {
            "Until ${formatLockEnd(current.lock!!.untilWall, now)}. Tap to lock it for longer."
        } else {
            "For a while, nothing can make it easier to get past: no wait or password opens it early."
        },
        first = true,
        onClick = { choosing = true },
    )
    if (choosing) LockDialog(current, store, onDismiss = { choosing = false })
}

/** Picks a length, then locks: only choices that end later than the lock it has, if any. */
@Composable
private fun LockDialog(block: Block, store: WorldStore, onDismiss: () -> Unit) {
    val world by store.world.collectAsStateWithLifecycle()
    val now = store.clock.nowWallMs()
    val until = block.lock?.untilWall ?: 0
    val choices = LOCK_CHOICES.filter { (_, ms) -> now + ms > until }
    var picked by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val left = BlockEdits.emergencyLeft(world)

    val choice = picked
    if (choice == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Lock ${block.name} for") },
            text = {
                Column {
                    if (choices.isEmpty()) Text("It's already locked for longer than a year.")
                    choices.forEachIndexed { i, c ->
                        GroupRow(title = c.first, subtitle = "Until ${formatLockEnd(now + c.second, now)}", first = i == 0, onClick = { picked = c })
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        return
    }
    val end = now + choice.second
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lock it until ${formatLockEnd(end, now)}?") },
        text = {
            Text(
                "Until then nothing that makes ${block.name} easier to get past can change: not its apps, habits or " +
                    "times, not turning it off or deleting it, whatever you wait or type. Making it stricter still works. " +
                    "Only an emergency unlock ends it early: you have $left of ${Defaults.EMERGENCY_UNLOCKS} left, " +
                    "and used ones never come back.",
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
            title = { Text("${locked.name} is locked") },
            text = {
                Text(
                    "Until $end, nothing can make it easier to get past. " +
                        if (left > 0) "An emergency unlock ends the lock now ($left of ${Defaults.EMERGENCY_UNLOCKS} left)."
                        else "All ${Defaults.EMERGENCY_UNLOCKS} emergency unlocks are used.",
                )
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Keep it") } },
            dismissButton = if (left > 0) ({ TextButton(onClick = { asking = true }) { Text("Emergency unlock") } }) else null,
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Use an emergency unlock?") },
        text = {
            Text(
                "It ends the lock on ${locked.name}, which would have lasted until $end. You'd have ${left - 1} left, " +
                    "on every device, and they never come back. The change still waits like any other.",
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
