package com.xern.xrnrule.app.update

import android.content.Context
import com.xern.xrnrule.app.data.SyncSettings

/** `sideload` flavor: new versions come from the home server; see [ApkUpdater]. */
fun createAppUpdates(context: Context, sync: SyncSettings): AppUpdates = ApkUpdater(context, sync)
