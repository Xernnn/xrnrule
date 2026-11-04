@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.xern.xrnrule.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.xern.xrnrule.app.XrnruleApp
import com.xern.xrnrule.app.ui.theme.ChartPalette
import com.xern.xrnrule.app.ui.theme.GeistMono
import com.xern.xrnrule.app.ui.theme.KindColors
import com.xern.xrnrule.app.ui.theme.StatusPalette
import com.xern.xrnrule.app.ui.theme.StatusStyle
import com.xern.xrnrule.domain.Block
import com.xern.xrnrule.domain.BlockState
import com.xern.xrnrule.domain.Sites

/** Outer padding for every screen body. */
val ScreenPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)

/** Corner radius of cards, and of the rows inside the block form. */
val CardShape = RoundedCornerShape(24.dp)
val RowShape = RoundedCornerShape(16.dp)

/**
 * Bumps on every `ON_RESUME` so Settings rows can re-read permission state the user changed
 * while they were off in a system screen.
 */
@Composable
fun rememberResumeSignal(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return tick
}

// ---- text helpers -----------------------------------------------------------------------

fun stateVerbose(state: BlockState): String = when (state) {
    BlockState.ARMED -> "Locked"
    BlockState.EARNED -> "Ready to open"
    BlockState.UNLOCKED -> "Open now"
}

fun stateIcon(state: BlockState): ImageVector = when (state) {
    BlockState.ARMED -> Icons.Filled.Lock
    BlockState.EARNED -> Icons.Filled.Bolt
    BlockState.UNLOCKED -> Icons.Filled.LockOpen
}

@Composable
fun statusStyleFor(state: BlockState): StatusStyle {
    val dark = MaterialTheme.colorScheme.background.luminanceIsDark()
    return when (state) {
        BlockState.ARMED -> if (dark) StatusPalette.lockedDark else StatusPalette.lockedLight
        BlockState.EARNED -> if (dark) StatusPalette.readyDark else StatusPalette.readyLight
        BlockState.UNLOCKED -> if (dark) StatusPalette.openDark else StatusPalette.openLight
    }
}

@Composable
fun chartSeriesColor(): Color =
    if (MaterialTheme.colorScheme.background.luminanceIsDark()) ChartPalette.seriesDark else ChartPalette.seriesLight

private fun Color.luminanceIsDark(): Boolean =
    (0.299 * red + 0.587 * green + 0.114 * blue) < 0.5

/** A block's apps and websites at a glance: "Instagram + youtube.com", "Reddit + 2 more". */
fun targetSummary(block: Block, labels: Map<String, String>): String =
    summarizeNames(block.targetPackages.map { labels[it] ?: it } + block.targetSites.sorted(), empty = "Nothing")

fun summarizeNames(names: List<String>, empty: String): String {
    if (names.isEmpty()) return empty
    return when (names.size) {
        1 -> names[0]
        2 -> "${names[0]} + ${names[1]}"
        else -> "${names[0]} + ${names.size - 1} more"
    }
}

fun formatMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

// ---- page furniture -------------------------------------------------------------------

/** A small caps heading over a group: "FROM YOUR PHONE". */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** The big lowercase page title ("settings", "insights") with optional actions on the right. */
@Composable
fun PageTitle(
    title: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let { it(); WGap(10) }
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** "xrnrule" in the mono face, for the Blocks page header and the block screen. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, size: Int = 22) {
    Text(
        "xrnrule",
        modifier = modifier,
        fontFamily = GeistMono,
        fontWeight = FontWeight.Bold,
        fontSize = size.sp,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

/**
 * [TopAppBar] with its container colour pinned to the screen background instead of the
 * default surface tone, so there's no visible seam between the bar and the scrolling content
 * underneath it.
 */
@Composable
fun HFTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = title,
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

/**
 * A circular button for page headers: on the raised grey (close, back), or [outlined] on the
 * card grey with a small icon, as Habits First's "+".
 */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    outlined: Boolean = false,
    container: Color = if (outlined) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(container)
            .then(if (outlined) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(size * if (outlined) 0.42f else 0.52f))
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, color = MaterialTheme.colorScheme.outlineVariant)
}

