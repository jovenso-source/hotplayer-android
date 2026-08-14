package com.hotplayer.utils

import com.hotplayer.data.model.Channel

object ChannelUtils {

    // Strips credentials from a channel URL before it ever reaches a log line —
    // Xtream URLs embed username/password in the path ("/live/<user>/<pass>/<id>.ts")
    // and M3U URLs sometimes carry them as query params.
    fun redactUrl(url: String): String = try {
        url
            .replace(Regex("(?i)/live/[^/]+/[^/]+/"), "/live/***/***/")
            .replace(Regex("(?i)(username|password|user|pass)=[^&]*"), "$1=***")
    } catch (_: Exception) {
        "***"
    }

    private val FRENCH_TOKENS = listOf(
        "FRANCE", "FRANÇAIS", "FRANCAIS", "FRENCH",
        "TF1", "M6", "CANAL+", "ARTE", "BFM", "TMC", "W9",
        "C8", "CSTAR", "GULLI", "TFX", "RMC", "LCI",
        "FRANCE 2", "FRANCE 3", "FRANCE 4", "FRANCE 5", "FRANCE 24"
    )

    private val HD_TOKENS = listOf("FHD", "4K", "UHD", " HD", "HD ")

    // Match "FR" only when it appears as a standalone segment (pipe/space delimited or alone)
    private fun containsFrTag(s: String): Boolean {
        val t = s.trim()
        return t == "FR" ||
            t.startsWith("FR ") || t.startsWith("FR|") ||
            t.endsWith(" FR") || t.endsWith("|FR") ||
            " FR " in t || "|FR|" in t || "|FR " in t || " FR|" in t
    }

    fun isFrench(ch: Channel): Boolean {
        val g = ch.group?.uppercase() ?: ""
        val n = ch.name.uppercase()
        return containsFrTag(g) || containsFrTag(n) ||
            FRENCH_TOKENS.any { kw -> g.contains(kw) || n.contains(kw) }
    }

    fun isHd(ch: Channel): Boolean {
        val g = ch.group?.uppercase() ?: ""
        val n = ch.name.uppercase()
        return HD_TOKENS.any { tag -> n.contains(tag) || g.contains(tag) }
    }

    private fun priority(ch: Channel): Int = when {
        isFrench(ch) && isHd(ch) -> 0  // French HD
        isFrench(ch)              -> 1  // French SD
        isHd(ch)                  -> 2  // Other HD
        else                      -> 3  // Other SD
    }

    fun sortChannels(channels: List<Channel>): List<Channel> =
        channels.sortedWith(compareBy({ priority(it) }, { it.name }))

    fun isFrenchCategory(groupTitle: String): Boolean {
        val g = groupTitle.uppercase()
        return containsFrTag(g) || FRENCH_TOKENS.any { kw -> g.contains(kw) }
    }

    fun isAdultCategory(groupTitle: String): Boolean {
        val g = groupTitle.uppercase()
        return g.contains("ADULT") || g.contains("XXX") ||
               g.contains("18+")   || g.contains("EROTIC") ||
               g.contains("PORN")  || g.contains("SEXE") || g.contains("SEX")
    }

    // Sort order: 0 = French, 1 = other, 2 = adult (always last)
    private fun catPriority(g: String) = when {
        isAdultCategory(g)  -> 2
        isFrenchCategory(g) -> 0
        else                -> 1
    }

    fun buildSortedCats(channels: List<Channel>, allLabel: String): List<Pair<String, Int>> {
        val counts = HashMap<String, Int>()
        for (ch in channels) {
            val g = ch.group ?: continue
            counts[g] = (counts[g] ?: 0) + 1
        }
        val sorted = counts.entries
            .sortedWith(compareBy({ catPriority(it.key) }, { it.key.lowercase() }))
            .map { it.key to it.value }
        return listOf(allLabel to channels.size) + sorted
    }

    // Préserve l'ordre d'apparition des catégories dans la playlist source.
    fun buildCatsInOrder(channels: List<Channel>, allLabel: String): List<Pair<String, Int>> {
        val counts = LinkedHashMap<String, Int>()
        for (ch in channels) {
            val g = ch.group ?: continue
            counts[g] = (counts[g] ?: 0) + 1
        }
        return listOf(allLabel to channels.size) + counts.map { (k, v) -> k to v }
    }

    private val DIACRITICS_REGEX = Regex("\\p{Mn}+")
    private val NON_ALNUM_SPACE_REGEX = Regex("[^a-z0-9\\s]")
    private val MULTI_SPACE_REGEX = Regex("\\s+")

    // Doit produire des résultats identiques à normalizeName() côté backend
    // (backend/backend/helpers/normalizeName.js) — même algorithme des deux côtés,
    // sinon le matching des chaînes masquées échoue silencieusement.
    fun normalizeName(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val decomposed = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFKD)
        val stripped = DIACRITICS_REGEX.replace(decomposed, "")
        return stripped.lowercase()
            .replace(NON_ALNUM_SPACE_REGEX, "")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
    }

    fun normalizeTvgId(input: String?): String = input?.trim()?.lowercase() ?: ""

    // Keeps the current selection stable across a filter/playlist refresh: same channel if
    // still present, otherwise clamps to a valid index (0, or -1 if the list is now empty)
    // instead of leaving a stale/out-of-bounds index pointing at the wrong channel.
    fun resolveSelectionIndex(channels: List<Channel>, prevUrl: String?): Int {
        val idx = if (prevUrl != null) channels.indexOfFirst { it.url == prevUrl } else -1
        return when {
            idx >= 0 -> idx
            channels.isNotEmpty() -> 0
            else -> -1
        }
    }
}
