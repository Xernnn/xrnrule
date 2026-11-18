package com.xern.xrnrule.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xern.xrnrule.app.ui.theme.LocalKinds

/**
 * What the app shows until the fingerprint step passes (see [com.xern.xrnrule.app.data.AppLock]).
 * The prompt opens by itself; [onUnlock] opens it again after it was dismissed.
 */
@Composable
fun LockScreen(message: String?, onUnlock: () -> Unit) {
    val green = LocalKinds.current.habit
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 28.dp)) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BrandTile(size = 44.dp)
                Gap(40)
                Box(
                    Modifier.size(84.dp).clip(RoundedCornerShape(22.dp)).background(green.fill),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Fingerprint, contentDescription = null, tint = green.color, modifier = Modifier.size(44.dp))
                }
                Gap(24)
                Text("Locked", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                if (message != null) {
                    Gap(10)
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            PillButton("Unlock", onClick = onUnlock, icon = Icons.Filled.Fingerprint)
        }
    }
}
