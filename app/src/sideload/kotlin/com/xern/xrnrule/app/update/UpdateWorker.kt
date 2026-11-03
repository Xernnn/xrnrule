package com.xern.xrnrule.app.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.xern.xrnrule.app.XrnruleApp

/** The periodic look for a new version; see [ApkUpdater.checkInBackground]. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        ((applicationContext as XrnruleApp).container.updates as? ApkUpdater)?.checkInBackground()
        return Result.success()
    }

    companion object {
        const val NAME = "app-update"
    }
}
