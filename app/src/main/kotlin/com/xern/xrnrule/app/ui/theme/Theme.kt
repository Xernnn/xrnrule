package com.xern.xrnrule.app.ui.theme

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.xern.xrnrule.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Dark graphite cards on a near-black ground, one green for "go", and a colour per kind of
 * habit (timer red, app time cyan, ...) so a block's rows read at a glance. Type is MiSans, the
 * xrn family's one font, numbers and countdowns included.
 */

private val Green = Color(0xFF22C55E)
private val GreenLight = Color(0xFF16A34A)

private val DarkScheme = darkColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1F3A2A),
    onPrimaryContainer = Color(0xFFBBF7D0),
    secondary = Color(0xFFAAAAAC),
    onSecondary = Color(0xFF1F1F23),
    secondaryContainer = Color(0xFF3A3A40),
    onSecondaryContainer = Color(0xFFF4F4F5),
    background = Color(0xFF1F1F23),
    onBackground = Color(0xFFF4F4F5),
    surface = Color(0xFF2B2B30),
    onSurface = Color(0xFFF4F4F5),
    surfaceVariant = Color(0xFF3A3A40),
    onSurfaceVariant = Color(0xFFAAAAAC),
    surfaceContainerLowest = Color(0xFF1A1A1E),
    surfaceContainerLow = Color(0xFF25252A),
    surfaceContainer = Color(0xFF2B2B30),
    surfaceContainerHigh = Color(0xFF313136),
    surfaceContainerHighest = Color(0xFF3A3A40),
    surfaceBright = Color(0xFF3A3A40),
    surfaceDim = Color(0xFF1F1F23),
    outline = Color(0xFF4A4A50),
    outlineVariant = Color(0xFF37373C),
    error = Color(0xFFF04242),
    onError = Color.White,
    errorContainer = Color(0xFF4A1F1F),
    onErrorContainer = Color(0xFFFDE3E3),
    inverseSurface = Color(0xFFF4F4F5),
    inverseOnSurface = Color(0xFF1F1F23),
)

private val LightScheme = lightColorScheme(
    primary = GreenLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCFCE7),
    onPrimaryContainer = Color(0xFF14532D),
    secondary = Color(0xFF6B6B73),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEBEBEF),
    onSecondaryContainer = Color(0xFF1B1B1E),
    background = Color(0xFFF2F2F4),
    onBackground = Color(0xFF1B1B1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1B1E),
    surfaceVariant = Color(0xFFEBEBEF),
    onSurfaceVariant = Color(0xFF6B6B73),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7F9),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0F0F3),
    surfaceContainerHighest = Color(0xFFEBEBEF),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE3E3E8),
    outline = Color(0xFFC9C9CF),
    outlineVariant = Color(0xFFE3E3E8),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFDE3E3),
    onErrorContainer = Color(0xFF7F1D1D),
    inverseSurface = Color(0xFF2B2B30),
    inverseOnSurface = Color(0xFFF4F4F5),
)

/**
 * MiSans Latin (Xiaomi's MiSans, the system font on the phone). MiSans runs heavier than its
 * names at the size the type scale below uses, so each Compose weight takes the cut that looks
 * like Habits First's (Nunito's) weight of that name: Bold draws MiSans Semibold, ExtraBold
 * MiSans Bold. It covers Vietnamese.
 *
 * Bold and Heavy come from the variable font at those cuts' weights (630, 700): the static
 * Bold and Heavy files of MiSans Latin 4.007 draw "@" as "&".
 */
