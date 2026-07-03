package com.hotplayer.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hotplayer.data.api.HotPlayerApi
import com.hotplayer.data.api.PlaylistInfo
import com.hotplayer.data.api.XtreamCategory
import com.hotplayer.data.api.XtreamEpgResponse
import com.hotplayer.data.api.XtreamLiveStream
import com.hotplayer.data.model.Channel
import com.hotplayer.data.model.ChannelType
import com.hotplayer.data.model.EpgItem
import com.hotplayer.data.api.ActivateRequest
import com.hotplayer.security.DeviceIdentityManager
import com.hotplayer.utils.MacAddressHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private data class ChannelCacheEntry(
    val credentialKey: String,
    val timestamp: Long,
    val channels: List<Channel>
)

private val Context.dataStore by preferencesDataStore(name = "hotplayer_session")

@Singleton
class SessionRepository @Inject constructor(
    private val context: Context,
    private val api: HotPlayerApi,
    private val deviceRepo: DeviceRepository,
    private val identity: DeviceIdentityManager
) {

    companion object {
        private val KEY_PLAN           = stringPreferencesKey("plan")
        private val KEY_LABEL          = stringPreferencesKey("label")
        private val KEY_M3U_URL        = stringPreferencesKey("m3u_url")
        private val KEY_PLAYLIST_TYPE  = stringPreferencesKey("playlist_type")
        private val KEY_XTREAM_SERVER  = stringPreferencesKey("xtream_server")
        private val KEY_XTREAM_USER    = stringPreferencesKey("xtream_user")
        private val KEY_XTREAM_PASS    = stringPreferencesKey("xtream_pass")
        private val KEY_EXPIRY_DATE    = stringPreferencesKey("expiration_date")
        // Clés legacy : présentes dans l'ancien DataStore "hotplayer_session" sur les appareils migrés.
        // Utilisées UNIQUEMENT pour lire l'ancienne MAC enregistrée, puis supprimées.
        private val KEY_MAC_LEGACY     = stringPreferencesKey("mac")
        private const val TAG          = "SessionRepo"
    }

    private val gson     = Gson()
    // Helper MAC utilisé uniquement pendant la migration — jamais après.
    private val macHelper: MacAddressHelper by lazy { MacAddressHelper(context) }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "HotPlayer/1.0 Android")
                    .build()
            )
        }
        .build()

    // ── Sealed type representing what the server told us ──────────────────────

    sealed class PlaylistCredentials {
        data class Xtream(val server: String, val user: String, val pass: String) : PlaylistCredentials()
        data class M3u(val url: String) : PlaylistCredentials()
        object None : PlaylistCredentials()
    }

    // ── Activation (délégation au DeviceRepository) ───────────────────────────

    sealed class ActivationResult {
        data class Success(val playlist: PlaylistInfo?) : ActivationResult()
        data class Failure(val code: String, val message: String) : ActivationResult()
        object NotActivated : ActivationResult()
        object NetworkError : ActivationResult()
    }

    /**
     * Point d'entrée principal — gère les deux cas :
     *
     *  CAS A — Ancien client (mise à jour depuis MAC) :
     *   `isMigrated()` = false → appelle `POST /auth/activate` avec MAC
     *   → serveur assigne un device_id → sauvegarde → migration terminée
     *
     *  CAS B — Nouveau client / déjà migré :
     *   `isMigrated()` = true → appelle `POST /device/register` + `/device/authenticate`
     *
     * L'utilisateur ne voit rien, aucune reconnexion nécessaire.
     */
    suspend fun activate(): ActivationResult {
        // ── CAS A : migration nécessaire ────────────────────────────────────
        if (!deviceRepo.isMigrated()) {
            val migrationResult = attemptLegacyMigration()
            if (migrationResult != null) return migrationResult
            // Si migration échoue (ancien backend indisponible ou appareil inconnu)
            // → tenter le nouveau flux pour les nouveaux appareils
        }

        // ── CAS B : nouveau flux Device ID ──────────────────────────────────
        if (!deviceRepo.registerIfNeeded()) return ActivationResult.NetworkError

        return when (val result = deviceRepo.authenticate()) {
            is DeviceRepository.DeviceResult.Success -> {
                val body = result.response
                // Persister les infos playlist localement pour le cache
                body.playlist?.let { pl ->
                    context.dataStore.edit { prefs ->
                        prefs[KEY_PLAN]          = body.device.plan
                        prefs[KEY_LABEL]         = body.device.label ?: ""
                        prefs[KEY_EXPIRY_DATE]   = body.device.expirationDate ?: ""
                        prefs[KEY_PLAYLIST_TYPE] = pl.type
                        pl.toM3uUrl()?.let { prefs[KEY_M3U_URL] = it }
                        if (pl.type == "xtream") {
                            pl.effectiveServer?.let { prefs[KEY_XTREAM_SERVER] = it }
                            pl.effectiveUser?.let   { prefs[KEY_XTREAM_USER]   = it }
                            pl.effectivePass?.let   { prefs[KEY_XTREAM_PASS]   = it }
                        }
                    }
                }
                ActivationResult.Success(body.playlist)
            }
            is DeviceRepository.DeviceResult.Failure ->
                ActivationResult.Failure(result.code, result.message)
            DeviceRepository.DeviceResult.NotActivated ->
                ActivationResult.NotActivated
            DeviceRepository.DeviceResult.NetworkError ->
                ActivationResult.NetworkError
        }
    }

    /**
     * Tente la migration transparente depuis le système MAC.
     *
     * Étapes :
     *  1. Récupère la MAC (depuis l'ancien DataStore si disponible, sinon MacAddressHelper)
     *  2. Appelle `POST /auth/activate` avec la MAC + le device_id local proposé
     *  3. Le serveur retourne un device_id (le sien ou le proposé)
     *  4. Si le device_id retourné diffère du local → met à jour EncryptedSharedPreferences
     *  5. Persiste le token, marque la migration comme terminée
     *
     * @return ActivationResult si la migration a réussi, null si elle doit être ignorée
     *         (appareil inconnu du serveur → nouveau flux)
     */
    private suspend fun attemptLegacyMigration(): ActivationResult? {
        // Lire la MAC depuis l'ancien DataStore ou recalculer
        val savedMac = context.dataStore.data.first()[KEY_MAC_LEGACY]
        val mac      = savedMac?.takeIf { it.isNotBlank() } ?: macHelper.getDeviceMac()

        Log.i(TAG, "Attempting legacy migration for MAC: ${mac.take(8)}…")

        return try {
            val response = api.activate(
                deviceId    = identity.deviceId,
                fingerprint = identity.deviceFingerprint,
                model       = identity.deviceModel,
                body        = ActivateRequest(mac = mac)
            )

            if (response.isSuccessful) {
                val body = response.body()!!

                // Si le serveur retourne un device_id différent (l'appareil avait déjà un UUID),
                // on adopte celui du serveur pour la cohérence
                val serverDeviceId = body.deviceId
                if (!serverDeviceId.isNullOrBlank() && serverDeviceId != identity.deviceId) {
                    Log.i(TAG, "Server assigned different device_id, updating local store")
                    identity.assignServerDeviceId(serverDeviceId)
                }

                // Persister le token dans le nouveau DataStore device_auth
                deviceRepo.completeLegacyMigration(
                    token     = body.token,
                    expiresAt = body.expiresAt,
                    sessionId = body.sessionId,
                    plan      = body.device.plan,
                    label     = body.device.label ?: ""
                )

                // Persister la playlist dans le DataStore session (pour le cache)
                body.playlist?.let { pl ->
                    context.dataStore.edit { prefs ->
                        prefs[KEY_PLAN]          = body.device.plan
                        prefs[KEY_LABEL]         = body.device.label ?: ""
                        prefs[KEY_EXPIRY_DATE]   = body.device.expirationDate ?: ""
                        prefs[KEY_PLAYLIST_TYPE] = pl.type
                        pl.toM3uUrl()?.let { prefs[KEY_M3U_URL] = it }
                        if (pl.type == "xtream") {
                            pl.effectiveServer?.let { prefs[KEY_XTREAM_SERVER] = it }
                            pl.effectiveUser?.let   { prefs[KEY_XTREAM_USER]   = it }
                            pl.effectivePass?.let   { prefs[KEY_XTREAM_PASS]   = it }
                        }
                    }
                }

                Log.i(TAG, "Legacy migration successful")
                ActivationResult.Success(body.playlist)

            } else {
                val code = parseErrorCode(response.errorBody()?.string())
                when (code) {
                    // Appareil inconnu du serveur (n'était pas dans l'ancien backend)
                    // → laisser le nouveau flux gérer (retourner null)
                    "DEVICE_NOT_FOUND" -> {
                        Log.i(TAG, "MAC not found on server, falling through to new flow")
                        null
                    }
                    // Appareil connu mais bloqué → informer l'utilisateur
                    else -> ActivationResult.Failure(code, legacyErrorMessage(code))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Legacy migration network error: ${e.message}")
            // En cas d'erreur réseau, laisser le nouveau flux tenter sa chance
            null
        }
    }

    suspend fun isSessionValid(): Boolean = deviceRepo.isSessionValid()

    suspend fun sendHeartbeat(): Boolean = deviceRepo.heartbeat()

    // ── Playlist credentials (refresh from server, fall back to cache) ────────

    suspend fun getPlaylistCredentials(): PlaylistCredentials {
        val prefs = context.dataStore.data.first()
        val token = deviceRepo.getToken()
        if (token != null) {
            try {
                val r = api.getPlaylist("Bearer $token", identity.deviceId)
                if (r.isSuccessful) {
                    val pl = r.body()!!
                    val oldCreds = fromCache(prefs)
                    context.dataStore.edit { p ->
                        p[KEY_PLAYLIST_TYPE] = pl.type
                        if (pl.type == "xtream") {
                            pl.effectiveServer?.let { p[KEY_XTREAM_SERVER] = it }
                            pl.effectiveUser?.let   { p[KEY_XTREAM_USER]   = it }
                            pl.effectivePass?.let   { p[KEY_XTREAM_PASS]   = it }
                        } else {
                            pl.toM3uUrl()?.let { p[KEY_M3U_URL] = it }
                        }
                    }
                    if (pl.type == "xtream") {
                        val s = pl.effectiveServer
                        val u = pl.effectiveUser
                        val p = pl.effectivePass
                        if (s != null && u != null && p != null) {
                            val newCreds = PlaylistCredentials.Xtream(s, u, p)
                            if (credentialKey(newCreds) != credentialKey(oldCreds)) {
                                Log.i(TAG, "Playlist changed → clearing channel cache")
                                clearChannelCache()
                            }
                            return newCreds
                        }
                    } else {
                        val url = pl.toM3uUrl()
                        if (url != null) {
                            val newCreds = PlaylistCredentials.M3u(url)
                            if (credentialKey(newCreds) != credentialKey(oldCreds)) {
                                Log.i(TAG, "M3U URL changed → clearing channel cache")
                                clearChannelCache()
                            }
                            return newCreds
                        }
                    }
                } else if (r.code() == 401 || r.code() == 403) {
                    // Token révoqué → re-activation immédiate pour obtenir un nouveau token + playlist
                    Log.i(TAG, "Token invalid (${r.code()}) → re-activating")
                    clearChannelCache()
                    val result = activate()
                    if (result is ActivationResult.Success) {
                        val pl = result.playlist ?: return@getPlaylistCredentials PlaylistCredentials.None
                        if (pl.type == "xtream") {
                            val s = pl.effectiveServer; val u = pl.effectiveUser; val p = pl.effectivePass
                            if (s != null && u != null && p != null)
                                return PlaylistCredentials.Xtream(s, u, p)
                        } else {
                            val url = pl.toM3uUrl()
                            if (url != null) return PlaylistCredentials.M3u(url)
                        }
                    }
                    return PlaylistCredentials.None
                } else if (r.code() == 404) {
                    // Aucune playlist assignée sur le backend → vider le cache local
                    Log.i(TAG, "No playlist on server (404) → clearing cache")
                    clearChannelCache()
                    context.dataStore.edit { p ->
                        p.remove(KEY_M3U_URL)
                        p.remove(KEY_PLAYLIST_TYPE)
                        p.remove(KEY_XTREAM_SERVER)
                        p.remove(KEY_XTREAM_USER)
                        p.remove(KEY_XTREAM_PASS)
                    }
                    return PlaylistCredentials.None
                }
            } catch (e: Exception) {
                Log.w(TAG, "getPlaylist failed: ${e.message}")
            }
        }

        // Fall back to cache
        return fromCache(prefs)
    }

    private fun fromCache(prefs: androidx.datastore.preferences.core.Preferences): PlaylistCredentials {
        val type   = prefs[KEY_PLAYLIST_TYPE]
        val server = prefs[KEY_XTREAM_SERVER]
        val user   = prefs[KEY_XTREAM_USER]
        val pass   = prefs[KEY_XTREAM_PASS]
        val m3uUrl = prefs[KEY_M3U_URL]

        return when {
            type == "xtream" && server != null && user != null && pass != null ->
                PlaylistCredentials.Xtream(server, user, pass)
            m3uUrl != null ->
                PlaylistCredentials.M3u(m3uUrl)
            else ->
                PlaylistCredentials.None
        }
    }

    // ── Legacy helper used by HomeActivity ────────────────────────────────────

    suspend fun getPlaylistUrl(): String? {
        return when (val creds = getPlaylistCredentials()) {
            is PlaylistCredentials.Xtream -> "${creds.server}/get.php?username=${creds.user}&password=${creds.pass}&type=m3u_plus&output=ts"
            is PlaylistCredentials.M3u    -> creds.url
            is PlaylistCredentials.None   -> null
        }
    }

    // ── Channel loading ───────────────────────────────────────────────────────

    suspend fun loadChannels(m3uUrl: String): List<Channel> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "M3U loading: $m3uUrl")
            val req     = Request.Builder().url(m3uUrl).build()
            val content = httpClient.newCall(req).execute().use { it.body?.string() ?: "" }
            parseM3U(content)
        } catch (e: Throwable) {
            Log.e(TAG, "M3U failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun loadChannelsXtream(server: String, user: String, pass: String): List<Channel> =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Xtream API loading: $server")

                // 1. Categories
                val catUrl  = "$server/player_api.php?username=$user&password=$pass&action=get_live_categories"
                val catJson = httpGet(catUrl)
                val catType = object : TypeToken<List<XtreamCategory>>() {}.type
                val cats: List<XtreamCategory> = gson.fromJson(catJson, catType) ?: emptyList()
                val catMap  = cats.associate { (it.id ?: "") to (it.name ?: "") }

                // 2. Live streams
                val streamUrl  = "$server/player_api.php?username=$user&password=$pass&action=get_live_streams"
                val streamJson = httpGet(streamUrl)
                val streamType = object : TypeToken<List<XtreamLiveStream>>() {}.type
                val streams: List<XtreamLiveStream> = gson.fromJson(streamJson, streamType) ?: emptyList()

                Log.d(TAG, "Xtream: ${streams.size} streams, ${cats.size} categories")

                streams.mapIndexedNotNull { idx, s ->
                    val id  = s.streamId ?: return@mapIndexedNotNull null
                    val nm  = s.name?.trim()?.takeIf { it.isNotEmpty() } ?: "Channel ${idx + 1}"
                    val grp = catMap[s.categoryId ?: ""]
                    // Names starting with "#" are provider markers, not real channels
                    if (nm.startsWith("#") || grp?.startsWith("#") == true)
                        return@mapIndexedNotNull null
                    Channel(
                        id    = "xt_$id",
                        name  = nm,
                        url   = "$server/live/$user/$pass/$id.ts",
                        logo  = s.icon?.takeIf { it.isNotBlank() },
                        group = grp,
                        type  = ChannelType.LIVE
                    )
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Xtream failed: ${e.message}")
                emptyList()
            }
        }

    private fun httpGet(url: String): String {
        val req = Request.Builder().url(url).build()
        return httpClient.newCall(req).execute().use { it.body?.string() ?: "[]" }
    }

    private fun parseM3U(content: String): List<Channel> {
        val channels  = mutableListOf<Channel>()
        var id        = 0
        var name      = ""
        var logo      = ""
        var group     = ""
        var hasExtInf = false

        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXTINF:")) {
                hasExtInf = true
                name  = trimmed.substringAfterLast(",").trim()
                logo  = Regex("""tvg-logo="([^"]+)"""").find(trimmed)?.groupValues?.get(1) ?: ""
                group = Regex("""group-title="([^"]+)"""").find(trimmed)?.groupValues?.get(1) ?: ""
            } else if (hasExtInf && (trimmed.startsWith("http") || trimmed.startsWith("rtmp"))) {
                // Filter separators/markers: hash-prefixed names, pure-dash/equals/plus lines, blanks
                val isSeparator = name.startsWith("#") || group.startsWith("#") ||
                    name.isBlank() || name.all { it == '-' || it == '=' || it == '+' || it == '*' || it == '_' }
                if (!isSeparator) {
                    val g    = group.lowercase()
                    val type = when {
                        g.contains("movie") || g.contains("film") || g.contains("vod") -> ChannelType.MOVIE
                        g.contains("serie") || g.contains("show")                       -> ChannelType.SERIES
                        else                                                             -> ChannelType.LIVE
                    }
                    channels.add(Channel(
                        id    = "ch_${id++}",
                        name  = name.ifEmpty { "Channel $id" },
                        url   = trimmed,
                        logo  = logo.ifEmpty { null },
                        group = group.ifEmpty { null },
                        type  = type
                    ))
                }
                hasExtInf = false
                name = ""; logo = ""; group = ""
            }
        }
        return channels
    }

    // ── EPG ───────────────────────────────────────────────────────────────────

    suspend fun fetchShortEpg(streamId: String): List<EpgItem> = withContext(Dispatchers.IO) {
        val prefs  = context.dataStore.data.first()
        val server = prefs[KEY_XTREAM_SERVER]
        val user   = prefs[KEY_XTREAM_USER]
        val pass   = prefs[KEY_XTREAM_PASS]

        val (s, u, p) = if (server != null && user != null && pass != null) {
            Triple(server, user, pass)
        } else {
            parseXtreamFromM3uUrl(prefs[KEY_M3U_URL]) ?: return@withContext emptyList()
        }

        try {
            val json = httpGet("$s/player_api.php?username=$u&password=$p&action=get_short_epg&stream_id=$streamId&limit=5")
            parseXtreamEpg(json)
        } catch (e: Exception) {
            Log.e(TAG, "EPG fetch failed: ${e.message}")
            emptyList()
        }
    }

    private fun parseXtreamFromM3uUrl(m3uUrl: String?): Triple<String, String, String>? {
        if (m3uUrl == null) return null
        return try {
            val uri    = java.net.URI(m3uUrl)
            val base   = "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
            val params = uri.query?.split("&")?.associate {
                val kv = it.split("=", limit = 2); kv[0] to kv.getOrElse(1) { "" }
            } ?: return null
            Triple(base, params["username"] ?: return null, params["password"] ?: return null)
        } catch (_: Exception) { null }
    }

    private fun parseXtreamEpg(json: String): List<EpgItem> = try {
        val response = gson.fromJson(json, XtreamEpgResponse::class.java)
        response.listings?.mapNotNull { item ->
            val startTs = item.startTimestamp?.toLongOrNull() ?: return@mapNotNull null
            val stopTs  = item.stopTimestamp?.toLongOrNull()  ?: return@mapNotNull null
            EpgItem(
                title       = decodeBase64OrPlain(item.title ?: ""),
                description = decodeBase64OrPlain(item.description ?: ""),
                startTs     = startTs,
                stopTs      = stopTs
            )
        } ?: emptyList()
    } catch (e: Exception) {
        Log.e(TAG, "EPG parse error: ${e.message}")
        emptyList()
    }

    private fun decodeBase64OrPlain(s: String): String = try {
        val decoded = android.util.Base64.decode(s, android.util.Base64.DEFAULT)
            .toString(Charsets.UTF_8).trim()
        if (decoded.isNotEmpty()) decoded else s
    } catch (_: Exception) { s }

    // ── Session ───────────────────────────────────────────────────────────────

    suspend fun logout() {
        deviceRepo.logout()
        context.dataStore.edit { it.clear() }
    }

    // ── Channel cache (persists across launches, expires after 12h) ──────────────

    private val cacheFile get() = File(context.filesDir, "channels_cache.json")
    private val cacheExpiryMs = 60 * 60 * 1000L // 1h

    fun saveChannelCache(channels: List<Channel>, creds: PlaylistCredentials) {
        try {
            val entry = ChannelCacheEntry(credentialKey(creds), System.currentTimeMillis(), channels)
            cacheFile.writeText(gson.toJson(entry))
        } catch (e: Exception) {
            Log.w(TAG, "Cache save failed: ${e.message}")
        }
    }

    fun loadChannelCache(creds: PlaylistCredentials): List<Channel>? {
        return try {
            if (!cacheFile.exists()) return null
            val entry = gson.fromJson(cacheFile.readText(), ChannelCacheEntry::class.java)
                ?: return null
            if (entry.credentialKey != credentialKey(creds)) return null
            if (System.currentTimeMillis() - entry.timestamp > cacheExpiryMs) return null
            entry.channels.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "Cache load failed: ${e.message}")
            try { cacheFile.delete() } catch (_: Exception) {}
            null
        }
    }

    fun clearChannelCache() { try { cacheFile.delete() } catch (_: Exception) {} }

    // Clears cache, contacts the server, saves fresh data. Returns channel count or -1 on failure.
    suspend fun reloadPlaylist(): Int {
        clearChannelCache()
        return try {
            val creds = getPlaylistCredentials()
            val channels: List<Channel> = when (creds) {
                is PlaylistCredentials.Xtream -> loadChannelsXtream(creds.server, creds.user, creds.pass)
                is PlaylistCredentials.M3u    -> loadChannels(creds.url).filter { it.type == com.hotplayer.data.model.ChannelType.LIVE }
                is PlaylistCredentials.None   -> return -1
            }
            if (channels.isNotEmpty()) saveChannelCache(channels, creds)
            channels.size
        } catch (e: Exception) {
            Log.e(TAG, "reloadPlaylist failed: ${e.message}")
            -1
        }
    }

    private fun credentialKey(creds: PlaylistCredentials) = when (creds) {
        is PlaylistCredentials.Xtream -> "xtream:${creds.server}:${creds.user}"
        is PlaylistCredentials.M3u    -> "m3u:${creds.url.hashCode()}"
        is PlaylistCredentials.None   -> "none"
    }

    private fun parseErrorCode(body: String?): String = try {
        com.google.gson.JsonParser.parseString(body ?: "").asJsonObject["code"]?.asString ?: "UNKNOWN"
    } catch (_: Exception) { "UNKNOWN" }

    private fun legacyErrorMessage(code: String) = when (code) {
        "DEVICE_INACTIVE"  -> "Appareil non activé. Contactez votre administrateur."
        "DEVICE_SUSPENDED" -> "Appareil suspendu. Contactez le support."
        "DEVICE_REVOKED"   -> "Accès révoqué. Contactez votre administrateur."
        "DEVICE_EXPIRED"   -> "Abonnement expiré. Veuillez renouveler."
        else               -> "Accès refusé. Contactez votre administrateur."
    }

    /** Device ID exposé pour affichage dans les écrans de debug/admin. */
    val deviceId: String get() = identity.deviceId

    fun getDeviceInfo(): Flow<Map<String, String>> =
        context.dataStore.data.map { prefs ->
            mapOf(
                "device_id" to identity.deviceId,
                "plan"      to (prefs[KEY_PLAN]       ?: "—"),
                "label"     to (prefs[KEY_LABEL]      ?: ""),
                "expiry"    to (prefs[KEY_EXPIRY_DATE] ?: "")
            )
        }
}
