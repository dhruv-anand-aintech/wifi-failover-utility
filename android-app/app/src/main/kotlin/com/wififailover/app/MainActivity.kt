package com.wififailover.app

import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.wififailover.app.api.AcknowledgeRequest
import com.wififailover.app.api.WorkerApi
import com.wififailover.app.data.Preferences
import com.wififailover.app.receiver.AdminReceiver
import com.wififailover.app.service.HotspotService
import com.wififailover.app.worker.WiFiFailoverWorker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var preferences: Preferences
    private lateinit var statusValue: TextView
    private lateinit var workerUrlInput: EditText
    private lateinit var workerSecretInput: EditText
    private lateinit var hotspotSsidInput: EditText
    private lateinit var pollingIntervalInput: EditText
    private lateinit var saveButton: Button
    private lateinit var monitorButton: Button
    private lateinit var deviceAdminButton: Button
    private lateinit var testFailoverButton: Button
    private lateinit var debugLog: TextView

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var hotspotService: HotspotService
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private val debugMessages = mutableListOf<String>()
    private var daemonOfflineCount = 0
    private val DAEMON_OFFLINE_THRESHOLD = 2 // Enable hotspot after 2 consecutive offline checks

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preferences = Preferences(this)
        hotspotService = HotspotService(this)
        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, AdminReceiver::class.java)

        // Initialize views
        statusValue = findViewById(R.id.statusValue)
        workerUrlInput = findViewById(R.id.workerUrlInput)
        workerSecretInput = findViewById(R.id.workerSecretInput)
        hotspotSsidInput = findViewById(R.id.hotspotSsidInput)
        pollingIntervalInput = findViewById(R.id.pollingIntervalInput)
        saveButton = findViewById(R.id.saveButton)
        monitorButton = findViewById(R.id.monitorButton)
        deviceAdminButton = findViewById(R.id.deviceAdminButton)
        testFailoverButton = findViewById(R.id.testFailoverButton)
        debugLog = findViewById(R.id.debugLog)

        // Load saved configuration
        loadConfiguration()

        // Set button listeners
        saveButton.setOnClickListener { saveConfiguration() }
        monitorButton.setOnClickListener { toggleMonitoring() }
        deviceAdminButton.setOnClickListener { openDeviceAdminSettings() }
        testFailoverButton.setOnClickListener { testFailover() }

        // Check and request Device Admin if needed
        checkDeviceAdmin()
        addDebugLog("App started")

        // Update status display
        updateStatus()
    }

    private fun checkDeviceAdmin() {
        if (!devicePolicyManager.isAdminActive(adminComponent)) {
            addDebugLog("⚠️ Device Admin not enabled")
        } else {
            addDebugLog("✓ Device Admin enabled")
        }
    }

    private fun openDeviceAdminSettings() {
        val intent = Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
        startActivity(intent)
        addDebugLog("Opened Device Admin settings")
    }

    private fun addDebugLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logMessage = "[$timestamp] $message"
        debugMessages.add(logMessage)

        // Keep only last 20 messages
        if (debugMessages.size > 20) {
            debugMessages.removeAt(0)
        }

        handler.post {
            debugLog.text = debugMessages.joinToString("\n")
        }
    }

    private fun testFailover() {
        if (!preferences.isConfigured()) {
            Toast.makeText(this, "Please configure all settings first", Toast.LENGTH_SHORT).show()
            return
        }

        addDebugLog("🧪 Manual failover test triggered")
        Toast.makeText(this, "Testing failover...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val api = WorkerApi.create(preferences.workerUrl)
                addDebugLog("📡 Sending enable command...")

                // Send enable command to Worker
                val response = api.enableHotspot(com.wififailover.app.api.EnableRequest(preferences.workerSecret))
                addDebugLog("✓ Command response: enabled=${response.enabled}")

                // Wait a moment then poll
                Thread.sleep(2000)
                addDebugLog("📡 Polling Worker status...")
                pollWorker()
            } catch (e: Exception) {
                e.printStackTrace()
                addDebugLog("❌ Error: ${e.message}")
            }
        }
    }

    private fun loadConfiguration() {
        // Prefill with known values or saved preferences
        workerUrlInput.setText(
            preferences.workerUrl.ifEmpty { "https://wifi-failover.dhruv-anand.workers.dev" }
        )
        workerSecretInput.setText(
            preferences.workerSecret.ifEmpty { "yZ0NDAKbwd24B9A4hjJxw2PTO+onteuBbe8RvWmqajo=" }
        )
        hotspotSsidInput.setText(
            preferences.hotspotSsid.ifEmpty { "Dhruv's iPhone" }
        )
        // Default to 10 seconds (interval is in seconds)
        pollingIntervalInput.setText(
            if (preferences.pollingInterval == 2) "10" else preferences.pollingInterval.toString()
        )
    }

    private fun saveConfiguration() {
        val url = workerUrlInput.text.toString().trim()
        val secret = workerSecretInput.text.toString().trim()
        val ssid = hotspotSsidInput.text.toString().trim()
        val interval = pollingIntervalInput.text.toString().trim()

        if (url.isEmpty() || secret.isEmpty() || ssid.isEmpty() || interval.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val intervalInt = interval.toInt()
            if (intervalInt < 1) {
                Toast.makeText(this, "Polling interval must be at least 1 second", Toast.LENGTH_SHORT).show()
                return
            }

            preferences.workerUrl = url
            preferences.workerSecret = secret
            preferences.hotspotSsid = ssid
            preferences.pollingInterval = intervalInt

            addDebugLog("Configuration saved")
            Toast.makeText(this, "Configuration saved", Toast.LENGTH_SHORT).show()
            updateStatus()
        } catch (e: NumberFormatException) {
            Toast.makeText(this, "Invalid polling interval", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleMonitoring() {
        if (!preferences.isConfigured()) {
            Toast.makeText(this, "Please configure all settings first", Toast.LENGTH_SHORT).show()
            return
        }

        if (preferences.monitoringEnabled) {
            stopMonitoring()
        } else {
            startMonitoring()
        }
    }

    private fun startMonitoring() {
        preferences.monitoringEnabled = true
        // Schedule background polling via WorkManager (runs every 10 seconds)
        val workRequest = PeriodicWorkRequestBuilder<WiFiFailoverWorker>(
            10,
            TimeUnit.SECONDS
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "wifi_failover_polling",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
        addDebugLog("▶ Monitoring started (WorkManager background service)")
        Toast.makeText(this, "Monitoring started", Toast.LENGTH_SHORT).show()
        updateStatus()
    }

    private fun stopMonitoring() {
        preferences.monitoringEnabled = false
        // Cancel background polling via WorkManager
        WorkManager.getInstance(this).cancelUniqueWork("wifi_failover_polling")
        addDebugLog("⏸ Monitoring stopped")
        Toast.makeText(this, "Monitoring stopped", Toast.LENGTH_SHORT).show()
        updateStatus()
    }

    private fun pollWorker() {
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val api = WorkerApi.create(preferences.workerUrl)
                val status = api.getStatus(preferences.workerSecret)

                addDebugLog("Poll: daemon_online=${status.daemon_online} (${status.time_since_heartbeat}ms)")

                if (status.daemon_online) {
                    // Daemon is online, reset counter
                    daemonOfflineCount = 0
                    addDebugLog("✓ Daemon is ONLINE")
                } else {
                    // Daemon is offline, increment counter
                    daemonOfflineCount++
                    addDebugLog("✗ Daemon OFFLINE (check #$daemonOfflineCount)")

                    if (daemonOfflineCount >= DAEMON_OFFLINE_THRESHOLD) {
                        addDebugLog("🚨 Daemon offline for $DAEMON_OFFLINE_THRESHOLD checks - enabling hotspot!")

                        // Automatically enable hotspot when daemon is offline
                        val hotspotEnabled = hotspotService.enableHotspot()

                        if (hotspotEnabled) {
                            addDebugLog("✓ Hotspot enabled successfully")
                            showNotification("WiFi Failover", "Daemon offline - hotspot enabled")
                        } else {
                            addDebugLog("✗ Hotspot enable failed - check logs")
                            showNotification("WiFi Failover", "Daemon offline - enable hotspot manually")
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                addDebugLog("✗ Poll error: ${e.message}")
                daemonOfflineCount++
            }
        }
    }

    private fun showNotification(title: String, message: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, "wifi_failover_alert")
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1001, notification)
    }

    private fun updateStatus() {
        val status = if (preferences.monitoringEnabled) {
            "Active (${preferences.pollingInterval}s interval)"
        } else {
            "Inactive"
        }
        statusValue.text = status

        val buttonText = if (preferences.monitoringEnabled) "Stop Monitoring" else "Start Monitoring"
        monitorButton.text = buttonText
    }
}

