@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.xern.xrnrule.app.ui

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xern.xrnrule.app.data.InstalledApp
import com.xern.xrnrule.app.data.InstalledApps
import com.xern.xrnrule.app.data.WorldStore
import com.xern.xrnrule.app.hardcore.HardcoreGuard
import com.xern.xrnrule.app.nfc.TagReader
import com.xern.xrnrule.app.ui.theme.KindColors
import com.xern.xrnrule.app.ui.theme.LocalKinds
import com.xern.xrnrule.app.web.WebsiteMonitor
import com.xern.xrnrule.domain.BlockEdits
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.CombineMode
import com.xern.xrnrule.domain.Devices
import com.xern.xrnrule.domain.EnforcementTier
import com.xern.xrnrule.domain.FormKind
import com.xern.xrnrule.domain.HabitKind
import com.xern.xrnrule.domain.LinkDraft
import com.xern.xrnrule.domain.Schedule
import com.xern.xrnrule.domain.Sites
import com.xern.xrnrule.domain.TagDraft
import com.xern.xrnrule.domain.TimeWindow
import com.xern.xrnrule.domain.WhenMode
import com.xern.xrnrule.domain.deleteBlock
import com.xern.xrnrule.domain.withBlock
import com.xern.xrnrule.domain.withHabit
import java.util.UUID

// =====================================================================================
//  New block: three steps
// =====================================================================================

/** The new-block wizard: what to block, what unlocks it, when it's on. */
@Composable
fun NewBlockScreen(
    store: WorldStore,
    websites: WebsiteMonitor,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val apps = remember { InstalledApps.launchable(context) }
    val world by store.world.collectAsStateWithLifecycle()
    val form = remember { BlockFormState(null, emptyList(), world.habits) }
    LaunchedEffect(world.habits) { form.knownHabits = world.habits }
    val cap = world.settings.dailyCapMs / 60_000
    var step by rememberSaveable { mutableIntStateOf(1) }
    BackHandler(enabled = step > 1) { step-- }

    val hint = when (step) {
        1 -> form.targetsHint()
        2 -> form.unlockHint()
        else -> form.whenHint(cap)
    }

    fun save() {
        val id = UUID.randomUUID().toString()
        val block = form.toBlock(id, autoName(form, apps))
        val items = form.toItems(id, emptyList())
        store.edit { it.withBlock(block, items).withLockdownStarted(id, store.clock) } // "for a while" starts now
        onDone()
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        WizardBar(step, onClose = onDone, onBack = { step-- })
        // Steps 1 and 3 sit in the middle of the page, as Habits First's do; the long step 2 starts at the top.
        BoxWithConstraints(Modifier.weight(1f)) {
        val page = maxHeight
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = page)
                .padding(horizontal = 20.dp),
            verticalArrangement = if (step == 2) Arrangement.Top else Arrangement.Center,
        ) {
            when (step) {
                1 -> {
                    Question("Which apps do you want to limit?")
                    Gap(22)
                    TargetsCard(form, apps, websites)
                    Gap(16)
                    DevicesCard(form)
                }
                2 -> {
                    Gap(8)
                    Question("What unlocks it?")
                    Gap(8)
                    Text("Tap as many as you like", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    UnlockSections(form, apps, store)
                }
                else -> {
                    Question("When is this block active?")
                    Gap(22)
                    WhenSection(form)
                    if (form.mode == BlockMode.HABIT) {
                        Gap(18)
                        BlockAgainCard(form, cap)
                    }
                }
            }
            Gap(24)
        }
        }
        BottomAction(
            // Step 1's empty card says it already.
            hint = hint.takeUnless { step == 1 && form.apps.isEmpty() && form.sites.isEmpty() },
            enabled = hint == null,
            label = if (step < 3) "Continue" else "Save block",
            onClick = { if (step < 3) step++ else save() },
        )
    }
}

/** "Instagram", "Instagram + YouTube", "Instagram + 2 more": a name until you give it one. */
private fun autoName(form: BlockFormState, apps: List<InstalledApp>): String {
    val labels = apps.associate { it.packageName to it.label }
    val names = form.apps.map { labels[it] ?: it }.sortedBy { it.lowercase() } + form.sites.sorted()
    return summarizeNames(names, empty = "Block")
}

@Composable
private fun WizardBar(step: Int, onClose: () -> Unit, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (step == 1) {
            RoundIconButton(Icons.Filled.Close, "Close", onClick = onClose)
        } else {
            RoundIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Back", onClick = onBack)
        }
        WGap(14)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..3).forEach { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
        WGap(14)
        Text("$step of 3", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Question(text: String) {
    Text(text, style = MaterialTheme.typography.displaySmall)
}

/** The green pill at the bottom, with what's still missing above it. */
@Composable
private fun BottomAction(hint: String?, label: String, onClick: () -> Unit, enabled: Boolean = hint == null) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp)) {
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        PillButton(label, onClick = onClick, enabled = enabled)
    }
}

