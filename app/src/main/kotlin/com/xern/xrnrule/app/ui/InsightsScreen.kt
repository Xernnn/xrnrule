package com.xern.xrnrule.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.FreeBreakfast
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.LockClock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreTime
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Web
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
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
import com.xern.xrnrule.app.data.WorldStore
import com.xern.xrnrule.app.data.isGranted
import com.xern.xrnrule.app.data.settingsIntent
import com.xern.xrnrule.app.ui.theme.LocalKinds
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
private const val HISTORY_PAGE = 60

/** Today's use of a block's daily limit, here and on the laptop (I19, §2.4). */
data class BlockToday(val phoneMs: Long, val laptopMs: Long, val allowanceMs: Long)

/** Whether the laptop's log could be read. */
enum class LaptopLog { OFF, OK, UNREACHABLE }

/** Everything the Insights page draws, worked out by [InsightsScreen] (or made up, in previews). */
data class InsightsUi(
    val summary: InsightsSummary,
    val blocks: List<Block>,
    val apps: List<TopTarget>,
    val sites: List<TopTarget>,
    val today: Map<String, BlockToday>,
    val usageAccess: Boolean,
    val sitesOn: Boolean,
    val laptop: LaptopLog,
    val zone: ZoneId,
    /** An app's name, by package (or laptop app id). */
    val appLabel: (String) -> String = { it },
)

/**
 * Insights: what both devices saw, from this phone's log and the laptop's (through the sync
 * server), for today, a week or a month. Figures, a colour per block, a timeline.
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
    // The laptop's log, from the server: once a visit, a month and a day of it.
    var elsewhere by remember { mutableStateOf<List<TelemetryEvent>?>(emptyList()) }
    LaunchedEffect(resume) {
        elsewhere = withContext(Dispatchers.IO) { shared.eventsElsewhere(clock.nowWallMs() - (InsightRange.MONTH.days + 1) * DAY_MS) }
    }

    val labels = remember { HashMap<String, String>() }
    val appLabel: (String) -> String = { pkg -> labels.getOrPut(pkg) { InstalledApps.labelFor(context, pkg) } }
    val merged = remember(local, elsewhere, world.emergencyLog, world.blocks) {
        InsightsModel.merge(local, elsewhere.orEmpty(), world.emergencyLog, world.blocks)
    }
    val now = clock.nowWallMs()
    val summary = remember(merged, device, blockId, range, world.blocks, minute) {
        InsightsModel.summarize(InsightsModel.select(merged, device, blockId), world.blocks, range, now, zone)
    }
    // Time per app and site is the phone's own (the laptop keeps none).
    val phoneShown = device != DeviceFilter.LAPTOP
    val (apps, sites) = remember(summary, usage, siteDays, phoneShown) {
        val appTime = HashMap<String, Pair<String, Long>>()
        val siteTime = HashMap<String, Long>()
        if (phoneShown) {
            val first = InsightsModel.today(now, zone).minusDays(range.days - 1L)
            usage.filter { !it.date.isBefore(first) }.flatMap { it.apps }.forEach { a ->
                appTime[a.packageName] = a.label to ((appTime[a.packageName]?.second ?: 0L) + a.foregroundMs)
            }
            InsightsModel.epochDays(range, now, zone).forEach { d -> siteDays[d]?.forEach { (host, ms) -> siteTime.merge(host, ms, Long::plus) } }
        }
        InsightsModel.topTargets(appTime, siteTime, summary.targets, world.blocks, blockId)
    }
    val dayStart = clock.startOfLocalDayMs()
    val today = world.blocks.filter { it.dailyAllowanceMs > 0 && (it.mode == BlockMode.ALLOWANCE || it.mode == BlockMode.HABIT) }.associate { b ->
        val total = store.engine.allowanceUsedMs(world, b, clock)
        val laptop = if (b.perDevice) 0L else world.otherAllowance[b.id].orEmpty().values.sumOf { if (it.dayStartWall == dayStart) it.ms else 0L }
        b.id to BlockToday((total - laptop).coerceAtLeast(0), laptop, b.dailyAllowanceMs)
    }

    InsightsContent(
        InsightsUi(
            summary = summary,
            blocks = world.blocks,
            apps = apps,
            sites = sites,
            today = today,
            usageAccess = hasAccess,
            sitesOn = websites.isSupported() && tracking != WebsiteTracking.OFF,
            laptop = when {
                !shared.settings.isConfigured -> LaptopLog.OFF
                elsewhere == null -> LaptopLog.UNREACHABLE
                else -> LaptopLog.OK
            },
            zone = zone,
            appLabel = appLabel,
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
 * The page itself, from [ui]: the range and device switches, the five figures, tries per day,
 * tries by hour, a card per block, the top apps and sites, and the history. Picking a block (its
 * card or a chip) narrows all of it to that block.
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
            Headline(s)
            TriesChart(s, ui.blocks, range)
            Heatmap(s)
            if (picked == null) {
                ui.blocks.forEach { b ->
                    val summary = s.blocks.firstOrNull { it.blockId == b.id } ?: return@forEach
                    BlockInsightCard(b, summary, ui.today[b.id], blockColor(ui.blocks, b.id), device, onClick = { onBlock(b.id) })
                }
            } else {
                s.blocks.firstOrNull { it.blockId == picked.id }?.let {
                    BlockInsightCard(picked, it, ui.today[picked.id], blockColor(ui.blocks, picked.id), device, onClick = { onBlock(null) })
                }
            }
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

/** The five figures, each in its colour. */
@Composable
private fun Headline(s: InsightsSummary) {
    val kinds = LocalKinds.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FigureTile(Icons.Outlined.Block, MaterialTheme.colorScheme.error, s.tries.toString(), "Blocked tries", Modifier.weight(1f), big = true)
        FigureTile(Icons.Outlined.LockOpen, kinds.appTime.color, formatDuration(s.unlockedMs), "Unlocked", Modifier.weight(1f), big = true, extra = s.unlocks.takeIf { it > 0 }?.let { "$it×" })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FigureTile(Icons.Outlined.MoreTime, kinds.limit.color, shortDuration(s.boughtMs), "Bought", Modifier.weight(1f), extra = s.bought.takeIf { it > 0 }?.let { "$it×" })
        FigureTile(Icons.Outlined.FreeBreakfast, kinds.blue.color, s.breaks.toString(), "Breaks", Modifier.weight(1f))
        FigureTile(Icons.Outlined.TaskAlt, kinds.habit.color, s.habits.toString(), "Habits", Modifier.weight(1f))
    }
}

