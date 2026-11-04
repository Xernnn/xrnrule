package com.xern.xrnrule.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xern.xrnrule.app.data.AppPermission
import com.xern.xrnrule.app.data.AppUsage
import com.xern.xrnrule.app.data.DayUsage
import com.xern.xrnrule.app.data.InstalledApps
import com.xern.xrnrule.app.data.LocalTelemetry
import com.xern.xrnrule.app.data.ScreenTime
import com.xern.xrnrule.app.data.SiteUsageLog
import com.xern.xrnrule.app.data.TelemetryEvent
import com.xern.xrnrule.app.data.isGranted
import com.xern.xrnrule.app.data.settingsIntent
import com.xern.xrnrule.app.web.WebsiteMonitor
import com.xern.xrnrule.app.web.WebsiteTracking
import com.xern.xrnrule.app.web.tracking
import com.xern.xrnrule.domain.BlockState
import com.xern.xrnrule.domain.Sites
import com.xern.xrnrule.domain.World
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val DAYS = 7
private const val APPS_COLLAPSED = 8
private const val REFRESH_MS = 60_000L
private val PlotHeight = 132.dp
private val PlotTopPad = 8.dp

@Composable
fun InsightsScreen(world: World, websites: WebsiteMonitor, siteUsage: SiteUsageLog, telemetry: LocalTelemetry) {
    val context = LocalContext.current
    val resume = rememberResumeSignal()
    val hasAccess = remember(resume) { AppPermission.USAGE_ACCESS.isGranted(context) }
    val websiteTracking = remember(resume) { websites.tracking(context) }
    val siteDays by siteUsage.days.collectAsStateWithLifecycle()
    val blockedSites = remember(world.blocks) { world.blocks.flatMapTo(HashSet()) { it.targetSites } }
    // Keyed on resume so returning to the app refetches; the previous value stays on screen meanwhile.
    var usage by remember { mutableStateOf<List<DayUsage>?>(null) }
    LaunchedEffect(hasAccess, resume) {
        while (hasAccess) {
            usage = withContext(Dispatchers.IO) { ScreenTime.lastDays(context, DAYS) }
            delay(REFRESH_MS)
        }
    }
    var history by remember { mutableStateOf<List<TelemetryEvent>>(emptyList()) }
    LaunchedEffect(resume) {
        while (true) {
            history = withContext(Dispatchers.IO) { telemetry.events() }
            delay(REFRESH_MS)
        }
    }
    var selected by rememberSaveable { mutableIntStateOf(DAYS - 1) }
    var week by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val blocked = remember(world.blocks) { world.blocks.flatMapTo(HashSet()) { it.targetPackages } }
    val days = usage
    // The day picked on the chart, or all of them; today when there's no chart to pick from.
    val today = LocalDate.now()
    val pickedDate = days?.takeIf { it.isNotEmpty() }?.let { it[selected.coerceIn(it.indices)].date } ?: today
    val dates: List<LocalDate> =
        if (week) days?.takeIf { it.isNotEmpty() }?.map { it.date } ?: (DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }
        else listOf(pickedDate)
    val rangeTitle = if (week) "This week" else dayTitle(pickedDate)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Habits First's header: the page's name, and whether it shows a day or the week.
        PageTitle("insights") {
            RangeSwitch(
                week = week,
                onDay = { week = false; selected = DAYS - 1 },
                onWeek = { week = true },
            )
        }
        InsightTabs(tab, onSelect = { tab = it })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (tab == 0) {
                when {
                    !hasAccess -> UsageAccessEmpty {
                        runCatching { context.startActivity(AppPermission.USAGE_ACCESS.settingsIntent(context)) }
                    }
                    days.isNullOrEmpty() -> HFCard {
                        Text("Screen time", style = MaterialTheme.typography.titleMedium)
                        Gap(4)
                        Text(
                            "Reading usage data…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        val index = selected.coerceIn(days.indices)
                        ScreenTimeCard(days, if (week) null else index, blocked, onSelect = { selected = it; week = false })
                        MostUsedCard(rangeTitle, if (week) addUp(days) else days[index].apps, blocked)
                    }
                }
                if (hasAccess && websites.isSupported()) {
                    val sites = remember(siteDays, dates) {
                        HashMap<String, Long>().also { sum ->
                            dates.forEach { d -> siteDays[d.toEpochDay()]?.forEach { (host, ms) -> sum[host] = (sum[host] ?: 0L) + ms } }
                        }
                    }
                    WebsitesCard(rangeTitle, sites, blockedSites, websiteTracking) {
                        runCatching { context.startActivity(websites.settingsIntent()) }
                    }
                }
            } else {
                HistoryCard(rangeTitle, dates.toSet(), history)
                val usedMin = (world.dailyUnlockUsedMs + world.unlockLog.values.sum()) / 60_000 // I28
                val capMin = world.settings.dailyCapMs / 60_000
                HFCard {
                    Text(
                        "Unlock time used today",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Gap(4)
                    Text("$usedMin / $capMin min", style = MaterialTheme.typography.headlineMedium)
                    Gap(10)
                    MeterBar(if (capMin == 0L) 0f else usedMin.toFloat() / capMin)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigStat(world.blocks.count { it.state == BlockState.ARMED }.toString(), "Locked", Modifier.weight(1f))
                    BigStat(world.blocks.count { it.state == BlockState.EARNED }.toString(), "Ready", Modifier.weight(1f))
                    BigStat(world.blocks.count { it.state == BlockState.UNLOCKED }.toString(), "Open", Modifier.weight(1f))
                }
            }
            Gap(8)
        }
    }
}

