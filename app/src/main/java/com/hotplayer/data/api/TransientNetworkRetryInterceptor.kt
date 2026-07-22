package com.hotplayer.data.api

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Retries idempotent GET requests on transient network failures (timeout, connection reset,
 * DNS resolution failure) with a short progressive backoff — aimed at the weak/unstable mobile
 * connections HotPlayer users commonly have.
 *
 * Deliberately narrow in scope:
 *  - only GET requests are retried (POST — activate/migrate/heartbeat/logout — are never retried,
 *    they are not safe to repeat blindly);
 *  - only I/O-level failures (thrown IOException) trigger a retry — HTTP error responses (401,
 *    403, 404, 5xx) are returned as-is and left to existing app-level handling
 *    (e.g. SessionRepository.getPlaylistCredentials()'s 401/403/404 branches), not retried here.
 *
 * Shared by every OkHttp client that talks to the backend or a playlist/EPG provider, so there is
 * a single retry policy in the app rather than several ad hoc ones.
 */
class TransientNetworkRetryInterceptor(
    private val maxRetries: Int = 2,
    private val baseDelayMs: Long = 500L
) : Interceptor {

    companion object {
        private const val TAG = "NetworkRetry"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "GET") return chain.proceed(request)

        var attempt = 0
        while (true) {
            try {
                return chain.proceed(request)
            } catch (e: IOException) {
                if (attempt >= maxRetries) throw e
                val delayMs = baseDelayMs * (attempt + 1)
                Log.w(TAG, "GET ${request.url.encodedPath} failed (${e.message}), retry ${attempt + 1}/$maxRetries in ${delayMs}ms")
                try {
                    Thread.sleep(delayMs)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
                attempt++
            }
        }
    }
}
