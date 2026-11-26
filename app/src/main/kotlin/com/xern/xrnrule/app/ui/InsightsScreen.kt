@file:OptIn(ExperimentalLayoutApi::class)

package com.xern.xrnrule.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.FreeBreakfast
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.LockClock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreTime
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xern.xrnrule.app.data.AppPermission
import com.xern.xrnrule.app.data.DayUsage
import com.xern.xrnrule.app.data.InstalledApps
import com.xern.xrnrule.app.data.LocalTelemetry
import com.xern.xrnrule.app.data.ScreenTime
import com.xern.xrnrule.app.data.SharedSync
import com.xern.xrnrule.app.data.SiteUsageLog
import com.xern.xrnrule.app.data.TelemetryEvent
import com.xern.xrnrule.app.data.UsageElsewhere
import com.xern.xrnrule.app.data.WorldStore
import com.xern.xrnrule.app.data.isGranted
import com.xern.xrnrule.app.data.settingsIntent
import com.xern.xrnrule.app.ui.theme.DataColor
import com.xern.xrnrule.app.ui.theme.TABULAR
import com.xern.xrnrule.app.web.WebsiteMonitor
import com.xern.xrnrule.app.web.WebsiteTracking
import com.xern.xrnrule.app.web.tracking
import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.Sites
import com.xern.xrnrule.domain.World
import com.xern.xrnrule.domain.startOfLocalDayMs
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as NameStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val REFRESH_MS = 60_000L
private const val DAY_MS = 86_400_000L
private const val TOP_COLLAPSED = 6
private const val TRIED_COLLAPSED = 5
private const val HISTORY_DAYS = 10

/** Whether the laptop's log could be read. */
enum class LaptopLog { OFF, OK, UNREACHABLE }

/** Everything the Insights page draws, worked out by [InsightsScreen] (or made up, in previews). */
data class InsightsUi(
    val summary: InsightsSummary,
    val blocks: List<Block>,
    val apps: List<TopTarget>,
    val sites: List<TopTarget>,
    /** Each block with a daily limit: its use a day over the range. */
    val limits: Map<String, LimitUse>,
    val usageAccess: Boolean,
    val sitesOn: Boolean,
    val laptop: LaptopLog,
    val zone: ZoneId,
    /** An app's name, by package (or laptop app id). */
    val appLabel: (String) -> String = { it },
    /** Each day's time on the phone and on the laptop. */
    val screen: ScreenTimeSummary? = null,
)

/**
 * Insights: what both devices saw, from this phone's log and the laptop's (through the sync
 * server), for today, a week or a month, each figure beside the stretch before it.
 */
