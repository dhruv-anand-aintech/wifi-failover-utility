package com.wififailover.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class WiFiFailoverApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val statusChannel = NotificationChannel(
            "wifi_failover_status",
            "WiFi Failover Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows current WiFi failover monitor status"
            setShowBadge(false)
        }

        val alertChannel = NotificationChannel(
            "wifi_failover_alert",
            "WiFi Failover Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts for failover events"
        }

        notificationManager.createNotificationChannels(listOf(statusChannel, alertChannel))
    }
}
