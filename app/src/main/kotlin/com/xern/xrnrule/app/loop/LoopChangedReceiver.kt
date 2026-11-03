package com.xern.xrnrule.app.loop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xern.xrnrule.app.XrnruleApp

/**
 * Loop's "habits changed" push. Declared in the manifest, so it also wakes a dead process; only
 * senders holding [LoopContract.PERMISSION] (our Loop build) can reach it.
 */
class LoopChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LoopContract.ACTION_HABITS_CHANGED) return
        val pending = goAsync()
        (context.applicationContext as XrnruleApp).container.loop.changed(onDone = pending::finish)
    }
}