@Composable
fun InsightsScreen(world: World, store: WorldStore, websites: WebsiteMonitor, siteUsage: SiteUsageLog, telemetry: LocalTelemetry, shared: SharedSync) {
    val context = LocalContext.current
    val resume = rememberResumeSignal()
    val hasAccess = remember(resume) { AppPermission.USAGE_ACCESS.isGranted(context) }
    val tracking = remember(resume) { websites.tracking(context) }
    val siteDays by siteUsage.days.collectAsStateWithLifecycle()
    var range by rememberSaveable { mutableStateOf(InsightRange.WEEK) }
    var device by rememberSaveable { mutableStateOf(DeviceFilter.ALL) }
    var blockId by rememberSaveable { mutableStateOf<String?>(null) }
    val clock = store.clock
    val zone = clock.zone()
    val minute = rememberTicker(REFRESH_MS)

    // Keyed on resume so returning to the app reads again; the last values stay up meanwhile.
    var usage by remember { mutableStateOf<List<DayUsage>>(emptyList()) }
    LaunchedEffect(hasAccess, resume, range) {
        while (hasAccess) {
            usage = withContext(Dispatchers.IO) { ScreenTime.lastDays(context, range.days, zone) }
            delay(REFRESH_MS)
        }
    }
    var local by remember { mutableStateOf<List<TelemetryEvent>>(emptyList()) }
    LaunchedEffect(resume) {
        while (true) {
            local = withContext(Dispatchers.IO) { telemetry.events() }
            delay(REFRESH_MS)
        }
    }
    // The laptop's log, from the server, once a visit: two months and a day of it, so 30 days
    // have the 30 before them to set beside.
    var elsewhere by remember { mutableStateOf<List<TelemetryEvent>?>(emptyList()) }
    // Each block's limit use day by day, as the server kept it: the days before today.
    var limitRecord by remember { mutableStateOf<Map<String, Map<String, Map<Long, Long>>>>(emptyMap()) }
    // The laptop's time per app and site, a month of it (docs/laptop-screen-time.md).
    var laptopUse by remember { mutableStateOf(UsageElsewhere()) }
    LaunchedEffect(resume) {
        elsewhere = withContext(Dispatchers.IO) { shared.eventsElsewhere(clock.nowWallMs() - (InsightRange.MONTH.days * 2 + 1) * DAY_MS) }
        withContext(Dispatchers.IO) { shared.limitsRecord() }?.let { limitRecord = it }
        withContext(Dispatchers.IO) { shared.usageElsewhere(InsightsModel.today(clock.nowWallMs(), zone).minusDays(InsightRange.MONTH.days - 1L)) }?.let { laptopUse = it }
    }

    val labels = remember { HashMap<String, String>() }
    val appLabel: (String) -> String = { pkg -> labels.getOrPut(pkg) { InstalledApps.labelFor(context, pkg) } }
    val merged = remember(local, elsewhere, world.emergencyLog, world.blocks) {
        InsightsModel.merge(local, elsewhere.orEmpty(), world.emergencyLog, world.blocks)
    }
    val now = clock.nowWallMs()
    val summary = remember(merged, device, blockId, range, world.blocks, minute) {
        InsightsModel.summarize(InsightsModel.select(merged, device, blockId), world.blocks, range, now, zone, logStartMs = merged.firstOrNull()?.atWallMs)
    }
    // Time per app and site: the phone's own, and the laptop's from the server.
    val phoneShown = device != DeviceFilter.LAPTOP
    val laptopShown = device != DeviceFilter.PHONE
    val (apps, sites) = remember(summary, usage, siteDays, laptopUse, phoneShown, laptopShown) {
        val appTime = HashMap<String, Pair<String, Long>>()
        val siteTime = HashMap<String, Long>()
        val laptopApps = HashMap<String, Pair<String, Long>>()
        val laptopSites = HashMap<String, Long>()
        val today = InsightsModel.today(now, zone)
        val first = today.minusDays(range.days - 1L)
        if (phoneShown) {
            usage.filter { !it.date.isBefore(first) }.flatMap { it.apps }.forEach { a ->
                appTime[a.packageName] = a.label to ((appTime[a.packageName]?.second ?: 0L) + a.foregroundMs)
            }
            InsightsModel.epochDays(range, now, zone).forEach { d -> siteDays[d]?.forEach { (host, ms) -> siteTime.merge(host, ms, Long::plus) } }
        }
        if (laptopShown) {
            laptopUse.days.filterKeys { !it.isBefore(first) && !it.isAfter(today) }.values.forEach { day ->
                day.apps.forEach { (id, ms) -> laptopApps[id] = (laptopUse.names[id] ?: id) to ((laptopApps[id]?.second ?: 0L) + ms) }
                day.sites.forEach { (host, ms) -> laptopSites.merge(host, ms, Long::plus) }
            }
        }
        InsightsModel.topTargets(appTime, siteTime, summary.targets, world.blocks, blockId, laptopApps, laptopSites)
    }
    val screen = remember(usage, siteDays, laptopUse, device, blockId, range, world.blocks, minute) {
        InsightsModel.screenTime(
            phoneApps = usage.associate { d -> d.date to d.apps.associate { it.packageName to it.foregroundMs } },
            phoneSites = siteDays.mapKeys { LocalDate.ofEpochDay(it.key) },
            laptop = laptopUse.days,
            blocks = world.blocks,
            blockId = blockId,
            range = range,
            device = device,
            nowWallMs = now,
            zone = zone,
        )
    }
    val dayStart = clock.startOfLocalDayMs()
    val limits = world.blocks.filter { it.dailyAllowanceMs > 0 && (it.mode == BlockMode.ALLOWANCE || it.mode == BlockMode.HABIT) }.associate { b ->
        val total = store.engine.allowanceUsedMs(world, b, clock)
        val laptop = if (b.perDevice) 0L else world.otherAllowance[b.id].orEmpty().values.sumOf { if (it.dayStartWall == dayStart) it.ms else 0L }
        val today = BlockToday((total - laptop).coerceAtLeast(0), laptop, b.dailyAllowanceMs)
        b.id to InsightsModel.limitUse(b, limitRecord[b.id].orEmpty(), today, range, device, now, zone)
    }

    InsightsContent(
        InsightsUi(
            summary = summary,
            blocks = world.blocks,
            apps = apps,
            sites = sites,
            limits = limits,
            usageAccess = hasAccess,
            sitesOn = websites.isSupported() && tracking != WebsiteTracking.OFF,
            laptop = when {
                !shared.settings.isConfigured -> LaptopLog.OFF
                elsewhere == null -> LaptopLog.UNREACHABLE
                else -> LaptopLog.OK
            },
            zone = zone,
            appLabel = appLabel,
            screen = screen,
        ),
        range = range,
        onRange = { range = it },
        device = device,
        onDevice = { device = it },
        blockId = blockId,
        onBlock = { blockId = it },
        onAllowUsage = { runCatching { context.startActivity(AppPermission.USAGE_ACCESS.settingsIntent(context)) } },
        onSetUpSites = { runCatching { context.startActivity(websites.settingsIntent()) } },
    )
}

/**
 * The page itself, from [ui], in the web pages' order: the range and device switches, the five
 * figures, tries per day (per hour on Today), when, the most tried, a row per block, then the
 * phone's own apps and sites, and the history. Picking a block (its row, or its name in the
 * history) narrows all of it to that block.
 */
