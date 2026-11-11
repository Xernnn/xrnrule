package com.xern.xrnrule.app.loop

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import com.xern.xrnrule.app.data.HabitSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
 * xrnloops, the habit tracker (our Loop fork, `../xrnloops`), next to xrnrule the blocker. Both
 * read and write the same habit server (contract §2.5), so xrnrule never reads xrnloops itself:
 * when xrnloops says a habit changed ([LoopChangedReceiver]), it is asked to send that to the
 * server, and then [HabitSync] reads it back from there. Every [CHECK_MS] it is also asked to
 * sync ([check]), so an xrnloops that has gone quiet is noticed (an alert) even when nothing is
 * ticked: MIUI turns a reinstalled app's autostart off, and then xrnloops neither syncs in the
 * background nor answers.
 */
class LoopHabits(
    private val context: Context,
    private val habits: HabitSync,
    private val scope: CoroutineScope,
) {
    /** When xrnloops' own sync with the home server started failing, for an alert; null while it works. */
    private val _serverFailingSince = MutableStateFlow<Long?>(null)
    val serverFailingSince: StateFlow<Long?> = _serverFailingSince.asStateFlow()

    /** xrnloops is installed but didn't answer the last time it was asked to sync (MIUI's autostart, most likely). */
    private val _unreachable = MutableStateFlow(false)
    val unreachable: StateFlow<Boolean> = _unreachable.asStateFlow()

    @Volatile
    private var lastCheck = 0L

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
        val round = scope.launch(Dispatchers.IO) {
            pushFromLoop()
            habits.request(0)?.join()
        }
        scope.finishWithin(BROADCAST_MS, round, onDone)
    }

    /**
     * Asks xrnloops to sync with the home server now (it waits up to ~5 s), instead of whenever
     * it would have. Older builds, or one with sync off, answer null or `synced = false`.
     */
    private fun pushFromLoop() {
        if (status() != LoopStatus.READY) {
            _unreachable.value = false
            _serverFailingSince.value = null
            return
        }
        val uri = Uri.Builder().scheme("content").authority(LoopContract.AUTHORITY).build()
        val result = runCatching { context.contentResolver.call(uri, LoopContract.METHOD_SYNC, null, null) }.getOrNull()
        val configured = result?.getBoolean(LoopContract.KEY_CONFIGURED) == true
        val synced = result?.getBoolean(LoopContract.KEY_SYNCED) == true
        // No answer at all counts as failing: our builds always answer, unless they can't be started.
        _unreachable.value = result == null
        val fine = result != null && (!configured || synced)
        _serverFailingSince.value = if (fine) null else _serverFailingSince.value ?: System.currentTimeMillis()
    }

    /** From the service's health check: asks xrnloops to sync at most every [CHECK_MS]. */
    fun check() {
        val now = SystemClock.elapsedRealtime()
        if (lastCheck != 0L && now - lastCheck < CHECK_MS) return
        lastCheck = now
        scope.launch(Dispatchers.IO) { pushFromLoop() }
    }

    private fun isInstalled(): Boolean =
        runCatching { context.packageManager.getPackageInfo(LoopContract.LOOP_PACKAGE, 0) }.isSuccess

    private companion object {
        const val CHECK_MS = 30 * 60 * 1000L

        /** Under the 10 s Android gives a broadcast receiver that went async. */
        const val BROADCAST_MS = 8_000L
    }
}

/**
 * Calls [onDone] once [work] is over, or after [withinMs] if it isn't by then; [work] goes on
 * either way. A receiver that went async ([android.content.BroadcastReceiver.goAsync]) must be
 * finished in time, or Android takes the app for hung, and a habit server that takes the
 * connection but never answers holds a round a quarter of a minute per call.
 */
internal fun CoroutineScope.finishWithin(withinMs: Long, work: Job, onDone: () -> Unit) {
    launch {
        try {
            withTimeoutOrNull(withinMs) { work.join() }
        } finally {
            onDone()
        }
    }
}
