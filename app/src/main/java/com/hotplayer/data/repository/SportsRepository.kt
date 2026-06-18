package com.hotplayer.data.repository

import com.google.gson.Gson
import com.hotplayer.data.model.Match
import com.hotplayer.data.model.TsdbEvent
import com.hotplayer.data.model.TsdbResponse
import com.hotplayer.data.model.toDomain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class SportsRepository {

    companion object {
        private const val BASE = "https://www.thesportsdb.com/api/v1/json/3"

        // Ligues suivies (Ligue 1 en premier pour public francophone)
        private val LEAGUE_IDS = listOf(4334, 4328, 4335, 4331, 4332, 4480, 4481)

        @Volatile private var cache: List<Match> = emptyList()
        @Volatile private var cacheTime = 0L
        private const val TTL = 120_000L // 2 minutes
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    fun isApiKeySet() = true

    suspend fun getLiveMatches(force: Boolean = false): List<Match> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && cache.isNotEmpty() && now - cacheTime < TTL) return@withContext cache

        val all = mutableListOf<Match>()

        // Matchs en direct (best-effort, endpoint peut ne pas fonctionner en free tier)
        try {
            fetch("$BASE/eventslive.php")
                ?.filter { it.strSport == "Soccer" }
                ?.mapNotNull { it.toDomain() }
                ?.let { all += it }
        } catch (_: Throwable) {}

        // Prochains matchs par ligue
        for (leagueId in LEAGUE_IDS) {
            try {
                fetch("$BASE/eventsnextleague.php?id=$leagueId")
                    ?.mapNotNull { it.toDomain() }
                    ?.let { all += it }
            } catch (_: Throwable) {}
        }

        cache = all
            .distinctBy { it.id }
            .sortedWith(compareByDescending<Match> { it.isLive }.thenBy { it.utcTime })
        cacheTime = now
        cache
    }

    fun invalidate() { cacheTime = 0L }

    private fun fetch(url: String): List<TsdbEvent>? {
        val req = Request.Builder().url(url).build()
        val body = client.newCall(req).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        } ?: return null
        return gson.fromJson(body, TsdbResponse::class.java)?.events
    }
}
