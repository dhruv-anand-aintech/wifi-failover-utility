package com.wififailover.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.wififailover.app.data.Preferences
import com.wififailover.app.worker.WiFiFailoverWorker
import java.util.concurrent.TimeUnit

class BootCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val preferences = Preferences(context)

            if (preferences.monitoringEnabled && preferences.isConfigured()) {
                schedulePollingWork(context)
            }
        }
    }

    private fun schedulePollingWork(context: Context) {
        val workRequest = PeriodicWorkRequestBuilder<WiFiFailoverWorker>(
            10,
            TimeUnit.SECONDS
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "wifi_failover_polling",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }
}
