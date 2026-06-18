package com.hotplayer.ui.home

import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.hotplayer.HotPlayerApp
import com.hotplayer.databinding.ActivityHomeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

object SportCardManager {

    private const val API_URL =
        "https://www.thesportsdb.com/api/v1/json/3/eventsnextleague.php?id=4328"

    private val LIVE_STATUSES = setOf("1H", "HT", "2H", "ET", "Pen", "P",
        "In Progress", "Extra Time", "Penalties")

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // ── Modèles API ───────────────────────────────────────────────────────────

    private data class ApiResponse(
        @SerializedName("events") val events: List<ApiEvent>?
    )

    private data class ApiEvent(
        @SerializedName("strHomeTeam")  val homeTeam: String?,
        @SerializedName("strAwayTeam")  val awayTeam: String?,
        @SerializedName("intHomeScore") val homeScore: String?,
        @SerializedName("intAwayScore") val awayScore: String?,
        @SerializedName("strStatus")    val status: String?,
        @SerializedName("dateEvent")    val date: String?,
        @SerializedName("strTime")      val time: String?,
        @SerializedName("strTVStation") val tvStation: String?
    ) {
        val isLive: Boolean get() = status?.trim() in LIVE_STATUSES
    }

    // ── Point d'entrée ────────────────────────────────────────────────────────

    fun bind(activity: AppCompatActivity, binding: ActivityHomeBinding) {
        activity.lifecycleScope.launch {
            try {
                Log.d("SportCard", "fetch() start")
                val event = fetch()
                Log.d("SportCard", "fetch() result=$event")
                if (event == null) return@launch
                val channel = try { findChannel(event.tvStation) } catch (e: Throwable) {
                    Log.e("SportCard", "findChannel error: $e"); null
                }
                Log.d("SportCard", "channel=$channel → render()")
                withContext(Dispatchers.Main) { render(binding, event, channel) }
            } catch (e: Throwable) {
                Log.e("SportCard", "bind error: $e")
            }
        }
    }

    // ── Réseau ────────────────────────────────────────────────────────────────

    private suspend fun fetch(): ApiEvent? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(API_URL).build()
        val body = http.newCall(req).execute().use { it.body?.string() }
            ?: return@withContext null
        val events = Gson().fromJson(body, ApiResponse::class.java)?.events
            ?: return@withContext null
        // Priorité au live, sinon prochain match non commencé
        events.firstOrNull { it.isLive }
            ?: events.firstOrNull { it.status.isNullOrBlank() || it.status == "Not Started" }
    }

    // ── Correspondance chaîne M3U ─────────────────────────────────────────────

    private suspend fun findChannel(tvStation: String?): String? {
        if (tvStation.isNullOrBlank()) return null
        val repo = HotPlayerApp.instance.sessionRepo
        val creds = repo.getPlaylistCredentials()
        val channels = withContext(Dispatchers.IO) { repo.loadChannelCache(creds) }
            ?: return null
        val tv = tvStation.lowercase().trim()
        val tvWords = tv.split(Regex("\\s+")).filter { it.length > 2 }.toSet()
        return channels.firstOrNull { ch ->
            val name = ch.name.lowercase()
            name.contains(tv) || tv.contains(name) ||
                tvWords.count { name.contains(it) } >= 2
        }?.name
    }

    // ── Mise à jour de la carte ───────────────────────────────────────────────

    private fun render(binding: ActivityHomeBinding, event: ApiEvent, channel: String?) {
        Log.d("SportCard", "render() live=${event.isLive} home=${event.homeTeam} away=${event.awayTeam}")
        binding.tvSubtitleRadios.visibility = View.GONE
        binding.layoutSportInfo.visibility  = View.VISIBLE

        if (event.isLive) {
            binding.badgeLiveSports.visibility = View.VISIBLE
            binding.tvSportScore.visibility    = View.VISIBLE
            binding.tvSportScore.text = "${event.homeScore ?: "0"} – ${event.awayScore ?: "0"}"
            val phase = when (event.status?.trim()) {
                "1H"       -> "1ère mi-temps"
                "HT"       -> "Mi-temps"
                "2H"       -> "2ème mi-temps"
                "ET"       -> "Prolongations"
                "Pen", "P" -> "Tirs au but"
                else       -> event.status.orEmpty()
            }
            binding.tvSportMatch.text = buildString {
                append("${event.homeTeam} vs ${event.awayTeam}")
                if (phase.isNotEmpty()) append("\n$phase")
            }
        } else {
            binding.badgeLiveSports.visibility = View.GONE
            binding.tvSportScore.visibility    = View.GONE
            val time = formatTime(event.date, event.time)
            binding.tvSportMatch.text = buildString {
                if (time.isNotEmpty()) append("$time\n")
                append("${event.homeTeam} vs ${event.awayTeam}")
            }
        }

        if (!channel.isNullOrBlank()) {
            binding.tvSportChannel.visibility = View.VISIBLE
            binding.tvSportChannel.text       = channel
        } else {
            binding.tvSportChannel.visibility = View.GONE
        }
        Log.d("SportCard", "render() done — info visible, text='${binding.tvSportMatch.text}'")
    }

    // ── Utilitaire heure UTC → locale ─────────────────────────────────────────

    private fun formatTime(date: String?, time: String?): String {
        if (date == null || time == null) return ""
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val parsed = sdf.parse("$date $time") ?: return time.take(5)
            val out = SimpleDateFormat("HH:mm", Locale.getDefault())
            out.timeZone = TimeZone.getDefault()
            out.format(parsed)
        } catch (_: Exception) {
            time.take(5)
        }
    }
}
