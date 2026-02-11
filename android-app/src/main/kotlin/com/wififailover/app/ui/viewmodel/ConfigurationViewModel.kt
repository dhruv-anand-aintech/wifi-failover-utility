package com.wififailover.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import com.wififailover.app.data.ConfigDataStore
import com.wififailover.app.data.WiFiFailoverConfig
import com.wififailover.app.worker.WiFiFailoverWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.concurrent.TimeUnit
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext

@HiltViewModel
class ConfigurationViewModel @Inject constructor(
    private val configDataStore: ConfigDataStore,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val config = configDataStore.getConfig()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val isMonitoring = configDataStore.isMonitorEnabled()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    fun saveConfig(config: WiFiFailoverConfig) {
        viewModelScope.launch {
            configDataStore.saveConfig(config)
        }
    }

    fun toggleMonitoring(enabled: Boolean) {
        viewModelScope.launch {
            configDataStore.setMonitorEnabled(enabled)

            if (enabled) {
                scheduleWorker()
            } else {
                cancelWorker()
            }
        }
    }

    private fun scheduleWorker() {
        val config = config.value ?: return

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<WiFiFailoverWorker>(
            config.pollingIntervalMinutes, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffPolicy(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "wifi_failover_monitor",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    private fun cancelWorker() {
        WorkManager.getInstance(context).cancelUniqueWork("wifi_failover_monitor")
    }
}
