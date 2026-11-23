@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.xern.xrnrule.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Autorenew
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
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
import com.xern.xrnrule.domain.Companions
import com.xern.xrnrule.domain.DayWindow
import com.xern.xrnrule.domain.Devices
import com.xern.xrnrule.domain.EnforcementTier
import com.xern.xrnrule.domain.FormKind
import com.xern.xrnrule.domain.Habit
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
//  New block and edit block: the same page
// =====================================================================================

@Composable
fun NewBlockScreen(
    store: WorldStore,
    websites: WebsiteMonitor,
    guard: HardcoreGuard,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val apps = remember { InstalledApps.launchable(context) }
    val world by store.world.collectAsStateWithLifecycle()
    val form = remember { BlockFormState(null, emptyList(), world.habits) }
    LaunchedEffect(world.habits) { form.knownHabits = world.habits }
    val cap = world.settings.dailyCapMs / 60_000
    val hint = form.hint(cap)
    var loosen by remember { mutableStateOf<LoosenRequest?>(null) }

    fun save() {
        val id = UUID.randomUUID().toString()
        val block = form.toBlock(id, autoName(form, apps))
        val items = form.toItems(id, emptyList())
        val apply = {
            store.edit { it.withBlock(block, items).withLockdownStarted(id, store.clock) } // "for a while" starts now
            onDone()
        }
        // I6: a habit block on another one's targets is a way past that one, so it asks that one's guard.
        val request = openingRequest(store, BlockEdits.opens(store.world.value, null, block), "Save this block?") { apply() }
        if (request == null) apply() else loosen = request
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        FormBar("New block", canSave = hint == null, onCancel = onDone, onSave = ::save)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            if (hint != null) FormHint(hint, MaterialTheme.colorScheme.onSurfaceVariant)
            BlockPage(form, apps, websites, store, cap)
            Gap(24)
        }
    }
    loosen?.let { request -> LoosenGuardDialog(request, guard, store, onDismiss = { loosen = null }) }
}

