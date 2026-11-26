package com.xern.xrnrule.app.ui

import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.FreeBreakfast
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xern.xrnrule.app.R
import com.xern.xrnrule.app.block.OverlayShieldPort
import com.xern.xrnrule.app.data.TelemetryEvent
import com.xern.xrnrule.app.data.UsageDay
import com.xern.xrnrule.app.ui.theme.DataColor
import com.xern.xrnrule.app.ui.theme.MiSans
import com.xern.xrnrule.app.ui.theme.XrnruleTheme
import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockMode
import com.xern.xrnrule.domain.BlockReason
import com.xern.xrnrule.domain.BlockTexts
import com.xern.xrnrule.domain.Trigger
import com.xern.xrnrule.domain.TriggerType
import com.xern.xrnrule.domain.TriggerUnit
import java.time.LocalDateTime
import java.time.ZoneId

// Made-up data for drawing the screens without a phone (`android preview render "?:app@sideloadDebug"`).

private val zone: ZoneId = ZoneId.of("Australia/Sydney")
private val now: Long = LocalDateTime.of(2026, 11, 19, 21, 40).atZone(zone).toInstant().toEpochMilli()
private const val MIN = 60_000L

private fun block(id: String, name: String, mode: BlockMode, apps: Set<String> = emptySet(), sites: Set<String> = emptySet(), desktop: Set<String> = emptySet(), allowanceMin: Long = 0) = Block(
    id = id,
    name = name,
    targetPackages = apps,
    targetSites = sites,
    targetDesktopApps = desktop,
    trigger = Trigger(TriggerType.CHECKLIST, 1, TriggerUnit.ITEMS),
    mode = mode,
    dailyAllowanceMs = allowanceMin * MIN,
)

private val sampleBlocks = listOf(
    block("yt", "YouTube", BlockMode.ALLOWANCE, apps = setOf("com.google.android.youtube"), sites = setOf("youtube.com"), allowanceMin = 30),
    block("social", "Social", BlockMode.HABIT, apps = setOf("com.instagram.android", "com.zhiliaoapp.musically")),
    block("reddit", "Reddit at night", BlockMode.SCHEDULE, sites = setOf("reddit.com")),
    block("games", "Games", BlockMode.LOCKDOWN, desktop = setOf("steam")),
    block("news", "News", BlockMode.ALLOWANCE, sites = setOf("news.example.com"), allowanceMin = 20),
)

private fun at(daysBack: Int, hour: Int, minute: Int): Long =
    LocalDateTime.of(2026, 11, 19, hour, minute).minusDays(daysBack.toLong()).atZone(zone).toInstant().toEpochMilli()

/** Two months of a phone and a laptop: tries late in the evening, habits in the morning, a few shortcuts. */
private val phoneLog: List<TelemetryEvent> = buildList {
    for (d in 0 until 60) {
        val n = (d * 7 + 3) % 5
        for (k in 0..n) {
            val hour = listOf(8, 13, 20, 21, 22, 23)[(d + k) % 6]
            if (d == 0 && hour > 21) continue
            add(TelemetryEvent.Blocked(listOf("com.instagram.android", "com.google.android.youtube", "com.zhiliaoapp.musically")[(d + k) % 3], at(d, hour, (k * 13) % 60)))
        }
        if (d % 4 == 3) add(TelemetryEvent.SiteBlocked("reddit.com", at(d, 23, 10)))
        if (d % 3 != 2) add(TelemetryEvent.HabitDone("social", at(d, 7, 30 + d % 20)))
        if (d % 3 != 2) add(TelemetryEvent.UnlockUsed("social", 20 * MIN, at(d, 8, 10), at(d, 7, 50)))
        if (d % 6 == 0) add(TelemetryEvent.MinutesBought(10 * MIN, at(d, 21, 5), "yt"))
        if (d % 5 == 3) add(TelemetryEvent.BreakTaken(15 * MIN, at(d, 16, 0)))
    }
}

