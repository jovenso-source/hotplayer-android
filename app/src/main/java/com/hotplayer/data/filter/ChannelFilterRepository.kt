package com.hotplayer.data.filter

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.hotplayer.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches and caches the "channels to hide" configuration from the backend.
 *
 * Fail-open by construction: any network/parsing failure returns null and never touches
 * the existing cache — the last known-good configuration (or none) is what stays in effect.
 * Mirrors the pattern already used by PopupManager.fetchConfig() and SessionRepository's
 * channel cache (cache-first, best-effort background refresh, never blocks display).
 */
@Singleton
class ChannelFilterRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "ChannelFilterRepo"
        private val CONFIG_URL = BuildConfig.API_BASE_URL.trimEnd('/') + "/channel-filters"
    }

    private val gson = Gson()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val cacheFile get() = File(context.filesDir, "channel_filters_cache.json")

    fun loadCachedConfigOrNull(): ChannelFilterResponse? = try {
        if (!cacheFile.exists()) null
        else gson.fromJson(cacheFile.readText(), ChannelFilterResponse::class.java)
    } catch (e: Throwable) {
        Log.w(TAG, "Cache load failed: ${e.message}")
        try { cacheFile.delete() } catch (_: Throwable) {}
        null
    }

    suspend fun refreshFromNetwork(): ChannelFilterResponse? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(CONFIG_URL).build()
            val body = httpClient.newCall(req).execute().use { it.body?.string() } ?: return@withContext null
            val parsed = gson.fromJson(body, ChannelFilterResponse::class.java) ?: return@withContext null
            try { cacheFile.writeText(gson.toJson(parsed)) } catch (e: Throwable) {
                Log.w(TAG, "Cache save failed: ${e.message}")
            }
            parsed
        } catch (e: Throwable) {
            Log.w(TAG, "Refresh failed: ${e.message}")
            null
        }
    }
}
