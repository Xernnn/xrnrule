package com.xern.xrnrule.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** [category] is the app's own `ApplicationInfo.category` (CATEGORY_UNDEFINED = -1 when it names none). */
data class InstalledApp(val packageName: String, val label: String, val category: Int = -1)

/**
 * Launchable apps a block could target — the same universe a home-screen launcher would show.
 * Queried fresh on demand (no caching): the create-block flow is opened rarely enough that a
 * live [PackageManager] query is cheap compared to keeping a list in sync with installs.
 */
object InstalledApps {

    /** Best-effort display name for a single package; falls back to the package id. */
    fun labelFor(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    fun launchable(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return resolveInfos
            .asSequence()
            .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.activityInfo.applicationInfo.category) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /**
     * The [packages] this phone has, in the order of their names in [labels] (the launchable apps
     * by package). An app that was uninstalled stays in its block, so installing it again doesn't
     * open it (contract §2.3: taking an app off a block is a loosening edit), but the block isn't
     * drawn with it.
     */
    fun onThisPhone(packages: Collection<String>, labels: Map<String, String>): List<String> =
        packages.filter { it in labels }.sortedBy { labels.getValue(it).lowercase() }

    /** The [picked] packages that aren't among [apps]: uninstalled since, or only on another phone. */
    fun notOnThisPhone(picked: Collection<String>, apps: List<InstalledApp>): List<String> {
        val here = apps.mapTo(mutableSetOf()) { it.packageName }
        return picked.filter { it !in here }.sorted()
    }
}
