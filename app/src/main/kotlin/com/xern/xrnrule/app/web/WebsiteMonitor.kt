package com.xern.xrnrule.app.web

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import com.xern.xrnrule.domain.ExtensionApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The site the xrnrule browser extension (in Quetta) last said is in front, handed from
 * [ExtensionServer] to the blocker service. The extension only speaks from a visible page, every
 * 2.5 s; a page that stops (closed, the browser sent to the background) is forgotten after
 * [FRESH_MS], so a site doesn't linger in front of a different screen.
 */
object SiteSightings {
    const val FRESH_MS = 6_000L

    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current.asStateFlow()

    /**
     * `elapsedRealtime` of the extension's last call of any kind. Starts at process start, so a
     * fresh process gives the extension the usual time to check in before calling it silent.
     */
    @Volatile
    var lastHeardElapsed: Long = SystemClock.elapsedRealtime()
        private set

    /** Whether the extension has called this process at all. */
    @Volatile
    var everHeard: Boolean = false
        private set

    /** `elapsedRealtime` of the last report of what's in front, and whether it named a website. */
    @Volatile
    var reportedAtElapsed: Long = 0
        private set

    @Volatile
    var lastReportWasSite: Boolean = false
        private set

    fun heard() {
        lastHeardElapsed = SystemClock.elapsedRealtime()
        everHeard = true
    }

    /** The site you chose to leave on the block screen, and when (`elapsedRealtime`). */
    @Volatile
    private var leaving: Pair<String, Long>? = null

    /**
     * The block screen's "Leave this site": the screen goes at once, and the extension's
     * stand-in page for [site] is told to step back, or close its tab, the next time it reports.
     * You stay in the browser, instead of being sent home with the blocked tab still in front.
     */
    fun leave(site: String) {
        leaving = site to SystemClock.elapsedRealtime()
        _current.value = null
    }

    /**
     * The extension's report: [host] in front, or null for a page that isn't a website.
     * [standIn]: from its block page, standing in for [host]. Returns whether that page should
     * leave (see [leave]).
     */
    fun report(host: String?, standIn: Boolean): Boolean {
        heard()
        reportedAtElapsed = lastHeardElapsed
        lastReportWasSite = host != null
        val left = leaving?.takeIf { (site, at) -> site == host && lastHeardElapsed - at < LEAVE_MS }
        if (left == null) {
            _current.value = host
            return false
        }
        // The site doesn't come back in front while its tab steps off it. Each stand-in told to
        // leave starts the wait again, so a step back onto another one for the site leaves too.
        _current.value = null
        if (standIn) leaving = left.first to lastHeardElapsed
        return standIn
    }

    /**
     * Whether a browser in front means the extension has gone quiet. A website's page reports
     * every 2.5 s, so silence after one is a problem within [SITE_SILENCE_MS]; on any other page
     * the extension only checks in once a minute, so only [EXTENSION_SILENCE_MS] of nothing is.
     */
    fun silent(nowElapsed: Long): Boolean =
        nowElapsed - lastHeardElapsed >= EXTENSION_SILENCE_MS ||
            (lastReportWasSite && nowElapsed - reportedAtElapsed >= SITE_SILENCE_MS)

    private const val SITE_SILENCE_MS = 15_000L
    /** How long a site you left stays out of front while its tab steps off it. */
    private const val LEAVE_MS = 4_000L
    private const val EXTENSION_SILENCE_MS = 90_000L

    /** Called every second by [ExtensionServer]. */
    fun expire() {
        if (_current.value != null && SystemClock.elapsedRealtime() - reportedAtElapsed > FRESH_MS) _current.value = null
    }
}

/**
 * Websites are blocked through the xrnrule extension in the phone's browser, not by reading the
 * screen: nothing here needs Accessibility, which banking apps refuse to run next to, and which
 * Android 17's Advanced Protection takes away. Apps are still blocked through usage access.
 */
interface WebsiteMonitor {
    fun isSupported(): Boolean

    /** Whether the extension has ever called this app. */
    fun isEnabled(context: Context): Boolean