/** Every app's time over [days], most first. */
private fun addUp(days: List<DayUsage>): List<AppUsage> =
    days.flatMap { it.apps }
        .groupBy { it.packageName }
        .map { (pkg, list) -> AppUsage(pkg, list.first().label, list.sumOf { it.foregroundMs }) }
        .sortedByDescending { it.foregroundMs }

/** Habits First's "✓ Today | Week": an outlined pair, the chosen one filled and ticked. */
@Composable
private fun RangeSwitch(week: Boolean, onDay: () -> Unit, onWeek: () -> Unit) {
    val outline = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    Row(
        Modifier
            .height(40.dp)
            .clip(CircleShape)
            .border(1.dp, outline, CircleShape),
    ) {
        listOf("Today" to !week, "Week" to week).forEachIndexed { i, (label, on) ->
            if (i == 1) Box(Modifier.width(1.dp).fillMaxHeight().background(outline))
            Row(
                Modifier
                    .fillMaxHeight()
                    .background(if (on) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                    .selectable(selected = on, onClick = if (i == 0) onDay else onWeek, role = Role.Tab)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (on) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    WGap(6)
                }
                Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp))
            }
        }
    }
}

/** "Screen time | Habits", underlined like Habits First's tabs. */
@Composable
private fun InsightTabs(selected: Int, onSelect: (Int) -> Unit) {
    val tabs = listOf(Icons.Filled.PhoneAndroid to "Screen time", Icons.Filled.GridView to "Habits")
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Row(Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { i, (icon, label) ->
                val on = i == selected
                val ink = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    Modifier
                        .weight(1f)
                        .selectable(selected = on, onClick = { onSelect(i) }, role = Role.Tab),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(Modifier.height(46.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
                        WGap(8)
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                            color = ink,
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(if (on) MaterialTheme.colorScheme.onSurface else Color.Transparent, RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
    }
}

/** Without usage access: Habits First's empty page, a chart mark and one button. */
@Composable
private fun UsageAccessEmpty(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 160.dp, bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.BarChart, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(44.dp))
        Gap(18)
        Text("See where your day goes", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal, fontSize = 19.sp))
        Gap(12)
        Text(
            "Allow usage access to see your usage breakdown.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Gap(18)
        Text(
            "Allow usage access",
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface)
                .clickable(onClick = onGrant)
                .padding(horizontal = 22.dp, vertical = 10.dp),
        )
    }
}

// ---- screen time: headline + week ------------------------------------------------------

@Composable
private fun ScreenTimeCard(days: List<DayUsage>, selected: Int?, blocked: Set<String>, onSelect: (Int) -> Unit) {
    val picked = if (selected == null) days else listOf(days[selected])
    val totalMs = picked.sumOf { it.totalMs }
    // Past days only: today is partial and would drag the average down all morning. Zero days
    // are usually just outside the OS's event retention, not a phone left untouched.
    val avgMs = days.dropLast(1).map { it.totalMs }.filter { it > 0 }
        .takeIf { it.isNotEmpty() }?.average()?.toLong()

    HFCard {
        Text(
            "Screen time · ${if (selected == null) "This week" else dayTitle(days[selected].date)}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Gap(2)
        Text(formatDuration(totalMs), style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.SemiBold))
        if (blocked.isNotEmpty()) {
            val blockedMs = picked.flatMap { it.apps }.filter { it.packageName in blocked }.sumOf { it.foregroundMs }
            Text(
                if (blockedMs > 0) "${formatDuration(blockedMs)} of it in apps you block" else "None of it in apps you block",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Gap(16)
        WeekChart(days, selected ?: -1, avgMs, onSelect)
        if (avgMs != null) {
            Gap(10)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(14.dp).height(1.5.dp).background(avgLineColor()))
                WGap(8)
                Text(
                    "Daily average ${formatDuration(avgMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun avgLineColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)

/**
 * One column per day, one series colour. The selected day is marked by its backing pill and a
 * bold label rather than a second bar colour, so every bar keeps full contrast; its value is
 * the headline above. Tap a column to select it.
 */
@Composable
private fun WeekChart(days: List<DayUsage>, selected: Int, avgMs: Long?, onSelect: (Int) -> Unit) {
    val series = chartSeriesColor()
    val maxMs = maxOf(days.maxOf { it.totalMs }, avgMs ?: 0L).coerceAtLeast(1L)

    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            days.forEachIndexed { i, d ->
                val isSelected = i == selected
                val fraction by animateFloatAsState(d.totalMs.toFloat() / maxMs, label = "bar$i")
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .selectable(selected = isSelected, onClick = { onSelect(i) }, role = Role.Tab)
                        .semantics { contentDescription = "${fullDay(d.date)}, ${formatDuration(d.totalMs)}" }
                        .padding(top = PlotTopPad, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.fillMaxWidth().height(PlotHeight), contentAlignment = Alignment.BottomCenter) {
                        if (d.totalMs > 0) {
                            Box(
                                Modifier
                                    .width(20.dp)
                                    .heightIn(min = 2.dp)
                                    .fillMaxHeight(fraction)
                                    .background(series, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                            )
                        }
                    }
                    Gap(6)
                    Text(
                        if (d.date == LocalDate.now()) "Today" else d.date.format(ShortDay),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
            }
        }
        // Baseline, then the average reference line, drawn over the columns.
        Box(
            Modifier
                .offset(y = PlotTopPad + PlotHeight)
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline),
        )
        if (avgMs != null) {
            Box(
                Modifier
                    .offset(y = PlotTopPad + PlotHeight * (1f - avgMs.toFloat() / maxMs))
                    .fillMaxWidth()
                    .height(1.5.dp)
                    .background(avgLineColor()),
            )
        }
    }
}

// ---- screen time: per app ----------------------------------------------------------------

@Composable
private fun MostUsedCard(title: String, used: List<AppUsage>, blocked: Set<String>) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    // Sub-minute blips (a share sheet, a permission prompt) still count toward the total above.
    val apps = used.filter { it.foregroundMs >= 60_000 }
    val shown = if (showAll) apps else apps.take(APPS_COLLAPSED)
    val maxMs = apps.firstOrNull()?.foregroundMs ?: 1L

    HFCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Most used", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (apps.isEmpty()) {
            Gap(6)
            Text(
                "No apps yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Gap(4)
            shown.forEach { AppUsageRow(it, it.foregroundMs.toFloat() / maxMs, it.packageName in blocked) }
        }
        if (apps.size > APPS_COLLAPSED) {
            TextButton(onClick = { showAll = !showAll }) {
                Text(if (showAll) "Show less" else "Show all ${apps.size} apps")
            }
        }
    }
}

@Composable
private fun AppUsageRow(app: AppUsage, fraction: Float, isBlocked: Boolean) =
    UsageRow(app.label, app.foregroundMs, fraction, isBlocked) { AppIcon(app.packageName, size = 36.dp) }

@Composable
private fun SiteUsageRow(host: String, ms: Long, fraction: Float, isBlocked: Boolean) =
    UsageRow(host, ms, fraction, isBlocked) { SiteIcon(host, size = 36.dp) }

@Composable
private fun UsageRow(label: String, ms: Long, fraction: Float, isBlocked: Boolean, leading: @Composable () -> Unit) {
    val series = chartSeriesColor()
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "usageBar")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        WGap(12)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isBlocked) {
                    WGap(8)
                    BlockedTag()
                }
            }
            Gap(6)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(series.copy(alpha = 0.14f), RoundedCornerShape(3.dp)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(animated)
                        .height(6.dp)
                        .background(series, RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp)),
                )
            }
        }
        WGap(12)
        Text(
            formatDuration(ms),
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
        )
    }
}