@Composable
internal fun InsightsContent(
    ui: InsightsUi,
    range: InsightRange,
    onRange: (InsightRange) -> Unit,
    device: DeviceFilter,
    onDevice: (DeviceFilter) -> Unit,
    blockId: String?,
    onBlock: (String?) -> Unit,
    onAllowUsage: () -> Unit = {},
    onSetUpSites: () -> Unit = {},
    scroll: ScrollState = rememberScrollState(),
    /** A bar tapped to begin with (previews). */
    chartPick: Int = -1,
) {
    val s = ui.summary
    val picked = ui.blocks.firstOrNull { it.id == blockId }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PageTitle("Insights")
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Segments(
                    InsightRange.entries.map { SegmentOption(it.label) },
                    range.ordinal,
                    onSelect = { onRange(InsightRange.entries[it]) },
                    modifier = Modifier.weight(1f),
                )
                WGap(10)
                Segments(
                    listOf(
                        SegmentOption("All"),
                        SegmentOption(null, Icons.Outlined.PhoneAndroid, "Phone"),
                        SegmentOption(null, Icons.Outlined.Laptop, "Laptop"),
                    ),
                    device.ordinal,
                    onSelect = { onDevice(DeviceFilter.entries[it]) },
                    modifier = Modifier.width(128.dp),
                )
            }
            if (device != DeviceFilter.PHONE && ui.laptop != LaptopLog.OK) {
                Warning(if (ui.laptop == LaptopLog.OFF) "Laptop: sync is off" else "Laptop: server not answering")
            }
            if (picked != null) {
                Row { BlockChip(picked.name, blockColor(ui.blocks, picked.id), onClick = { onBlock(null) }, closable = true) }
            }
            Tiles(s)
            TriesChart(s, ui.blocks, range, chartPick)
            Heatmap(s)
            MostTried(s, ui, device)
            ByBlock(s, ui, range, picked, onBlock)
            ui.screen?.let { ScreenCard(it, range, device, s.today) }
            TopCard("Apps", ui.apps, ui, showSetup = device != DeviceFilter.LAPTOP && !ui.usageAccess, setupLabel = "Allow usage access", onSetup = onAllowUsage)
            TopCard("Sites", ui.sites, ui, showSetup = device != DeviceFilter.LAPTOP && !ui.sitesOn, setupLabel = "Set up the browser extension", onSetup = onSetUpSites)
            HistoryCard(s, ui, onBlock = onBlock)
            Gap(8)
        }
    }
}

// ---- pieces ----------------------------------------------------------------------------------

data class SegmentOption(val label: String?, val icon: ImageVector? = null, val description: String? = null)

/** Options on a raised track, the chosen one a light pill. */
@Composable
private fun Segments(options: List<SegmentOption>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .height(38.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp),
    ) {
        options.forEachIndexed { i, option ->
            val on = i == selected
            val ink = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
            Box(
                Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) MaterialTheme.colorScheme.onSurface else Color.Transparent)
                    .selectable(selected = on, onClick = { onSelect(i) }, role = Role.Tab)
                    .semantics { option.description?.let { contentDescription = it } },
                contentAlignment = Alignment.Center,
            ) {
                if (option.icon != null) {
                    Icon(option.icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
                } else {
                    Text(option.label.orEmpty(), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium), color = ink, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Warning(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 4.dp))
}

/** A plain card: the surface, no border, no tint. */
@Composable
private fun Panel(
    padding: PaddingValues = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(padding),
        content = content,
    )
}

/** "All 12", "Fewer", "More": a plain button in the text's grey. */
@Composable
private fun MoreButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** A card's title, with what goes at its right. */
@Composable
private fun PanelTitle(title: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 26.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodySmall) {
    Text(text, style = style.copy(fontFeatureSettings = TABULAR), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** The five figures, each over the same figure for the stretch before. */
@Composable
private fun Tiles(s: InsightsSummary) {
    val p = s.previous
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile("Blocked tries", s.tries.toString(), p?.tries?.toString(), Modifier.weight(1f), big = true)
        Tile("Unlocked", formatDuration(s.unlockedMs), p?.let { formatDuration(it.unlockedMs) }, Modifier.weight(1f), big = true)
    }
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile("Bought", shortDuration(s.boughtMs), p?.let { shortDuration(it.boughtMs) }, Modifier.weight(1f))
        Tile("Breaks", s.breaks.toString(), p?.breaks?.toString(), Modifier.weight(1f))
        Tile("Habits", s.habits.toString(), p?.habits?.toString(), Modifier.weight(1f))
    }
}

@Composable
private fun Tile(label: String, value: String, before: String?, modifier: Modifier, big: Boolean = false) {
    Column(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = if (big) 16.dp else 13.dp, vertical = 13.dp)
            .semantics(mergeDescendants = true) { },
    ) {
        Muted(label)
        Gap(2)
        Text(
            value,
            style = TextStyle(fontSize = if (big) 26.sp else 20.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = TABULAR),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        if (before != null) {
            Gap(1)
            Muted("$before before", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp))
        }
    }
}

/** A duration for a narrow tile: "45 min", "95 min", "6.7 h". */
internal fun shortDuration(ms: Long): String = when {
    ms in 1 until 60_000 -> "< 1 min"
    ms < 100 * 60_000L -> "${ms / 60_000} min"
    else -> "%.1f h".format(Locale.ROOT, ms / 3_600_000.0).replace(".0 h", " h")
}

/** The figure a chart's scale goes up to: 1, 2 or 5 times a power of ten, at least [max]. */
internal fun niceMax(max: Int): Int {
    if (max <= 4) return 4
    val p = 10.0.pow(floor(log10(max.toDouble())))
    val m = max / p
    val step = when {
        m <= 1 -> 1.0
        m <= 2 -> 2.0
        m <= 5 -> 5.0
        else -> 10.0
    }
    return ceil(step * p).toInt()
}

/** A block's name for a part of a chart: its own, or "Other" for what no block covers any more. */
private fun blockName(blocks: List<Block>, id: String?): String = blocks.firstOrNull { it.id == id }?.name ?: "Other"

