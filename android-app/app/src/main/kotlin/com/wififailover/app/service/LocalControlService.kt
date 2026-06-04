package com.wififailover.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD

class LocalControlService : Service() {
    private lateinit var hotspotService: HotspotService
    private var server: ControlServer? = null

    companion object {
        const val ACTION_START = "com.wififailover.app.action.START_LOCAL_CONTROL"
        const val ACTION_ENABLE_HOTSPOT = "com.wififailover.app.action.ENABLE_HOTSPOT"
        const val ACTION_CANCEL_HOTSPOT = "com.wififailover.app.action.CANCEL_HOTSPOT"
        const val ACTION_STOP = "com.wififailover.app.action.STOP_LOCAL_CONTROL"
        const val CHANNEL_ID = "wifi_failover_local_control"
        const val NOTIFICATION_ID = 2001
        const val PORT = 38788
        private const val TAG = "LocalControlService"

        fun start(context: Context) {
            val intent = Intent(context, LocalControlService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        hotspotService = HotspotService(this)
        createNotificationChannel()
        startForegroundCompat()
        startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_ENABLE_HOTSPOT -> enableHotspot("notification")
            ACTION_CANCEL_HOTSPOT -> cancelHotspot()
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startServer()
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notification = buildNotification("Local control ready on port $PORT")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WiFi Failover")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(0, "Enable hotspot", pendingAction(ACTION_ENABLE_HOTSPOT, 1))
            .addAction(0, "Cancel", pendingAction(ACTION_CANCEL_HOTSPOT, 2))
            .addAction(0, "Stop", pendingAction(ACTION_STOP, 3))
            .build()

    private fun pendingAction(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, LocalControlService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(this, requestCode, intent, flags)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WiFi Failover Local Control",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Local hotspot control without Cloudflare heartbeat polling"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startServer() {
        if (server != null) return
        server = ControlServer(PORT) { path ->
            when (path) {
                "/health" -> """{"ok":true,"mode":"local","port":$PORT}"""
                "/enable-hotspot" -> {
                    val ok = enableHotspot("http")
                    """{"ok":$ok,"action":"enable-hotspot"}"""
                }
                "/cancel" -> {
                    cancelHotspot()
                    """{"ok":true,"action":"cancel"}"""
                }
                else -> """{"ok":false,"error":"not_found"}"""
            }
        }
        try {
            server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "Local control server started on $PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start local control server: ${e.message}", e)
            server = null
        }
    }

    private fun enableHotspot(source: String): Boolean {
        Log.i(TAG, "Enable hotspot requested from $source")
        return hotspotService.enableHotspot()
    }

    private fun cancelHotspot() {
        HotspotAccessibilityService.cancelHotspotEnable()
        Log.i(TAG, "Cancelled pending hotspot enable")
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private class ControlServer(
        port: Int,
        private val handler: (String) -> String
    ) : NanoHTTPD("0.0.0.0", port) {
        override fun serve(session: IHTTPSession): Response {
            val status = if (session.uri == "/health" || session.uri == "/enable-hotspot" || session.uri == "/cancel") {
                Response.Status.OK
            } else {
                Response.Status.NOT_FOUND
            }
            return newFixedLengthResponse(status, "application/json", handler(session.uri))
        }
    }
}
