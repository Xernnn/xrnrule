@file:OptIn(ExperimentalMaterial3Api::class)

package com.xern.xrnrule.app.ui

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xern.xrnrule.app.data.InstalledApp
import com.xern.xrnrule.app.web.WebsiteMonitor
import com.xern.xrnrule.app.web.WebsiteTracking
import com.xern.xrnrule.app.web.tracking
import com.xern.xrnrule.domain.Sites

/** What the picker hands back on Done. */
data class PickedTargets(val apps: Set<String>, val sites: Set<String>, val exceptSites: Set<String>)

/**
 * The bottom sheet for choosing what a block covers: an Apps / Websites switch, search, category
 * chips, and a list with a round check per app. Nothing changes until Done; Cancel or a swipe
 * down drops the picks. [websites] null shows apps only (picking which apps count as a habit).
 */
@Composable
fun AppPickerSheet(
    title: String,
    apps: List<InstalledApp>,
    initial: PickedTargets,
    websites: WebsiteMonitor?,
    onDone: (PickedTargets) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var picked by remember { mutableStateOf(initial.apps) }
    var sites by remember { mutableStateOf(initial.sites) }
    var excepts by remember { mutableStateOf(initial.exceptSites) }
    var tab by remember { mutableIntStateOf(0) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal, fontSize = 18.sp),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    Modifier
                        .padding(end = 8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { onDone(PickedTargets(picked, sites, excepts)) }
                        .padding(horizontal = 18.dp, vertical = 7.dp),
                ) { Text("Done", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary) }
            }
            Gap(10)
            if (websites != null && websites.isSupported()) {
                SegmentSwitch(
                    options = listOf(
                        "Apps" + if (picked.isEmpty()) "" else " · ${picked.size}",
                        "Websites" + if (sites.isEmpty()) "" else " · ${sites.size}",
                    ),
                    selected = tab,
                    onSelect = { tab = it },
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Gap(12)
            }
            if (tab == 0) {
                AppList(apps, picked, onToggle = { pkg -> picked = if (pkg in picked) picked - pkg else picked + pkg })
            } else {
                WebsitesTab(
                    sites = sites,
                    excepts = excepts,
                    websites = websites!!,
                    onSites = { sites = it },
                    onExcepts = { excepts = it },
                )
            }
        }
    }
}

/** Two or three options on a raised track, the chosen one as a white pill. */
@Composable
fun SegmentSwitch(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (on) MaterialTheme.colorScheme.onSurface else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

private val Categories = listOf(
    ApplicationInfo.CATEGORY_SOCIAL to ("💬" to "Social"),
    ApplicationInfo.CATEGORY_GAME to ("🎮" to "Games"),
    ApplicationInfo.CATEGORY_VIDEO to ("📺" to "Video"),
    ApplicationInfo.CATEGORY_AUDIO to ("🎵" to "Music & Audio"),
    ApplicationInfo.CATEGORY_NEWS to ("📰" to "News"),
    ApplicationInfo.CATEGORY_IMAGE to ("🖼️" to "Photos"),
    ApplicationInfo.CATEGORY_MAPS to ("🗺️" to "Maps"),
    ApplicationInfo.CATEGORY_PRODUCTIVITY to ("💼" to "Productivity"),
)

private fun categoryName(category: Int): String? = Categories.firstOrNull { it.first == category }?.second?.second

@Composable
private fun AppList(apps: List<InstalledApp>, picked: Set<String>, onToggle: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<Int?>(null) }
    val present = remember(apps) { apps.map { it.category }.toSet() }
    val shown = remember(query, category, apps) {
        apps.filter { (category == null || it.category == category) && (query.isBlank() || it.label.contains(query, ignoreCase = true)) }
    }
    SearchField(query, onChange = { query = it }, placeholder = "Search apps", modifier = Modifier.padding(horizontal = 20.dp))
    Gap(10)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChoicePill("All", category == null) { category = null }
        Categories.filter { it.first in present }.forEach { (cat, label) ->
            ChoicePill("${label.first} ${label.second}", category == cat) { category = if (category == cat) null else cat }
        }
    }
    Gap(6)
    LazyColumn(Modifier.fillMaxWidth()) {
        items(shown, key = { it.packageName }) { app ->
            val on = app.packageName in picked
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(app.packageName) }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(app.packageName, size = 36.dp)
                WGap(14)
                Column(Modifier.weight(1f)) {
                    Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    categoryName(app.category)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                CheckCircle(on)
            }
        }
    }
}

