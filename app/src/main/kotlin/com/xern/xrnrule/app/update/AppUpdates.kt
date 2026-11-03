package com.xern.xrnrule.app.update

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How the last check or install went, for Settings. */
data class UpdateStatus(val atWallMs: Long, val ok: Boolean, val message: String)

/**
 * New versions of the app itself, from the home server's `/update/` (published by
 * `scripts/release.mjs app`). Real in the `sideload` flavor (`ApkUpdater`); a Play build updates
 * through the store, whose policy forbids an app updating itself, so there it does nothing.
 */
interface AppUpdates {
    val isSupported: Boolean

    /** The installed version and build, e.g. "0.1.0 (5)". */
    val installedVersion: String

    /** The last check or install; null before the first. */
    val status: StateFlow<UpdateStatus?>

    /** Whether Android lets xrnrule install apps ("Install unknown apps"), which an update needs. */
    fun canInstall(): Boolean

    /** The system screen that allows it. */
    fun installPermissionIntent(): Intent

    /** Looks now and installs a newer version straight away (Settings → Check now). */
    suspend fun checkNow()

    /** Starts the background checks. Called once per process. */
    fun start()

    /** The screen went off: the moment a waiting update installs. */
    fun onScreenOff()
}

object NoopAppUpdates : AppUpdates {
    override val isSupported = false
    override val installedVersion = ""
    override val status: StateFlow<UpdateStatus?> = MutableStateFlow(null)
    override fun canInstall() = false
    override fun installPermissionIntent() = Intent()
    override suspend fun checkNow() = Unit
    override fun start() = Unit
    override fun onScreenOff() = Unit
}
