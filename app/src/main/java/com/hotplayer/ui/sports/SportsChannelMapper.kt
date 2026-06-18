package com.hotplayer.ui.sports

import com.hotplayer.data.model.Channel
import com.hotplayer.data.model.Match
import com.hotplayer.utils.ChannelUtils

object SportsChannelMapper {

    // Competition name keywords → channel keywords (highest priority)
    private val competitionMap = mapOf(
        "Premier League"    to listOf("sky sports premier", "sky sports main", "bein sport", "canal"),
        "FA Cup"            to listOf("bbc sport", "itv", "sky sports"),
        "La Liga"           to listOf("movistar", "dazn", "la liga tv", "bein sport"),
        "Copa del Rey"      to listOf("movistar", "copa del rey", "canal sur"),
        "Ligue 1"           to listOf("canal+", "canal plus", "rmc sport", "bein sport"),
        "Coupe de France"   to listOf("france tv", "france 3", "bein sport", "canal+"),
        "Serie A"           to listOf("dazn", "sky italia", "mediaset premium", "bein sport"),
        "Coppa Italia"      to listOf("dazn", "rai sport", "mediaset"),
        "Bundesliga"        to listOf("sky sport", "dazn", "sport1", "ard", "sat1"),
        "DFB-Pokal"         to listOf("ard", "sky sport", "sport1"),
        "Eredivisie"        to listOf("espn", "ziggo sport", "nos"),
        "Primeira Liga"     to listOf("sport tv", "eleven sport", "rtp"),
        "Super Lig"         to listOf("bein sport", "s sport", "exxen"),
        "Champions League"  to listOf("canal+", "bein sport", "rmc sport", "cbs sport", "canal plus"),
        "Europa League"     to listOf("rmc sport", "canal+", "bein sport", "paramount"),
        "Conference"        to listOf("canal+", "bein sport", "conference"),
        "Nations League"    to listOf("tf1", "m6", "france tv", "bein sport"),
        "World Cup"         to listOf("tf1", "bein sport", "rmc sport", "france tv"),
        "Euro 20"           to listOf("tf1", "m6", "bein sport", "france tv"),
        "African Nations"   to listOf("canal africa", "canal+ afrique", "bein sport"),
        "NBA"               to listOf("nba", "beinsport basket", "canal+ sport"),
        "Tennis"            to listOf("eurosport", "roland garros", "tennis channel"),
        "Formula"           to listOf("sky f1", "canal+ f1", "f1 tv", "formula"),
        "MMA"               to listOf("rmc sport", "canal+ sport", "ufc"),
        "Rugby"             to listOf("france tv", "tf1", "canal+ sport", "rugby"),
        "Cycling"           to listOf("eurosport", "france tv", "cycling"),
        "MLS"               to listOf("mls", "apple tv", "espn", "fox sport", "univision"),
        "Serie B"           to listOf("dazn"),
        "Championship"      to listOf("sky sports", "bbc"),
        "Ligue 2"           to listOf("canal+", "bein sport"),
        "2. Bundesliga"     to listOf("sky sport", "sport1", "dazn"),
    )

    // Country code → broadcaster keywords
    private val countryChannels = mapOf(
        "ENG" to listOf("sky sports", "bt sport", "itv", "bbc", "talksport"),
        "FRA" to listOf("canal+", "rmc sport", "bein sport", "tf1", "france tv", "m6", "l equipe"),
        "ESP" to listOf("movistar", "dazn", "la liga tv", "gol tv", "canal sur"),
        "ITA" to listOf("dazn", "sky italia", "rai sport", "mediaset", "sport italia"),
        "DEU" to listOf("sky sport", "dazn", "sport1", "ard", "zdf", "sat1"),
        "PRT" to listOf("sport tv", "eleven sport", "rtp", "benfica tv"),
        "NLD" to listOf("ziggo sport", "espn", "nos", "rtl"),
        "BEL" to listOf("rtbf", "eleven sport", "vtm sport"),
        "TUR" to listOf("bein sport", "s sport", "exxen", "a spor", "tvplus"),
        "RUS" to listOf("match tv", "russia 1", "match premier", "okko sport"),
        "USA" to listOf("espn", "fox sport", "nbc sport", "cbs sport", "peacock"),
        "BRA" to listOf("sportv", "globo esporte", "espn brasil", "tntsport"),
        "ARG" to listOf("espn", "fox sport", "tntsport", "tyc sport"),
        "MEX" to listOf("tudn", "azteca", "tnt sport", "espn"),
        "SCO" to listOf("sky sports", "bbc scotland", "celtic tv"),
        "GRC" to listOf("nova sport", "ert sport", "cosmote"),
        "NOR" to listOf("viaplay", "tv2 sport", "nrk"),
        "SWE" to listOf("viaplay", "c more sport", "tv4 sport"),
        "DNK" to listOf("viaplay", "tv2 sport", "discovery sport"),
        "POL" to listOf("polsat sport", "tvp sport", "eleven sport"),
        "CZE" to listOf("nova sport", "ct sport", "digi sport"),
        "HUN" to listOf("arena4", "sport1", "digi sport", "m4 sport"),
        "ROU" to listOf("digi sport", "look sport", "pro tv"),
        "MAR" to listOf("arryadia", "2m", "bein sport", "canal+ maroc"),
        "DZA" to listOf("bein sport", "canal algerie", "eptv"),
        "TUN" to listOf("bein sport", "canal+ tunisie", "watania"),
        "INT" to listOf("bein sport", "canal+", "eurosport"),
    )

    // Last-resort fallback only when nothing else matches
    private val genericSportsKeywords = listOf(
        "sport", "bein", "eurosport", "canal+", "canalplus", "rmc",
        "sky sport", "dazn", "eleven", "setanta", "arena", "match tv",
        "foot", "soccer", "football"
    )

    fun findChannels(match: Match, channels: List<Channel>): List<Channel> {
        // 1. Competition-specific keywords
        val compKeywords = competitionMap.entries
            .firstOrNull { (key, _) -> match.leagueName.contains(key, ignoreCase = true) }
            ?.value

        // 2. Country-specific keywords
        val ccode = match.ccode.uppercase().trim()
        val ctryKeywords = countryChannels[ccode]

        // 3. Combine: competition keywords take priority, country adds depth
        val keywords = when {
            compKeywords != null && ctryKeywords != null -> (compKeywords + ctryKeywords).distinct()
            compKeywords != null                         -> compKeywords
            ctryKeywords != null                         -> ctryKeywords
            else                                         -> null
        }

        val result = if (keywords != null) search(channels, keywords) else emptyList()

        // 4. Fall back to generic only when specific search yields nothing
        return result.ifEmpty { search(channels, genericSportsKeywords) }.take(12)
    }

    private fun search(channels: List<Channel>, keywords: List<String>): List<Channel> {
        val matched = channels.filter { ch ->
            val name  = ch.name.lowercase()
            val group = ch.group?.lowercase() ?: ""
            keywords.any { kw -> name.contains(kw.lowercase()) || group.contains(kw.lowercase()) }
        }
        return ChannelUtils.sortChannels(matched)
    }

    fun buildCompetitionList(matches: List<Match>): List<Pair<String, Int>> {
        val all = listOf("Tous" to matches.size)
        val byComp = matches.groupBy { it.leagueName }
            .map { (comp, list) -> comp to list.size }
            .sortedByDescending { it.second }
        return all + byComp
    }
}
