package com.xern.xrnrule.app.loop

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.xern.xrnrule.app.data.HabitSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Copy of `org.isoron.uhabits.xrnrule.XrnruleContract` in xrnloops, our Loop fork (`../xrnloops`).
 * The two apps only share these strings; change both together.
 */
object LoopContract {
    const val LOOP_PACKAGE = "com.xern.xrnloops"
    const val AUTHORITY = "$LOOP_PACKAGE.provider"

    /** Signature permission both apps declare: only apps signed with the same key can talk. */
    const val PERMISSION = "com.xern.xrnrule.permission.HABITS"
    const val ACTION_HABITS_CHANGED = "com.xern.xrnrule.action.HABITS_CHANGED"

    /** `call(AUTHORITY, METHOD_SYNC)`: xrnloops syncs with the home server (a few seconds at most). */
    const val METHOD_SYNC = "sync"

    /** In [METHOD_SYNC]'s result: sync is set up in xrnloops, and this attempt worked. */
    const val KEY_CONFIGURED = "configured"
    const val KEY_SYNCED = "synced"
}

enum class LoopStatus { NOT_INSTALLED, NO_ACCESS, READY }

/**
 * xrnloops while both apps are in use (the habit list moved into xrnrule, contract §2.5). Both
 * read and write the same habit server, so xrnrule never reads xrnloops itself: when xrnloops
 * says a habit changed ([LoopChangedReceiver]), it is asked to send that to the server, and then
 * [HabitSync] reads it back from there.
 */
class LoopHabits(
    private val context: Context,
    private val habits: HabitSync,
    private val scope: CoroutineScope,
) {
    /** When xrnloops' own sync with the home server started failing, for an alert; null while it works. */
    private val _serverFailingSince = MutableStateFlow<Long?>(null)
    val serverFailingSince: StateFlow<Long?> = _serverFailingSince.asStateFlow()

    fun status(): LoopStatus = when {
        !isInstalled() -> LoopStatus.NOT_INSTALLED
        context.checkSelfPermission(LoopContract.PERMISSION) != PackageManager.PERMISSION_GRANTED -> LoopStatus.NO_ACCESS
        else -> LoopStatus.READY
    }

    fun launchIntent(): Intent? =
        context.packageManager.getLaunchIntentForPackage(LoopContract.LOOP_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** A tick made in xrnloops: it goes to the habit server, and from there here. */
    fun changed(onDone: () -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            try {
                pushFromLoop()
                habits.request(0)?.join()
            } finally {
                onDone()
            }
        }
    }

    /**
     * Asks xrnloops to sync with the home server now (it waits up to ~5 s), instead of whenever
     * it would have. Older builds, or one with sync off, answer null or `synced = false`.
     */
    private fun pushFromLoop() {
        if (status() != LoopStatus.READY) return
        val uri = Uri.Builder().scheme("content").authority(LoopContract.AUTHORITY).build()
        val result = runCatching { context.contentResolver.call(uri, LoopContract.METHOD_SYNC, null, null) }.getOrNull()
        val configured = result?.getBoolean(LoopContract.KEY_CONFIGURED) == true
        val synced = result?.getBoolean(LoopContract.KEY_SYNCED) == true
        _serverFailingSince.value = if (!configured || synced) null else _serverFailingSince.value ?: System.currentTimeMillis()
    }

    private fun isInstalled(): Boolean =
        runCatching { context.packageManager.getPackageInfo(LoopContract.LOOP_PACKAGE, 0) }.isSuccess
}