// =====================================================================================
//  Edit block: everything on one page
// =====================================================================================

@Composable
fun EditBlockScreen(
    store: WorldStore,
    websites: WebsiteMonitor,
    guard: HardcoreGuard,
    blockId: String,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val world by store.world.collectAsStateWithLifecycle()
    // Read once: the form edits a copy, and a tick mustn't reset it.
    val existing = remember { world.block(blockId) }
    if (existing == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val oldItems = remember { world.items(blockId) }
    val form = remember { BlockFormState(existing, oldItems, world.habits) }
    LaunchedEffect(world.habits) { form.knownHabits = world.habits }
    val apps = remember { InstalledApps.launchable(context) }
    val cap = world.settings.dailyCapMs / 60_000
    var loosen by remember { mutableStateOf<LoosenRequest?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val hardcore = existing.enforcementTier == EnforcementTier.HARDCORE
    val hint = form.hint(cap)

    fun save() {
        val updated = form.toBlock(existing.id, existing.name)
        val newItems = form.toItems(existing.id, oldItems)
        val apply = {
            // Items are rebuilt against the checklist as it is now, so ticks made meanwhile stay.
            store.edit { w -> w.withBlock(updated, form.toItems(existing.id, w.items(existing.id))) }
            onDone()
        }
        val loosens = form.saveLoosens(oldItems)
        if (loosens && BlockEdits.guardNeeded(store.engine, store.world.value, existing, store.clock)) {
            loosen = LoosenRequest("Save these changes?", "They make ${existing.name} easier to get past.", hardcore, listOf(existing.id)) { apply() }
        } else {
            apply()
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        // Habits First's bar: Cancel, the title in the middle, Save.
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDone) { Text("Cancel", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) }
            Text(
                "Edit block",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal, fontSize = 18.sp),
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = ::save, enabled = hint == null) {
                Text(
                    "Save",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = if (hint == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
            }
            NameCard(form, apps)
            FormSection(Icons.Filled.GridView, "Which apps do you want to limit?") {
                TargetsCard(form, apps, websites, framed = false)
            }
            Gap(14)
            DevicesCard(form)
            when (existing.mode) {
                BlockMode.HABIT -> FormSection(Icons.Outlined.VpnKey, "Select what unlocks your apps", hint = "(Tap as many as you like)") {
                    UnlockSections(form, apps, store, headers = false)
                }
                BlockMode.ALLOWANCE -> FormSection(Icons.Outlined.HourglassBottom, "How long can it be open?") {
                    UnlockSections(form, apps, store, headers = false)
                }
                else -> {
                    Gap(12)
                    Note(
                        if (existing.mode == BlockMode.LOCKDOWN) "Nothing unlocks a lockdown: it blocks for its length, then stops until you start it again."
                        else "Nothing unlocks this block: it just blocks while it's on.",
                    )
                }
            }
            if (existing.mode == BlockMode.HABIT) {
                Gap(14)
                BlockAgainCard(form, cap)
            }
            if (existing.mode == BlockMode.LOCKDOWN) {
                FormSection(Icons.Outlined.HourglassTop, "How long") { WhenSection(form) }
            } else {
                Gap(14)
                ScheduleCard(form)
            }

            Gap(24)
            TextButton(
                onClick = {
                    if (BlockEdits.guardNeeded(store.engine, store.world.value, existing, store.clock)) {
                        loosen = LoosenRequest("Delete ${existing.name}?", "It stops blocking for good.", hardcore, listOf(existing.id)) {
                            store.edit { it.deleteBlock(existing.id) }
                            onDone()
                        }
                    } else {
                        confirmDelete = true
                    }
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text("Delete block", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error) }
            Gap(24)
        }
    }

    loosen?.let { request -> LoosenGuardDialog(request, guard, store, onDismiss = { loosen = null }) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing.name}?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    store.edit { it.deleteBlock(existing.id) }
                    onDone()
                }) { Text("Delete block", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

// =====================================================================================
//  Sections shared by both
// =====================================================================================

/**
 * Step 1: one row that opens the picker, as Habits First's: "Choose apps to block", or a badge
 * and "2 apps selected" with the picked icons under it. [framed]: in its own card (the steps);
 * the edit page puts it in a titled one.
 */
@Composable
private fun TargetsCard(form: BlockFormState, apps: List<InstalledApp>, websites: WebsiteMonitor, framed: Boolean = true) {
    var picking by remember { mutableStateOf(false) }
    val labels = remember(apps) { apps.associate { it.packageName to it.label } }
    val content: @Composable ColumnScope.() -> Unit = {
        val empty = form.apps.isEmpty() && form.sites.isEmpty()
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RowShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { picking = true }
                .padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (empty) Icons.Filled.AddCircle else Icons.Filled.Verified,
                contentDescription = null,
                tint = if (empty) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
            )
            WGap(12)
            Text(
                if (empty) "Choose apps to block" else pickedSummary(form.apps.size, Sites.shown(form.sites).size),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        if (!empty) {
            Gap(12)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                form.apps.sortedBy { labels[it]?.lowercase() ?: it }.forEach { AppIcon(it, size = 26.dp) }
                Sites.shown(form.sites.sorted()).forEach { SiteIcon(it, size = 26.dp) }
            }
        }
        val laptop = form.existing?.targetDesktopApps?.size ?: 0
        if (laptop > 0) {
            Text(
                "Also $laptop laptop app${if (laptop == 1) "" else "s"}, chosen on the laptop.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp, top = 10.dp),
            )
        }
    }
    if (framed) HFCard(content = content) else Column(content = content)
    if (picking) {
        AppPickerSheet(
            title = form.name.ifBlank { "Block" },
            apps = apps,
            initial = PickedTargets(form.apps, form.sites, form.exceptSites),
            websites = websites,
            onDone = { picked ->
                form.apps = picked.apps
                form.sites = picked.sites
                form.exceptSites = picked.exceptSites
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

/** The edit page's first card: the block's icon and its name, which you can type over. */
@Composable
private fun NameCard(form: BlockFormState, apps: List<InstalledApp>) {
    val labels = remember(apps) { apps.associate { it.packageName to it.label } }
    HFCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppTile(
                packages = form.apps.sortedBy { labels[it]?.lowercase() ?: it },
                fallback = Icons.Filled.Shield,
                sites = form.sites.sorted(),
            )
            WGap(16)
            val style = MaterialTheme.typography.headlineMedium.copy(fontSize = 24.sp, fontWeight = FontWeight.Black)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (form.name.isEmpty()) Text("Block name", style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(
                    value = form.name,
                    onValueChange = { form.name = it },
                    singleLine = true,
                    textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** One of the edit page's cards: an icon and a question in grey bold over its content. */
@Composable
private fun FormSection(icon: ImageVector, title: String, hint: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Gap(14)
    HFCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            WGap(12)
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (hint != null) {
            Gap(12)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Gap(14)
        content()
    }
}

private fun pickedSummary(apps: Int, sites: Int): String = listOfNotNull(
    when (apps) { 0 -> null; 1 -> "1 app"; else -> "$apps apps" },
    when (sites) { 0 -> null; 1 -> "1 site"; else -> "$sites sites" },
).joinToString(" · ") + " selected"

/** Step 2: the kinds of unlock, grouped like the form says them, each opening its own settings. */
@Composable
private fun UnlockSections(form: BlockFormState, apps: List<InstalledApp>, store: WorldStore, headers: Boolean = true) {
    val kinds = LocalKinds.current
    val mode = form.existing?.mode
    val labels = remember(apps) { apps.associate { it.packageName to it.label } }
    var pickingAppTime by remember { mutableStateOf(false) }
    var pickingLoop by remember { mutableStateOf(false) }
    // A habit sheet open: picking timers (true) or other habits (false), or making one of those.
    var pickingHabits by remember { mutableStateOf<Boolean?>(null) }
    var makingHabit by remember { mutableStateOf<Boolean?>(null) }

    if (mode == null || mode == BlockMode.HABIT) {
        if (headers) SectionLabel("From your phone", top = 14.dp)
        KindCard(
            icon = Icons.Outlined.Timer,
            title = "App time",
            kind = kinds.appTime,
            selected = form.appTimeOn,
            summary = if (form.appTimeApps.isEmpty()) "no apps picked yet" else "${form.appTimeMinutes} min in ${summarizeNames(form.appTimeApps.map { labels[it] ?: it }, "")}",
            onToggle = { form.setKind(FormKind.APP_TIME, it) },
        ) {
            PickRow(
                if (form.appTimeApps.isEmpty()) "Tap to select apps" else summarizeNames(form.appTimeApps.map { labels[it] ?: it }.sorted(), ""),
                form.appTimeApps.sortedBy { labels[it]?.lowercase() },
                kinds.appTime,
            ) { pickingAppTime = true }
            Gap(10)
            MinuteChips(listOf(5, 10, 15, 30), form.appTimeMinutes, kinds.appTime) { form.appTimeMinutes = it }
            KindNote("Counts minutes in these apps while the block's timer runs. Start it from the block's card.")
        }

        KindCard(
            icon = Icons.Outlined.HourglassTop,
            title = "Wait timer",
            kind = kinds.appTime,
            selected = form.timerOn,
            summary = "${form.timerMinutes} min",
            onToggle = { form.setKind(FormKind.TIMER, it) },
        ) {
            MinuteChips(listOf(5, 10, 15, 30), form.timerMinutes, kinds.appTime) { form.timerMinutes = it }
            KindNote("Runs while you wait on the block screen, once you start it.")
        }

        if (headers) SectionLabel("You track it", top = 6.dp)
        KindCard(
            icon = Icons.Outlined.TaskAlt,
            title = "Manual habit",
            kind = kinds.habit,
            selected = form.manualOn,
            summary = andList(form.doHabits.mapNotNull { id -> form.knownHabits.firstOrNull { it.id == id }?.name } + form.tasks.filter { it.text.isNotBlank() }.map { it.text })
                .ifEmpty { "no habit picked yet" },
            onToggle = { form.setKind(FormKind.MANUAL, it) },
        ) {
            HabitPicks(form, form.doHabits, onRemove = { id -> form.habitIds = form.habitIds - id })
            form.tasks.forEachIndexed { i, task ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FieldBox(
                        task.text,
                        onChange = { v -> form.tasks = form.tasks.toMutableList().also { it[i] = task.copy(text = v) } },
                        placeholder = "Task",
                        modifier = Modifier.weight(1f),
                        container = Color.Black.copy(alpha = 0.18f),
                    )
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Remove task",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp).size(22.dp).clickable {
                            form.tasks = form.tasks.toMutableList().also { it.removeAt(i) }
                        },
                    )
                }
                Gap(8)
            }
            if (form.knownHabits.any { it.kind != HabitKind.TIMER && it.id !in form.habitIds }) {
                DashedButton("Choose a habit", kinds.habit) { pickingHabits = false }
                Gap(8)
            }
            DashedButton("New habit", kinds.habit) { makingHabit = false }
            KindNote("Tick it or count it on the block's card or its block screen. Your laptop counts too.")
        }
        KindCard(
            icon = Icons.Outlined.Timer,
            title = "Manual timer",
            kind = kinds.timer,
            selected = form.focusOn,
            summary = andList(form.timerHabits.mapNotNull { id -> form.knownHabits.firstOrNull { it.id == id }?.name }).ifEmpty { "no timer picked yet" },
            onToggle = { form.setKind(FormKind.FOCUS, it) },
        ) {
            HabitPicks(form, form.timerHabits, onRemove = { id -> form.habitIds = form.habitIds - id })
            if (form.knownHabits.any { it.kind == HabitKind.TIMER && it.id !in form.habitIds }) {
                DashedButton("Choose a timer", kinds.timer) { pickingHabits = true }
                Gap(8)
            }
            DashedButton("New timer", kinds.timer) { makingHabit = true }
            KindNote("Run it with the focus timer: the ▶ on the block's card opens it.")
        }

        if (headers) SectionLabel("Yesterday's or a weekly goal", top = 6.dp)
        KindCard(
            icon = Icons.Outlined.Link,
            title = "Tracked habit",
            kind = kinds.loop,
            selected = form.loopOn,
            summary = if (form.links.isEmpty()) "no habit linked yet" else andList(form.links.map { it.name }),
            onToggle = { form.setKind(FormKind.LOOP, it) },
        ) {
            form.links.forEachIndexed { i, link ->
                LinkRow(
                    link,
                    onChange = { new -> form.links = form.links.toMutableList().also { it[i] = new } },
                    onRemove = { form.links = form.links.toMutableList().also { it.removeAt(i) } },
                )
            }
            DashedButton("Pick habits", kinds.loop) { pickingLoop = true }
            KindNote("Yesterday: a habit you tick at night counts the next day. Goal days: a habit that isn't daily counts on every day its goal is met. Each tick opens the block once.")
        }

        // A phone without NFC isn't offered tags, but still shows the ones a block has.
        if (TagReader.hasNfc(LocalContext.current) || form.tags.isNotEmpty()) {
            if (headers) SectionLabel("Somewhere else", top = 6.dp)
            TagCard(form)
        }
    }

    if (mode == null || mode == BlockMode.ALLOWANCE) {
        if (mode == null) SectionLabel("Or just limit it", top = 6.dp)
        KindCard(
            icon = Icons.Outlined.HourglassBottom,
            title = "Daily limit",
            kind = kinds.limit,
            selected = form.limitOn || mode == BlockMode.ALLOWANCE,
            summary = listOfNotNull(
                if (form.dailyOn) "${formatDuration(form.dailyMinutes * 60_000)} a day" else null,
                if (form.rowOn) "${formatDuration(form.rowMinutes * 60_000)} in a row" else null,
            ).joinToString(" · "),
            onToggle = if (mode == null) ({ form.setKind(FormKind.LIMIT, it) }) else null,
        ) {
            LimitEditor(form, kinds.limit)
        }
    }

    if (mode == null && !form.habitChosen && !form.limitOn) {
        Gap(12)
        Note("You could also choose nothing, which just keeps it blocked.")
    }

    if (form.draft().let { it.hasTimer && it.hasChecklist }) {
        Gap(16)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("To unlock", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                if (form.combineMode == CombineMode.AND) "do everything" else "the timer, or all the rest",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { form.combineMode = if (form.combineMode == CombineMode.AND) CombineMode.OR else CombineMode.AND }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }

    if (pickingAppTime) {
        AppPickerSheet(
            title = "Which apps count?",
            apps = apps,
            initial = PickedTargets(form.appTimeApps, emptySet(), emptySet()),
            websites = null,
            onDone = { form.appTimeApps = it.apps; pickingAppTime = false },
            onDismiss = { pickingAppTime = false },
        )
    }
    pickingHabits?.let { timers ->
        HabitPickerSheet(
            title = if (timers) "Timers" else "Habits",
            // An "at most" habit can't open a block (I36), so it isn't offered.
            habits = form.knownHabits.filter {
                (it.kind == HabitKind.TIMER) == timers && (it.id in form.habitIds || (!it.archived && it.unlockUnit != null))
            },
            initial = form.habitIds.toSet(),
            onDone = { ids -> form.setHabits(timers, ids); pickingHabits = null },
            onDismiss = { pickingHabits = null },
        )
    }
    makingHabit?.let { timers ->
        HabitEditorSheet(
            existing = null,
            kinds = if (timers) listOf(HabitKind.TIMER) else listOf(HabitKind.CHECK, HabitKind.COUNT),
            onSave = { habit ->
                store.edit { it.withHabit(habit, store.clock.nowWallMs()) }
                form.knownHabits = form.knownHabits + habit
                form.addHabit(habit.id)
                makingHabit = null
            },
            onDismiss = { makingHabit = null },
        )
    }
    if (pickingLoop) {
        val linked = form.links.mapTo(mutableSetOf()) { it.uuid }
        HabitPickerSheet(
            title = "Habits",
            habits = form.knownHabits.filter { it.id in linked || (!it.archived && it.unlockUnit != null && it.kind != HabitKind.TIMER) },
            initial = linked,
            onDone = { ids -> form.setLinks(ids); pickingLoop = false },
            onDismiss = { pickingLoop = false },
        )
    }
}

@Composable
private fun LimitEditor(form: BlockFormState, kind: KindColors) {
    ToggleLine("A limit a day", form.dailyOn) { form.dailyOn = it }
    if (form.dailyOn) {
        MinuteChips(listOf(15, 30, 60, 120), form.dailyMinutes, kind, format = ::shortMinutes) { form.dailyMinutes = it }
        Gap(6)
    }
    ToggleLine("At most so long in a row", form.rowOn) { form.rowOn = it }
    if (form.rowOn) {
        Text("In a row", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Gap(4)
        MinuteChips(listOf(10, 15, 30, 60), form.rowMinutes, kind, format = ::shortMinutes) { form.rowMinutes = it }
        Gap(8)
        Text("Then a break of", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Gap(4)
        MinuteChips(listOf(5, 15, 30, 60), form.rowBreakMinutes, kind, format = ::shortMinutes) { form.rowBreakMinutes = it }
        KindNote("Resting that long on your own counts as the break.")
    }
    Gap(10)
    Text("Phone and laptop", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Gap(6)
    SegmentSwitch(listOf("Share one limit", "Each has its own"), if (form.perDevice) 1 else 0, onSelect = { form.perDevice = it == 1 })
}

@Composable
private fun ToggleLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        XSwitch(checked, onCheckedChange = onChange)
    }
}

/** A linked xrnloops habit in the form, with its "which day" and "goal days" choices (I23). */
@Composable
private fun LinkRow(link: LinkDraft, onChange: (LinkDraft) -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(link.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                when {
                    link.goal -> "Counts on days its goal is met${if (link.yesterday) ", yesterday" else ""}"
                    link.yesterday -> "Yesterday's tick counts"
                    else -> "Today's tick counts"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = link.yesterday, onClick = { onChange(link.copy(yesterday = !link.yesterday)) }, label = { Text("Yesterday") })
                if (!link.daily || link.goal) {
                    FilterChip(selected = link.goal, onClick = { onChange(link.copy(goal = !link.goal)) }, label = { Text("Goal days") })
                }
            }
        }
        Icon(
            Icons.Filled.Close,
            contentDescription = "Remove ${link.name}",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp).size(22.dp).clickable(onClick = onRemove),
        )
    }
}

/** Step 3: always, on a schedule, or (a block nothing unlocks) for a while. */
@Composable
private fun WhenSection(form: BlockFormState) {
    val mode = form.mode
    val strictNew = form.existing == null && (mode == BlockMode.SCHEDULE || mode == BlockMode.LOCKDOWN)
    val options = when {
        form.existing?.mode == BlockMode.LOCKDOWN -> listOf(WhenMode.FOR_A_WHILE)
        strictNew -> listOf(WhenMode.ALWAYS, WhenMode.SCHEDULE, WhenMode.FOR_A_WHILE)
        else -> listOf(WhenMode.ALWAYS, WhenMode.SCHEDULE)
    }
    LaunchedEffect(options) { if (form.whenMode !in options) form.whenMode = options.first() }

    if (options.size > 1) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEach { option ->
                OptionButton(
                    icon = when (option) {
                        WhenMode.ALWAYS -> Icons.Filled.AllInclusive
                        WhenMode.SCHEDULE -> Icons.Outlined.CalendarMonth
                        WhenMode.FOR_A_WHILE -> Icons.Outlined.HourglassTop
                    },
                    label = when (option) {
                        WhenMode.ALWAYS -> "Always"
                        WhenMode.SCHEDULE -> "Schedule"
                        WhenMode.FOR_A_WHILE -> "For a while"
                    },
                    selected = form.whenMode == option,
                    modifier = Modifier.weight(1f),
                    centred = options.size > 2,
                ) { form.whenMode = option }
            }
        }
        Gap(14)
    }
    val kinds = LocalKinds.current
    when (form.whenMode) {
        WhenMode.ALWAYS -> Note(
            when (mode) {
                BlockMode.HABIT -> "Blocks all day, every day, until you unlock it."
                BlockMode.ALLOWANCE -> "The limit counts all day, every day."
                else -> "Blocks all the time. Turn it off or delete it to stop."
            },
        )
        WhenMode.SCHEDULE -> HFCard {
            Text(
                "Blocks " + describeDays(form.scheduleDays).replaceFirstChar { it.lowercase() } +
                    if (form.scheduleDays.size == 7 && form.existing == null) " (recommended)" else "",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Gap(10)
            ScheduleEditor(form)
        }
        WhenMode.FOR_A_WHILE -> {
            MinuteChips(listOf(30, 60, 120, 240), form.lockdownMinutes, kinds.strict, format = ::shortMinutes) { form.lockdownMinutes = it }
            Note(
                if (form.existing == null) "Starts as soon as you save. Start it again later from its card or the quick-settings tile."
                else "The new length counts from the next time you start it.",
            )
        }
    }
}

/**
 * HABIT blocks: how long one unlock lasts, up to the daily unlock limit (I9), as Habits First's
 * "Block again after" card with its value as a menu, and whether that is a timer or minutes of
 * use over the day (self paced, I41).
 */
@Composable
private fun BlockAgainCard(form: BlockFormState, capMinutes: Long) {
    val red = LocalKinds.current.timer
    var menu by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    HFCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(red.fill),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Autorenew, contentDescription = null, tint = red.color, modifier = Modifier.size(17.dp)) }
            WGap(10)
            Text("Block again after", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), modifier = Modifier.weight(1f))
            Box {
                Text(
                    shortMinutes(form.unlockMinutes),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { menu = true }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    listOf(2L, 5L, 10L, 15L, 30L, 60L).filter { it <= capMinutes }.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(if (form.selfPaced) "$m min of use" else "$m min", fontWeight = if (m == form.unlockMinutes) FontWeight.Bold else null) },
                            onClick = { form.unlockMinutes = m; menu = false },
                        )
                    }
                    DropdownMenuItem(text = { Text("Other…") }, onClick = { menu = false; custom = true })
                }
            }
        }
        Gap(16)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(false to "Timer", true to "Self paced").forEachIndexed { i, (paced, label) ->
                SegmentedButton(
                    selected = form.selfPaced == paced,
                    onClick = { form.selfPaced = paced },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        activeContentColor = MaterialTheme.colorScheme.onSurface,
                        inactiveContainerColor = Color.Transparent,
                        inactiveContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) { Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp)) }
            }
        }
        Gap(16)
        Text(
            (
                if (form.selfPaced) "It blocks again after ${form.unlockMinutes} minutes of total use throughout the day, on any device. "
                else "A ${form.unlockMinutes} minute timer starts, and blocks again at the end of it. "
                ) + "All unlocks together give at most ${formatDuration(capMinutes * 60_000)} a day (Settings).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (custom) {
        MinutesDialog(initial = form.unlockMinutes, max = capMinutes, onDismiss = { custom = false }, onConfirm = { form.unlockMinutes = it; custom = false })
    }
}

