package com.wififailover.app.worker

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wififailover.app.R
import com.wififailover.app.api.WorkerApi
import com.wififailover.app.api.AcknowledgeRequest
import com.wififailover.app.data.Preferences
import kotlinx.coroutines.delay

class WiFiFailoverWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val preferences = Preferences(context)

    override suspend fun doWork(): Result {
        return try {
            val url = preferences.workerUrl
            val secret = preferences.workerSecret

            if (url.isEmpty() || secret.isEmpty()) {
                return Result.retry()
            }

            val api = WorkerApi.create(url)

            // Check status from Worker
            val status = api.getStatus(secret)

            if (status.enabled) {
                // Hotspot is requested, send acknowledgment
                api.acknowledge(AcknowledgeRequest(secret))

                // Show notification
                showNotification("WiFi Failover Active", "Hotspot enabled on your phone")
            }

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }

    private fun showNotification(title: String, message: String) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notification = NotificationCompat.Builder(applicationContext, "wifi_failover_alert")
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1001, notification)
    }
}
