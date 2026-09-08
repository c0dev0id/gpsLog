package de.codevoid.gpslog.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.codevoid.gpslog.App

/** Resumes an interrupted run after a device reboot, if one was active when the device went down. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (App.from(context).settings.activeRunId() != null) {
            LoggingService.resume(context)
        }
    }
}
