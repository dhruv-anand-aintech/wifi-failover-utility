package com.wififailover.app.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.wififailover.app.R
import com.wififailover.app.api.WorkerApi
import com.wififailover.app.data.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PollingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var pollingRunnable: Runnable? = null
    private lateinit var preferences: Preferences
    private lateinit var hotspotService: HotspotService
    private var daemonOfflineCount = 0
    private val DAEMON_OFFLINE_THRESHOLD = 2  // Enable hotspot after 2 consecutive offline checks (~10 seconds)
    private val POLLING_INTERVAL_SECONDS = 5   // Poll every 5 seconds

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "wifi_failover_polling"
    }

    override fun onCreate() {
        super.onCreate()
        preferences = Preferences(this)
        hotspotService = HotspotService(this)
        createNotificationChannel()
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (preferences.isConfigured() && preferences.monitoringEnabled) {
            scheduleNextPoll()
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                "WiFi Failover Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background polling for daemon status"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WiFi Failover")
            .setContentText("Monitoring daemon status...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun scheduleNextPoll() {
        if (!preferences.monitoringEnabled) return

        pollingRunnable = Runnable {
            pollWorker()
            scheduleNextPoll()
        }

        val intervalMs = (POLLING_INTERVAL_SECONDS * 1000).toLong()
        handler.postDelayed(pollingRunnable!!, intervalMs)
    }

    private fun pollWorker() {
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val api = WorkerApi.create(preferences.workerUrl)
                val status = api.getStatus(preferences.workerSecret)

                when (status.daemon_status) {
                    "online" -> {
                        // Daemon is online, reset counter
                        daemonOfflineCount = 0
                    }
                    "paused" -> {
                        // Daemon is paused (screen locked/sleeping), reset counter
                        // Don't trigger failover when computer is asleep
                        daemonOfflineCount = 0
                    }
                    "offline" -> {
                        // Daemon is offline, increment counter
                        daemonOfflineCount++

                        if (daemonOfflineCount >= DAEMON_OFFLINE_THRESHOLD) {
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
            } catch (e: Exception) {
                daemonOfflineCount++
            }
        }
    }

    private fun showNotification(title: String, message: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1002, notification)
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollingRunnable ?: return)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
