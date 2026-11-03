package com.xern.xrnrule.app.update

import android.content.Context
import com.xern.xrnrule.app.data.SyncSettings
import kotlinx.coroutines.CoroutineScope

/** `play` flavor: the store delivers updates, and its policy forbids an app replacing itself. */
@Suppress("UNUSED_PARAMETER")
fun createAppUpdates(context: Context, sync: SyncSettings, scope: CoroutineScope): AppUpdates = NoopAppUpdates
