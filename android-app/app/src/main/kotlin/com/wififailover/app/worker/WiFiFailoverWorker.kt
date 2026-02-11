package com.wififailover.app.worker

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wififailover.app.api.WorkerApi
import com.wififailover.app.data.Preferences
import com.wififailover.app.service.HotspotService
import java.util.concurrent.TimeUnit

class WiFiFailoverWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val preferences = Preferences(context)
    private val hotspotService = HotspotService(context)
    private val sharedPrefs: SharedPreferences = context.getSharedPreferences("wifi_failover_worker", Context.MODE_PRIVATE)

    companion object {
        private const val OFFLINE_COUNT_KEY = "daemon_offline_count"
        private const val HOTSPOT_ENABLED_KEY = "hotspot_enabled_by_failover"
        private const val POLL_COUNT_KEY = "poll_count"
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

            val api = WorkerApi.create(url)

            // Get status from Worker
            val status = api.getStatus(secret)

            // CRITICAL: Android app (not Worker) detects offline by checking heartbeat staleness
            // Daemon sends heartbeats every 2 seconds. If no heartbeat in 10+ seconds (5 cycles),
            // daemon is definitely offline. This works even when daemon has NO INTERNET.
            val HEARTBEAT_TIMEOUT_MS = 10000  // 10 seconds = 5 heartbeat cycles

            val isDaemonOffline = status.time_since_heartbeat > HEARTBEAT_TIMEOUT_MS
            val isDaemonPaused = status.daemon_status == "paused"  // Screen locked

            when {
                isDaemonPaused -> {
                    // Daemon is paused (screen locked/sleeping), reset counter
                    sharedPrefs.edit().putInt(OFFLINE_COUNT_KEY, 0).apply()
                    Log.i(TAG, "⏸️  Daemon PAUSED (screen locked) - offline counter reset to 0")
                }
                isDaemonOffline -> {
                    // Check if we've already enabled hotspot for this offline event
                    val hotspotAlreadyEnabled = sharedPrefs.getBoolean(HOTSPOT_ENABLED_KEY, false)

                    if (hotspotAlreadyEnabled) {
                        Log.i(TAG, "🔴 Daemon OFFLINE but hotspot already enabled - waiting for daemon to recover")
                    } else {
                        // Daemon is offline (stale heartbeat), increment counter
                        val offlineCount = sharedPrefs.getInt(OFFLINE_COUNT_KEY, 0) + 1
                        sharedPrefs.edit().putInt(OFFLINE_COUNT_KEY, offlineCount).apply()
                        Log.w(TAG, "🔴 Daemon OFFLINE (no heartbeat for ${status.time_since_heartbeat}ms) - offline counter: $offlineCount / $OFFLINE_THRESHOLD")

                        if (offlineCount >= OFFLINE_THRESHOLD) {
                            // Enable hotspot when daemon is offline
                            Log.e(TAG, "🚨 THRESHOLD REACHED ($OFFLINE_THRESHOLD checks) - Enabling hotspot!")
                            val hotspotEnabled = hotspotService.enableHotspot()
                            if (hotspotEnabled) {
                                // Mark hotspot as enabled so we don't keep trying
                                sharedPrefs.edit().putBoolean(HOTSPOT_ENABLED_KEY, true).apply()
                                showNotification("WiFi Failover", "Daemon offline - hotspot enabled")
                                Log.i(TAG, "✅ Hotspot enabled - will not attempt again until daemon recovers")
                            } else {
                                showNotification("WiFi Failover", "Daemon offline - enable hotspot manually")
                            }
                        }
                    }
                }
                else -> {
                    // Daemon is online and active, reset counter and hotspot flag
                    val pollCount = sharedPrefs.getInt(POLL_COUNT_KEY, 0) + 1
                    sharedPrefs.edit()
                        .putInt(OFFLINE_COUNT_KEY, 0)
                        .putBoolean(HOTSPOT_ENABLED_KEY, false)
                        .putInt(POLL_COUNT_KEY, pollCount)
                        .apply()

                    // Only log every 12 polls (1 minute) in steady state
                    if (pollCount % 12 == 0) {
                        Log.i(TAG, "🟢 Daemon ONLINE - steady state (${pollCount} polls)")
                    }
                }
            }

            // Reschedule self for next poll (5 seconds)
            scheduleNextPoll()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Poll FAILED: ${e.javaClass.simpleName} - ${e.message}")
            e.printStackTrace()

            // Check if we've already enabled hotspot for this offline event
            val hotspotAlreadyEnabled = sharedPrefs.getBoolean(HOTSPOT_ENABLED_KEY, false)

            if (hotspotAlreadyEnabled) {
                Log.i(TAG, "🔴 Worker unreachable but hotspot already enabled - waiting for daemon to recover")
            } else {
                // Can't reach Worker = assume daemon is offline (or Worker is unreachable)
                val offlineCount = sharedPrefs.getInt(OFFLINE_COUNT_KEY, 0) + 1
                sharedPrefs.edit().putInt(OFFLINE_COUNT_KEY, offlineCount).apply()
                Log.w(TAG, "🔴 Worker unreachable - offline counter: $offlineCount / $OFFLINE_THRESHOLD")

                if (offlineCount >= OFFLINE_THRESHOLD) {
                    Log.e(TAG, "🚨 THRESHOLD REACHED ($OFFLINE_THRESHOLD checks) - Enabling hotspot!")
                    val hotspotEnabled = hotspotService.enableHotspot()
                    if (hotspotEnabled) {
                        // Mark hotspot as enabled so we don't keep trying
                        sharedPrefs.edit().putBoolean(HOTSPOT_ENABLED_KEY, true).apply()
                        showNotification("WiFi Failover", "Daemon offline - hotspot enabled")
                        Log.i(TAG, "✅ Hotspot enabled - will not attempt again until daemon recovers")
                    } else {
                        showNotification("WiFi Failover", "Daemon offline - enable hotspot manually")
                    }
                }
            }

            // Reschedule self for next poll (5 seconds)
            scheduleNextPoll()
            Result.success()
        }
    }

    private fun scheduleNextPoll() {
        try {
            val nextPoll = OneTimeWorkRequestBuilder<WiFiFailoverWorker>()
                .setInitialDelay(5, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                "wifi_failover_polling",
                androidx.work.ExistingWorkPolicy.REPLACE,
                nextPoll
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reschedule poll: ${e.message}")
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