@OptIn(ExperimentalTextApi::class)
val MiSans = FontFamily(
    Font(R.font.misans_regular, FontWeight.Normal),
    Font(R.font.misans_medium, FontWeight.Medium),
    Font(R.font.misans_demibold, FontWeight.SemiBold),
    Font(R.font.misans_semibold, FontWeight.Bold),
    Font(R.font.misans_vf, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(630))),
    Font(R.font.misans_vf, FontWeight.Black, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

/** MiSans's tabular figures: every digit one width, so a countdown or a column of numbers doesn't jitter. */
const val TABULAR = "tnum"

private fun style(size: Double, weight: FontWeight, spacing: Double = 0.0) = TextStyle(
    fontFamily = MiSans,
    fontSize = size.sp,
    lineHeight = (size * 1.36).sp,
    fontWeight = weight,
    letterSpacing = spacing.sp,
)

/**
 * Habits First's sizes, measured off its screens, at 0.94 of them: MiSans is taller than
 * Nunito at the same size. Names and running text are Regular; only titles and buttons are
 * heavier.
 */
private val AppTypography = Typography(
    displaySmall = style(27.0, FontWeight.Black), // the new-block steps' question
    headlineLarge = style(27.0, FontWeight.Black),
    headlineMedium = style(22.5, FontWeight.ExtraBold), // page titles: "settings"
    headlineSmall = style(20.0, FontWeight.Bold), // dialog titles
    titleLarge = style(19.0, FontWeight.SemiBold),
    titleMedium = style(17.0, FontWeight.SemiBold), // top bars and sheets: "Edit block"
    titleSmall = style(14.0, FontWeight.Bold),
    bodyLarge = style(16.0, FontWeight.Normal), // list rows
    bodyMedium = style(13.5, FontWeight.Normal), // a block's habit rows
    bodySmall = style(12.0, FontWeight.Normal), // second lines
    labelLarge = style(15.0, FontWeight.Bold), // buttons
    labelMedium = style(11.5, FontWeight.Medium), // the tab bar
    labelSmall = style(12.0, FontWeight.Medium, spacing = 1.0), // section labels, in capitals
)

/** Container / content / accent colours for a block state. Read in both themes. */
data class StatusStyle(val container: Color, val content: Color, val accent: Color)

object StatusPalette {
    // ARMED: blocking. The alert red, softened.
    val lockedLight = StatusStyle(Color(0xFFFDE3E3), Color(0xFF7F1D1D), Color(0xFFDC2626))
    val lockedDark = StatusStyle(Color(0xFF4A1F1F), Color(0xFFFDE3E3), Color(0xFFF04242))

    // EARNED: ready. Amber: something good is waiting for you.
    val readyLight = StatusStyle(Color(0xFFFEF3C7), Color(0xFF713F12), Color(0xFFCA8A04))
    val readyDark = StatusStyle(Color(0xFF423814), Color(0xFFFEF3C7), Color(0xFFFFD60A))

    // UNLOCKED: open. The green.
    val openLight = StatusStyle(Color(0xFFDCFCE7), Color(0xFF14532D), GreenLight)
    val openDark = StatusStyle(Color(0xFF1F3A2A), Color(0xFFBBF7D0), Green)
}

/**
 * Single-series data colour for charts: the green, which sits in the lightness band for marks
 * on both card surfaces (#FFFFFF / #2B2B30) with >= 3:1 contrast.
 */
object ChartPalette {
    val seriesLight = GreenLight
    val seriesDark = Green
}

/** One kind of habit or rule: its colour, and the tinted fill and border of a card picked for it. */
data class KindColors(val color: Color, val fill: Color, val border: Color)

/** The colour of each kind of thing a block can ask for. */
class Kinds(private val dark: Boolean, private val surface: Color) {
    private fun kind(darkColor: Long, lightColor: Long): KindColors {
        val c = Color(if (dark) darkColor else lightColor)
        return KindColors(c, lerp(surface, c, if (dark) 0.16f else 0.10f), lerp(surface, c, if (dark) 0.55f else 0.45f))
    }

    /** Tasks you tick yourself. */
    val habit = kind(0xFF22C55E, 0xFF16A34A)
    /** A timer you run on the block screen. */
    val timer = kind(0xFFE0473A, 0xFFDC2626)
    /** Minutes in other apps. */
    val appTime = kind(0xFF40C8E0, 0xFF0891B2)
    /** xrnloops habits. */
    val loop = kind(0xFFA78BFA, 0xFF7C3AED)
    /** Daily limits. */
    val limit = kind(0xFFFFD60A, 0xFFCA8A04)
    /** Schedules and lockdowns: no habit, just blocked. */
    val strict = kind(0xFFFF8C1A, 0xFFEA580C)
    val blue = kind(0xFF4A9EFF, 0xFF2563EB)
    val pink = kind(0xFFF472B6, 0xFFDB2777)

    /** Loop's habit colours (`Habit.paletteColor`, 0..19): its light palette, or its dark one. */
    fun palette(index: Int): KindColors {
        val i = index.coerceIn(0, PALETTE_LIGHT.size - 1)
        return kind(PALETTE_DARK[i], PALETTE_LIGHT[i])
    }

    companion object {
        /** Loop Habit Tracker's palette, as in its light theme, and as in its dark one. */
        val PALETTE_LIGHT = longArrayOf(
            0xFFD32F2F, 0xFFE64A19, 0xFFF57C00, 0xFFFF8F00, 0xFFF9A825, 0xFFAFB42B, 0xFF7CB342,
            0xFF388E3C, 0xFF00897B, 0xFF00ACC1, 0xFF039BE5, 0xFF1976D2, 0xFF303F9F, 0xFF5E35B1,
            0xFF8E24AA, 0xFFD81B60, 0xFF5D4037, 0xFF424242, 0xFF757575, 0xFF9E9E9E,
        )
        val PALETTE_DARK = longArrayOf(
            0xFFEF9A9A, 0xFFFFAB91, 0xFFFFCC80, 0xFFFFECB3, 0xFFFFF59D, 0xFFE6EE9C, 0xFFC5E1A5,
            0xFF69F0AE, 0xFF80CBC4, 0xFF80DEEA, 0xFF81D4FA, 0xFF64B5F6, 0xFF9FA8DA, 0xFFB39DDB,
            0xFFCE93D8, 0xFFF48FB1, 0xFFBCAAA4, 0xFFF5F5F5, 0xFFE0E0E0, 0xFF9E9E9E,
        )
    }
}

val LocalKinds = staticCompositionLocalOf { Kinds(dark = true, surface = DarkScheme.surface) }

/** Whether the app follows the phone, or is always light or dark (Settings → Appearance). */
enum class ThemeMode(val label: String) { AUTO("Auto"), LIGHT("Light"), DARK("Dark") }

/** The appearance choice, kept in its own prefs file; read by every Compose root, overlays included. */
object Appearance {
    private const val PREFS = "appearance"
    private val mode = MutableStateFlow(ThemeMode.AUTO)
    private var prefs: android.content.SharedPreferences? = null

    val themeMode: StateFlow<ThemeMode> get() = mode

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        mode.value = runCatching { ThemeMode.valueOf(p.getString("theme", null) ?: "AUTO") }.getOrDefault(ThemeMode.AUTO)
    }

    fun setThemeMode(value: ThemeMode) {
        mode.value = value
        prefs?.edit()?.putString("theme", value.name)?.apply()
    }
}

@Composable
fun XrnruleTheme(content: @Composable () -> Unit) {
    val mode by Appearance.themeMode.collectAsState()
    val darkTheme = when (mode) {
        ThemeMode.AUTO -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val bars = WindowCompat.getInsetsController(window, view)
            bars.isAppearanceLightStatusBars = !darkTheme
            bars.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalKinds provides Kinds(darkTheme, scheme.surface),
            content = content,
        )
    }
}
