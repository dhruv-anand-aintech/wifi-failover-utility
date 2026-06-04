package com.wififailover.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wififailover.app.data.Preferences
import com.wififailover.app.service.LocalControlService

class BootCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val preferences = Preferences(context)

            if (preferences.monitoringEnabled && preferences.isConfigured()) {
                LocalControlService.start(context)
            }
        }
    }
}