@Composable
private fun FigureTile(icon: ImageVector, color: Color, value: String, label: String, modifier: Modifier, big: Boolean = false, extra: String? = null) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { },
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        }
        Gap(8)
        Text(
            value,
            style = TextStyle(fontSize = if (big) 26.sp else 19.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = TABULAR),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            if (extra != null) "$label · $extra" else label,
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TABULAR),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
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

/** Blocked tries per day (per hour on Today), each block's part in its colour. A tapped bar gives its figure. */
@Composable
private fun TriesChart(s: InsightsSummary, blocks: List<Block>, range: InsightRange) {
    val bars = s.bars
    var picked by remember(range, bars.size) { mutableIntStateOf(-1) }
    val top = niceMax(bars.maxOfOrNull { it.total } ?: 0)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val baseline = MaterialTheme.colorScheme.outline
    val colors = bars.map { bar -> bar.segments.map { blockColor(blocks, it.blockId) } }
    val pick = picked.takeIf { it in bars.indices }?.let { bars[it] }
    HFCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Blocked tries", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                pick?.let { barTitle(it, range, s.today) } ?: range.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            WGap(8)
            Text((pick?.total ?: s.tries).toString(), style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = TABULAR))
        }
        Gap(10)
        Text(top.toString(), style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = TABULAR, letterSpacing = 0.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Gap(4)
        val n = bars.size.coerceAtLeast(1)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(132.dp)
                .pointerInput(n) {
                    detectTapGestures { at ->
                        val i = (at.x / (size.width.toFloat() / n)).toInt().coerceIn(0, n - 1)
                        picked = if (picked == i) -1 else i
                    }
                },
        ) {
            val h = size.height
            drawLine(grid, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
            drawLine(grid, Offset(0f, h / 2), Offset(size.width, h / 2), strokeWidth = 1.dp.toPx())
            val slot = size.width / n
            val barW = (slot * if (n > 12) 0.64f else 0.5f).coerceAtMost(26.dp.toPx())
            val radius = (barW / 2).coerceAtMost(5.dp.toPx())
            bars.forEachIndexed { i, bar ->
                val x = slot * i + (slot - barW) / 2
                var y = h
                val dim = picked >= 0 && picked != i
                bar.segments.forEachIndexed { j, seg ->
                    val segH = h * seg.count / top
                    val color = colors[i][j].let { if (dim) it.copy(alpha = it.alpha * 0.35f) else it }
                    if (j == bar.segments.lastIndex) {
                        // The top part is rounded at the top only.
                        drawRoundRect(color, Offset(x, y - segH), Size(barW, segH), CornerRadius(radius, radius))
                        if (segH > radius) drawRect(color, Offset(x, y - segH / 2), Size(barW, segH / 2))
                    } else {
                        drawRect(color, Offset(x, y - segH), Size(barW, segH))
                    }
                    y -= segH
                }
            }
            drawLine(baseline, Offset(0f, h), Offset(size.width, h), strokeWidth = 1.dp.toPx())
        }
        Gap(6)
        AxisLabels(axisLabels(bars, range, s.today), n)
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
    InsightRange.MONTH -> bars.indices.filter { (bars.lastIndex - it) % 7 == 0 }.map { it to bars[it].date.format(DayOfMonth) }
}

