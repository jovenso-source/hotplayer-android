package com.hotplayer.data.model

import com.google.gson.annotations.SerializedName
import java.text.SimpleDateFormat
import java.util.*

// ── Raw API response (free-api-live-football-data) ────────────────────────────

data class LiveMatchesResponse(
    val status: String,
    val response: LiveMatchesData?
)

data class LiveMatchesData(val live: List<LiveMatchItem>)

data class LiveMatchItem(
    val id: Int,
    val leagueId: Int,
    val time: String,
    val home: TeamScore,
    val away: TeamScore,
    val statusId: Int,
    val status: MatchStatusDetail
)

data class TeamScore(val id: Int, val score: Int, val name: String, val longName: String)

data class MatchStatusDetail(
    val utcTime: String,
    val finished: Boolean,
    val started: Boolean,
    val cancelled: Boolean,
    val ongoing: Boolean,
    val scoreStr: String,
    val liveTime: LiveTimeDetail?
)

data class LiveTimeDetail(val short: String, val long: String, val maxTime: Int, val addedTime: Int)

data class LeaguesResponse(
    val status: String,
    val response: LeaguesData?
)

data class LeaguesData(val leagues: List<LeagueItem>)

data class LeagueItem(val id: Int, val name: String, val localizedName: String, val ccode: String, val logo: String)

// ── Domain model ──────────────────────────────────────────────────────────────

enum class MatchStatus { LIVE, FINISHED, OTHER }

data class Match(
    val id: Int,
    val leagueId: Int,
    val leagueName: String,
    val leagueLogo: String,
    val ccode: String,
    val homeTeam: String,
    val homeTeamId: Int,
    val homeScore: Int,
    val awayTeam: String,
    val awayTeamId: Int,
    val awayScore: Int,
    val status: MatchStatus,
    val timeLabel: String,
    val utcTime: String,
    val homeLogoUrl: String = "",
    val awayLogoUrl: String = ""
) {
    val isLive     get() = status == MatchStatus.LIVE
    val isUpcoming get() = status == MatchStatus.OTHER

    fun scoreText() = "$homeScore - $awayScore"
    fun homeLogo()  = homeLogoUrl.ifBlank { "https://images.fotmob.com/image_resources/logo/teamlogo/$homeTeamId.png" }
    fun awayLogo()  = awayLogoUrl.ifBlank { "https://images.fotmob.com/image_resources/logo/teamlogo/$awayTeamId.png" }
}

fun LiveMatchItem.toDomain(leagues: Map<Int, LeagueItem>): Match {
    val league = leagues[leagueId]
    val matchStatus = when {
        status.ongoing  -> MatchStatus.LIVE
        status.finished -> MatchStatus.FINISHED
        else            -> MatchStatus.OTHER
    }
    val label = when {
        status.ongoing  -> status.liveTime?.short?.ifBlank { "LIVE" } ?: "LIVE"
        status.finished -> "FT"
        else            -> formatUtcTime(status.utcTime)
    }
    return Match(
        id          = id,
        leagueId    = leagueId,
        leagueName  = league?.name ?: "League #$leagueId",
        leagueLogo  = league?.logo ?: "",
        ccode       = league?.ccode ?: "",
        homeTeam    = home.longName.ifBlank { home.name },
        homeTeamId  = home.id,
        homeScore   = home.score,
        awayTeam    = away.longName.ifBlank { away.name },
        awayTeamId  = away.id,
        awayScore   = away.score,
        status      = matchStatus,
        timeLabel   = label,
        utcTime     = status.utcTime
    )
}

private fun formatUtcTime(utcTime: String): String {
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val date = sdf.parse(utcTime) ?: return ""
        val local = SimpleDateFormat("HH:mm", Locale.getDefault())
        local.format(date)
    } catch (e: Exception) {
        ""
    }
}

// ── Match list items (for RecyclerView with section headers) ──────────────────

sealed class MatchListItem {
    data class Header(val label: String, val count: Int) : MatchListItem()
    data class MatchRow(val match: Match)                : MatchListItem()
}

// ── TheSportsDB raw models ────────────────────────────────────────────────────

