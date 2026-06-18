package com.hotplayer.utils

import com.hotplayer.data.model.Channel

object ChannelUtils {

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
}