/** Labels under [n] columns, each centred on its column as far as the edges allow. */
@Composable
private fun AxisLabels(labels: List<Pair<Int, String>>, n: Int) {
    Box(Modifier.fillMaxWidth().height(18.dp)) {
        labels.forEach { (i, text) ->
            val bias = if (n <= 1) 0f else -1f + 2f * (i + 0.5f) / n
            Text(
                text,
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = TABULAR, letterSpacing = 0.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.align(BiasAlignment(bias, 0f)),
            )
        }
    }
}

/** Blocked tries by weekday and hour, and the busiest hour as a figure. */
@Composable
private fun Heatmap(s: InsightsSummary) {
    val red = MaterialTheme.colorScheme.error
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val max = s.heat.maxOf { row -> row.maxOrNull() ?: 0 }
    val peak = if (max == 0) null else s.heat.withIndex().firstNotNullOf { (d, row) -> row.indexOfFirst { it == max }.takeIf { it >= 0 }?.let { d to it } }
    HFCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("By hour", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (peak != null) {
                Text(
                    DayOfWeek.of(peak.first + 1).getDisplayName(NameStyle.SHORT, Locale.getDefault()) + " %02d:00".format(peak.second),
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TABULAR),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                WGap(8)
                Text(max.toString(), style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = TABULAR))
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
                                    if (t == 0f) empty else lerp(empty, red, t),
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

/** One block in its colour: tried, opened, bought; today's limit; its clean days in a row. */
@Composable
private fun BlockInsightCard(block: Block, s: BlockSummary, today: BlockToday?, color: Color, device: DeviceFilter, onClick: () -> Unit) {
    val surface = MaterialTheme.colorScheme.surface
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(lerp(surface, color, 0.07f))
            .border(1.dp, lerp(surface, color, 0.45f), CardShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { BlockChip(block.name, color, onClick = null) }
            WGap(8)
            Icon(Icons.Outlined.LocalFireDepartment, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            WGap(3)
            Text(
                "${s.cleanDays} d",
                style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = TABULAR),
                modifier = Modifier.semantics { contentDescription = "${s.cleanDays} clean days" },
            )
        }
        Gap(12)
        Row {
            BlockFigure(s.tries.toString(), "Tries")
            if (block.mode == BlockMode.HABIT) {
                BlockFigure(s.habits.toString(), "Habits")
                BlockFigure(formatDuration(s.unlockedMs), if (s.unlocks == 1) "1 unlock" else "${s.unlocks} unlocks", wide = true)
            } else {
                BlockFigure(s.bought.toString(), "Bought")
                BlockFigure(formatDuration(s.boughtMs), "Extra", wide = true)
            }
        }
        if (today != null) {
            val phone = if (device == DeviceFilter.LAPTOP) 0L else today.phoneMs
            val laptop = if (device == DeviceFilter.PHONE) 0L else today.laptopMs
            Gap(12)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Today", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(
                    "${formatDuration(phone + laptop).removeSuffix(" min")} / ${formatDuration(today.allowanceMs)}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TABULAR, fontWeight = FontWeight.SemiBold),
                )
            }
            Gap(6)
            SplitMeter(phone, laptop, today.allowanceMs, color)
            if (phone > 0 && laptop > 0) {
                Gap(6)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DeviceFigure(Icons.Outlined.PhoneAndroid, formatDuration(phone), color)
                    WGap(14)
                    DeviceFigure(Icons.Outlined.Laptop, formatDuration(laptop), color.copy(alpha = 0.55f))
                }
            }
        }
    }
}