/**
 * Blocked tries per day (per hour on Today), each block's part in its tone, today's bar marked.
 * Under it, the range's tries by block; a tapped bar gives its own.
 */
@Composable
private fun TriesChart(s: InsightsSummary, blocks: List<Block>, range: InsightRange, initialPick: Int = -1) {
    val bars = s.bars
    var picked by remember(range, bars.size) { mutableIntStateOf(initialPick) }
    val top = niceMax(bars.maxOfOrNull { it.total } ?: 0)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val baseline = MaterialTheme.colorScheme.outline
    val mark = MaterialTheme.colorScheme.onSurface
    val gapColor = MaterialTheme.colorScheme.surface
    val colors = bars.map { bar -> bar.segments.map { blockColor(blocks, it.blockId) } }
    val pick = picked.takeIf { it in bars.indices }?.let { bars[it] }
    // Today's bar: the last day, or the hour it is now.
    val nowIndex = if (range == InsightRange.TODAY) s.nowHour.coerceAtMost(bars.lastIndex) else bars.lastIndex
    Panel {
        PanelTitle("Blocked tries") {
            if (pick != null) {
                Muted(barTitle(pick, range, s.today))
                WGap(8)
            }
            Text((pick?.total ?: s.tries).toString(), style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = TABULAR))
        }
        Gap(8)
        Muted(top.toString(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp))
        Gap(4)
        val n = bars.size.coerceAtLeast(1)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .pointerInput(n) {
                    detectTapGestures { at ->
                        val i = (at.x / (size.width.toFloat() / n)).toInt().coerceIn(0, n - 1)
                        picked = if (picked == i) -1 else i
                    }
                },
        ) {
            val h = size.height - 9.dp.toPx() // room under the baseline for today's mark
            drawLine(grid, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
            drawLine(grid, Offset(0f, h / 2), Offset(size.width, h / 2), strokeWidth = 1.dp.toPx())
            val slot = size.width / n
            val barW = (slot * if (n > 12) 0.64f else 0.5f).coerceAtMost(26.dp.toPx())
            val radius = (barW / 2).coerceAtMost(4.dp.toPx())
            val gap = 1.5.dp.toPx()
            bars.forEachIndexed { i, bar ->
                val x = slot * i + (slot - barW) / 2
                var y = h
                val dim = picked >= 0 && picked != i
                bar.segments.forEachIndexed { j, seg ->
                    val segH = h * seg.count / top
                    val color = colors[i][j].let { if (dim) it.copy(alpha = it.alpha * 0.3f) else it }
                    if (j == bar.segments.lastIndex) {
                        // The top part is rounded at the top only.
                        drawRoundRect(color, Offset(x, y - segH), Size(barW, segH), CornerRadius(radius, radius))
                        if (segH > radius) drawRect(color, Offset(x, y - segH / 2), Size(barW, segH / 2))
                    } else {
                        drawRect(color, Offset(x, y - segH), Size(barW, segH))
                        // A hairline of the card between two blocks' parts.
                        if (segH > gap * 2) drawRect(gapColor, Offset(x, y - segH), Size(barW, gap))
                    }
                    y -= segH
                }
            }
            drawLine(baseline, Offset(0f, h), Offset(size.width, h), strokeWidth = 1.dp.toPx())
            if (nowIndex >= 0) {
                drawCircle(mark, radius = 2.dp.toPx(), center = Offset(slot * nowIndex + slot / 2, h + 5.5.dp.toPx()))
            }
        }
        Gap(4)
        AxisLabels(axisLabels(bars, range, s.today), n, strong = if (range == InsightRange.TODAY) null else bars.lastIndex)
        val split = pick?.segments ?: s.split
        if (split.isNotEmpty()) {
            Gap(12)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                split.forEach { seg -> SplitFigure(blockName(blocks, seg.blockId), blockColor(blocks, seg.blockId), seg.count) }
            }
        }
    }
}

/** "● YouTube 11": a block's part of a figure. */
@Composable
private fun SplitFigure(name: String, color: Color, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { }) {
        BlockDot(color)
        WGap(6)
        Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
        WGap(5)
        Text(count.toString(), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = TABULAR), color = MaterialTheme.colorScheme.onSurface)
    }
}

private val DayOfMonth = DateTimeFormatter.ofPattern("d MMM")
private val Weekday = DateTimeFormatter.ofPattern("EEE")
private val LongDay = DateTimeFormatter.ofPattern("EEE d MMM")
private val HourMinute = DateTimeFormatter.ofPattern("HH:mm")

private fun barTitle(bar: Bar, range: InsightRange, today: LocalDate): String =
    if (range == InsightRange.TODAY) "%02d:00".format(bar.hour ?: 0) else dayTitle(bar.date, today)

private fun axisLabels(bars: List<Bar>, range: InsightRange, today: LocalDate): List<Pair<Int, String>> = when (range) {
    InsightRange.TODAY -> listOf(0, 6, 12, 18, 23).map { it to "%02d".format(it) }
    InsightRange.WEEK -> bars.mapIndexed { i, b -> i to if (b.date == today) "Today" else b.date.format(Weekday) }
    InsightRange.MONTH -> bars.indices.filter { (bars.lastIndex - it) % 7 == 0 }.map { it to if (bars[it].date == today) "Today" else bars[it].date.format(DayOfMonth) }
}

