package com.xern.xrnrule.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xern.xrnrule.domain.Block

/**
 * The tone of each block: its place in the block list, in this fixed palette, round again after
 * the eighth. The web pages tone them the same way, with the same values in both themes. Muted
 * (clay, blue, green, violet, rose, teal, ochre, sky): a tone only ever marks a block at a small
 * size, as a dot before its name, a part of a bar or a strip, a sparkline. Never a card's
 * background, a filled chip or text.
 */
object BlockColors {
    val PALETTE: List<Long> = listOf(
        0xFFD27A4A, 0xFF5B8FD6, 0xFF4E9E68, 0xFF8F7AD8, 0xFFCE6488, 0xFF3E9E92, 0xFFAE8834, 0xFF4C98C4,
    )

    /** [blockId]'s tone as ARGB, or null for no block or one that's gone. */
    fun argb(blocks: List<Block>, blockId: String?): Long? {
        val i = blocks.indexOfFirst { it.id == blockId }
        return if (i < 0) null else PALETTE[i % PALETTE.size]
    }
}

/** A block's tone (its place in the list, [BlockColors]); grey for none. */
@Composable
fun blockColor(blocks: List<Block>, blockId: String?): Color =
    BlockColors.argb(blocks, blockId)?.let { Color(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)

/** A block's dot: its tone at the size it may take. */
@Composable
fun BlockDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/**
 * A block's name after its dot: the tone's label wherever it stands for the block. As a control
 * ([onClick], [closable]) it sits on the grey of a button, never on its tone.
 */
@Composable
fun BlockChip(name: String, color: Color, onClick: (() -> Unit)?, modifier: Modifier = Modifier, closable: Boolean = false, small: Boolean = false) {
    val control = onClick != null || closable
    Row(
        modifier
            .clip(CircleShape)
            .then(if (control) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = if (!control) 2.dp else if (small) 8.dp else 12.dp, vertical = if (small) 3.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BlockDot(color, size = if (small) 7.dp else 8.dp)
        WGap(if (small) 5 else 7)
        Text(
            name,
            style = if (small) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (closable) {
            WGap(6)
            Icon(Icons.Filled.Close, contentDescription = "Every block", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
}
