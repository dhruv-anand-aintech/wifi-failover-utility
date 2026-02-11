package com.wififailover.app.data

import com.google.gson.annotations.SerializedName

// Configuration data
data class WiFiFailoverConfig(
    val workerUrl: String,
    val workerSecret: String,
    val hotspotSsid: String,
    val pollingIntervalMinutes: Long = 2
)

// API Response models
@kotlinx.serialization.Serializable
data class WorkerStatusResponse(
    @SerializedName("hotspot_enabled")
    val hotspotEnabled: Boolean,
    @SerializedName("timestamp")
    val timestamp: Long? = null,
    @SerializedName("mac_acknowledged")
    val macAcknowledged: Boolean? = null
)

@kotlinx.serialization.Serializable
data class AcknowledgeRequest(
    val secret: String,
    val status: String = "acknowledged"
)

@kotlinx.serialization.Serializable
data class ErrorResponse(
    val error: String
)

// Monitor state
enum class MonitorState {
    IDLE,
    CHECKING,
    HOTSPOT_ENABLING,
    HOTSPOT_ENABLED,
    ERROR
}

data class MonitorStatus(
    val state: MonitorState,
    val message: String,
    val lastUpdate: Long = System.currentTimeMillis(),
    val hotspotEnabled: Boolean = false
)
