package com.xern.xrnrule.app.block

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.xern.xrnrule.app.hardcore.HardcoreGuard
import com.xern.xrnrule.app.hardcore.PasswordTries
import com.xern.xrnrule.app.ui.PasswordGateContent
import com.xern.xrnrule.domain.World

/**
 * Phase C anti-bypass (brainstorm §4.5): while a HARDCORE block is ARMED/EARNED, intercept the
 * system Settings app itself (and OEM security apps such as MIUI's, which can force-stop apps
 * and revoke permissions) with a password prompt, so the user can't navigate to
 * Settings > Apps > xrnrule > Device admin apps and deactivate protection mid-urge.
 *
 * Coarser than the brainstorm's per-screen targeting — this only sees "Settings is in the
 * foreground", not which screen inside it, because the baseline detector is UsageStats-only
 * (brainstorm §4.1 Option C, no Accessibility). Same honesty tradeoff as the rest of the app:
 * gate the whole app rather than claim precision we don't have. [NoopHardcoreGuard] makes
 * [HardcoreGuard.shouldIntercept] always false, so this is a no-op in the `play` flavor and
 * when no password/device-admin is configured.
 */
class SettingsGuardOverlay(
    private val context: Context,
    private val guard: HardcoreGuard,
    private val unlockGraceMs: Long = 5 * 60_000L,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val showError = mutableStateOf<String?>(null)

    private class Gate(val view: ComposeView, val owner: OverlayWindowOwner)

    private val gate = OverlayWindow(
        make = ::makeGate,
        add = { g ->
            wm.addView(g.view, params())
            g.owner.onStart()
        },
        remove = { g -> wm.removeView(g.view) },
        release = { g -> g.owner.onDestroy() },
    )

    @Volatile
    private var unlockedUntilMono = 0L

    /**
     * Without "Display over other apps" (an update can take it away) the prompt can't be drawn,
     * and Settings stays reachable: it's the only way to give the permission back, and the shield
     * can't block anything until then either, which the service's alert says. Sending Settings
     * home instead would leave it out of reach for as long as a hardcore block is armed.
     */
    fun render(foregroundPkg: String?, world: World) {
        val shouldGuard = guard.shouldIntercept(foregroundPkg, world) &&
            SystemClock.elapsedRealtime() >= unlockedUntilMono
        if (!shouldGuard || !Settings.canDrawOverlays(context)) {
            hide()
            return
        }
        gate.show()
    }

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        overlayType(),
        0, // no keep-screen-on: this prompt mustn't hold the phone awake if left up
        PixelFormat.OPAQUE,
    ).apply {
        gravity = Gravity.CENTER
        screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private fun makeGate(): Gate {
        val owner = OverlayWindowOwner().also { it.onCreate() }
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                PasswordGateContent(
                    error = showError.value,
                    onSubmit = { input ->
                        val wait = guard.waitMs()
                        if (wait > 0) {
                            showError.value = PasswordTries.waitText(wait)
                        } else if (guard.verifyPassword(input)) {
                            unlockedUntilMono = SystemClock.elapsedRealtime() + unlockGraceMs
                            showError.value = null
                            hide()
                        } else {
                            showError.value = "Wrong password." + guard.waitMs().takeIf { it > 0 }?.let { " " + PasswordTries.waitText(it) }.orEmpty()
                        }
                    },
                    onCancel = { goHome() },
                )
            }
        }
        return Gate(composeView, owner)
    }

    private fun hide() = gate.hide()

    private fun goHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(homeIntent)
        hide()
    }

    private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
}
