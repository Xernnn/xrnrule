package com.xern.xrnrule.desktop

/**
 * Which browser in front gets past the site blocks (contract I49). Pure, so it can be tested
 * without a desktop: [Foreground] feeds it what's in front and what the extension says.
 *
 * A browser the extension has never spoken from goes after [OTHER_GRACE_MS] in front, once some
 * browser is known to have it. One that has it goes only after [SILENT_GRACE_MS] of silence in
 * front: it's the extension turned off, and that's long enough to turn it back on from the
 * extensions page. The silence adds up across stays in front, until the extension speaks again:
 * stepping away and back, or opening the browser again after it was closed, doesn't start the
 * two minutes again; once they're used, each return gets [OTHER_GRACE_MS]. Silence is about the
 * window in front: a private window the extension may not run in, or another profile's window, is
 * silent however much the extension says from the windows behind it ([Foreground] only calls
 * [heard] for what speaks for the front).
 */
class BrowserWatch(known: Set<String> = emptySet(), startedAtMs: Long = 0) {
    private val browsers = known.toMutableSet()
    private var front: String? = null
    private var frontSince = 0L
    private var lastHeard = startedAtMs
    /** The silence of earlier stays in front since the extension last spoke for the front. */
    private var silentBefore = 0L
    /** The current stay's silence counts from here at the earliest: the laptop woke up. */
    private var countFrom = startedAtMs

    val known: Set<String>
        @Synchronized get() = browsers.toSet()

    /** [app] came in front; null, or not a browser, ends the watch. */
    @Synchronized
    fun front(app: String?, browser: Boolean, nowMs: Long) {
        val next = app.takeIf { browser }
        if (next != front) {
            silentBefore += stay(nowMs)
            frontSince = nowMs
        }
        front = next
    }

    /** Anything from the extension, or a block page in front: it's alive. */
    @Synchronized
    fun heard(nowMs: Long) {
        lastHeard = nowMs
        silentBefore = 0
    }

    /**
     * The laptop slept from [sleptAtMs] to [nowMs]: that isn't silence, but what was silent before
     * it still counts (a sleep is no way to start the two minutes again).
     */
    @Synchronized
    fun woke(sleptAtMs: Long, nowMs: Long) {
        silentBefore += stay(sleptAtMs)
        countFrom = nowMs
    }

    /** The silence of the current stay in front, up to [nowMs]. */
    private fun stay(nowMs: Long): Long =
        if (front == null) 0 else (nowMs - maxOf(lastHeard, frontSince, countFrom)).coerceAtLeast(0)

    /**
     * The extension spoke from the focused window. Returns whether that made this browser known.
     * A beat in the first moments after switching can still be the last window's, and a browser
     * wrongly marked as having the extension would stay open for good.
     */
    @Synchronized
    fun spoke(nowMs: Long): Boolean {
        heard(nowMs)
        return nowMs - frontSince >= SETTLE_MS && front?.let(browsers::add) == true
    }

    /** How long the extension has been silent for the browser in front, earlier stays included. */
    @Synchronized
    fun silentFor(nowMs: Long): Long = silentBefore + stay(nowMs)

    /** The browser to close now, if any. */
    @Synchronized
    fun toClose(nowMs: Long): String? {
        val app = front ?: return null
        if (browsers.isEmpty()) return null
        val inFront = nowMs - frontSince >= OTHER_GRACE_MS
        val due = if (app in browsers) inFront && silentFor(nowMs) >= SILENT_GRACE_MS else inFront
        return app.takeIf { due }
    }

    companion object {
        const val SETTLE_MS = 3_000L
        const val OTHER_GRACE_MS = 10_000L
        const val SILENT_GRACE_MS = 120_000L
    }
}
