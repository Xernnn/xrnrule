package com.xern.xrnrule.desktop

/**
 * Which browser in front gets past the site blocks (contract I49). Pure, so it can be tested
 * without a desktop: [Foreground] feeds it what's in front and what the extension says.
 *
 * A browser the extension has never spoken from goes after [OTHER_GRACE_MS] in front, once some
 * browser is known to have it. One that has it goes only after [SILENT_GRACE_MS] of silence:
 * it's the extension turned off, and that's long enough to turn it back on from the extensions
 * page, which every new window gets again.
 */
class BrowserWatch(known: Set<String> = emptySet(), startedAtMs: Long = 0) {
    private val browsers = known.toMutableSet()
    private var front: String? = null
    private var frontSince = 0L
    private var lastHeard = startedAtMs

    val known: Set<String> get() = browsers

    /** [app] came in front; null, or not a browser, ends the watch. */
    fun front(app: String?, browser: Boolean, nowMs: Long) {
        val next = app.takeIf { browser }
        if (next != front) frontSince = nowMs
        front = next
    }

    /** Anything from the extension or a block page: it's alive. */
    fun heard(nowMs: Long) {
        lastHeard = nowMs
    }

    /**
     * The extension spoke from the focused window. Returns whether that made this browser known.
     * A beat in the first moments after switching can still be the last window's, and a browser
     * wrongly marked as having the extension would stay open for good.
     */
    fun spoke(nowMs: Long): Boolean {
        heard(nowMs)
        return nowMs - frontSince >= SETTLE_MS && front?.let(browsers::add) == true
    }

    fun silentFor(nowMs: Long): Long = nowMs - maxOf(lastHeard, frontSince)

    /** The browser to close now, if any. */
    fun toClose(nowMs: Long): String? {
        val app = front ?: return null
        if (browsers.isEmpty()) return null
        val due = if (app in browsers) silentFor(nowMs) >= SILENT_GRACE_MS else nowMs - frontSince >= OTHER_GRACE_MS
        return app.takeIf { due }
    }

    companion object {
        const val SETTLE_MS = 3_000L
        const val OTHER_GRACE_MS = 10_000L
        const val SILENT_GRACE_MS = 120_000L
    }
}