private val laptopLog: List<TelemetryEvent> = buildList {
    for (d in 0 until 60) {
        if (d % 2 == 0) add(TelemetryEvent.Blocked("youtube.com", at(d, 14, 20), "youtube.com"))
        if (d % 3 == 2) add(TelemetryEvent.Blocked("steam", at(d, 22, 15), "Steam"))
        if (d % 5 == 4) add(TelemetryEvent.Blocked("reddit.com", at(d, 23, 40), "reddit.com"))
        // The laptop hears of the phone's habit too: one event, on the phone.
        if (d % 3 != 2) add(TelemetryEvent.HabitDone("social", at(d, 7, 33 + d % 20)))
        if (d % 7 == 2) add(TelemetryEvent.MinutesBought(10 * MIN, at(d, 15, 0), "yt"))
    }
}

private val sampleEmergencies = mapOf("games@${at(9, 19, 0)}" to at(9, 19, 0))

/** Each limited block's use a day as the server kept it: under the limit, near it, past it now and then. */
private val sampleLimits: Map<String, Map<String, Map<Long, Long>>> = mapOf(
    "yt" to mapOf(
        "phone-1a2b3c4d" to (1..60).associate { d -> at(d, 0, 0) to (14 + (d * 7) % 13) * MIN },
        "laptop-9f8e7d6c" to (1..60).filter { it % 2 == 0 }.associate { d -> at(d, 0, 0) to (3 + d % 6) * MIN },
    ),
    "news" to mapOf("phone-1a2b3c4d" to (1..60).associate { d -> at(d, 0, 0) to (8 + (d * 5) % 9) * MIN }),
)

private val sampleToday = mapOf("yt" to BlockToday(18 * MIN, 7 * MIN, 30 * MIN), "news" to BlockToday(4 * MIN, 0, 20 * MIN))

private fun sampleUi(events: List<InsightEvent>, range: InsightRange, device: DeviceFilter = DeviceFilter.ALL, blockId: String? = null, laptop: LaptopLog = LaptopLog.OK): InsightsUi {
    val summary = InsightsModel.summarize(InsightsModel.select(events, device, blockId), sampleBlocks, range, now, zone, logStartMs = events.firstOrNull()?.atWallMs)
    val phone = device != DeviceFilter.LAPTOP
    val k = range.days
    val appTime = if (!phone) emptyMap() else mapOf(
        "com.google.android.youtube" to ("YouTube" to 41 * MIN * k),
        "com.instagram.android" to ("Instagram" to 26 * MIN * k),
        "org.telegram.messenger" to ("Telegram" to 18 * MIN * k),
        "com.zhiliaoapp.musically" to ("TikTok" to 9 * MIN * k),
        "com.android.chrome" to ("Chrome" to 7 * MIN * k),
    )
    val siteTime = if (!phone) emptyMap() else mapOf("youtube.com" to 22 * MIN * k, "news.example.com" to 12 * MIN * k, "reddit.com" to 3 * MIN * k, "docs.example.org" to 5 * MIN * k)
    // The laptop's, as the server keeps it: made up, a day at a time.
    val laptopDays = (0L until 30L).associate { back ->
        now.let { InsightsModel.today(it, zone).minusDays(back) } to UsageDay(
            apps = mapOf("zen" to (90 + back * 7 % 60) * MIN, "org.example.Editor" to (40 + back * 11 % 80) * MIN, "steam" to if (back % 3 == 0L) 35 * MIN else 0L),
            sites = mapOf("youtube.com" to (25 + back * 5 % 20) * MIN, "docs.example.org" to 20 * MIN),
        )
    }
    val laptopShown = device != DeviceFilter.PHONE
    val first = InsightsModel.today(now, zone).minusDays(k - 1L)
    val inRange = laptopDays.filterKeys { !it.isBefore(first) }.values
    val laptopApps = if (!laptopShown) emptyMap() else inRange.flatMap { it.apps.entries }.groupBy({ it.key }, { it.value })
        .mapValues { (id, ms) -> (mapOf("zen" to "Zen Browser", "org.example.Editor" to "Editor", "steam" to "Steam")[id] ?: id) to ms.sum() }
    val laptopSites = if (!laptopShown) emptyMap() else inRange.flatMap { it.sites.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
    val (apps, sites) = InsightsModel.topTargets(appTime, siteTime, summary.targets, sampleBlocks, blockId, laptopApps, laptopSites)
    val phoneDays = (0L until 30L).associate { back -> InsightsModel.today(now, zone).minusDays(back) to appTime.mapValues { it.value.second / k * (8 + back % 5) / 10 } }
    return InsightsUi(
        summary = summary,
        blocks = sampleBlocks,
        apps = apps,
        sites = sites,
        limits = sampleBlocks.filter { it.dailyAllowanceMs > 0 }.associate { b ->
            b.id to InsightsModel.limitUse(b, sampleLimits[b.id].orEmpty(), sampleToday[b.id], range, device, now, zone)
        },
        usageAccess = true,
        sitesOn = true,
        laptop = laptop,
        zone = zone,
        appLabel = { pkg -> mapOf("com.instagram.android" to "Instagram", "com.zhiliaoapp.musically" to "TikTok", "com.google.android.youtube" to "YouTube")[pkg] ?: pkg },
        screen = InsightsModel.screenTime(phoneDays, emptyMap(), laptopDays, sampleBlocks, blockId, range, device, now, zone),
    )
}

private val busyEvents = InsightsModel.merge(phoneLog, laptopLog, sampleEmergencies, sampleBlocks)

/** The app's theme (dark or light by the preview's uiMode) on its background, as the Scaffold gives it. */
@Composable
private fun Frame(content: @Composable () -> Unit) = XrnruleTheme {
    Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) { content() }
}