data class TsdbResponse(
    @SerializedName("events") val events: List<TsdbEvent>?
)

data class TsdbEvent(
    @SerializedName("idEvent")          val idEvent: String?,
    @SerializedName("idLeague")         val idLeague: String?,
    @SerializedName("strLeague")        val strLeague: String?,
    @SerializedName("strLeagueBadge")   val strLeagueBadge: String?,
    @SerializedName("strCountry")       val strCountry: String?,
    @SerializedName("strSport")         val strSport: String?,
    @SerializedName("strHomeTeam")      val strHomeTeam: String?,
    @SerializedName("strAwayTeam")      val strAwayTeam: String?,
    @SerializedName("idHomeTeam")       val idHomeTeam: String?,
    @SerializedName("idAwayTeam")       val idAwayTeam: String?,
    @SerializedName("strHomeTeamBadge") val strHomeTeamBadge: String?,
    @SerializedName("strAwayTeamBadge") val strAwayTeamBadge: String?,
    @SerializedName("intHomeScore")     val intHomeScore: String?,
    @SerializedName("intAwayScore")     val intAwayScore: String?,
    @SerializedName("strStatus")        val strStatus: String?,
    @SerializedName("dateEvent")        val dateEvent: String?,
    @SerializedName("strTime")          val strTime: String?
)

private val TSDB_LIVE = setOf("1H", "HT", "2H", "ET", "Pen", "P", "In Progress", "Extra Time", "Penalties")

fun TsdbEvent.toDomain(): Match? {
    val home = strHomeTeam?.takeIf { it.isNotBlank() } ?: return null
    val away = strAwayTeam?.takeIf { it.isNotBlank() } ?: return null
    val matchStatus = when {
        strStatus?.trim() in TSDB_LIVE                              -> MatchStatus.LIVE
        strStatus == "Match Finished" || strStatus == "FT"
            || strStatus == "AET" || strStatus == "AP"              -> MatchStatus.FINISHED
        else                                                        -> MatchStatus.OTHER
    }
    val label = when (matchStatus) {
        MatchStatus.LIVE     -> strStatus ?: "LIVE"
        MatchStatus.FINISHED -> "FT"
        MatchStatus.OTHER    -> formatTsdbTime(dateEvent, strTime)
    }
    val utc = "${dateEvent ?: ""}T${strTime ?: "00:00:00"}Z"
    return Match(
        id          = idEvent?.toIntOrNull() ?: 0,
        leagueId    = idLeague?.toIntOrNull() ?: 0,
        leagueName  = strLeague ?: "",
        leagueLogo  = strLeagueBadge ?: "",
        ccode       = tsdbCountryToCcode(strCountry),
        homeTeam    = home,
        homeTeamId  = idHomeTeam?.toIntOrNull() ?: 0,
        homeScore   = intHomeScore?.toIntOrNull() ?: 0,
        awayTeam    = away,
        awayTeamId  = idAwayTeam?.toIntOrNull() ?: 0,
        awayScore   = intAwayScore?.toIntOrNull() ?: 0,
        status      = matchStatus,
        timeLabel   = label,
        utcTime     = utc,
        homeLogoUrl = strHomeTeamBadge ?: "",
        awayLogoUrl = strAwayTeamBadge ?: ""
    )
}

private fun formatTsdbTime(date: String?, time: String?): String {
    if (date == null || time == null) return ""
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val parsed = sdf.parse("$date $time") ?: return time.take(5)
        val out = SimpleDateFormat("EEE HH:mm", Locale.getDefault())
        out.timeZone = TimeZone.getDefault()
        out.format(parsed)
    } catch (_: Exception) { time.take(5) }
}

private fun tsdbCountryToCcode(country: String?) = when (country?.lowercase()?.trim()) {
    "england", "united kingdom" -> "ENG"
    "france"                    -> "FRA"
    "spain"                     -> "ESP"
    "germany"                   -> "GER"
    "italy"                     -> "ITA"
    "portugal"                  -> "POR"
    "netherlands"               -> "NED"
    "belgium"                   -> "BEL"
    else                        -> ""
}