/** Labels under [n] columns, each centred on its column as far as the edges allow; [strong] in the text colour. */
@Composable
private fun AxisLabels(labels: List<Pair<Int, String>>, n: Int, strong: Int? = null) {
    Box(Modifier.fillMaxWidth().height(18.dp)) {
        labels.forEach { (i, text) ->
            val bias = if (n <= 1) 0f else -1f + 2f * (i + 0.5f) / n
            Text(
                text,
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = TABULAR, letterSpacing = 0.sp, fontWeight = if (i == strong) FontWeight.Bold else FontWeight.Medium),
                color = if (i == strong) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.align(BiasAlignment(bias, 0f)),
            )
        }
    }
}

/** When: blocked tries by weekday and hour on the one orange ramp, the busiest hour at the right. */
@Composable
private fun Heatmap(s: InsightsSummary) {
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val max = s.heat.maxOf { row -> row.maxOrNull() ?: 0 }
    val peak = if (max == 0) null else s.heat.withIndex().firstNotNullOf { (d, row) -> row.indexOfFirst { it == max }.takeIf { it >= 0 }?.let { d to it } }
    Panel {
        PanelTitle("When") {
            if (peak != null) {
                Muted(
                    DayOfWeek.of(peak.first + 1).getDisplayName(NameStyle.SHORT, Locale.getDefault()) + " %02d:00 · %d".format(peak.second, max),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Gap(14)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val labelW = 20.dp
            val gap = 2.dp
            val gridW = maxWidth - labelW
            val cell = (gridW - gap * 23) / 24
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                s.heat.forEachIndexed { d, row ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            DayOfWeek.of(d + 1).getDisplayName(NameStyle.NARROW, Locale.getDefault()),
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(labelW),
                        )
                        Canvas(Modifier.width(gridW).height(cell)) {
                            val c = cell.toPx()
                            val g = gap.toPx()
                            row.forEachIndexed { h, count ->
                                val t = if (max == 0 || count == 0) 0f else 0.25f + 0.75f * sqrt(count.toFloat() / max)
                                drawRoundRect(
                                    if (t == 0f) empty else lerp(empty, DataColor, t),
                                    Offset(h * (c + g), 0f),
                                    Size(c, c),
                                    CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                                )
                            }
                        }
                    }
                }
                Gap(2)
                Row {
                    Box(Modifier.width(labelW))
                    Box(Modifier.width(gridW)) { AxisLabels(listOf(0, 6, 12, 18, 23).map { it to "%02d".format(it) }, 24) }
                }
            }
        }
    }
}

/** What a tried app or site is called on the page. */
private fun targetName(target: String, isSite: Boolean, label: String?, ui: InsightsUi): String =
    if (isSite) target else label ?: ui.appLabel(target)

/** The apps and sites tried most, on every device shown: each block's dot, a bar in its tone, where. */
@Composable
private fun MostTried(s: InsightsSummary, ui: InsightsUi, device: DeviceFilter) {
    var all by rememberSaveable { mutableStateOf(false) }
    val rows = if (all) s.targets else s.targets.take(TRIED_COLLAPSED)
    val max = s.targets.maxOfOrNull { it.tries }?.coerceAtLeast(1) ?: 1
    Panel {
        PanelTitle("Most tried")
        if (rows.isEmpty()) {
            Gap(6)
            Muted("None", style = MaterialTheme.typography.bodyMedium)
            return@Panel
        }
        Gap(6)
        rows.forEach { t ->
            val color = blockColor(ui.blocks, t.blockId)
            val fraction by animateFloatAsState(t.tries.toFloat() / max, label = "tried")
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 5.dp).semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically) {
                BlockDot(color)
                WGap(10)
                Column(Modifier.width(118.dp)) {
                    Text(targetName(t.target, t.isSite, t.label, ui), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (device == DeviceFilter.ALL) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (t.phone > 0) DeviceCount(Icons.Outlined.PhoneAndroid, t.phone, "on the phone")
                            if (t.phone > 0 && t.laptop > 0) WGap(8)
                            if (t.laptop > 0) DeviceCount(Icons.Outlined.Laptop, t.laptop, "on the laptop")
                        }
                    }
                }
                WGap(10)
                Box(Modifier.weight(1f).height(6.dp).clip(CircleShape)) {
                    Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(6.dp).clip(CircleShape).background(color))
                }
                WGap(10)
                Text(
                    t.tries.toString(),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = TABULAR),
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(min = 26.dp),
                )
            }
        }
        if (s.targets.size > TRIED_COLLAPSED) {
            MoreButton(if (all) "Fewer" else "All ${s.targets.size}") { all = !all }
        }
    }
}

/** A device's glyph and a small grey figure: tries on the phone, on the laptop. */
@Composable
private fun DeviceCount(icon: ImageVector, n: Int, where: String) {
    Icon(icon, contentDescription = where, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
    WGap(2)
    Muted(n.toString(), style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp))
}

/** A device's glyph and a small grey time: on the phone, on the laptop. */
@Composable
private fun DeviceTime(icon: ImageVector, ms: Long, where: String) {
    Icon(icon, contentDescription = where, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
    WGap(2)
    Muted(shortDuration(ms), style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp))
}

/**
 * Screen time: the range's, each device's under it beside its glyph, and (not on Today) a column a
 * day, the phone's part below the laptop's. Figures only.
 */