@Composable
private fun RowScope.BlockFigure(value: String, label: String, wide: Boolean = false) {
    Column(Modifier.weight(if (wide) 1.3f else 1f).semantics(mergeDescendants = true) { }) {
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = TABULAR), maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun DeviceFigure(icon: ImageVector, value: String, color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
    WGap(5)
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
    WGap(3)
    Text(value, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TABULAR), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A limit's use: the phone's part in the block's colour, the laptop's in a lighter one. */
@Composable
private fun SplitMeter(phoneMs: Long, laptopMs: Long, allowanceMs: Long, color: Color) {
    val total = allowanceMs.coerceAtLeast(1).toFloat()
    val a by animateFloatAsState((phoneMs / total).coerceIn(0f, 1f), label = "phone")
    val b by animateFloatAsState((laptopMs / total).coerceIn(0f, 1f), label = "laptop")
    Canvas(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape)) {
        drawRect(color.copy(alpha = 0.16f))
        drawRect(color, Offset.Zero, Size(size.width * a, size.height))
        drawRect(color.copy(alpha = 0.55f), Offset(size.width * a, 0f), Size(size.width * b.coerceAtMost(1f - a), size.height))
    }
}

/** The apps or the sites: time on the phone and tries, most first. */
@Composable
private fun TopCard(title: String, rows: List<TopTarget>, ui: InsightsUi, showSetup: Boolean, setupLabel: String, onSetup: () -> Unit) {
    var all by rememberSaveable(title) { mutableStateOf(false) }
    val shown = if (all) rows else rows.take(TOP_COLLAPSED)
    val maxMs = rows.maxOfOrNull { it.ms } ?: 0L
    val maxTries = rows.maxOfOrNull { it.tries } ?: 0
    HFCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            val total = rows.sumOf { it.ms }
            if (total > 0) {
                Text(formatDuration(total), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TABULAR), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (showSetup) {
            Gap(12)
            PillButton(setupLabel, onClick = onSetup)
        }
        if (rows.isEmpty()) {
            if (!showSetup) {
                Gap(6)
                Text("None", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@HFCard
        }
        Gap(4)
        shown.forEach { row ->
            TopRow(row, ui, if (maxMs > 0) row.ms.toFloat() / maxMs else row.tries.toFloat() / maxTries.coerceAtLeast(1))
        }
        if (rows.size > TOP_COLLAPSED) {
            TextButton(onClick = { all = !all }) { Text(if (all) "Fewer" else "All ${rows.size}") }
        }
    }
}

@Composable
private fun TopRow(row: TopTarget, ui: InsightsUi, fraction: Float) {
    // An app or a site in a block takes the block's colour; one in none, the grey.
    val color = blockColor(ui.blocks, row.blockId)
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "top")
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp).semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TargetIcon(row.target, row.isSite, 34.dp)
        WGap(12)
        Column(Modifier.weight(1f)) {
            Text(
                if (row.isSite) row.target else row.label ?: ui.appLabel(row.target),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Gap(5)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(color.copy(alpha = 0.16f))) {
                Box(Modifier.fillMaxWidth(animated).height(6.dp).clip(CircleShape).background(color))
            }
        }
        WGap(12)
        Column(horizontalAlignment = Alignment.End) {
            if (row.ms > 0) Text(formatDuration(row.ms), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TABULAR))
            if (row.tries > 0) TriesBadge(row.tries)
        }
    }
}

