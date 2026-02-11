package com.wififailover.app.worker

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wififailover.app.api.WorkerApi
import com.wififailover.app.data.Preferences
import com.wififailover.app.service.HotspotService

class WiFiFailoverWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val preferences = Preferences(context)
    private val hotspotService = HotspotService(context)
    private val sharedPrefs: SharedPreferences = context.getSharedPreferences("wifi_failover_worker", Context.MODE_PRIVATE)

    companion object {
        private const val OFFLINE_COUNT_KEY = "daemon_offline_count"
        private const val OFFLINE_THRESHOLD = 2  // Enable hotspot after 2 consecutive offline checks (~10 seconds)
    }

    override suspend fun doWork(): Result {
        return try {
            val url = preferences.workerUrl
            val secret = preferences.workerSecret

            if (url.isEmpty() || secret.isEmpty()) {
                return Result.retry()
            }

            val api = WorkerApi.create(url)

            // Check daemon status from Worker
            val status = api.getStatus(secret)

            when (status.daemon_status) {
                "online" -> {
                    // Daemon is online, reset counter
                    sharedPrefs.edit().putInt(OFFLINE_COUNT_KEY, 0).apply()
                }
                "paused" -> {
                    // Daemon is paused (screen locked/sleeping), reset counter
                    // Don't trigger failover when computer is asleep
                    sharedPrefs.edit().putInt(OFFLINE_COUNT_KEY, 0).apply()
                }
                "offline" -> {
                    // Daemon is offline, increment counter
                    val offlineCount = sharedPrefs.getInt(OFFLINE_COUNT_KEY, 0) + 1
                    sharedPrefs.edit().putInt(OFFLINE_COUNT_KEY, offlineCount).apply()

                    if (offlineCount >= OFFLINE_THRESHOLD) {
                        // Enable hotspot when daemon is offline
                        val hotspotEnabled = hotspotService.enableHotspot()
                        if (hotspotEnabled) {
                            showNotification("WiFi Failover", "Daemon offline - hotspot enabled")
                        } else {
                            showNotification("WiFi Failover", "Daemon offline - enable hotspot manually")
                        }
                    }
                }
            }

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            // Treat failures to contact the Worker as daemon being offline
            // This handles cases where daemon is offline (can't send heartbeats to Worker)
            val offlineCount = sharedPrefs.getInt(OFFLINE_COUNT_KEY, 0) + 1
            sharedPrefs.edit().putInt(OFFLINE_COUNT_KEY, offlineCount).apply()

            if (offlineCount >= OFFLINE_THRESHOLD) {
                val hotspotEnabled = hotspotService.enableHotspot()
                if (hotspotEnabled) {
                    showNotification("WiFi Failover", "Daemon offline - hotspot enabled")
                } else {
                    showNotification("WiFi Failover", "Daemon offline - enable hotspot manually")
                }
            }

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
