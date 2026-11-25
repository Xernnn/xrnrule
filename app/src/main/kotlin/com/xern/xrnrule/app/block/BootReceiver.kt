package com.xern.xrnrule.app.block

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xern.xrnrule.app.data.OemAutostart

/**
 * Restarts the enforcement service after reboot or an app update. The engine rebuilds state
 * from storage and re-applies the shield before any UI is shown (I8). Phase B adds the
 * WorkManager watchdog and OEM autostart guidance.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            -> BlockerForegroundService.start(context)
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // HyperOS turned it off with this install: say so now, whether or not the service gets going (S1).
                if (OemAutostart.enabled(context) == false) Notifications(context).also { it.createChannels() }.autostartOff()
                // Where the service can't see autostart for itself, one reminder per update.
                if (OemAutostart.isKnownOem() && OemAutostart.enabled(context) == null) {
                    Notifications(context).alert(
                        Notifications.ALERT_AUTOSTART_OFF,
                        "Check autostart",
                        "Updates can turn it off, and then xrnrule may not come back if it's stopped. Tap to check.",
                        tap = OemAutostart.fixIt(context),
                    )
                }
                BlockerForegroundService.start(context)
            }
        }
    }
}