/** "12×" on the alert red: the times the block screen met it. */
@Composable
private fun TriesBadge(n: Int) {
    val red = MaterialTheme.colorScheme.error
    Row(
        Modifier.padding(top = 2.dp).clip(CircleShape).background(red.copy(alpha = 0.16f)).padding(horizontal = 7.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Block, contentDescription = null, tint = red, modifier = Modifier.size(11.dp))
        WGap(3)
        Text("$n×", style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TABULAR), color = red)
    }
}

/** An app's own icon or a site's; a plain one for what the phone has no icon of (a laptop app). */
@Composable
private fun TargetIcon(target: String, isSite: Boolean, size: Dp) {
    val fallback: @Composable () -> Unit = {
        Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.26f)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(if (isSite) Icons.Outlined.Web else Icons.Outlined.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size * 0.55f))
        }
    }
    if (isSite) SiteIcon(target, size = size, fallback = fallback) else AppIcon(target, size = size, fallback = fallback)
}

/** Day by day, newest first: when, where, which block, what happened. A block's chip shows only that block. */
@Composable
private fun HistoryCard(s: InsightsSummary, ui: InsightsUi, onBlock: (String?) -> Unit) {
    var limit by remember(s.range) { mutableIntStateOf(HISTORY_PAGE) }
    val total = s.history.sumOf { it.events.size }
    HFCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("History", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(total.toString(), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TABULAR), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (total == 0) {
            Gap(6)
            Text("None", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Gap(8)
            return@HFCard
        }
        var left = limit
        for (day in s.history) {
            if (left <= 0) break
            Gap(12)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(dayTitle(day.date, s.today), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(day.events.size.toString(), style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = TABULAR), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Gap(2)
            day.events.take(left).forEach { e -> HistoryRow(e, ui, onBlock) }
            left -= day.events.size
        }
        if (total > limit) {
            TextButton(onClick = { limit += HISTORY_PAGE * 2 }) { Text("More") }
        }
    }
}

@Composable
private fun HistoryRow(e: InsightEvent, ui: InsightsUi, onBlock: (String?) -> Unit) {
    val kinds = LocalKinds.current
    val (icon, tint) = when (e.kind) {
        EventKind.BLOCKED -> Icons.Outlined.Block to MaterialTheme.colorScheme.error
        EventKind.HABIT -> Icons.Outlined.TaskAlt to kinds.habit.color
        EventKind.UNLOCK -> Icons.Outlined.LockOpen to kinds.appTime.color
        EventKind.BOUGHT -> Icons.Outlined.MoreTime to kinds.limit.color
        EventKind.BREAK -> Icons.Outlined.FreeBreakfast to kinds.blue.color
        EventKind.EMERGENCY -> Icons.Outlined.LockClock to kinds.pink.color
    }
    val what = when (e.kind) {
        EventKind.BLOCKED -> if (e.isSite) Sites.hostOf(e.target.orEmpty()).removePrefix("www.") else e.label?.takeIf { it != e.target } ?: ui.appLabel(e.target.orEmpty())
        EventKind.HABIT -> "Habit done"
        EventKind.UNLOCK -> "Unlocked ${formatDuration(e.ms)}"
        EventKind.BOUGHT -> "Bought ${formatDuration(e.ms)}"
        EventKind.BREAK -> "Break ${formatDuration(e.ms)}"
        EventKind.EMERGENCY -> "Emergency unlock"
    }
    val block = ui.blocks.firstOrNull { it.id == e.blockId }
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            Instant.ofEpochMilli(e.atWallMs).atZone(ui.zone).format(HourMinute),
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TABULAR),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(46.dp),
        )
        Icon(
            when (e.device) {
                Device.PHONE -> Icons.Outlined.PhoneAndroid
                Device.LAPTOP -> Icons.Outlined.Laptop
                null -> Icons.Outlined.Devices
            },
            contentDescription = e.device?.name?.lowercase() ?: "every device",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(17.dp),
        )
        WGap(10)
        // One width for the chip, so what happened lines up row under row.
        Box(Modifier.width(104.dp)) {
            if (block != null) BlockChip(block.name, blockColor(ui.blocks, block.id), onClick = { onBlock(block.id) }, small = true)
        }
        WGap(8)
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        WGap(7)
        Text(what, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/** "Today", "Yesterday", "Mon 16 Nov". */
private fun dayTitle(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> date.format(LongDay)
}
