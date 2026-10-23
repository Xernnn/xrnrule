package com.xern.xrnrule.app.web

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
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

    /** `elapsedRealtime` of the extension's last call of any kind; 0 before the first. */
    @Volatile
    var lastHeardElapsed: Long = 0
        private set

    @Volatile
    private var reportedAtElapsed: Long = 0

    fun heard() {
        lastHeardElapsed = SystemClock.elapsedRealtime()
    }

    /** The extension's report: [host] in front, or null for a page that isn't a website. */
    fun report(host: String?) {
        heard()
        reportedAtElapsed = lastHeardElapsed
        _current.value = host
    }

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

    override fun isSupported() = true

    override fun isEnabled(context: Context) = lastHeardWall > 0 || SiteSightings.lastHeardElapsed > 0

    override fun settingsIntent(): Intent =
        (extensionBrowser ?: QUETTA).let { context.packageManager.getLaunchIntentForPackage(it) }
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Xernnn/xrnrule#the-browser-extension"))

    private companion object {
        const val KEY_BROWSER = "browser"
        const val KEY_HEARD = "heardAt"
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
