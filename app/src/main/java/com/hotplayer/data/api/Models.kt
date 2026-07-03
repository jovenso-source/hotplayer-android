package com.hotplayer.data.api

import com.google.gson.annotations.SerializedName

data class ActivateRequest(
    @SerializedName("mac") val mac: String?   // null = UUID-only auth (no MAC sent)
)

data class MigrateRequest(
    @SerializedName("mac")                 val mac: String,
    @SerializedName("app_version")         val appVersion: String?,
    @SerializedName("device_model")        val deviceModel: String?,
    @SerializedName("device_manufacturer") val deviceManufacturer: String?,
    @SerializedName("android_version")     val androidVersion: String?,
    @SerializedName("device_abi")          val deviceAbi: String?
)

data class ActivateResponse(
    @SerializedName("success")     val success: Boolean,
    @SerializedName("token")       val token: String,
    @SerializedName("expires_at")  val expiresAt: String,
    @SerializedName("session_id")  val sessionId: String,   // UUID (nouveau format, rétrocompatible)
    @SerializedName("device_id")   val deviceId: String?,   // null sur l'ancien backend, UUID sur le nouveau
    @SerializedName("device")      val device: DeviceInfo,
    @SerializedName("playlist")    val playlist: PlaylistInfo?
)

data class DeviceInfo(
    @SerializedName("label")           val label: String?,
    @SerializedName("plan")            val plan: String,
    @SerializedName("status")          val status: String,
    @SerializedName("expiration_date") val expirationDate: String?,
    @SerializedName("max_connections") val maxConnections: Int
)

data class PlaylistInfo(
    @SerializedName("type")     val type: String,
    @SerializedName("url")      val url: String?,
    @SerializedName("server")   val server: String?,
    @SerializedName("user")     val user: String?,
    @SerializedName("username") val username: String?,
    @SerializedName("pass")     val pass: String?,
    @SerializedName("password") val password: String?
) {
    val effectiveUser get() = (user ?: username)?.trim()
    val effectivePass get() = (pass ?: password)?.trim()
    val effectiveServer get() = server?.trimEnd('/')

    fun toM3uUrl(): String? {
        return when (type) {
            "m3u"    -> url?.takeIf { it.isNotBlank() }
            "xtream" -> {
                val s = effectiveServer ?: return null
                val u = effectiveUser   ?: return null
                val p = effectivePass   ?: return null
                "$s/get.php?username=$u&password=$p&type=m3u_plus&output=ts"
            }
            else -> null
        }
    }
}

data class StatusResponse(
    @SerializedName("activated")       val activated: Boolean,
    @SerializedName("status")          val status: String,
    @SerializedName("plan")            val plan: String,
    @SerializedName("expiration_date") val expirationDate: String?,
    @SerializedName("label")           val label: String?
)

data class HeartbeatResponse(
    @SerializedName("ok")          val ok: Boolean,
    @SerializedName("server_time") val serverTime: String
)

data class StreamPlaylistResponse(
    @SerializedName("type")     val type: String,
    @SerializedName("url")      val url: String?,
    @SerializedName("server")   val server: String?,
    @SerializedName("user")     val user: String?,
    @SerializedName("username") val username: String?,
    @SerializedName("pass")     val pass: String?,
    @SerializedName("password") val password: String?
) {
    val effectiveUser   get() = (user ?: username)?.trim()
    val effectivePass   get() = (pass ?: password)?.trim()
    val effectiveServer get() = server?.trimEnd('/')

    fun toM3uUrl(): String? {
        return when (type) {
            "m3u"    -> url?.takeIf { it.isNotBlank() }
            "xtream" -> {
                val s = effectiveServer ?: return null
                val u = effectiveUser   ?: return null
                val p = effectivePass   ?: return null
                "$s/get.php?username=$u&password=$p&type=m3u_plus&output=ts"
            }
            else -> null
        }
    }
}

// ── Xtream API responses ──────────────────────────────────────────────────────

data class XtreamLiveStream(
    @SerializedName("num")         val num: Int?,
    @SerializedName("name")        val name: String?,
    @SerializedName("stream_id")   val streamId: Int?,
    @SerializedName("stream_icon") val icon: String?,
    @SerializedName("category_id") val categoryId: String?
)

data class XtreamCategory(
    @SerializedName("category_id")   val id: String?,
    @SerializedName("category_name") val name: String?
)

data class XtreamEpgResponse(
    @SerializedName("epg_listings") val listings: List<XtreamEpgListing>?
)

data class XtreamEpgListing(
    @SerializedName("title")           val title: String?,
    @SerializedName("description")     val description: String?,
    @SerializedName("start_timestamp") val startTimestamp: String?,
    @SerializedName("stop_timestamp")  val stopTimestamp: String?
)