/** A soft rounded container. The main layout unit on every screen. */
@Composable
fun HFCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CardShape)
            .padding(padding),
        content = content,
    )
}

/** Rounded progress meter. */
@Composable
fun MeterBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    track: Color = MaterialTheme.colorScheme.surfaceVariant,
    height: Dp = 8.dp,
    dot: Boolean = false,
) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "meter")
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(track),
    ) {
        if (animated > 0f || dot) {
            // With [dot], an empty meter still shows a round start in the colour, as Habits First's do.
            Box(
                Modifier
                    .widthIn(min = if (dot) height else 0.dp) // before the fill, which would pin the width
                    .fillMaxWidth(animated)
                    .height(height)
                    .clip(RoundedCornerShape(height / 2))
                    .background(color),
            )
        }
    }
}

/** The main action of a page: a full-width green pill. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    container: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(54.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            WGap(8)
        }
        Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A quieter full-width pill: outlined on the card grey. */
@Composable
fun OutlinePill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(50.dp),
        shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            WGap(8)
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A small rounded tag: "DO BOTH", "HARDCORE". */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.8.sp),
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/** On/off switch in the app's colours: green when on, a grey ring when off. */
@Composable
fun XSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            checkedBorderColor = MaterialTheme.colorScheme.primary,
            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
            uncheckedTrackColor = Color.Transparent,
            uncheckedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

// ---- grouped rows (settings style) ------------------------------------------------------

/** A card of rows separated by inset hairlines, like a settings group. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CardShape),
        content = content,
    )
}

/**
 * One row of a [GroupCard]: an icon, a title with an optional line under it, and whatever goes
 * on the right (a value, a switch, a chevron). Draws the hairline above itself unless [first].
 */
@Composable
fun GroupRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    subtitle: String? = null,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    first: Boolean = false,
    onClick: (() -> Unit)? = null,
    chevron: Boolean = onClick != null,
    /** In place of [icon]: a picture, such as a site's own icon. */
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    if (!first) Hairline(Modifier.padding(start = if (icon != null || leading != null) 52.dp else 16.dp))
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 54.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(Modifier.width(28.dp).offset(x = (-3).dp), contentAlignment = Alignment.Center) { leading() }
            WGap(12)
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
            WGap(14)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor)
            }
        }
        if (trailing != null) {
            WGap(12)
            trailing()
        }
        if (chevron) {
            WGap(6)
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** A [GroupRow] whose right side is a switch, the whole row toggling it. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    subtitle: String? = null,
    first: Boolean = false,
    enabled: Boolean = true,
) {
    GroupRow(
        title = title,
        icon = icon,
        subtitle = subtitle,
        first = first,
        onClick = if (enabled) ({ onCheckedChange(!checked) }) else null,
        chevron = false,
    ) {
        XSwitch(checked = checked, onCheckedChange = { onCheckedChange(it) }, enabled = enabled)
    }
}

/** A grey explanation line under a group. */
@Composable
fun FootNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 6.dp, vertical = 8.dp),
    )
}

// ---- habit rows ---------------------------------------------------------------------

/**
 * One thing a block asks for, as its card shows it: the kind's icon in the kind's colour, a
 * label, how far along it is ("12 / 30 min"), a thin bar, and the action for it on the right.
 */
@Composable
fun HabitRow(
    icon: ImageVector,
    kind: KindColors,
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    fraction: Float? = null,
    note: String? = null,
    dim: Boolean = false,
    onClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = kind.color, modifier = Modifier.size(20.dp))
        WGap(12)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (value != null) {
                    WGap(8)
                    Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (fraction != null) {
                Gap(4)
                MeterBar(fraction, color = kind.color, height = 6.dp, dot = true)
            }
            if (note != null) {
                Gap(2)
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (action != null) {
            WGap(2)
            action()
        }
    }
}

/**
 * The round button at the end of a [HabitRow], Habits First's small ring: filled (start a timer,
 * done) or ringed (tick, add), 22 dp in a 44 dp touch target.
 */
