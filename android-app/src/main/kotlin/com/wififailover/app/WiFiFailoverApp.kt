package com.wififailover.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class WiFiFailoverApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // Status notification channel
        val statusChannel = NotificationChannel(
            CHANNEL_STATUS,
            "WiFi Failover Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows current WiFi failover monitor status"
            setShowBadge(false)
        }

        // Alert notification channel
        val alertChannel = NotificationChannel(
            CHANNEL_ALERT,
            "WiFi Failover Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts for failover events"
        }

        notificationManager.createNotificationChannels(
            listOf(statusChannel, alertChannel)
        )
    }

    companion object {
        const val CHANNEL_STATUS = "wifi_failover_status"
        const val CHANNEL_ALERT = "wifi_failover_alert"
    }
}
