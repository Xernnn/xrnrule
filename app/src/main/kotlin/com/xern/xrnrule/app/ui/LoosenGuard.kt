package com.xern.xrnrule.app.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xern.xrnrule.app.data.WorldStore
import com.xern.xrnrule.app.hardcore.HardcoreGuard
import com.xern.xrnrule.app.hardcore.PasswordTries
import com.xern.xrnrule.app.ui.theme.LocalKinds
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

    val blocks = request.blockIds.mapNotNull { id -> world.block(id) }
    val chips = blocks.map { it.name to blockColor(world.blocks, it.id) }
    Dialog(onDismissRequest = onDismiss) {
        LoosenWait(
            title = request.title,
            detail = request.detail,
            chips = chips,
            color = chips.firstOrNull()?.second ?: LocalKinds.current.strict.color,
            leftMs = leftMs,
            usePassword = usePassword,
            password = password,
            onPassword = { password = it; wrong = null },
            wrong = wrong,
            onGoAhead = {
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
            onCancel = onDismiss,
        )
    }
}

/**
 * What the guard shows: the wait as a big ring counting down [LOOSEN_WAIT_MS] (in [color], the
 * block's), the change in one line ([title]; [detail] is for screen readers), the blocks it
 * touches as their chips, and one Cancel. Go ahead comes up only once the wait is over. A
 * HARDCORE change asks for the password instead of the wait.
 */
@Composable
internal fun LoosenWait(
    title: String,
    detail: String,
    chips: List<Pair<String, Color>>,
    color: Color,
    leftMs: Long,
    usePassword: Boolean,
    password: String = "",
    onPassword: (String) -> Unit = {},
    wrong: String? = null,
    onGoAhead: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 22.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (usePassword) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Password, contentDescription = null, tint = color, modifier = Modifier.size(30.dp))
            }
        } else {
            CountdownRing(leftMs, LOOSEN_WAIT_MS, color, size = 176.dp)
        }
        Gap(20)
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { contentDescription = "$title $detail" },
        )
        if (chips.isNotEmpty()) {
            Gap(10)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
                chips.take(3).forEach { (name, c) -> BlockChip(name, c, onClick = null, small = true, modifier = Modifier.widthIn(max = 140.dp)) }
                if (chips.size > 3) Text("+${chips.size - 3}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (usePassword) {
            Gap(16)
            OutlinedTextField(
                value = password,
                onValueChange = onPassword,
                label = { Text("Hardcore password") },
                singleLine = true,
                isError = wrong != null,
                supportingText = wrong?.let { ({ Text(it) }) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Gap(22)
        val ready = if (usePassword) password.isNotEmpty() else leftMs == 0L
        if (usePassword || ready) {
            PillButton("Go ahead", onClick = onGoAhead, enabled = ready)
            Gap(4)
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = CircleShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            ) { Text("Cancel", style = MaterialTheme.typography.labelLarge) }
        }
    }
}