@Composable
private fun ScreenCard(st: ScreenTimeSummary, range: InsightRange, device: DeviceFilter, today: LocalDate) {
    val phoneColor = DataColor
    val laptopColor = DataColor.copy(alpha = 0.45f)
    val empty = MaterialTheme.colorScheme.surfaceVariant
    Panel {
        PanelTitle("Screen time") { Text(formatDuration(st.totalMs), style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = TABULAR)) }
        if (device == DeviceFilter.ALL) {
            Gap(6)
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScreenFigure(Icons.Outlined.PhoneAndroid, st.phoneMs, phoneColor, "on the phone")
                WGap(18)
                ScreenFigure(Icons.Outlined.Laptop, st.laptopMs, laptopColor, "on the laptop")
            }
        }
        if (range != InsightRange.TODAY && st.days.isNotEmpty()) {
            Gap(14)
            val most = st.days.maxOf { it.totalMs }.coerceAtLeast(1)
            val n = st.days.size
            Canvas(Modifier.fillMaxWidth().height(72.dp).semantics { contentDescription = "Screen time a day" }) {
                val slot = size.width / n
                val barW = (slot * if (n > 12) 0.64f else 0.5f).coerceAtMost(26.dp.toPx())
                val radius = (barW / 2).coerceAtMost(3.dp.toPx())
                st.days.forEachIndexed { i, d ->
                    val x = slot * i + (slot - barW) / 2
                    val h = size.height * d.totalMs / most
                    if (d.totalMs == 0L) {
                        drawRoundRect(empty, Offset(x, size.height - 2.dp.toPx()), Size(barW, 2.dp.toPx()), CornerRadius(radius, radius))
                        return@forEachIndexed
                    }
                    val phoneH = h * d.phoneMs / d.totalMs
                    drawRoundRect(laptopColor, Offset(x, size.height - h), Size(barW, h), CornerRadius(radius, radius))
                    if (phoneH > 0f) drawRect(phoneColor, Offset(x, size.height - phoneH), Size(barW, phoneH))
                }
            }
            Gap(4)
            AxisLabels(axisLabels(st.days.map { Bar(it.date, null, emptyList()) }, range, today), n, strong = n - 1)
        }
    }
}

/** A device's glyph, its colour's dot and its time. */
@Composable
private fun ScreenFigure(icon: ImageVector, ms: Long, color: Color, where: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { }) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        WGap(6)
        Icon(icon, contentDescription = where, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
        WGap(4)
        Text(formatDuration(ms), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = TABULAR))
    }
}

/** A row per block: its tries and their trend, its time open and bought, its clean days, and a limit's use a day. */
@Composable
private fun ByBlock(s: InsightsSummary, ui: InsightsUi, range: InsightRange, picked: Block?, onBlock: (String?) -> Unit) {
    val rows = if (picked == null) ui.blocks else listOf(picked)
    if (rows.isEmpty()) return
    Panel(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 6.dp)) {
        PanelTitle("By block")
        Gap(4)
        rows.forEachIndexed { i, b ->
            val summary = s.blocks.firstOrNull { it.blockId == b.id } ?: return@forEachIndexed
            if (i > 0) Hairline()
            BlockRow(b, summary, ui.limits[b.id], blockColor(ui.blocks, b.id), range, s.nowHour, onClick = { onBlock(if (picked == null) b.id else null) })
        }
    }
}

@Composable
private fun BlockRow(block: Block, s: BlockSummary, limit: LimitUse?, color: Color, range: InsightRange, nowHour: Int, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BlockDot(color)
            WGap(9)
            Text(
                block.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            WGap(12)
            Sparkline(if (range == InsightRange.TODAY) s.trend.take(nowHour + 1) else s.trend, color, Modifier.width(76.dp).height(20.dp).semantics { contentDescription = "Tries ${if (range == InsightRange.TODAY) "an hour" else "a day"}" })
        }
        Gap(8)
        Row(Modifier.padding(start = 17.dp)) {
            BlockFigure(s.tries.toString(), if (s.tries == 1) "try" else "tries", zero = s.tries == 0)
            BlockFigure(formatDuration(s.unlockedMs), if (s.unlocks == 1) "1 unlock" else "${s.unlocks} unlocks", zero = s.unlocks == 0, wide = true)
            BlockFigure(shortDuration(s.boughtMs), "bought", zero = s.boughtMs == 0L)
            BlockFigure(s.cleanDays.toString(), if (s.cleanDays == 1) "clean day" else "clean days", zero = s.cleanDays == 0)
        }
        if (limit != null) {
            Gap(10)
            LimitLine(limit, range, Modifier.padding(start = 17.dp))
        }
    }
}

@Composable
private fun RowScope.BlockFigure(value: String, label: String, zero: Boolean, wide: Boolean = false) {
    Column(Modifier.weight(if (wide) 1.25f else 1f).semantics(mergeDescendants = true) { }) {
        Text(
            value,
            style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = TABULAR),
            color = if (zero) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Muted(label)
    }
}

