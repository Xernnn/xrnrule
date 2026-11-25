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

/**
 * The block screen's window. It used to be made, added and composed afresh for every showing,
 * and removed after: the blocked app showed through until the new window's first frame. Now it
 * is made and added once and then [reveal]ed and hidden (a hidden root view takes the window off
 * the screen and out of touch, as `Dialog.hide()` does), so a showing only draws what's already
 * composed. A window the OS took away ([lost]) is made again at the next [show]; one that can't
 * go up is let go and asked for again, as with [OverlayWindow]. Pure, so it's tested without a phone.
 */
class ShieldWindow<W : Any>(
    private val make: () -> W,
    private val add: (W) -> Unit,
    private val remove: (W) -> Unit,
    private val release: (W) -> Unit,
    private val reveal: (W, Boolean) -> Unit,
    private val lost: (W) -> Boolean = { false },
) {
    var window: W? = null
        private set

    var shown: Boolean = false
        private set

    /** Makes and adds the window, hidden, unless there is one; false when it can't go up. */
    fun prepare(): Boolean {
        window?.takeIf(lost)?.let { dispose() }
        if (window != null) return true
        val made = make()
        if (runCatching { add(made) }.isFailure) {
            release(made)
            return false
        }
        window = made
        shown = false
        return true
    }

    /** Shows the window, making it first if there's none; false when it can't go up. */
    fun show(): Boolean {
        if (!prepare()) return false
        val w = window ?: return false
        if (!shown) {
            reveal(w, true)
            shown = true
        }
        return true
    }

    /** Off the screen, kept for the next showing. */
    fun hide() {
        val w = window ?: return
        if (!shown) return
        shown = false
        runCatching { reveal(w, false) }
    }

    /** Gone for good (the service stopping, or the OS took it). */
    fun dispose() {
        val w = window ?: return
        window = null
        shown = false
        runCatching { remove(w) }
        release(w)
    }
}

/**
 * The app in front as the block screen goes by it: while the screen is off, the one that was in
 * front when it went off. It's still there under the lock screen, and dropping the block screen
 * then showed the blocked app at every unlock, until the next look found it in front again.
 */
class HeldWhileAsleep<T>(initial: T) {
    var value: T = initial
        private set

    /** [now] while [awake]; what was in front before the screen went off, while it is. */
    fun of(awake: Boolean, now: T): T {
        if (awake) value = now
        return value
    }
}
