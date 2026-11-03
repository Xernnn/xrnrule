package com.xern.xrnrule.app.update

import android.content.Context
import com.xern.xrnrule.app.data.SyncSettings
import kotlinx.coroutines.CoroutineScope

/** `sideload` flavor: new versions come from the home server; see [ApkUpdater]. */
fun createAppUpdates(context: Context, sync: SyncSettings, scope: CoroutineScope): AppUpdates = ApkUpdater(context, sync, scope)
