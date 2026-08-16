package com.hotplayer.utils

import com.hotplayer.data.model.Channel

/**
 * Pure, stateless quality-tier detection + stable sort for Live TV's channel list, used purely
 * for DISPLAY ORDER within a category — never removes a channel, never changes the visible
 * count (that stays the job of ChannelVisibilityFilter, applied upstream). Detection is
 * intentionally simple word-token matching on name+group, mirroring the tolerant style already
 * used elsewhere in this codebase (see LiveChannelAdapter's separate liveTvQualityBadge(), a
 * different concern: a UI badge, not sort order — not reused here to avoid coupling two
 * independent behaviors to one token list).
 */
object ChannelQualitySort {

    enum class Quality(val order: Int) { SD(0), HD(1), FHD(2), UHD(3), UNKNOWN(4) }

    private val TOKEN_SPLIT_REGEX = Regex("[^A-Z0-9]+")

    private val UHD_TOKENS = setOf("4K", "UHD", "2160", "2160P")
    private val FHD_TOKENS = setOf("FHD", "FULLHD", "1080", "1080P")
    private val HD_TOKENS  = setOf("HD", "720", "720P")
    private val SD_TOKENS  = setOf("SD", "480", "480P", "576", "576P")

    // Word-token matching (not raw substring) so a channel merely containing "HD" inside an
    // unrelated word never false-positives — and checked in UHD → FHD → HD → SD order, so a name
    // containing "FHD" is classified as FHD before the HD check is ever reached (FHD must never
    // be detected as HD).
    fun detect(channel: Channel): Quality {
        val text = ((channel.name) + " " + (channel.group ?: "")).uppercase()
        val tokens = text.split(TOKEN_SPLIT_REGEX).filterTo(HashSet()) { it.isNotEmpty() }
        return when {
            tokens.any { it in UHD_TOKENS } -> Quality.UHD
            tokens.any { it in FHD_TOKENS } || text.contains("FULL HD") -> Quality.FHD
            tokens.any { it in HD_TOKENS } -> Quality.HD
            tokens.any { it in SD_TOKENS } -> Quality.SD
            else -> Quality.UNKNOWN
        }
    }

    // Kotlin's sortedBy() is a stable sort (documented stdlib guarantee) — channels sharing the
    // same detected quality keep their original relative order. Never adds/removes elements.
    fun sortedByQuality(channels: List<Channel>): List<Channel> =
        channels.sortedBy { detect(it).order }
}
