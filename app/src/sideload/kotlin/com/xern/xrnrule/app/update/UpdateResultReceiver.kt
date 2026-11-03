package com.xern.xrnrule.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.IntentCompat
import com.xern.xrnrule.app.XrnruleApp

/**
 * Where [PackageInstaller] says how an update went. Not exported: only the installer, through the
 * PendingIntent [sender] makes, can reach it, so the confirmation intent it carries is the system's.
 */
class UpdateResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val updater = (context.applicationContext as XrnruleApp).container.updates as? ApkUpdater ?: return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let(updater::needsConfirmation)
            PackageInstaller.STATUS_SUCCESS -> updater.installSucceeded()
            else -> updater.installFailed(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }

    companion object {
        fun sender(context: Context, sessionId: Int): IntentSender = PendingIntent.getBroadcast(
            context,
            sessionId,
            Intent(context, UpdateResultReceiver::class.java),
            // Mutable: the installer writes the result into it.
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
        ).intentSender
    }
}
