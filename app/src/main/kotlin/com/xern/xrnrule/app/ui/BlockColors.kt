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
import androidx.compose.ui.unit.dp
import com.xern.xrnrule.domain.Block

/**
 * The colour of each block: its place in the block list, in this fixed palette, round again
 * after the eighth. The laptop's page colours them the same way.
 */
object BlockColors {
    val PALETTE: List<Long> = listOf(
        0xFFE8590C, 0xFF1C6FD8, 0xFF2B9348, 0xFF7048E8, 0xFFD6336C, 0xFF0CA678, 0xFFF59F00, 0xFF1283C4,
    )

    /** [blockId]'s colour as ARGB, or null for no block or one that's gone. */
    fun argb(blocks: List<Block>, blockId: String?): Long? {
        val i = blocks.indexOfFirst { it.id == blockId }
        return if (i < 0) null else PALETTE[i % PALETTE.size]
    }
}

/** A block's colour (its place in the list, [BlockColors]); grey for none. */
@Composable
fun blockColor(blocks: List<Block>, blockId: String?): Color =
    BlockColors.argb(blocks, blockId)?.let { Color(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)

/** A block's name on its colour: the colour's only label, wherever it stands for the block. */
@Composable
fun BlockChip(name: String, color: Color, onClick: (() -> Unit)?, modifier: Modifier = Modifier, closable: Boolean = false, small: Boolean = false) {
    Row(
        modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.18f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = if (small) 8.dp else 11.dp, vertical = if (small) 3.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(if (small) 7.dp else 9.dp).clip(CircleShape).background(color))
        WGap(if (small) 5 else 7)
        Text(
            name,
            style = (if (small) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium).copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (closable) {
            WGap(6)
            Icon(Icons.Filled.Close, contentDescription = "Every block", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
        }
    }
}
