package com.xern.xrnrule.app.block

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.compose.ui.Alignment
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import com.xern.xrnrule.app.R
import com.xern.xrnrule.domain.CornerClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ClockCorner(val label: String, val gravity: Int, val alignment: Alignment) {
    TOP_LEFT("Top left", Gravity.TOP or Gravity.START, Alignment.TopStart),
    TOP_RIGHT("Top right", Gravity.TOP or Gravity.END, Alignment.TopEnd),
    BOTTOM_LEFT("Bottom left", Gravity.BOTTOM or Gravity.START, Alignment.BottomStart),
    BOTTOM_RIGHT("Bottom right", Gravity.BOTTOM or Gravity.END, Alignment.BottomEnd),
}

enum class ClockSize(val label: String, val sp: Float) {
    SMALL("Small", 11f),
    MEDIUM("Medium", 13f),
    LARGE("Large", 16f),
}

/** How the corner clock looks. [strength] is how much of it shows while nothing is close. */
data class ClockLook(
    val on: Boolean = true,
    val corner: ClockCorner = ClockCorner.TOP_RIGHT,
    val size: ClockSize = ClockSize.MEDIUM,
    val strength: Float = 0.5f,
)

/** Set in Settings. This phone's own, like the theme: where a clock sits suits one screen. */
object CornerClockLook {
    private const val PREFS = "corner_clock"
    private val state = MutableStateFlow(ClockLook())
    private var prefs: android.content.SharedPreferences? = null

    val look: StateFlow<ClockLook> = state.asStateFlow()

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val default = ClockLook()
        state.value = ClockLook(
            on = p.getBoolean("on", default.on),
            corner = runCatching { ClockCorner.valueOf(p.getString("corner", null)!!) }.getOrDefault(default.corner),
            size = runCatching { ClockSize.valueOf(p.getString("size", null)!!) }.getOrDefault(default.size),
            strength = p.getFloat("strength", default.strength),
        )
    }

    fun set(value: ClockLook) {
        state.value = value
        prefs?.edit {
            putBoolean("on", value.on)
            putString("corner", value.corner.name)
            putString("size", value.size.name)
            putFloat("strength", value.strength)
        }
    }
}

/**
 * The time left, faintly, in a corner of an app that's running out of it; it stands out for a few
 * seconds at the milestones ([CornerClock.strong]). A plain View: this stays up for as long as a
 * game does, and has no use for Compose. Nothing here can be touched.
 */
class CornerClockOverlay(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    // MiSans, the app's font; its tabular figures (set on the view) keep the clock from jittering.
    private val medium by lazy { ResourcesCompat.getFont(context, R.font.misans_medium) }
    private val bold by lazy { ResourcesCompat.getFont(context, R.font.misans_semibold) }

    private var label: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var look: ClockLook? = null
    private var strong: Boolean? = null

    /** Shows [leftMs] as [wanted] asks; null takes the clock away. */
    fun show(leftMs: Long?, wanted: ClockLook) {
        if (leftMs == null) {
            hide()
            return
        }
        val view = label ?: add() ?: return
        if (wanted != look) place(view, wanted)
        val now = CornerClock.strong(leftMs)
        if (now != strong || wanted != look) {
            view.typeface = if (now) bold else medium
            view.animate().alpha(if (now) 1f else wanted.strength).setDuration(FADE_MS).start()
        }
        strong = now
        look = wanted
        val text = CornerClock.text(leftMs)
        if (view.text.toString() != text) view.text = text
    }

    fun hide() {
        label?.let { view -> runCatching { wm.removeView(view) } }
        label = null
        params = null
        look = null
        strong = null
    }

    private fun add(): TextView? {
        val view = TextView(context).apply {
            setTextColor(Color.WHITE)
            includeFontPadding = false
            fontFeatureSettings = "tnum"
            alpha = 0f
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(PILL)
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        val layout = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { alpha = WINDOW_ALPHA }
        // No overlay permission: the service says so; there's just no clock meanwhile.
        runCatching { wm.addView(view, layout) }.onFailure { return null }
        label = view
        params = layout
        return view
    }

    private fun place(view: TextView, wanted: ClockLook) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, wanted.size.sp)
        params?.let {
            it.gravity = wanted.corner.gravity
            it.x = dp(10)
            it.y = dp(10)
            runCatching { wm.updateViewLayout(view, it) }
        }
    }

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        /**
         * Android 12+ drops touches that pass through another app's window above this, even one
         * that can't be touched, so a stronger window would swallow taps on the game under it. The
         * strength the clock shows is the view's alpha, which doesn't count.
         */
        const val WINDOW_ALPHA = 0.8f
        const val FADE_MS = 400L
        const val PILL = 0x99000000.toInt()
    }
}
