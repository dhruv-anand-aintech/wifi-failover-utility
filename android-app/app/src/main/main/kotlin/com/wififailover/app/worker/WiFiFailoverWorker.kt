package com.wififailover.app.worker

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.CoroutineWorker
import com.wififailover.app.api.WorkerApiFactory
import com.wififailover.app.data.AcknowledgeRequest
import com.wififailover.app.data.ConfigDataStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.runBlocking

@HiltWorker
class WiFiFailoverWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val configDataStore: ConfigDataStore
) : CoroutineWorker(context, params) {

    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val tag = "WiFiFailoverWorker"

    override suspend fun doWork(): Result {
        return try {
            Log.d(tag, "Starting WiFi failover check")

            // Get configuration
            val config = runBlocking {
                configDataStore.getConfig().collect { cfg ->
                    if (cfg != null) {
                        checkAndHandleFailover(cfg)
                    }
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(tag, "Error in WiFi failover worker", e)
            Result.retry()
        }
    }

    private suspend fun checkAndHandleFailover(config: com.wififailover.app.data.WiFiFailoverConfig) {
        try {
            val api = WorkerApiFactory.create(config.workerUrl)
            val status = api.getStatus()

            Log.d(tag, "Worker status: hotspot_enabled=${status.hotspotEnabled}")

            if (status.hotspotEnabled) {
                // Enable hotspot
                Log.d(tag, "Enabling hotspot")
                enableHotspot()

                // Acknowledge to worker
                api.acknowledge(
                    AcknowledgeRequest(
                        secret = config.workerSecret,
                        status = "acknowledged"
                    )
                )
                Log.d(tag, "Acknowledged to worker")
            } else {
                // Disable hotspot if needed
                if (isHotspotEnabled()) {
                    Log.d(tag, "Disabling hotspot")
                    disableHotspot()
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error checking worker status", e)
        }
    }

    private fun enableHotspot() {
        try {
            // Note: Direct hotspot control requires Device Owner or System app
            // For regular apps, we use reflection to call hidden APIs
            val method = wifiManager.javaClass.getDeclaredMethod("setWifiApEnabled", String::class.java, Boolean::class.javaPrimitiveType)
            method.invoke(wifiManager, null, true)
            Log.d(tag, "Hotspot enable called")
        } catch (e: Exception) {
            Log.e(tag, "Error enabling hotspot", e)
        }
    }

    private fun disableHotspot() {
        try {
            val method = wifiManager.javaClass.getDeclaredMethod("setWifiApEnabled", String::class.java, Boolean::class.javaPrimitiveType)
            method.invoke(wifiManager, null, false)
            Log.d(tag, "Hotspot disable called")
        } catch (e: Exception) {
            Log.e(tag, "Error disabling hotspot", e)
        }
    }

    private fun isHotspotEnabled(): Boolean {
        return try {
            val method = wifiManager.javaClass.getDeclaredMethod("isWifiApEnabled")
            method.invoke(wifiManager) as Boolean
        } catch (e: Exception) {
            false
        }
    }
}