/**
 * I43: NFC tags to scan. A tag stuck somewhere out of reach (the kitchen, the gym bag) opens the
 * block when the phone is held to it, from any screen. Setting one up reads it twice: some cards
 * show a new id on every read and could never match.
 */
@Composable
private fun TagCard(form: BlockFormState) {
    val kinds = LocalKinds.current
    val context = LocalContext.current
    val resume = rememberResumeSignal()
    val hasNfc = remember { TagReader.hasNfc(context) }
    val nfcOn = remember(resume) { TagReader.isOn(context) }
    var scanning by remember { mutableStateOf(false) }
    KindCard(
        icon = Icons.Outlined.Nfc,
        title = "NFC tag",
        kind = kinds.blue,
        selected = form.tagOn,
        summary = if (form.tags.isEmpty()) "no tag yet" else andList(form.tags.map { it.name.ifBlank { "the tag" } }),
        onToggle = { form.setKind(FormKind.TAG, it) },
    ) {
        form.tags.forEachIndexed { i, tag ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldBox(
                    tag.name,
                    onChange = { v -> form.tags = form.tags.toMutableList().also { it[i] = tag.copy(name = v) } },
                    placeholder = "Name it: the kitchen tag",
                    modifier = Modifier.weight(1f),
                    container = Color.Black.copy(alpha = 0.18f),
                )
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Remove tag",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp).size(22.dp).clickable {
                        form.tags = form.tags.toMutableList().also { it.removeAt(i) }
                    },
                )
            }
            Gap(8)
        }
        when {
            !hasNfc -> KindNote("This phone has no NFC.")
            !nfcOn -> {
                DashedButton("Turn on NFC", kinds.blue) { runCatching { context.startActivity(TagReader.settingsIntent()) } }
                Gap(8)
            }
            else -> {
                DashedButton("Scan a tag", kinds.blue) { scanning = true }
                Gap(8)
            }
        }
        KindNote("Stick a tag somewhere out of reach. Hold your phone to it, on any screen, and the block opens. A blank sticker or key fob works best: a tag with a link on it opens its link unless xrnrule is in front.")
    }
    if (scanning) {
        TagScanDialog(
            onDismiss = { scanning = false },
            onScanned = { id ->
                scanning = false
                if (form.tags.none { it.tag == id }) {
                    form.tags = form.tags + TagDraft(UUID.randomUUID().toString(), null, "", id)
                }
            },
        )
    }
}