@Composable
private fun Insights(ui: InsightsUi, range: InsightRange, device: DeviceFilter = DeviceFilter.ALL, blockId: String? = null, scrolledTo: Int = 0, chartPick: Int = -1) =
    InsightsContent(ui, range, {}, device, {}, blockId, {}, scroll = androidx.compose.foundation.ScrollState(scrolledTo), chartPick = chartPick)

// ---- Insights --------------------------------------------------------------------------------

@Preview(name = "Insights week", widthDp = 393, heightDp = 2900, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun InsightsWeekPreview() = Frame { Insights(sampleUi(busyEvents, InsightRange.WEEK), InsightRange.WEEK) }

/** The same page scrolled to its end: the apps, the sites and the history. */
@Preview(name = "Insights week, lower half", widthDp = 393, heightDp = 2900, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun InsightsWeekLowerPreview() = Frame { Insights(sampleUi(busyEvents, InsightRange.WEEK), InsightRange.WEEK, scrolledTo = 100_000) }

@Preview(name = "Insights week light", widthDp = 393, heightDp = 2900, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun InsightsWeekLightPreview() = Frame { Insights(sampleUi(busyEvents, InsightRange.WEEK), InsightRange.WEEK) }

/** A bar tapped: Tuesday's tries and their split by block. */
@Preview(name = "Insights week, a day tapped", widthDp = 393, heightDp = 900, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun InsightsTappedPreview() = Frame { Insights(sampleUi(busyEvents, InsightRange.WEEK), InsightRange.WEEK, chartPick = 4) }

@Preview(name = "Insights today light", widthDp = 393, heightDp = 2600, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun InsightsTodayLightPreview() = Frame { Insights(sampleUi(busyEvents, InsightRange.TODAY), InsightRange.TODAY) }

@Preview(name = "Insights 30 days", widthDp = 393, heightDp = 2600, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun InsightsMonthPreview() = Frame { Insights(sampleUi(busyEvents, InsightRange.MONTH), InsightRange.MONTH) }

@Preview(name = "Insights today", widthDp = 393, heightDp = 2600, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun InsightsTodayPreview() = Frame { Insights(sampleUi(busyEvents, InsightRange.TODAY), InsightRange.TODAY) }

@Preview(name = "Insights one block, laptop", widthDp = 393, heightDp = 2200, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun InsightsBlockLaptopPreview() = Frame {
    Insights(sampleUi(busyEvents, InsightRange.WEEK, DeviceFilter.LAPTOP, "yt"), InsightRange.WEEK, DeviceFilter.LAPTOP, "yt")
}

@Preview(name = "Insights empty", widthDp = 393, heightDp = 2600, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun InsightsEmptyPreview() = Frame {
    val ui = sampleUi(emptyList(), InsightRange.WEEK, laptop = LaptopLog.OFF).copy(apps = emptyList(), sites = emptyList(), limits = emptyMap(), usageAccess = false, sitesOn = false)
    Insights(ui, InsightRange.WEEK)
}

// ---- Settings --------------------------------------------------------------------------------

private val sampleSettings = SettingsSummary(
    sync = RowValue("2 min ago"),
    syncOn = true,
    permissions = listOf(
        PermissionState("Usage access", Icons.Outlined.QueryStats, true),
        PermissionState("Draw over other apps", Icons.Outlined.Layers, true),
        PermissionState("Notifications", Icons.Outlined.Notifications, true),
        PermissionState("Ignore battery optimizations", Icons.Outlined.BatteryChargingFull, false),
        PermissionState("Autostart (Xiaomi)", Icons.Outlined.RocketLaunch, null),
        PermissionState("Automatic date & time", Icons.Outlined.AccessTime, true),
    ),
    clockWarnings = emptyList(),
    unlocks = RowValue("1 h"),
    habits = RowValue("4"),
    loop = RowValue("Connected"),
    extension = RowValue("On"),
    security = RowValue("Lock on"),
    appearance = RowValue("Auto"),
    cornerTimer = RowValue("Top right"),
    updates = RowValue("0.2.18"),
    version = "Version 0.2.18",
)

@Preview(name = "Settings", widthDp = 393, heightDp = 1500, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SettingsPreview() = Frame { SettingsContent(sampleSettings, onOpen = {}, onPermission = {}, onLoop = {}) }

@Preview(name = "Settings needing things", widthDp = 393, heightDp = 1500, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SettingsWarningsPreview() = Frame {
    SettingsContent(
        sampleSettings.copy(
            sync = RowValue("Not syncing", warn = true),
            clockWarnings = listOf("Clock set 5 min ahead by hand"),
            loop = RowValue("Not answering", warn = true),
            extension = RowValue("Out of date", warn = true),
            updates = RowValue("0.2.19 ready"),
        ),
        onOpen = {}, onPermission = {}, onLoop = {},
    )
}

@Preview(name = "Settings light", widthDp = 393, heightDp = 1500, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun SettingsLightPreview() = Frame { SettingsContent(sampleSettings, onOpen = {}, onPermission = {}, onLoop = {}) }

// ---- The waits ----------------------------------------------------------------------------------

@Composable
private fun Dimmed(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).padding(28.dp), contentAlignment = Alignment.Center) { content() }
}

@Preview(name = "Loosen wait", widthDp = 393, heightDp = 760, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun LoosenWaitPreview() = Frame {
    Dimmed {
        LoosenWait("Delete YouTube?", "It stops blocking for good.", listOf("YouTube" to Color(BlockColors.PALETTE[0])), DataColor, 42_000, usePassword = false, onGoAhead = {}, onCancel = {})
    }
}

@Preview(name = "Loosen wait, several blocks", widthDp = 393, heightDp = 760, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun LoosenWaitBlocksPreview() = Frame {
    Dimmed {
        LoosenWait(
            "Save these changes?", "They make Social easier to get past.",
            listOf("Social" to Color(BlockColors.PALETTE[1]), "Reddit at night" to Color(BlockColors.PALETTE[2])),
            DataColor, 7_400, usePassword = false, onGoAhead = {}, onCancel = {},
        )
    }
}

@Preview(name = "Loosen wait done", widthDp = 393, heightDp = 760, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun LoosenWaitDonePreview() = Frame {
    Dimmed {
        LoosenWait("Take a 15 min break?", "", emptyList(), DataColor, 0, usePassword = false, onGoAhead = {}, onCancel = {})
    }
}

@Preview(name = "Loosen password", widthDp = 393, heightDp = 760, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun LoosenPasswordPreview() = Frame {
    Dimmed {
        LoosenWait("Turn Games off?", "", listOf("Games" to Color(BlockColors.PALETTE[3])), DataColor, 60_000, usePassword = true, password = "hunter", wrong = "Wrong password.", onGoAhead = {}, onCancel = {})
    }
}

@Preview(name = "Loosen wait light", widthDp = 393, heightDp = 760, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun LoosenWaitLightPreview() = Frame {
    Dimmed {
        LoosenWait("Raise the limit to 1 h 30 min?", "", emptyList(), DataColor, 23_000, usePassword = false, onGoAhead = {}, onCancel = {})
    }
}

private val limitBlock = sampleBlocks[0].copy(openWaitS = 10, opensPerDay = 5)

@Preview(name = "Block screen, buying minutes", widthDp = 393, heightDp = 860, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun OverrideWaitPreview() = BlockOverlayContent(
    ui = OverlayShieldPort.OverlayUi(
        subject = "YouTube", pkg = "com.google.android.youtube", isSite = false, block = limitBlock, reason = BlockReason.LIMIT, items = emptyList(),
        back = { BlockTexts.Back("Back at midnight", "Today's 30 min is used up") },
        overrideReadyAtElapsed = SystemClock.elapsedRealtime() + 31_000, overridePhrase = "I choose to keep scrolling", overrideGrantMs = 10 * MIN, overrideWaitMs = 45_000,
    ),
    onToggleItem = { _, _ -> }, onSetTimer = { _, _ -> }, onRedeem = {}, onLeave = {},
)

@Preview(name = "Block screen, opening", widthDp = 393, heightDp = 860, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun OpenWaitPreview() = BlockOverlayContent(
    ui = OverlayShieldPort.OverlayUi(
        subject = "YouTube", pkg = "com.google.android.youtube", isSite = false, block = limitBlock, reason = BlockReason.WAIT, items = emptyList(),
        back = { BlockTexts.Back("Opens in 7 s", "12 min left today · 3 of 5 opens left") },
        openWaitLeftMs = { 6_300 },
        overrideRejection = com.xern.xrnrule.domain.OverrideRejection.NOT_OVERRIDABLE,
    ),
    onToggleItem = { _, _ -> }, onSetTimer = { _, _ -> }, onRedeem = {}, onLeave = {},
)

// ---- Brand: the top bar, the lock screen, the launcher icon, the font -----------------------------

@Preview(name = "Blocks top bar", widthDp = 393, heightDp = 120, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun TopBarPreview() = Frame {
    PageTitle(title = "", leading = { BrandTile() }) {
        RoundIconButton(Icons.Outlined.FreeBreakfast, "Take a break", outlined = true, onClick = {})
        RoundIconButton(Icons.Filled.Add, "New block", outlined = true, onClick = {})
    }
}

@Preview(name = "Lock screen", widthDp = 393, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun LockScreenPreview() = Frame { LockScreen(null, onUnlock = {}) }

/**
 * The launcher icon (adaptive layers, masked to the launcher's rounded square) next to the tile
 * drawn from extension/icons/icon.svg, and the themed (monochrome) layer: they should match.
 */
@Preview(name = "Launcher icon", widthDp = 393, heightDp = 200, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun LauncherIconPreview() = Frame {
    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(96.dp).clip(RoundedCornerShape(23.dp)), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.requiredSize(144.dp))
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(144.dp))
        }
        Image(painterResource(R.drawable.tile_xrnrule), null, Modifier.size(96.dp))
        Box(Modifier.size(96.dp).clip(RoundedCornerShape(48.dp)).background(Color(0xFF3A3A40)), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_launcher_monochrome), null, Modifier.requiredSize(144.dp), colorFilter = ColorFilter.tint(Color(0xFFFFD6C2)))
        }
    }
}

@Preview(name = "Tiles", widthDp = 393, heightDp = 120, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun TilesPreview() = Frame {
    Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        BrandTile(size = 64.dp)
        LoopsTile(size = 64.dp)
        BrandTile(size = 30.dp)
        LoopsTile(size = 28.dp)
    }
}

/** Every weight with "@" in it: the static Bold and Heavy files drew it as "&". */
@Preview(name = "Font weights", widthDp = 393, heightDp = 330, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun FontPreview() = Frame {
    Column(Modifier.padding(20.dp)) {
        listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold, FontWeight.Black).forEach { w ->
            Text("me@home 0123456789 ${w.weight}", style = TextStyle(fontFamily = MiSans, fontWeight = w, fontSize = 20.sp, fontFeatureSettings = "tnum"))
        }
        Text("1111 / 8888", style = TextStyle(fontFamily = MiSans, fontWeight = FontWeight.Bold, fontSize = 20.sp, fontFeatureSettings = "tnum"))
        Text("1111 / 8888", style = TextStyle(fontFamily = MiSans, fontWeight = FontWeight.Bold, fontSize = 20.sp))
    }
}
