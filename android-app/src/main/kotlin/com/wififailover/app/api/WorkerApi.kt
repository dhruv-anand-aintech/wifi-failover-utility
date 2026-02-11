package com.wififailover.app.api

import com.wififailover.app.data.WorkerStatusResponse
import com.wififailover.app.data.AcknowledgeRequest
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Body
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.google.gson.Gson
import java.util.concurrent.TimeUnit

interface WorkerApi {
    @GET("api/status")
    suspend fun getStatus(): WorkerStatusResponse

    @POST("api/acknowledge")
    suspend fun acknowledge(@Body request: AcknowledgeRequest)

    @GET("health")
    suspend fun health(): String
}

class WorkerApiFactory {
    companion object {
        fun create(baseUrl: String): WorkerApi {
            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()

            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create(Gson()))
                .build()
                .create(WorkerApi::class.java)
        }
    }
}
