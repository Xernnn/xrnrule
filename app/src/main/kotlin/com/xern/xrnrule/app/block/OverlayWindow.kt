package com.xern.xrnrule.app.block

/**
 * One overlay window that is up or not, for [SettingsGuardOverlay]. Putting it up can fail:
 * without "Display over other apps", which an update can take away, `WindowManager.addView`
 * throws. Thrown out of the service's render loop, that took the service down every time
 * Settings came to the front, and it came back to do it again. Here a window that can't go up
 * is let go and asked for again at the next [show]. Pure, so it can be tested without a phone.
 */
class OverlayWindow<W : Any>(
    private val make: () -> W,
    private val add: (W) -> Unit,
    private val remove: (W) -> Unit,
    private val release: (W) -> Unit,
) {
    var window: W? = null
        private set

    /** Puts the window up unless it is; false when it can't go up. */
    fun show(): Boolean {
        if (window != null) return true
        val w = make()
        if (runCatching { add(w) }.isFailure) {
            release(w)
            return false
        }
        window = w
        return true
    }

    fun hide() {
        val w = window ?: return
        window = null
        runCatching { remove(w) }
        release(w)
    }
}