/**
 * Time per website for the day or week shown. Android keeps no per-site history, so this only
 * covers time since the browser extension first reported a site.
 */
@Composable
private fun WebsitesCard(
    title: String,
    perSite: Map<String, Long>,
    blockedSites: Set<String>,
    tracking: WebsiteTracking,
    onTurnOn: () -> Unit,
) {
    // Time per site comes from the browser extension; once it has ever answered there is data to show.
    val trackingOn = tracking != WebsiteTracking.OFF
    var showAll by rememberSaveable { mutableStateOf(false) }
    val sites = perSite.entries
        .filter { it.value >= 60_000 }
        .sortedByDescending { it.value }
    val shown = if (showAll) sites else sites.take(APPS_COLLAPSED)
    val maxMs = sites.firstOrNull()?.value ?: 1L

    HFCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Websites", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            !trackingOn -> {
                Gap(12)
                Button(onClick = onTurnOn, modifier = Modifier.fillMaxWidth()) {
                    Text("Set up the browser extension")
                }
            }
            sites.isEmpty() -> {
                Gap(6)
                Text(
                    "No websites yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                Gap(4)
                shown.forEach { (host, ms) ->
                    SiteUsageRow(host, ms, ms.toFloat() / maxMs, blockedSites.any { Sites.matches(host, it) })
                }
            }
        }
        if (trackingOn && sites.size > APPS_COLLAPSED) {
            TextButton(onClick = { showAll = !showAll }) {
                Text(if (showAll) "Show less" else "Show all ${sites.size} sites")
            }
        }
    }
}