/** A limit's use a day (today's on Today) against the limit: a meter in the data colour, amber near it, red past it. */
@Composable
private fun LimitLine(limit: LimitUse, range: InsightRange, modifier: Modifier = Modifier) {
    val t = tones()
    val color = when (MeterTone.of(limit.fraction)) {
        MeterTone.DATA -> DataColor
        MeterTone.NEAR -> t.amber
        MeterTone.OVER -> t.used
    }
    val fraction by animateFloatAsState(limit.fraction.coerceIn(0f, 1f), label = "limit")
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Muted(if (range == InsightRange.TODAY || limit.todayOnly) "Today" else "A day")
        WGap(8)
        Text(
            "${if (limit.days == 0) "–" else formatDuration(limit.perDayMs)} / ${formatDuration(limit.dailyMs)}",
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = TABULAR),
            maxLines = 1,
        )
        WGap(12)
        Box(Modifier.weight(1f).height(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction).height(5.dp).clip(CircleShape).background(color))
        }
    }
}

/** Tries a day (an hour on Today) as a thin line in the block's tone, the last point a dot. */
@Composable
private fun Sparkline(values: List<Int>, color: Color, modifier: Modifier) {
    val faint = MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val max = values.max()
        val stroke = 1.6.dp.toPx()
        val r = 2.2.dp.toPx()
        val top = r
        val bottom = size.height - r
        fun x(i: Int) = r + (size.width - 2 * r) * i / (values.size - 1)
        fun y(v: Int) = if (max == 0) bottom else bottom - (bottom - top) * v / max
        val path = Path()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
        val ink = if (max == 0) faint else color
        drawPath(path, ink, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(ink, radius = r, center = Offset(x(values.lastIndex), y(values.last())))
    }
}

/** The apps or the sites: time on the phone and tries, most first, a bar in the block's tone (grey for none). */
@Composable
private fun TopCard(title: String, rows: List<TopTarget>, ui: InsightsUi, showSetup: Boolean, setupLabel: String, onSetup: () -> Unit) {
    var all by rememberSaveable(title) { mutableStateOf(false) }
    val shown = if (all) rows else rows.take(TOP_COLLAPSED)
    val maxMs = rows.maxOfOrNull { it.ms } ?: 0L
    val maxTries = rows.maxOfOrNull { it.tries } ?: 0
    Panel {
        PanelTitle(title) {
            val total = rows.sumOf { it.ms }
            if (total > 0) Muted(formatDuration(total), style = MaterialTheme.typography.bodyMedium)
        }
        if (showSetup) {
            Gap(12)
            PillButton(setupLabel, onClick = onSetup)
        }
        if (rows.isEmpty()) {
            if (!showSetup) {
                Gap(6)
                Muted("None", style = MaterialTheme.typography.bodyMedium)
            }
            return@Panel
        }
        Gap(4)
        shown.forEach { row ->
            TopRow(row, ui, if (maxMs > 0) row.ms.toFloat() / maxMs else row.tries.toFloat() / maxTries.coerceAtLeast(1))
        }
        if (rows.size > TOP_COLLAPSED) {
            MoreButton(if (all) "Fewer" else "All ${rows.size}") { all = !all }
        }
    }
}

@Composable
private fun TopRow(row: TopTarget, ui: InsightsUi, fraction: Float) {
    val color = blockColor(ui.blocks, row.blockId)
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "top")
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TargetIcon(row, 34.dp)
        WGap(12)
        Column(Modifier.weight(1f)) {
            Text(targetName(row.target, row.isSite, row.label, ui), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (row.phoneMs >= 60_000 && row.laptopMs >= 60_000) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DeviceTime(Icons.Outlined.PhoneAndroid, row.phoneMs, "on the phone")
                    WGap(8)
                    DeviceTime(Icons.Outlined.Laptop, row.laptopMs, "on the laptop")
                }
            }
            Gap(5)
            Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                if (animated > 0f) Box(Modifier.fillMaxWidth(animated).height(5.dp).clip(CircleShape).background(color))
            }
        }
        WGap(12)
        Column(horizontalAlignment = Alignment.End) {
            if (row.ms > 0) Text(formatDuration(row.ms), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TABULAR))
            if (row.tries > 0) Muted(if (row.tries == 1) "1 try" else "${row.tries} tries")
        }
    }
}

/**
 * An app's own icon (the phone's launcher icon) or a site's; for what the phone has no icon of, a
 * plain grey square with what it is: a laptop for a laptop app, a globe for a site, a phone for an
 * app no longer installed.
 */
@Composable
private fun TargetIcon(row: TopTarget, size: Dp) {
    val fallback: @Composable () -> Unit = {
        Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.26f)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(
                when {
                    row.isSite -> Icons.Outlined.Language
                    row.laptopOnly -> Icons.Outlined.Laptop
                    else -> Icons.Outlined.PhoneAndroid
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
    when {
        row.isSite -> SiteIcon(row.target, size = size, fallback = fallback)
        row.laptopOnly -> fallback()
        else -> AppIcon(row.target, size = size, fallback = fallback)
    }
}

/**
 * Day by day, newest first: Today, Yesterday, then dates. Each day's head gives its tries, unlocks
 * and time open over a strip of its tries by block; the newest day is open, the others open on a
 * tap. A block's name shows only that block.
 */
@Composable
private fun HistoryCard(s: InsightsSummary, ui: InsightsUi, onBlock: (String?) -> Unit) {
    var days by remember(s.range) { mutableIntStateOf(HISTORY_DAYS) }
    val opened = remember(s.range) { mutableStateMapOf<LocalDate, Boolean>() }
    val total = s.history.sumOf { it.events }
    val most = s.history.maxOfOrNull { it.tries }?.coerceAtLeast(1) ?: 1
    Panel(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 10.dp)) {
        Box(Modifier.padding(horizontal = 2.dp)) {
            PanelTitle("History") { Muted(total.toString(), style = MaterialTheme.typography.bodyMedium) }
        }
        if (total == 0) {
            Gap(6)
            Muted("None", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 2.dp))
            Gap(8)
            return@Panel
        }
        Gap(4)
        s.history.take(days).forEachIndexed { i, day ->
            val open = opened[day.date] ?: (i == 0)
            DayHead(day, s.today, day.tries.toFloat() / most, ui.blocks, open, onToggle = { opened[day.date] = !open })
            if (open) {
                day.rows.forEach { HistoryLine(it, ui, onBlock) }
                Gap(6)
            }
        }
        if (s.history.size > days) {
            MoreButton("More") { days += HISTORY_DAYS }
        }
    }
}

