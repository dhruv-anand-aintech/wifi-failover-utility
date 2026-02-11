package com.wififailover.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("wifi_failover_config")

@Singleton
class ConfigDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val workerUrlKey = stringPreferencesKey("worker_url")
    private val workerSecretKey = stringPreferencesKey("worker_secret")
    private val hotspotSsidKey = stringPreferencesKey("hotspot_ssid")
    private val pollingIntervalKey = longPreferencesKey("polling_interval_minutes")
    private val monitorEnabledKey = stringPreferencesKey("monitor_enabled")

    suspend fun saveConfig(config: WiFiFailoverConfig) {
        context.dataStore.edit { preferences ->
            preferences[workerUrlKey] = config.workerUrl
            preferences[workerSecretKey] = config.workerSecret
            preferences[hotspotSsidKey] = config.hotspotSsid
            preferences[pollingIntervalKey] = config.pollingIntervalMinutes
        }
    }

    fun getConfig(): Flow<WiFiFailoverConfig?> = context.dataStore.data.map { preferences ->
        val url = preferences[workerUrlKey]
        val secret = preferences[workerSecretKey]
        val ssid = preferences[hotspotSsidKey]

        if (url != null && secret != null && ssid != null) {
            WiFiFailoverConfig(
                workerUrl = url,
                workerSecret = secret,
                hotspotSsid = ssid,
                pollingIntervalMinutes = preferences[pollingIntervalKey] ?: 2
            )
        } else {
            null
        }
    }

    suspend fun setMonitorEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[monitorEnabledKey] = enabled.toString()
        }
    }

    fun isMonitorEnabled(): Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[monitorEnabledKey]?.toBoolean() ?: false
    }
}
