package com.hotplayer.data.api

import com.google.gson.annotations.SerializedName

// ══════════════════════════════════════════════════════════════════════════════
// POST /device/register
// Appelé au 1er lancement ou si le device n'est pas encore connu du serveur.
// ══════════════════════════════════════════════════════════════════════════════

data class DeviceRegisterRequest(
    @SerializedName("device_id")        val deviceId: String,
    @SerializedName("installation_id")  val installationId: String,
    @SerializedName("fingerprint")      val fingerprint: String,
    @SerializedName("signature")        val signature: String,
    @SerializedName("timestamp")        val timestamp: Long,
    @SerializedName("nonce")            val nonce: String,
    @SerializedName("device_info")      val deviceInfo: DeviceInfoPayload
)

data class DeviceInfoPayload(
    @SerializedName("model")       val model: String,
    @SerializedName("os_version")  val osVersion: String,
    @SerializedName("app_version") val appVersion: String
)

data class DeviceRegisterResponse(
    @SerializedName("success")       val success: Boolean,
    @SerializedName("device_status") val deviceStatus: String,   // "pending" | "active" | "suspended"
    @SerializedName("message")       val message: String?
)

// ══════════════════════════════════════════════════════════════════════════════
// POST /device/authenticate
// Échange le Device ID + signature contre un JWT de session.
// ══════════════════════════════════════════════════════════════════════════════

data class DeviceAuthRequest(
    @SerializedName("device_id")       val deviceId: String,
    @SerializedName("installation_id") val installationId: String,
    @SerializedName("fingerprint")     val fingerprint: String,
    @SerializedName("signature")       val signature: String,
    @SerializedName("timestamp")       val timestamp: Long,
    @SerializedName("nonce")           val nonce: String
)

data class DeviceAuthResponse(
    @SerializedName("success")      val success: Boolean,
    @SerializedName("token")        val token: String,
    @SerializedName("expires_at")   val expiresAt: String,
    @SerializedName("session_id")   val sessionId: String,
    @SerializedName("device")       val device: DeviceInfo,
    @SerializedName("playlist")     val playlist: PlaylistInfo?
)

// ══════════════════════════════════════════════════════════════════════════════
// POST /device/heartbeat
// Maintient la session active, renouvelle le JWT si proche de l'expiration.
// ══════════════════════════════════════════════════════════════════════════════

data class HeartbeatRequest(
    @SerializedName("device_id")  val deviceId: String,
    @SerializedName("session_id") val sessionId: String
)

data class HeartbeatResponse(
    @SerializedName("ok")           val ok: Boolean,
    @SerializedName("server_time")  val serverTime: String,
    @SerializedName("new_token")    val newToken: String?,   // présent si token renouvelé
    @SerializedName("expires_at")   val expiresAt: String?
)

// ══════════════════════════════════════════════════════════════════════════════
// POST /device/logout
// Invalide la session côté serveur.
// ══════════════════════════════════════════════════════════════════════════════

data class LogoutRequest(
    @SerializedName("device_id")  val deviceId: String,
    @SerializedName("session_id") val sessionId: String
)

// ══════════════════════════════════════════════════════════════════════════════
// Erreur API standard
// ══════════════════════════════════════════════════════════════════════════════

data class ApiError(
    @SerializedName("code")    val code: String,
    @SerializedName("message") val message: String
)