/** Reads a new tag twice; [onScanned] gets its [com.xern.xrnrule.domain.Tags.id] once both agree. */
@Composable
private fun TagScanDialog(onDismiss: () -> Unit, onScanned: (String) -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        TagReader.startRegistering { id ->
            val seen = first
            when {
                seen == null -> { first = id; problem = null }
                seen == id -> onScanned(id)
                else -> {
                    // A card that makes up a new id on each read: it would never open the block.
                    first = null
                    problem = "That tag reads differently each time. Use a sticker or a key fob instead."
                }
            }
        }
        onDispose { TagReader.stopRegistering() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Nfc, contentDescription = null) },
        title = { Text(if (first == null) "Hold your phone to the tag" else "Once more, to check it") },
        text = {
            Text(
                problem ?: if (first == null) "Near the top of the phone's back, until it buzzes." else "Take the phone away, then hold it to the same tag again.",
                color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * I42: where the block holds, as a switch like "Block again after"'s: the phone, the laptop, or
 * both. The other device still shows it and can unlock it.
 */
@Composable
private fun DevicesCard(form: BlockFormState) {
    HFCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp)) }
            WGap(10)
            Text("Blocks on", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), modifier = Modifier.weight(1f))
        }
        Gap(16)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val choices = listOf(Devices.BOTH to "Both", Devices.PHONE to "Phone", Devices.LAPTOP to "Laptop")
            choices.forEachIndexed { i, (devices, label) ->
                SegmentedButton(
                    selected = form.devices == devices,
                    onClick = { form.devices = devices },
                    shape = SegmentedButtonDefaults.itemShape(i, choices.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        activeContentColor = MaterialTheme.colorScheme.onSurface,
                        inactiveContainerColor = Color.Transparent,
                        inactiveContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) { Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp)) }
            }
        }
        Gap(16)
        Text(
            when (form.devices) {
                Devices.BOTH -> "This phone and your laptop. Its websites are blocked in both browsers."
                Devices.PHONE -> "This phone only: the laptop leaves its websites alone."
                Devices.LAPTOP -> "Your laptop only: this phone leaves its apps and websites alone. You can still unlock it from here."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * When it blocks, as Habits First's folding card: "Blocks every day (recommended)", and open, the
 * days and "Specify time". Every day with no times is "always"; a strict schedule block keeps its
 * schedule whatever it says.
 */
@Composable
private fun ScheduleCard(form: BlockFormState) {
    val strict = form.mode == BlockMode.SCHEDULE
    var open by remember { mutableStateOf(form.whenMode == WhenMode.SCHEDULE) }
    fun settle() {
        if (!strict) {
            val always = form.scheduleDays.size == 7 && !form.timeOn && form.moreWindows.isEmpty()
            form.whenMode = if (always) WhenMode.ALWAYS else WhenMode.SCHEDULE
        }
    }
    val summary = if (form.whenMode == WhenMode.ALWAYS) {
        "Blocks every day (recommended)"
    } else {
        "Blocks " + describeSchedule(
            Schedule(form.scheduleDays, if (form.timeOn) TimeWindow(form.startMinute, form.endMinute) else null, form.moreWindows),
        ).replaceFirstChar { it.lowercase() }
    }
    HFCard(padding = PaddingValues(0.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            WGap(12)
            Text(summary, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Icon(
                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (open) "Fold" else "Change",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(open) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
                ScheduleEditor(form, onChange = ::settle)
                if (form.mode == BlockMode.ALLOWANCE && form.whenMode == WhenMode.ALWAYS) {
                    Gap(4)
                    Note("The limit counts all day, every day.")
                }
            }
        }
    }
}

/**
 * The days a schedule blocks on, "Specify time" or its hours, and more times with their own
 * days. [onChange] after a change to which days or whether there are times.
 */
@Composable
private fun ScheduleEditor(form: BlockFormState, onChange: () -> Unit = {}) {
    DayCircles(form.scheduleDays) { form.scheduleDays = it; onChange() }
    Gap(12)
    if (form.timeOn) {
        TimeRange(form.startMinute, form.endMinute, onStart = { form.startMinute = it }, onEnd = { form.endMinute = it })
        TextButton(onClick = { form.timeOn = false; onChange() }) { Text("All day instead") }
    } else {
        GreyButton("Specify time", Icons.Filled.AddCircle) { form.timeOn = true; onChange() }
    }
    form.moreWindows.forEachIndexed { i, w ->
        fun update(new: com.xern.xrnrule.domain.DayWindow) {
            form.moreWindows = form.moreWindows.toMutableList().also { it[i] = new }
        }
        Gap(12)
        Hairline()
        Gap(12)
        DayCircles(w.days) { update(w.copy(days = it)) }
        Gap(10)
        TimeRange(
            w.window.startMinuteOfDay,
            w.window.endMinuteOfDay,
            onStart = { update(w.copy(window = w.window.copy(startMinuteOfDay = it))) },
            onEnd = { update(w.copy(window = w.window.copy(endMinuteOfDay = it))) },
        )
        TextButton(onClick = { form.moreWindows = form.moreWindows.toMutableList().also { it.removeAt(i) }; onChange() }) {
            Text("Remove these times")
        }
    }
    if (form.timeOn) {
        Gap(8)
        DashedButton("More times", null) { form.moreWindows = form.moreWindows + newDayWindow(); onChange() }
    }
}

/** A wide grey button with its icon and label in the middle: "⊕ Specify time". */
@Composable
private fun GreyButton(label: String, icon: ImageVector, onClick: () -> Unit) {
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
private fun KindCard(
    icon: ImageVector,
    title: String,
    kind: KindColors,
    selected: Boolean,
    summary: String,
    onToggle: ((Boolean) -> Unit)?,
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
            if (selected) {
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
private fun PickRow(label: String, packages: List<String>, kind: KindColors?, onClick: () -> Unit) {
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
private fun OptionButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    centred: Boolean = false,
    onClick: () -> Unit,
) {
    val green = LocalKinds.current.habit
    Row(
        modifier
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) green.fill else MaterialTheme.colorScheme.surfaceVariant)
            .then(if (selected) Modifier.border(1.5.dp, green.border, RoundedCornerShape(12.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = if (centred) 8.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (centred) Arrangement.Center else Arrangement.Start,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) green.color else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        WGap(if (centred) 8 else 12)
        Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
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

@Composable
private fun MinutePill(label: String, selected: Boolean, kind: KindColors, modifier: Modifier, onClick: () -> Unit) {
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
private fun MinutesDialog(initial: Long, max: Long, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
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
private fun describeDays(days: Set<Int>): String = describeSchedule(Schedule(days, null)).substringBefore(" · ")

@Composable
private fun DayCircles(days: Set<Int>, onChange: (Set<Int>) -> Unit) {
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
private val DayBlue = Color(0xFF3B82F6)

@Composable
private fun TimeRange(start: Int, end: Int, onStart: (Int) -> Unit, onEnd: (Int) -> Unit) {
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
private fun TimeTile(label: String, minute: Int, modifier: Modifier, onClick: () -> Unit) {
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
private fun TimeInputDialog(initialMinute: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        is24Hour = DateFormat.is24HourFormat(context),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimeInput(state = state) },
    )
}

/** The habits picked on a card, each with its goal and a cross to take it off. */
@Composable
private fun HabitPicks(form: BlockFormState, ids: List<String>, onRemove: (String) -> Unit) {
    ids.forEach { id ->
        val habit = form.knownHabits.firstOrNull { it.id == id } ?: return@forEach
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.18f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HabitTile(habit, size = 34)
            WGap(12)
            Column(Modifier.weight(1f)) {
                Text(habit.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(habitGoalText(habit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                Icons.Filled.Close,
                contentDescription = "Take ${habit.name} off",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp).clickable { onRemove(id) },
            )
        }
        Gap(8)
    }
}
