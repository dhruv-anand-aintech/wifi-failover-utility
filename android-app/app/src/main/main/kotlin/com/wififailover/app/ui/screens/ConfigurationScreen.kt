package com.wififailover.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import com.wififailover.app.data.WiFiFailoverConfig
import com.wififailover.app.ui.viewmodel.ConfigurationViewModel
import com.wififailover.app.worker.WiFiFailoverWorker
import java.util.concurrent.TimeUnit

@Composable
fun ConfigurationScreen(
    viewModel: ConfigurationViewModel = hiltViewModel()
) {
    val config by viewModel.config.collectAsState(initial = null)
    val isMonitoring by viewModel.isMonitoring.collectAsState(initial = false)

    var workerUrl by remember { mutableStateOf("") }
    var workerSecret by remember { mutableStateOf("") }
    var hotspotSsid by remember { mutableStateOf("") }
    var pollingInterval by remember { mutableStateOf("2") }

    // Load existing config
    LaunchedEffect(config) {
        config?.let {
            workerUrl = it.workerUrl
            workerSecret = it.workerSecret
            hotspotSsid = it.hotspotSsid
            pollingInterval = it.pollingIntervalMinutes.toString()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "WiFi Failover Configuration",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Status indicator
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Monitor Status")
            if (isMonitoring) {
                Row {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Active",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text("Active", color = MaterialTheme.colorScheme.primary)
                }
            } else {
                Row {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Inactive",
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text("Inactive", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Divider()

        // Worker URL field
        OutlinedTextField(
            value = workerUrl,
            onValueChange = { workerUrl = it },
            label = { Text("Worker URL") },
            placeholder = { Text("https://wifi-failover.xxx.workers.dev") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )

        // Worker Secret field
        OutlinedTextField(
            value = workerSecret,
            onValueChange = { workerSecret = it },
            label = { Text("Worker Secret") },
            placeholder = { Text("Your random secret string") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )

        // Hotspot SSID field
        OutlinedTextField(
            value = hotspotSsid,
            onValueChange = { hotspotSsid = it },
            label = { Text("Phone Hotspot SSID") },
            placeholder = { Text("e.g., Dhruv's Phone") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // Polling interval field
        OutlinedTextField(
            value = pollingInterval,
            onValueChange = { pollingInterval = it },
            label = { Text("Polling Interval (minutes)") },
            placeholder = { Text("2") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Save button
        Button(
            onClick = {
                val interval = pollingInterval.toLongOrNull() ?: 2
                val newConfig = WiFiFailoverConfig(
                    workerUrl = workerUrl,
                    workerSecret = workerSecret,
                    hotspotSsid = hotspotSsid,
                    pollingIntervalMinutes = interval
                )
                viewModel.saveConfig(newConfig)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = workerUrl.isNotEmpty() && workerSecret.isNotEmpty() && hotspotSsid.isNotEmpty()
        ) {
            Text("Save Configuration")
        }

        // Start/Stop monitoring button
        Button(
            onClick = { viewModel.toggleMonitoring(!isMonitoring) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isMonitoring) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (isMonitoring) "Stop Monitoring" else "Start Monitoring")
        }

        Spacer(modifier = Modifier.height(8.dp))
        Divider()

        Text(
            text = "Information",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        Text(
            text = "This app periodically polls your Cloudflare Worker to check if the hotspot should be enabled. When enabled, it will automatically turn on your phone's WiFi hotspot.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = "Make sure your Mac daemon is running and configured with the same Worker URL and secret.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
