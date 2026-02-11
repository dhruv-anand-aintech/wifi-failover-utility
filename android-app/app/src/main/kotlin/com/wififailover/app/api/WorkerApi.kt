package com.wififailover.app.api

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Header

data class StatusResponse(
    val daemon_status: String = "offline",  // "online", "paused", or "offline"
    val daemon_online: Boolean = false,
    val daemon_last_heartbeat: Long = 0,
    val time_since_heartbeat: Long = 0,
    val hotspot_enabled: Boolean = false,
    val timestamp: Long = 0,
    val mac_acknowledged: Boolean = false
) {
    val enabled: Boolean get() = hotspot_enabled
}

data class AcknowledgeRequest(
    val secret: String
)

data class EnableRequest(
    val secret: String
)

interface WorkerApi {
    @GET("api/status")
    suspend fun getStatus(@Header("Authorization") secret: String): StatusResponse

    @POST("api/acknowledge")
    suspend fun acknowledge(@Body request: AcknowledgeRequest): StatusResponse

    @POST("api/command/enable")
    suspend fun enableHotspot(@Body request: EnableRequest): StatusResponse

    companion object {
        fun create(baseUrl: String): WorkerApi {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(WorkerApi::class.java)
        }
    }
}
