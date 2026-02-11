package com.wififailover.app.data

import android.content.Context
import android.content.SharedPreferences

class Preferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("wifi_failover_config", Context.MODE_PRIVATE)

    var workerUrl: String
        get() = prefs.getString("worker_url", "") ?: ""
        set(value) = prefs.edit().putString("worker_url", value).apply()

    var workerSecret: String
        get() = prefs.getString("worker_secret", "") ?: ""
        set(value) = prefs.edit().putString("worker_secret", value).apply()

    var hotspotSsid: String
        get() = prefs.getString("hotspot_ssid", "") ?: ""
        set(value) = prefs.edit().putString("hotspot_ssid", value).apply()

    var pollingInterval: Int
        get() = prefs.getInt("polling_interval", 10)
        set(value) = prefs.edit().putInt("polling_interval", value).apply()

    var monitoringEnabled: Boolean
        get() = prefs.getBoolean("monitoring_enabled", false)
        set(value) = prefs.edit().putBoolean("monitoring_enabled", value).apply()

    fun isConfigured(): Boolean {
        return workerUrl.isNotEmpty() &&
               workerSecret.isNotEmpty() &&
               hotspotSsid.isNotEmpty()
    }
}
