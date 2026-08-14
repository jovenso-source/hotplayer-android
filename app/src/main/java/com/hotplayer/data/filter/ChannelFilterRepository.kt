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

    // Express already serves a content-hash ETag on GET /api/channel-filters and honors
    // If-None-Match with a 304 (verified against the real backend) — no server change needed.
    // Kept strictly paired with cacheFile: an ETag is only ever trusted if the cache it
    // describes is still present, so a corrupted/deleted cache always forces a full refetch
    // instead of risking a 304 that would leave a stale/missing config in place forever.
    private val etagFile get() = File(context.filesDir, "channel_filters_etag.txt")

    // 0L = "never checked in this process" — refreshIfDue() always proceeds on the very first
    // call regardless of minIntervalMs, which is exactly the desired "always check on app/session
    // launch, throttle afterwards" behavior without needing a separate app-launch code path.
    @Volatile private var lastCheckedAtMs: Long = 0L

    fun loadCachedConfigOrNull(): ChannelFilterResponse? = try {
        if (!cacheFile.exists()) null
        else gson.fromJson(cacheFile.readText(), ChannelFilterResponse::class.java)
    } catch (e: Throwable) {
        Log.w(TAG, "Cache load failed: ${e.message}")
        try { cacheFile.delete() } catch (_: Throwable) {}
        try { etagFile.delete() } catch (_: Throwable) {}
        null
    }

    // Throttled entry point: skips the network call entirely (not even a conditional GET) if
    // the last check was less than [minIntervalMs] ago. Use this everywhere except when an
    // unconditional check is explicitly wanted — it already behaves correctly as "always check"
    // on the first call of a fresh process.
    suspend fun refreshIfDue(minIntervalMs: Long): ChannelFilterResponse? {
        val now = System.currentTimeMillis()
        if (!RefreshThrottle.isDue(now, lastCheckedAtMs, minIntervalMs)) return null
        lastCheckedAtMs = now
        return refreshFromNetwork()
    }

    // Unconditional network check. Sends If-None-Match when a valid cache+ETag pair exists —
    // on 304 (unchanged) this is a small header-only round trip, no body parse, no cache write,
    // no filter rebuild. Fail-open on every path: any failure (timeout, HTTP error, invalid
    // JSON, corrupt response) returns null without ever touching the existing cache/ETag.
    suspend fun refreshFromNetwork(): ChannelFilterResponse? = withContext(Dispatchers.IO) {
        try {
            val savedEtag = if (cacheFile.exists()) {
                try { etagFile.takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null } } catch (_: Throwable) { null }
            } else null

            val reqBuilder = Request.Builder().url(CONFIG_URL)
            if (savedEtag != null) reqBuilder.header("If-None-Match", savedEtag)

            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                if (response.code == 304) return@withContext null
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val parsed = gson.fromJson(body, ChannelFilterResponse::class.java) ?: return@withContext null
                try {
                    cacheFile.writeText(gson.toJson(parsed))
                    response.header("ETag")?.let { etagFile.writeText(it) }
                } catch (e: Throwable) {
                    Log.w(TAG, "Cache save failed: ${e.message}")
                }
                parsed
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Refresh failed: ${e.message}")
            null
        }
    }
}
