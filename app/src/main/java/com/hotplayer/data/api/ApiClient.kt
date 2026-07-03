package com.hotplayer.data.api

import com.hotplayer.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

/* ════════════════════════════════════════════════════
   RETROFIT SERVICE
════════════════════════════════════════════════════ */
interface HotPlayerApi {

    @POST("auth/activate")
    suspend fun activate(
        @Header("X-Device-ID")          deviceId: String,
        @Header("X-Device-Fingerprint") fingerprint: String,
        @Header("X-Device-Model")       model: String,
        @Body body: ActivateRequest
    ): Response<ActivateResponse>

    @POST("auth/migrate")
    suspend fun migrate(
        @Header("X-Device-ID")          deviceId: String,
        @Header("X-Device-Fingerprint") fingerprint: String,
        @Header("X-Device-Model")       model: String,
        @Body body: MigrateRequest
    ): Response<ActivateResponse>

    @GET("auth/status")
    suspend fun status(
        @Header("X-Device-ID") mac: String
    ): Response<StatusResponse>

    @POST("auth/heartbeat")
    suspend fun heartbeat(
        @Header("Authorization")        token: String,
        @Header("X-Device-ID")        deviceId: String
    ): Response<HeartbeatResponse>

    @POST("auth/logout")
    suspend fun logout(
        @Header("Authorization") token: String,
        @Header("X-Device-ID") mac: String
    ): Response<Unit>

    @GET("stream/playlist")
    suspend fun getPlaylist(
        @Header("Authorization") token: String,
        @Header("X-Device-ID") mac: String
    ): Response<StreamPlaylistResponse>
}

/* ════════════════════════════════════════════════════
   API CLIENT FACTORY
════════════════════════════════════════════════════ */
object ApiClient {

    fun create(): HotPlayerApi {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG)
                HttpLoggingInterceptor.Level.BODY
            else
                HttpLoggingInterceptor.Level.NONE
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30,    TimeUnit.SECONDS)
            .writeTimeout(15,   TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL + "/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(HotPlayerApi::class.java)
    }
}
