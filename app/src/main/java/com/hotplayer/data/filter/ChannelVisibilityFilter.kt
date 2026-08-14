package com.hotplayer.data.filter

import com.hotplayer.data.model.Channel
import com.hotplayer.utils.ChannelUtils

/**
 * Central, stateless-ish visibility filter for hidden channels — the single place this logic
 * lives (never duplicated across Activities/ViewModels, see LiveTvViewModel.computeVisible()).
 *
 * Fail-open by construction: [from] never throws (falls back to [PASSTHROUGH] on any malformed
 * input), and [isVisible]/[apply] never throw either — a channel is only ever hidden when a
 * lookup positively matches, any error defaults to "visible".
 *
 * Hidden keys are partitioned per category (normalized), so a channel from one category can
 * never accidentally match a hidden-list entry declared for another category.
 */
class ChannelVisibilityFilter private constructor(
    private val hiddenByCategory: Map<String, Set<String>>
) {

    fun isVisible(channel: Channel): Boolean = try {
        if (hiddenByCategory.isEmpty()) {
            true
        } else {
            val hiddenSet = hiddenByCategory[ChannelUtils.normalizeName(channel.group)]
            hiddenSet == null || candidateKeys(channel).none { it in hiddenSet }
        }
    } catch (_: Throwable) {
        true
    }

    fun apply(channels: List<Channel>): List<Channel> = try {
        if (hiddenByCategory.isEmpty()) channels else channels.filter(::isVisible)
    } catch (_: Throwable) {
        channels
    }

    private fun candidateKeys(channel: Channel): List<String> {
        val keys = mutableListOf<String>()
        // Only Xtream ids ("xt_<stream_id>") are stable across reloads — M3U ids are a
        // per-session sequential counter and must never be used for matching.
        if (channel.id.startsWith("xt_")) {
            keys += "id:${channel.id.removePrefix("xt_")}"
        }
        channel.tvgId?.takeIf { it.isNotBlank() }?.let {
            keys += "tvg:${ChannelUtils.normalizeTvgId(it)}"
        }
        keys += "name:${ChannelUtils.normalizeName(channel.name)}"
        return keys
    }

    companion object {
        val PASSTHROUGH = ChannelVisibilityFilter(emptyMap())

        fun from(response: ChannelFilterResponse?): ChannelVisibilityFilter = try {
            if (response == null || !response.enabled || response.lists.isEmpty()) {
                PASSTHROUGH
            } else {
                val map = HashMap<String, MutableSet<String>>()
                for (list in response.lists) {
                    val catKey = ChannelUtils.normalizeName(list.playlistCategory)
                    if (catKey.isEmpty()) continue
                    map.getOrPut(catKey) { HashSet() }.addAll(list.hiddenChannels)
                }
                if (map.isEmpty()) PASSTHROUGH else ChannelVisibilityFilter(map)
            }
        } catch (_: Throwable) {
            PASSTHROUGH
        }
    }
}
