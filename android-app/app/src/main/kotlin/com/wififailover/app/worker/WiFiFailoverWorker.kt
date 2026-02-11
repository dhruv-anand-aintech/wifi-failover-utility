package com.wififailover.app.worker

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
        private const val TAG = "WiFiFailoverWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            val url = preferences.workerUrl
            val secret = preferences.workerSecret

            if (url.isEmpty() || secret.isEmpty()) {
                Log.w(TAG, "Missing Worker URL or secret, retrying...")
                return Result.retry()
            }

            Log.d(TAG, "📡 Poll: Checking daemon heartbeat from $url")
            val api = WorkerApi.create(url)

            // Get status from Worker
            val status = api.getStatus(secret)
            Log.d(TAG, "✅ Poll successful: time_since_heartbeat=${status.time_since_heartbeat}ms")

            // CRITICAL: Android app (not Worker) detects offline by checking heartbeat staleness
            // Daemon sends heartbeats every 2 seconds. If no heartbeat in 12+ seconds (6 cycles),
            // daemon is definitely offline. This works even when daemon has NO INTERNET.
            val HEARTBEAT_TIMEOUT_MS = 12000  // 12 seconds = 6 heartbeat cycles

            val isDaemonOffline = status.time_since_heartbeat > HEARTBEAT_TIMEOUT_MS
            val isDaemonPaused = status.daemon_status == "paused"  // Screen locked

            when {
                isDaemonPaused -> {
                    // Daemon is paused (screen locked/sleeping), reset counter
                    sharedPrefs.edit().putInt(OFFLINE_COUNT_KEY, 0).apply()
                    Log.i(TAG, "⏸️  Daemon PAUSED (screen locked) - offline counter reset to 0")
                }
                isDaemonOffline -> {
                    // Daemon is offline (stale heartbeat), increment counter
                    val offlineCount = sharedPrefs.getInt(OFFLINE_COUNT_KEY, 0) + 1
                    sharedPrefs.edit().putInt(OFFLINE_COUNT_KEY, offlineCount).apply()
                    Log.w(TAG, "🔴 Daemon OFFLINE (no heartbeat for ${status.time_since_heartbeat}ms) - offline counter: $offlineCount / $OFFLINE_THRESHOLD")

                    if (offlineCount >= OFFLINE_THRESHOLD) {
                        // Enable hotspot when daemon is offline
                        Log.e(TAG, "🚨 THRESHOLD REACHED ($OFFLINE_THRESHOLD checks) - Enabling hotspot!")
                        val hotspotEnabled = hotspotService.enableHotspot()
                        if (hotspotEnabled) {
                            showNotification("WiFi Failover", "Daemon offline - hotspot enabled")
                        } else {
                            showNotification("WiFi Failover", "Daemon offline - enable hotspot manually")
                        }
                    }
                }
                else -> {
                    // Daemon is online and active, reset counter
                    sharedPrefs.edit().putInt(OFFLINE_COUNT_KEY, 0).apply()
                    Log.i(TAG, "🟢 Daemon ONLINE (heartbeat ${status.time_since_heartbeat}ms ago) - offline counter reset to 0")
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Poll FAILED: ${e.javaClass.simpleName} - ${e.message}")
            e.printStackTrace()
            // Can't reach Worker = assume daemon is offline (or Worker is unreachable)
            val offlineCount = sharedPrefs.getInt(OFFLINE_COUNT_KEY, 0) + 1
            sharedPrefs.edit().putInt(OFFLINE_COUNT_KEY, offlineCount).apply()
            Log.w(TAG, "🔴 Worker unreachable - offline counter: $offlineCount / $OFFLINE_THRESHOLD")

            if (offlineCount >= OFFLINE_THRESHOLD) {
                Log.e(TAG, "🚨 THRESHOLD REACHED ($OFFLINE_THRESHOLD checks) - Enabling hotspot!")
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