@Composable
private fun BlockedTag() {
    val style = statusStyleFor(BlockState.ARMED)
    Row(
        Modifier
            .background(style.container, RoundedCornerShape(50))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = style.content, modifier = Modifier.size(11.dp))
        WGap(3)
        Text("Blocked", style = MaterialTheme.typography.labelSmall, color = style.content)
    }
}

// ---- formatting ------------------------------------------------------------------------------

private val ShortDay = DateTimeFormatter.ofPattern("EEE")
private val LongDay = DateTimeFormatter.ofPattern("EEEE, d MMM")

/**
 * What xrnrule itself saw on [dates] (the day picked on the chart, or the week): habits done,
 * attempts to open a blocked app and which apps, unlock time used. Plus the run of days with a
 * habit done.
 */
@Composable
private fun HistoryCard(title: String, dates: Set<LocalDate>, events: List<TelemetryEvent>) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    fun TelemetryEvent.date(): LocalDate = Instant.ofEpochMilli(atWallMs).atZone(zone).toLocalDate()
    val onDay = events.filter { it.date() in dates }
    val habits = onDay.count { it is TelemetryEvent.HabitDone }
    val blocked = onDay.filterIsInstance<TelemetryEvent.Blocked>()
    val unlockMs = onDay.filterIsInstance<TelemetryEvent.UnlockUsed>().sumOf { it.ms }
    val habitDays = events.filterIsInstance<TelemetryEvent.HabitDone>().mapTo(HashSet()) { it.date() }
    val today = LocalDate.now(zone)
    // A streak isn't broken until today ends without a habit, so it may still run from yesterday.
    var day = if (today in habitDays) today else today.minusDays(1)
    var streak = 0
    while (day in habitDays) {
        streak++
        day = day.minusDays(1)
    }
    val topBlocked = remember(blocked) {
        blocked.groupingBy { it.pkg }.eachCount().entries.sortedByDescending { it.value }.take(3)
            .joinToString(", ") { (pkg, n) -> "${InstalledApps.labelFor(context, pkg)} ${n}×" }
    }

    HFCard {
        Text(
            "Your habits · $title",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Gap(8)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InlineStat(habits.toString(), "Habits done", Modifier.weight(1f))
            InlineStat(blocked.size.toString(), "Times blocked", Modifier.weight(1f))
            InlineStat(formatDuration(unlockMs), "Unlocked", Modifier.weight(1f))
        }
        if (topBlocked.isNotEmpty()) {
            Gap(10)
            Text(
                "Most tried: $topBlocked",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Gap(6)
        Text(
            when (streak) {
                0 -> "No habit streak yet"
                1 -> "1 day in a row with a habit done"
                else -> "$streak days in a row with a habit done"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A number and its label inside a card ([BigStat] is a card of its own). */
@Composable
private fun InlineStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun dayTitle(date: LocalDate): String = when (date) {
    LocalDate.now() -> "Today"
    LocalDate.now().minusDays(1) -> "Yesterday"
    else -> date.format(LongDay)
}

private fun fullDay(date: LocalDate): String = date.format(LongDay)
