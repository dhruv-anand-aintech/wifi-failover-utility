package com.wififailover.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.wififailover.app.worker.WiFiFailoverWorker
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class BootCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootCompleteReceiver", "Boot completed, scheduling WiFi failover worker")
            scheduleWiFiFailoverWorker(context)
        }
    }

    private fun scheduleWiFiFailoverWorker(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<WiFiFailoverWorker>(
            2, TimeUnit.MINUTES  // Default interval - can be customized
        )
            .setConstraints(constraints)
            .setBackoffPolicy(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "wifi_failover_monitor",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )

        Log.d("BootCompleteReceiver", "WiFi failover worker scheduled")
    }
}
