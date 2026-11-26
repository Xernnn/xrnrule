package com.xern.xrnrule.app

import android.app.Application
import com.xern.xrnrule.app.block.BlockerForegroundService
import com.xern.xrnrule.app.block.CornerClockLook
import com.xern.xrnrule.app.block.ServiceWatchdogWorker
import com.xern.xrnrule.app.data.AppContainer
import com.xern.xrnrule.app.loop.LoopLinks
import com.xern.xrnrule.app.ui.theme.Appearance
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Entry point. Manual DI (a 3-screen app does not need Hilt).
 */
class XrnruleApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Appearance.init(this)
        CornerClockLook.init(this)
        container = AppContainer(this)
        // xrnloops' habit list shows what each habit unlocks here; tell it when that changes.
        LoopLinks.start(this)
        // The engine's core loop lives in the service so it survives the UI being gone.
        BlockerForegroundService.start(this)
        scheduleWatchdog()
    }

    /** Survival backstop: see [ServiceWatchdogWorker]. */
    private fun scheduleWatchdog() {
        val request = PeriodicWorkRequestBuilder<ServiceWatchdogWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            ServiceWatchdogWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
