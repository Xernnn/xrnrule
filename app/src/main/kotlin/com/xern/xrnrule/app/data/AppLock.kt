package com.xern.xrnrule.app.data

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The fingerprint step in front of the app's screens. Opening xrnrule, or coming back to it after
 * more than [RELOCK_AFTER_MS] away, shows the lock screen until the phone's fingerprint says it's
 * you; the screen-lock PIN works too, for a finger the sensor won't read. Only the app's own
 * screens wait for it: the block screen is how you get through a block and isn't behind it, and
 * the service, the Quick Settings tile and sync carry on regardless.
 *
 * On by default. A phone with no screen lock has nothing to check against, so there the lock
 * stays out of the way rather than shutting you out of your blocks. Android 9 or later: older
 * versions have no system prompt, and the library's own dialog needs an AppCompat theme.
 *
 * Main thread only (the activity's lifecycle and the Settings switch).
 */
class AppLock(context: Context) {
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences("lock", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Not yet past the fingerprint step. Every new process starts here. */
    private var locked = true

    /** Whether the lock screen is up; MainActivity shows it instead of the app while it is. */
    private val _showing = MutableStateFlow(_enabled.value)
    val showing: StateFlow<Boolean> = _showing.asStateFlow()

    /** `elapsedRealtime` when the app went out of sight; null while it's in front. */
    private var hiddenAtMs: Long? = null

    enum class Method { FINGERPRINT, PIN, NONE }

    /** What the prompt asks for on this phone: NONE when there's nothing to ask. */
    fun method(): Method {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return Method.NONE
        val biometrics = BiometricManager.from(context)
        return when {
            // Any biometric the prompt can use, a strong one included.
            biometrics.canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS -> Method.FINGERPRINT
            biometrics.canAuthenticate(authenticators()) == BiometricManager.BIOMETRIC_SUCCESS -> Method.PIN
            else -> Method.NONE
        }
    }

    /**
     * What the prompt accepts: the fingerprint, or the screen-lock PIN for a finger the sensor won't
     * read. Where Android rates the sensor strong, only it counts, not a weaker face unlock beside
     * it; a phone that rates its fingerprint weak still gets to use it. Android 9–10 can't pair
     * "strong only" with the PIN.
     */
    fun authenticators(): Int {
        val strong = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        return (if (strong) BIOMETRIC_STRONG else BIOMETRIC_WEAK) or DEVICE_CREDENTIAL
    }

    fun setEnabled(on: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, on) }
        _enabled.value = on
        // Switched from inside the app, so you're already past it: it starts at the next return.
        locked = false
        refresh()
    }

    fun onHidden() {
        hiddenAtMs = SystemClock.elapsedRealtime()
    }

    fun onShown() {
        val awayMs = hiddenAtMs?.let { SystemClock.elapsedRealtime() - it }
        hiddenAtMs = null
        if (awayMs != null && awayMs > RELOCK_AFTER_MS) locked = true
        refresh()
    }

    fun unlock() {
        locked = false
        refresh()
    }

    private fun refresh() {
        _showing.value = _enabled.value && locked && method() != Method.NONE
    }

    companion object {
        /** Long enough to grant a permission in system Settings and come back without a prompt. */
        const val RELOCK_AFTER_MS = 30_000L

        private const val KEY_ENABLED = "enabled"
    }
}