/** The round tick at the end of a pickable row. */
@Composable
fun CheckCircle(on: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(22.dp)
            .clip(CircleShape)
            .then(
                if (on) Modifier.background(MaterialTheme.colorScheme.primary)
                else Modifier.border(1.6.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
    }
}

/** A rounded filter pill: filled white when chosen. */
@Composable
fun ChoicePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 5.dp),
    )
}

/** A search box on the raised grey, no outline. */
@Composable
fun SearchField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.Search,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    actions: KeyboardActions = KeyboardActions.Default,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        WGap(10)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = keyboard,
                keyboardActions = actions,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        trailing?.invoke()
    }
}

@Composable
private fun WebsitesTab(
    sites: Set<String>,
    excepts: Set<String>,
    websites: WebsiteMonitor,
    onSites: (Set<String>) -> Unit,
    onExcepts: (Set<String>) -> Unit,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Text("Choose websites to block", style = MaterialTheme.typography.titleMedium)
        Text(
            "A site covers its subdomains: youtube.com covers m.youtube.com. Add a path for just one part: youtube.com/shorts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Gap(10)
        SiteEntry(sites, onAdd = { onSites(sites + it) }, placeholder = "Enter a domain here")
        SiteList(sites, onRemove = { onSites(sites - it) })
        if (sites.isNotEmpty() || excepts.isNotEmpty()) {
            Gap(18)
            Text("Leave these alone", style = MaterialTheme.typography.titleMedium)
            Text(
                "Parts of those sites this block doesn't touch, like music.youtube.com.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Gap(10)
            SiteEntry(excepts, onAdd = { onExcepts(excepts + it) }, placeholder = "e.g. music.youtube.com")
            SiteList(excepts, onRemove = { onExcepts(excepts - it) })
        }
        Gap(12)
        WebsiteTrackingWarning(websites)
        Gap(24)
    }
}

/** Type a site, press Add or Done; normalized to a bare domain that also covers its subdomains. */
@Composable
private fun SiteEntry(sites: Set<String>, onAdd: (String) -> Unit, placeholder: String) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    fun submit() {
        val host = Sites.normalizeTarget(text) // I46: a path is fine, "youtube.com/shorts"
        if (host == null) {
            error = text.isNotBlank()
        } else {
            if (host !in sites) onAdd(host)
            text = ""
            error = false
        }
    }
    SearchField(
        value = text,
        onChange = { text = it; error = false },
        placeholder = placeholder,
        icon = Icons.Filled.Language,
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        actions = KeyboardActions(onDone = { submit() }),
        trailing = {
            RoundIconButton(
                Icons.Filled.Add,
                "Add website",
                onClick = { submit() },
                size = 38.dp,
                container = MaterialTheme.colorScheme.surface,
            )
        },
    )
    if (error) {
        Text(
            "That doesn't look like a website address.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp, start = 4.dp),
        )
    }
}

@Composable
private fun SiteList(sites: Set<String>, onRemove: (String) -> Unit) {
    if (sites.isEmpty()) return
    Gap(8)
    GroupCard {
        sites.sorted().forEachIndexed { i, host ->
            GroupRow(
                title = host,
                leading = { SiteIcon(host, size = 28.dp) },
                first = i == 0,
                chevron = false,
                trailing = {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Remove $host",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp).clickable { onRemove(host) },
                    )
                },
            )
        }
    }
}

/** Website rules do nothing without the xrnrule extension in the browser; say so where the rule is made. */
@Composable
fun WebsiteTrackingWarning(websites: WebsiteMonitor) {
    val context = LocalContext.current
    val resume = rememberResumeSignal()
    val tracking = remember(resume) { websites.tracking(context) }
    if (tracking == WebsiteTracking.ON) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = statusStyleFor(com.xern.xrnrule.domain.BlockState.EARNED).accent,
            modifier = Modifier.size(18.dp),
        )
        WGap(8)
        Text(
            if (tracking == WebsiteTracking.OFF) "Sites need the xrnrule extension in your browser" else "The browser extension isn't answering",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { runCatching { context.startActivity(websites.settingsIntent()) } }) {
            Text("Open browser")
        }
    }
}