    /** Where to go to set it up: the browser that has (or should have) the extension. */
    fun settingsIntent(): Intent

    /** The extension's version, as it last said. */
    val version: String? get() = null

    /** The version this app was built with, which the home server hands out. */
    val latestVersion: String? get() = null
}

/** Whether the extension is older than the one this app was built with. */
val WebsiteMonitor.outOfDate: Boolean
    get() {
        val have = version ?: return false
        val latest = latestVersion ?: return false
        return ExtensionApi.isOlder(have, latest)
    }

enum class WebsiteTracking { OFF, NOT_RUNNING, ON }

/**
 * [WebsiteTracking.NOT_RUNNING] is the silent failure worth surfacing: the extension has
 * answered before, but a browser was in front lately and it said nothing (turned off, removed,
 * or a browser without it).
 */
fun WebsiteMonitor.tracking(context: Context): WebsiteTracking = when {
    !isEnabled(context) -> WebsiteTracking.OFF
    this is ExtensionWebsiteMonitor && quiet -> WebsiteTracking.NOT_RUNNING
    else -> WebsiteTracking.ON
}

class ExtensionWebsiteMonitor(private val context: Context) : WebsiteMonitor {
    private val prefs = context.getSharedPreferences("extension", Context.MODE_PRIVATE)

    /** Set by the blocker service: a browser has been in front a while and the extension is silent. */
    @Volatile
    var quiet: Boolean = false

    /** The browser the extension lives in, learned from which browser is in front when it calls. */
    var extensionBrowser: String?
        get() = prefs.getString(KEY_BROWSER, null)
        set(value) {
            if (value != null && value != extensionBrowser) prefs.edit().putString(KEY_BROWSER, value).apply()
        }

    /** Wall time the extension last called, saved at most once a minute. */
    val lastHeardWall: Long get() = prefs.getLong(KEY_HEARD, 0)

    fun onHeard() {
        val now = System.currentTimeMillis()
        if (now - lastHeardWall > 60_000) prefs.edit().putLong(KEY_HEARD, now).apply()
    }

    /** The version the extension last said it is (versions up to 2.1.1 don't say). */
    override val version: String? get() = prefs.getString(KEY_VERSION, null)

    fun onVersion(value: String) {
        if (value.length <= 20 && value != version) prefs.edit().putString(KEY_VERSION, value).apply()
    }

    /** The extension this app was built with: what the home server should be handing out. */
    override val latestVersion: String? by lazy {
        runCatching {
            val text = context.assets.open("web/manifest.json").use { it.readBytes().decodeToString() }
            Regex("\"version\"\\s*:\\s*\"([0-9.]+)\"").find(text)?.groupValues?.get(1)
        }.getOrNull()
    }

    override fun isSupported() = true

    override fun isEnabled(context: Context) = lastHeardWall > 0 || SiteSightings.everHeard

    override fun settingsIntent(): Intent =
        (extensionBrowser ?: QUETTA).let { context.packageManager.getLaunchIntentForPackage(it) }
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Xernnn/xrnrule#the-browser-extension"))

    private companion object {
        const val KEY_BROWSER = "browser"
        const val KEY_HEARD = "heardAt"
        const val KEY_VERSION = "version"
        const val QUETTA = "net.quetta.browser"
    }
}

/** Which apps are web browsers: whatever opens an https link, plus the common ones by name. */
object Browsers {
    private val KNOWN = setOf(
        "com.android.chrome",
        "net.quetta.browser",
        "org.mozilla.firefox",
        "com.brave.browser",
        "com.microsoft.emmx",
        "com.sec.android.app.sbrowser",
        "com.opera.browser",
        "com.duckduckgo.mobile.android",
        "com.mi.globalbrowser",
        "com.vivaldi.browser",
    )

    @Volatile
    private var found: Set<String>? = null

    fun isBrowser(context: Context, pkg: String): Boolean = pkg in KNOWN || pkg in all(context)

    private fun all(context: Context): Set<String> = found ?: runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapTo(mutableSetOf()) { it.activityInfo.packageName }
    }.getOrDefault(emptySet()).also { found = it }
}
