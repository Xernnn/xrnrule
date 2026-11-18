package com.xern.xrnrule.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xern.xrnrule.app.ui.theme.TABULAR

/**
 * A wait counted down on a ring: the arc is what's left of [totalMs], the seconds sit in the
 * middle in MiSans's tabular figures (so the number doesn't jitter). At zero the ring closes in
 * the green with a tick. It only shows the wait; whoever owns the wait decides when it's over.
 */
@Composable
fun CountdownRing(leftMs: Long, totalMs: Long, color: Color, modifier: Modifier = Modifier, size: Dp = 168.dp) {
    val done = leftMs <= 0
    val target = if (done || totalMs <= 0) 0f else (leftMs.toFloat() / totalMs).coerceIn(0f, 1f)
    // Eased over the ticker's step, so the arc runs smoothly rather than in quarter-second jumps.
    val left by animateFloatAsState(target, animationSpec = tween(250, easing = LinearEasing), label = "wait")
    val track = MaterialTheme.colorScheme.surfaceVariant
    val green = MaterialTheme.colorScheme.primary
    val seconds = (leftMs + 999) / 1000
    Box(
        modifier.size(size).clearAndSetSemantics { contentDescription = if (done) "Done" else "$seconds seconds left" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = (this.size.minDimension * 0.075f).coerceAtLeast(6.dp.toPx())
            val inset = stroke / 2
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(if (done) green.copy(alpha = 0.18f) else track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            if (done) {
                drawArc(green, -90f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            } else if (left > 0f) {
                drawArc(color, -90f, 360f * left, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        if (done) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = green, modifier = Modifier.size(size * 0.36f))
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    seconds.toString(),
                    style = TextStyle(fontSize = (size.value * 0.32f).sp, fontWeight = FontWeight.Bold, fontFeatureSettings = TABULAR),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "s",
                    style = TextStyle(fontSize = (size.value * 0.12f).sp, fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = (size.value * 0.06f).dp, start = 2.dp),
                )
            }
        }
    }
}