@Composable
fun RowAction(
    icon: ImageVector,
    contentDescription: String,
    color: Color,
    filled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .then(if (filled) Modifier.background(color) else Modifier.border(2.dp, color, CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = if (filled) onKind(color) else color,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

// ---- app icons ----------------------------------------------------------------------

@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val context = LocalContext.current
    val icon = remember(packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(packageName).toBitmap(width = 144, height = 144).asImageBitmap()
        }.getOrNull()
    }
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = modifier.size(size))
    } else {
        Box(modifier.size(size).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)))
    }
}

/**
 * A website's own icon (asked of the site, kept on the phone; [SiteIconCache]) on a small white
 * tile, so a dark icon still shows on the dark theme; a globe until it arrives or if it has none.
 */
@Composable
fun SiteIcon(host: String, modifier: Modifier = Modifier, size: Dp = 32.dp, fallback: (@Composable () -> Unit)? = null) {
    val icons = (LocalContext.current.applicationContext as XrnruleApp).container.siteIcons
    val loaded by icons.loaded.collectAsState()
    val icon = remember(host, loaded) { icons.icon(host) }
    if (icon != null) {
        Box(
            modifier.size(size).clip(RoundedCornerShape(size * 0.22f)).background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Image(bitmap = icon, contentDescription = null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.High, modifier = Modifier.size(size * 0.84f))
        }
    } else if (fallback != null) {
        fallback()
    } else {
        Box(modifier.size(size), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Language, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size * 0.8f))
        }
    }
}

/**
 * The square at the left of a block card: the one blocked app's or site's icon on its own, as
 * Habits First shows it; several in a grid of up to four on the raised grey; or [fallback] (a
 * shield for nothing).
 */
@Composable
fun AppTile(
    packages: List<String>,
    fallback: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    extra: Int = 0,
    sites: List<String> = emptyList(),
) {
    // An app's package, or (true) a site's host; a site's other names (redd.it) don't get a tile of their own.
    val icons: List<Pair<String, Boolean>> = packages.map { it to false } + Sites.shown(sites).map { it to true }

    @Composable
    fun TileIcon(icon: Pair<String, Boolean>, side: Dp) = if (icon.second) SiteIcon(icon.first, size = side) else AppIcon(icon.first, size = side)
    if (icons.size == 1 && extra == 0) {
        Box(modifier.size(size), contentAlignment = Alignment.Center) { TileIcon(icons[0], if (icons[0].second) size * 0.92f else size) }
        return
    }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.26f))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when (icons.size) {
            0 -> Icon(fallback, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size * 0.45f))
            1 -> TileIcon(icons[0], size * (if (packages.isEmpty()) 0.62f else 0.78f))
            else -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                icons.take(4).chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        pair.forEach { TileIcon(it, size * 0.38f) }
                    }
                }
            }
        }
        val hidden = extra + (icons.size - 4).coerceAtLeast(0)
        if (hidden > 0) {
            Text(
                "+$hidden",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .clip(RoundedCornerShape(topStart = 6.dp))
                    .background(MaterialTheme.colorScheme.outline)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

// ---- misc (Insights, dialogs) --------------------------------------------------------

@Composable
fun StatusChip(status: BlockStatusUi, modifier: Modifier = Modifier) =
    StatusChip(status.label, status.icon, status.tone, modifier)

@Composable
fun StatusChip(label: String, icon: ImageVector, tone: BlockState, modifier: Modifier = Modifier) {
    val style = statusStyleFor(tone)
    val container by animateColorAsState(style.container, label = "chipBg")
    Row(
        modifier
            .background(container, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, contentDescription = null, tint = style.content, modifier = Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = style.content)
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun KeyValueRow(key: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun BigStat(value: String, label: String, modifier: Modifier = Modifier) {
    HFCard(modifier) {
        Text(value, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Gap(height: Int = 12) = Spacer(Modifier.height(height.dp))

@Composable
fun WGap(width: Int = 12) = Spacer(Modifier.width(width.dp))

/** Text or an icon on a filled kind colour: dark on the bright ones (the limit yellow), else white. */
fun onKind(color: Color): Color = if (color.luminance() > 0.5f) Color(0xFF1F1F23) else Color.White
