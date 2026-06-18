package com.hotplayer

import android.app.Application
import android.content.SharedPreferences
import com.hotplayer.data.api.ApiClient
import com.hotplayer.data.model.Channel
import com.hotplayer.data.repository.SessionRepository
import com.hotplayer.utils.MacAddressHelper

class HotPlayerApp : Application() {
    companion object {
        lateinit var instance: HotPlayerApp
            private set
    }
    val apiClient   by lazy { ApiClient.create() }
    val sessionRepo by lazy { SessionRepository(this, apiClient) }
    val macHelper   by lazy { MacAddressHelper(this) }
    val prefs: SharedPreferences by lazy { getSharedPreferences("hp_prefs", MODE_PRIVATE) }

    /** Shared channel list for PlayerActivity UP/DOWN navigation */
    var liveTvChannels: List<Channel> = emptyList()
    var liveTvChannelIndex: Int = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
