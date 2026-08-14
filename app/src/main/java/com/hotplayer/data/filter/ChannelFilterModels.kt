package com.hotplayer.data.filter

import com.google.gson.annotations.SerializedName

data class ChannelFilterResponse(
    val enabled: Boolean = false,
    @SerializedName("global_version") val globalVersion: Int = 0,
    val lists: List<ChannelFilterListDto> = emptyList()
)

data class ChannelFilterListDto(
    val id: String = "",
    val name: String = "",
    @SerializedName("playlist_category") val playlistCategory: String = "",
    val version: Int = 0,
    @SerializedName("hidden_channels") val hiddenChannels: List<String> = emptyList()
)