/** Habits First's bar: Cancel, the title in the middle, Save. */
@Composable
private fun FormBar(title: String, canSave: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onCancel) { Text("Cancel", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal, fontSize = 18.sp),
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSave, enabled = canSave) {
            Text(
                "Save",
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (canSave) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** What's still missing before it can be saved. */
@Composable
private fun FormHint(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(bottom = 12.dp))
}

/**
 * The block, top to bottom as you'd say it: what's blocked, when, for how long, and what opens it.
 * Rules add up (I44): whichever one blocks, the block blocks. With no habit and no limit there is
 * only "when", which is a schedule; with either, "Blocked hours" go on top of it.
 */
@Composable
private fun BlockPage(form: BlockFormState, apps: List<InstalledApp>, websites: WebsiteMonitor, store: WorldStore, cap: Long) {
    NameCard(form, apps)
    FormSection(Icons.Filled.GridView, "What to block") {
        TargetsCard(form, apps, websites, framed = false)
    }
    Gap(14)
    DevicesCard(form)
    if (form.existing?.mode == BlockMode.LOCKDOWN) {
        Gap(12)
        FormSection(Icons.Outlined.HourglassTop, "How long") { WhenSection(form) }
        return
    }
    val hasRules = form.draft().hasRules
    if (hasRules) {
        Gap(14)
        HoursCard(form)
    } else {
        FormSection(Icons.Outlined.CalendarMonth, "When it blocks") { WhenSection(form) }
        Gap(14)
    }
    LimitCard(form)
    FormSection(Icons.Outlined.VpnKey, "What opens it") {
        UnlockSections(form, apps, store)
    }
    RuleOrder(form)
    if (form.mode == BlockMode.HABIT) {
        Gap(14)
        BlockAgainCard(form, cap)
    }
    if (hasRules) {
        Gap(14)
        ScheduleCard(form)
    }
}

/** "Instagram", "Instagram + YouTube", "Instagram + 2 more": a name until you give it one. */
private fun autoName(form: BlockFormState, apps: List<InstalledApp>): String {
    val labels = apps.associate { it.packageName to it.label }
    val names = form.apps.map { labels[it] ?: it }.sortedBy { it.lowercase() } + form.sites.sorted()
    return summarizeNames(names, empty = "Block")
}

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
    // Another device may have changed the block while the page is open: whether a save or a
    // delete needs the password goes by the block as it is now.
    fun hardcore() = (store.world.value.block(existing.id) ?: existing).enforcementTier == EnforcementTier.HARDCORE
    val hint = form.hint(cap)

    fun save() {
        // I39: judged against the block and its checklist as they are now, not as the page found
        // them: what another device tightened or locked meanwhile, the page's older values loosen.
        val now = store.world.value
        val stored = now.block(existing.id) ?: existing
        val draft = form.draft().onto(stored)
        val updated = draft.toBlock(stored.id, stored.name)
        val apply = {
            // Items are rebuilt against the checklist as it is now, so ticks made meanwhile stay.
            store.edit { w -> w.withBlock(updated, draft.toItems(stored.id, w.items(stored.id))) }
            onDone()
        }
        val loosens = draft.saveLoosens(now.items(stored.id))
        // I6: and its unlock may open more of what other habit blocks block than it did.
        val opening = openingRequest(store, BlockEdits.opens(now, stored, updated, loosens), "Save these changes?") { apply() }
        val request = if (loosens && BlockEdits.guardNeeded(store.engine, now, stored, store.clock)) {
            LoosenRequest(
                "Save these changes?",
                "They make ${stored.name} easier to get past." + opening?.let { " " + it.detail }.orEmpty(),
                hardcore() || opening?.hardcore == true,
                listOf(stored.id) + opening?.blockIds.orEmpty(),
            ) { apply() }
        } else {
            opening
        }
        if (request == null) apply() else loosen = request
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        FormBar("Edit block", canSave = hint == null, onCancel = onDone, onSave = ::save)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            if (hint != null) FormHint(hint, MaterialTheme.colorScheme.error)
            BlockPage(form, apps, websites, store, cap)

            Gap(24)
            TextButton(
                onClick = {
                    if (BlockEdits.guardNeeded(store.engine, store.world.value, existing, store.clock)) {
                        loosen = LoosenRequest("Delete ${existing.name}?", "It stops blocking for good.", hardcore(), listOf(existing.id)) {
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
                if (empty) "Choose apps and sites" else pickedSummary(form.apps.size, Sites.shown(form.sites).size),
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
        // The other half of a service: the usual way around a block, usually without meaning to.
        Companions.appsFor(form.sites, form.apps, labels.keys).forEach { pkg ->
            Gap(10)
            DashedButton("Also block the ${labels[pkg] ?: pkg} app", null) { form.apps = form.apps + pkg }
        }
        Companions.sitesFor(form.apps, form.sites).forEach { site ->
            Gap(10)
            DashedButton("Also block $site", null) { form.sites = form.sites + site }
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

/** The ways to open a block, each opening its own settings. */
@Composable
private fun UnlockSections(form: BlockFormState, apps: List<InstalledApp>, store: WorldStore) {
    val kinds = LocalKinds.current
    val labels = remember(apps) { apps.associate { it.packageName to it.label } }
    var pickingAppTime by remember { mutableStateOf(false) }
    var pickingLoop by remember { mutableStateOf(false) }
    var pickingTimers by remember { mutableStateOf(false) }
    // The new-habit sheet open: for a timer (true) or a habit to tick or count (false).
    var makingHabit by remember { mutableStateOf<Boolean?>(null) }

    KindCard(
        icon = Icons.Outlined.Timer,
        title = "Time in another app",
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
    }

    KindCard(
        icon = Icons.Outlined.Timer,
        title = "Focus session",
        kind = kinds.timer,
        selected = form.focusOn,
        summary = andList(form.timerIds.mapNotNull { id -> form.knownHabits.firstOrNull { it.id == id }?.name }).ifEmpty { "no timer picked yet" },
        onToggle = { form.setKind(FormKind.FOCUS, it) },
    ) {
        HabitPicks(form, form.timerIds, onRemove = { id -> form.timerIds = form.timerIds - id })
        if (form.knownHabits.any { it.kind == HabitKind.TIMER && it.id !in form.timerIds }) {
            DashedButton("Choose a timer", kinds.timer) { pickingTimers = true }
            Gap(8)
        }
        DashedButton("New timer", kinds.timer) { makingHabit = true }
    }

    KindCard(
        icon = Icons.Outlined.Link,
        // xrnloops' tile names the app.
        title = "Linked habit",
        tile = { LoopsTile(size = it) },
        kind = kinds.loop,
        selected = form.loopOn,
        summary = andList(form.links.map { it.name } + form.tasks.filter { it.text.isNotBlank() }.map { it.text }).ifEmpty { "no habit linked yet" },
        onToggle = { form.setKind(FormKind.LOOP, it) },
    ) {
        form.links.forEachIndexed { i, link ->
            LinkRow(
                link,
                habit = form.knownHabits.firstOrNull { it.id == link.uuid },
                onChange = { new -> form.links = form.links.toMutableList().also { it[i] = new } },
                onRemove = { form.links = form.links.toMutableList().also { it.removeAt(i) } },
            )
        }
        // Tasks typed into a block before habits were shared ones: kept until taken off.
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
        val linked = form.links.mapTo(mutableSetOf()) { it.uuid }
        if (form.knownHabits.any { it.id !in linked && !it.archived && it.unlockUnit != null && it.kind != HabitKind.TIMER }) {
            DashedButton("Choose a habit", kinds.loop) { pickingLoop = true }
            Gap(8)
        }
        DashedButton("New habit", kinds.loop) { makingHabit = false }
    }

    // A phone without NFC isn't offered tags, but still shows the ones a block has.
    if (TagReader.hasNfc(LocalContext.current) || form.tags.isNotEmpty()) {
        TagCard(form)
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
    if (pickingTimers) {
        HabitPickerSheet(
            title = "Timers",
            habits = form.knownHabits.filter { it.kind == HabitKind.TIMER && (it.id in form.timerIds || (!it.archived && it.unlockUnit != null)) },
            initial = form.timerIds.toSet(),
            onDone = { ids -> form.setTimers(ids); pickingTimers = false },
            onDismiss = { pickingTimers = false },
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

/** A daily limit and at most so long in a row. */
@Composable
private fun LimitCard(form: BlockFormState) {
    val kinds = LocalKinds.current
    KindCard(
        icon = Icons.Outlined.HourglassBottom,
        title = "Daily limit",
        kind = kinds.limit,
        selected = form.limitOn,
        summary = listOfNotNull(
            if (form.dailyOn) "${formatDuration(form.dailyMinutes * 60_000)} a day" else null,
            if (form.rowOn) "${formatDuration(form.rowMinutes * 60_000)} in a row" else null,
            if (form.opensOn) "${form.opensPerDay} opens a day" else null,
            if (form.openWaitS > 0) "${form.openWaitS} s pause" else null,
        ).joinToString(" · "),
        onToggle = { form.setKind(FormKind.LIMIT, it) },
    ) {
        LimitEditor(form, kinds.limit)
    }
}

/** How rules that overlap go together: the timer and the rest, and a limit and a habit. */
@Composable
private fun RuleOrder(form: BlockFormState) {
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

    if (form.draft().orderMatters) {
        Gap(16)
        Text("What comes first", style = MaterialTheme.typography.bodyLarge)
        Gap(8)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OptionButton(Icons.Outlined.HourglassBottom, "Limit first", !form.habitFirst, Modifier.weight(1f), centred = true) { form.habitFirst = false }
            OptionButton(Icons.Outlined.TaskAlt, "Habit first", form.habitFirst, Modifier.weight(1f), centred = true) { form.habitFirst = true }
        }
    }
}

private val OPEN_WAIT_CHOICES = listOf(3, 5, 10, 20)

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
    }
    ToggleLine("At most so many opens a day", form.opensOn) { form.opensOn = it }
    if (form.opensOn) {
        NumberChips(listOf(1, 2, 3, 5, 10), form.opensPerDay, kind) { form.opensPerDay = it }
    }
    ToggleLine("A pause before it opens", form.openWaitS > 0) { form.openWaitS = if (it) OPEN_WAIT_CHOICES[1] else 0 }
    if (form.openWaitS > 0) {
        NumberChips(OPEN_WAIT_CHOICES, form.openWaitS, kind, suffix = " s") { form.openWaitS = it }
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

/** A linked habit in the form: its tile and name, and which day's tick counts (I23). */
@Composable
private fun LinkRow(link: LinkDraft, habit: Habit?, onChange: (LinkDraft) -> Unit, onRemove: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.18f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HabitTile(habit, size = 34)
            WGap(12)
            Column(Modifier.weight(1f)) {
                Text(habit?.name ?: link.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (habit != null) {
                    Text(habitGoalText(habit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(
                Icons.Filled.Close,
                contentDescription = "Take ${link.name} off",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp).clickable(onClick = onRemove),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !link.yesterday, onClick = { onChange(link.copy(yesterday = false)) }, label = { Text("Today") })
            FilterChip(selected = link.yesterday, onClick = { onChange(link.copy(yesterday = true)) }, label = { Text("Yesterday") })
            if (!link.daily || link.goal) {
                FilterChip(selected = link.goal, onClick = { onChange(link.copy(goal = !link.goal)) }, label = { Text("Goal days") })
            }
        }
    }
    Gap(8)
}

/** All the time, at set hours, or (a block nothing unlocks) for a while. */
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
                        WhenMode.ALWAYS -> "All the time"
                        WhenMode.SCHEDULE -> "These hours"
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
        WhenMode.ALWAYS -> Unit
        WhenMode.SCHEDULE -> ScheduleEditor(form)
        WhenMode.FOR_A_WHILE -> {
            MinuteChips(listOf(30, 60, 120, 240), form.lockdownMinutes, kinds.strict, format = ::shortMinutes) { form.lockdownMinutes = it }
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
 * When the rules count at all, as a folding card: every day unless you say otherwise. Blocked
 * hours are the other way round: those block whatever the rules say.
 */
@Composable
private fun ScheduleCard(form: BlockFormState) {
    var open by remember { mutableStateOf(form.whenMode == WhenMode.SCHEDULE) }
    fun settle() {
        val always = form.scheduleDays.size == 7 && !form.timeOn && form.moreWindows.isEmpty()
        form.whenMode = if (always) WhenMode.ALWAYS else WhenMode.SCHEDULE
    }
    val summary = if (form.whenMode == WhenMode.ALWAYS) {
        "The rules count every day"
    } else {
        "The rules count " + describeSchedule(
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
            }
        }
    }
}

/**
 * I44: hours blocked outright on a block with a habit or a limit, whatever the rest of it says.
 * Off: a grey row. On: the same editor as a schedule.
 */
@Composable
private fun HoursCard(form: BlockFormState) {
    KindCard(
        icon = Icons.Outlined.CalendarMonth,
        title = "Blocked hours",
        kind = LocalKinds.current.strict,
        selected = form.hoursOn,
        summary = describeSchedule(Schedule(form.hoursDays, if (form.hoursTimeOn) TimeWindow(form.hoursStart, form.hoursEnd) else null, form.hoursMore)),
        onToggle = { form.hoursOn = it },
    ) {
        TimesEditor(
            days = form.hoursDays,
            onDays = { form.hoursDays = it },
            timeOn = form.hoursTimeOn,
            onTimeOn = { form.hoursTimeOn = it },
            start = form.hoursStart,
            end = form.hoursEnd,
            onStart = { form.hoursStart = it },
            onEnd = { form.hoursEnd = it },
            more = form.hoursMore,
            onMore = { form.hoursMore = it },
        )
    }
}

/** A schedule's days and times. [onChange] after a change to which days or whether there are times. */
@Composable
private fun ScheduleEditor(form: BlockFormState, onChange: () -> Unit = {}) {
    TimesEditor(
        days = form.scheduleDays,
        onDays = { form.scheduleDays = it; onChange() },
        timeOn = form.timeOn,
        onTimeOn = { form.timeOn = it; onChange() },
        start = form.startMinute,
        end = form.endMinute,
        onStart = { form.startMinute = it },
        onEnd = { form.endMinute = it },
        more = form.moreWindows,
        onMore = { form.moreWindows = it; onChange() },
    )
}

/** The days, "Specify time" or its hours, and more times with their own days. */
@Composable
private fun TimesEditor(
    days: Set<Int>,
    onDays: (Set<Int>) -> Unit,
    timeOn: Boolean,
    onTimeOn: (Boolean) -> Unit,
    start: Int,
    end: Int,
    onStart: (Int) -> Unit,
    onEnd: (Int) -> Unit,
    more: List<DayWindow>,
    onMore: (List<DayWindow>) -> Unit,
) {
    DayCircles(days, onDays)
    Gap(12)
    if (timeOn) {
        TimeRange(start, end, onStart = onStart, onEnd = onEnd)
        TextButton(onClick = { onTimeOn(false) }) { Text("All day instead") }
    } else {
        GreyButton("Specify time", Icons.Filled.AddCircle) { onTimeOn(true) }
    }
    more.forEachIndexed { i, w ->
        fun update(new: DayWindow) = onMore(more.toMutableList().also { it[i] = new })
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
        TextButton(onClick = { onMore(more.toMutableList().also { it.removeAt(i) }) }) {
            Text("Remove these times")
        }
    }
    if (timeOn) {
        Gap(8)
        DashedButton("More times", null) { onMore(more + newDayWindow()) }
    }
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
