package com.xern.xrnrule.app.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xern.xrnrule.app.data.WorldStore
import com.xern.xrnrule.app.hardcore.HardcoreGuard
import com.xern.xrnrule.app.hardcore.PasswordTries
import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockEdits
import com.xern.xrnrule.domain.EnforcementTier

/**
 * A change that makes a block easier to get past, held until [LoosenGuardDialog] lets it through
 * (contract §2.3).
 */
data class LoosenRequest(
    val title: String,
    val detail: String,
    /** A HARDCORE block is affected: the hardcore password is asked for instead of a wait. */
    val hardcore: Boolean,
    /** The blocks whose own settings this changes: while one is locked (I39), nothing goes through. */
    val blockIds: List<String> = emptyList(),
    val apply: () -> Unit,
)

/**
 * I6, §2.3: a change whose block's unlock would open other blocks ([BlockEdits.opens]) goes
 * through their guard: the request for those of [opened] that need it now, or null if none does.
 */
fun openingRequest(store: WorldStore, opened: List<Block>, title: String, apply: () -> Unit): LoosenRequest? {
    val guarded = opened.filter { BlockEdits.guardNeeded(store.engine, store.world.value, it, store.clock) }
    if (guarded.isEmpty()) return null
    val names = andList(guarded.map { it.name })
    return LoosenRequest(
        title,
        "Its unlock would open what $names block${if (guarded.size == 1) "s" else ""} too.",
        hardcore = guarded.any { it.enforcementTier == EnforcementTier.HARDCORE },
        blockIds = guarded.map { it.id },
        apply = apply,
    )
}

/** How long a SOFT block makes you wait before a loosening change goes through. */
const val LOOSEN_WAIT_MS = 60_000L

/**
 * Friction for loosening a block, the moment you're most likely to regret it: a 60-second wait
 * (leaving the screen starts it over), or the hardcore password for a HARDCORE block. A locked
 * block (I39) refuses instead, offering an emergency unlock ([LockedGuard]).
 */
@Composable
fun LoosenGuardDialog(request: LoosenRequest, guard: HardcoreGuard, store: WorldStore, onDismiss: () -> Unit) {
    val world by store.world.collectAsStateWithLifecycle()
    val locked = BlockEdits.lockedBy(world, request.blockIds, store.clock.nowWallMs())
    val usePassword = request.hardcore && guard.hasPassword()
    var password by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf<String?>(null) }
    // The wait starts when there is one to sit through: after an emergency unlock, from then.
    val startedAt = remember(locked == null) { SystemClock.elapsedRealtime() }
    rememberTicker(250)
    val leftMs = (LOOSEN_WAIT_MS - (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0)

    // Waiting it out means staying here: going to another app or the home screen cancels.
    val lifecycleOwner = LocalLifecycleOwner.current
    val dismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) dismiss() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (locked != null) {
        LockedGuard(locked, store, onDismiss)
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(request.title) },
        text = {
            Column {
                Text(request.detail)
                Gap(12)
                if (usePassword) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; wrong = null },
                        label = { Text("Hardcore password") },
                        singleLine = true,
                        isError = wrong != null,
                        supportingText = wrong?.let { ({ Text(it) }) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        if (leftMs > 0) {
                            "Wait ${(leftMs + 999) / 1000} s. Leaving this screen starts the wait over."
                        } else {
                            "You can go ahead now."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = if (usePassword) password.isNotEmpty() else leftMs == 0L,
                onClick = {
                    val wait = if (usePassword) guard.waitMs() else 0
                    if (wait > 0) {
                        wrong = PasswordTries.waitText(wait)
                    } else if (usePassword && !guard.verifyPassword(password)) {
                        wrong = "Wrong password." + guard.waitMs().takeIf { it > 0 }?.let { " " + PasswordTries.waitText(it) }.orEmpty()
                    } else {
                        request.apply()
                        onDismiss()
                    }
                },
            ) { Text("Go ahead") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep it") } },
    )
}