@Composable
private fun DayHead(day: HistoryDay, today: LocalDate, share: Float, blocks: List<Block>, open: Boolean, onToggle: () -> Unit) {
    val figures = listOfNotNull(
        day.tries.takeIf { it > 0 }?.let { if (it == 1) "1 try" else "$it tries" },
        day.unlocks.takeIf { it > 0 }?.let { if (it == 1) "1 unlock" else "$it unlocks" },
        day.unlockedMs.takeIf { it >= 60_000 }?.let { "${formatDuration(it)} open" },
    ).joinToString(" · ")
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 2.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (open) Icons.Outlined.KeyboardArrowDown else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = if (open) "Close" else "Open",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            WGap(6)
            Text(dayTitle(day.date, today), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
            Muted(figures)
        }
        if (day.tries > 0) {
            Gap(7)
            DayStrip(day.split, blocks, share, Modifier.padding(start = 26.dp))
        }
    }
}

/** A day's tries as a thin strip, each block its share, as long as the day is busy against the busiest. */
@Composable
private fun DayStrip(split: List<Segment>, blocks: List<Block>, share: Float, modifier: Modifier = Modifier) {
    val colors = split.map { blockColor(blocks, it.blockId) }
    val gapColor = MaterialTheme.colorScheme.surface
    val total = split.sumOf { it.count }.coerceAtLeast(1)
    Canvas(modifier.fillMaxWidth().height(4.dp).clip(CircleShape)) {
        val w = size.width * share.coerceIn(0.03f, 1f)
        var x = 0f
        val gap = 1.5.dp.toPx()
        split.forEachIndexed { i, seg ->
            val segW = w * seg.count / total
            drawRect(colors[i], Offset(x, 0f), Size(segW, size.height))
            if (i > 0) drawRect(gapColor, Offset(x, 0f), Size(gap, size.height))
            x += segW
        }
    }
}

@Composable
private fun HistoryLine(row: HistoryRow, ui: InsightsUi, onBlock: (String?) -> Unit) {
    val e = row.event
    val (icon, tint) = kindLook(e.kind)
    val what = when (e.kind) {
        EventKind.BLOCKED -> if (e.isSite) Sites.hostOf(e.target.orEmpty()).removePrefix("www.") else e.label?.takeIf { it != e.target } ?: ui.appLabel(e.target.orEmpty())
        EventKind.HABIT -> "Habit done"
        EventKind.UNLOCK -> "Unlocked ${formatDuration(e.ms)}"
        EventKind.BOUGHT -> "Bought ${formatDuration(e.ms)}"
        EventKind.BREAK -> "Break ${formatDuration(e.ms)}"
        EventKind.EMERGENCY -> "Emergency unlock"
    }
    val block = ui.blocks.firstOrNull { it.id == e.blockId }
    Row(Modifier.fillMaxWidth().heightIn(min = 38.dp).padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Muted(Instant.ofEpochMilli(e.atWallMs).atZone(ui.zone).format(HourMinute), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(46.dp))
        Icon(
            when (e.device) {
                Device.PHONE -> Icons.Outlined.PhoneAndroid
                Device.LAPTOP -> Icons.Outlined.Laptop
                null -> Icons.Outlined.Devices
            },
            contentDescription = e.device?.name?.lowercase() ?: "every device",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
        WGap(10)
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        WGap(8)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(what, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (row.n > 1) {
                WGap(5)
                Muted("×${row.n}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (block != null) {
            WGap(8)
            Row(
                Modifier.widthIn(max = 116.dp).clip(CircleShape).clickable { onBlock(block.id) }.padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BlockDot(blockColor(ui.blocks, block.id), size = 7.dp)
                WGap(5)
                Muted(block.name)
            }
        }
    }
}

/** An event kind's icon and its muted colour. */
@Composable
private fun kindLook(kind: EventKind): Pair<ImageVector, Color> {
    val t = tones()
    return when (kind) {
        EventKind.BLOCKED -> Icons.Outlined.Block to t.red
        EventKind.HABIT -> Icons.Outlined.TaskAlt to t.green
        EventKind.UNLOCK -> Icons.Outlined.LockOpen to t.green
        EventKind.BOUGHT -> Icons.Outlined.MoreTime to t.amber
        EventKind.BREAK -> Icons.Outlined.FreeBreakfast to t.blue
        EventKind.EMERGENCY -> Icons.Outlined.LockClock to t.amber
    }
}

/** "Today", "Yesterday", "Mon 16 Nov". */
private fun dayTitle(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> date.format(LongDay)
}
