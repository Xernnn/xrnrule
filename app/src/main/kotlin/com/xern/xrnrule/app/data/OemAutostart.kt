package com.xern.xrnrule.app.data

import android.app.AppOpsManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity

/**
 * OEM battery managers (Xiaomi/MIUI, Oppo/ColorOS, Vivo, Huawei/EMUI, OnePlus) kill background
 * services that aren't explicitly whitelisted for autostart, regardless of the standard
 * battery-optimization exemption — brainstorm §8 calls this the highest-risk item with "no full
 * programmatic fix". Best effort: deep-link straight to the known autostart-manager screen for
 * the current manufacturer, falling back to the app's details page if that activity isn't
 * present on this particular ROM build. These are explicit-component intents, so they don't
 * need a `<queries>` manifest entry — Android 11+ package-visibility rules gate *querying*
 * other packages, not launching a component you already know by name.
 */
object OemAutostart {

    fun isKnownOem(): Boolean = candidates(Build.MANUFACTURER.lowercase()).isNotEmpty()

    /**
     * Whether autostart is on, or null where the phone won't say. Only Xiaomi's can be read: MIUI
     * and HyperOS keep it as an app op of their own, which Android's public API has no name for.
     * They turn it off at every install, a self-update included.
     */
    fun enabled(context: Context): Boolean? = miuiOp(context, MIUI_AUTOSTART_OP)

    /**
     * HyperOS's "Display pop-up windows while running in the background", or null where the phone
     * won't say. Another app op of its own (10021): off, it silently refuses every screen started
     * from the block screen, so Close can't go home and Focus can't open the timer.
     */
    fun backgroundPopups(context: Context): Boolean? = miuiOp(context, MIUI_BACKGROUND_START_OP)

    private fun miuiOp(context: Context, op: Int): Boolean? {
        if ("xiaomi" !in Build.MANUFACTURER.lowercase()) return null
        return runCatching {
            val check = AppOpsManager::class.java.getMethod("checkOpNoThrow", Int::class.java, Int::class.java, String::class.java)
            opAllows(check.invoke(context.getSystemService(AppOpsManager::class.java), op, Process.myUid(), context.packageName) as? Int)
        }.getOrNull()
    }

    /** An app op's mode as on or off; null for one that leaves it to something else. */
    fun opAllows(mode: Int?): Boolean? = when (mode) {
        AppOpsManager.MODE_ALLOWED -> true
        AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> false
        else -> null
    }

    /** The page with HyperOS's own switches for this app ("Other permissions"), pop-ups in the background among them. */
    fun openOtherPermissions(context: Context) {
        val editor = Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", context.packageName)
        for (intent in listOf(editor, appDetailsIntent(context))) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // The next page.
            } catch (_: SecurityException) {
                // Not exported on this build: the app's details page has the same switches a tap further.
            }
        }
    }

    /** For a notification: the page where autostart is turned on. */
    fun fixIt(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, AutostartActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun open(context: Context) {
        for (candidate in candidates(Build.MANUFACTURER.lowercase())) {
            try {
                context.startActivity(candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // Try the next known component for this manufacturer.
            }
        }
        context.startActivity(appDetailsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun candidates(manufacturer: String): List<Intent> = when {
        "xiaomi" in manufacturer -> listOf(
            component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            component("com.miui.securitycenter", "com.miui.permcenter.MainActivity"),
        )
        "oppo" in manufacturer -> listOf(
            component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        )
        "vivo" in manufacturer -> listOf(
            component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        )
        "huawei" in manufacturer -> listOf(
            component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        )
        "oneplus" in manufacturer -> listOf(
            component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        )
        else -> emptyList()
    }

    private fun component(pkg: String, cls: String) = Intent().apply { component = ComponentName(pkg, cls) }

    private const val MIUI_AUTOSTART_OP = 10008
    private const val MIUI_BACKGROUND_START_OP = 10021
}

/** A notification can only name one page; this tries each known page in turn ([OemAutostart.open]). */
class AutostartActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { OemAutostart.open(this) }
        finish()
    }
}
