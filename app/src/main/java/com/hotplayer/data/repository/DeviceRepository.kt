package com.hotplayer.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.hotplayer.BuildConfig
import com.hotplayer.data.api.DeviceApiService
import com.hotplayer.data.api.DeviceAuthRequest
import com.hotplayer.data.api.DeviceAuthResponse
import com.hotplayer.data.api.DeviceInfoPayload
import com.hotplayer.data.api.DeviceRegisterRequest
import com.hotplayer.data.api.DeviceHeartbeatResponse
import com.hotplayer.data.api.HeartbeatRequest
import com.hotplayer.data.api.LogoutRequest
import com.hotplayer.security.DeviceIdentityManager
import com.hotplayer.security.DeviceSignatureBuilder
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.deviceDataStore by preferencesDataStore(name = "hotplayer_device_auth")

/**
 * Gère l'enregistrement, l'authentification et la session basés sur le Device ID.
 *
 * Flux complet au démarrage de l'app :
 *  1. isRegistered() → si false, appeler register()
 *  2. isSessionValid() → si false, appeler authenticate()
 *  3. Utiliser le token JWT pour les requêtes métier
 *  4. Heartbeat toutes les 5 min pour maintenir la session
 */
@Singleton
class DeviceRepository @Inject constructor(
    private val context: Context,
    private val api: DeviceApiService,
    private val identity: DeviceIdentityManager,
    private val signer: DeviceSignatureBuilder
) {
    companion object {
        private const val TAG = "DeviceRepo"

        private val KEY_TOKEN      = stringPreferencesKey("device_token")
        private val KEY_EXPIRES    = stringPreferencesKey("device_token_expires")
        private val KEY_SESSION_ID = stringPreferencesKey("device_session_id")
        private val KEY_REGISTERED = booleanPreferencesKey("device_registered")
        private val KEY_MIGRATED   = booleanPreferencesKey("device_migrated")   // migration MAC→UUID terminée
        private val KEY_STATUS     = stringPreferencesKey("device_status")
        private val KEY_PLAN       = stringPreferencesKey("device_plan")
        private val KEY_LABEL      = stringPreferencesKey("device_label")
    }

    private val gson = Gson()

    // ── Résultat typé ─────────────────────────────────────────────────────────

    sealed class DeviceResult {
        data class Success(val response: DeviceAuthResponse) : DeviceResult()
        data class Failure(val code: String, val message: String) : DeviceResult()
        object NotActivated : DeviceResult()
        object NetworkError : DeviceResult()
    }

    // ── Enregistrement ────────────────────────────────────────────────────────

    /**
     * Enregistre l'appareil sur le serveur si ce n'est pas encore fait.
     * L'enregistrement crée l'entrée device en base — sans activer l'accès.
     * L'activation (assignation d'une playlist) est faite par l'admin.
     *
     * @return true si l'appareil est désormais connu du serveur.
     */
    suspend fun registerIfNeeded(): Boolean {
        if (isRegistered()) return true

        val signed = signer.build(identity.deviceId, identity.installationId)
        return try {
            val response = api.register(
                deviceId = identity.deviceId,
                body = DeviceRegisterRequest(
                    deviceId       = identity.deviceId,
                    installationId = identity.installationId,
                    fingerprint    = identity.deviceFingerprint,
                    signature      = signed.signature,
                    timestamp      = signed.timestamp,
                    nonce          = signed.nonce,
                    deviceInfo     = DeviceInfoPayload(
                        model      = identity.deviceModel,
                        osVersion  = identity.osVersion,
                        appVersion = BuildConfig.VERSION_NAME
                    )
                )
            )
            if (response.isSuccessful) {
                val body = response.body()!!
                context.deviceDataStore.edit { prefs ->
                    prefs[KEY_REGISTERED] = true
                    prefs[KEY_STATUS]     = body.deviceStatus
                }
                Log.i(TAG, "Device registered — status: ${body.deviceStatus}")
                true
            } else {
                Log.w(TAG, "Register failed: ${response.code()}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Register network error", e)
            false
        }
    }

    // ── Authentification ──────────────────────────────────────────────────────

    /**
     * Échange le Device ID + signature HMAC contre un JWT de session.
     *
     * La signature est calculée avec la clé HMAC stockée dans l'Android Keystore.
     * Le serveur vérifie que :
     *  - le deviceId est connu et actif
     *  - le timestamp est dans la fenêtre de ±5 minutes (anti-replay)
     *  - le nonce n'a pas déjà été utilisé (anti-replay)
     *  - la signature HMAC correspond (l'appareil possède bien la clé)
     */
    suspend fun authenticate(): DeviceResult {
        val signed = signer.build(identity.deviceId, identity.installationId)
        return try {
            val response = api.authenticate(
                deviceId = identity.deviceId,
                body = DeviceAuthRequest(
                    deviceId       = identity.deviceId,
                    installationId = identity.installationId,
                    fingerprint    = identity.deviceFingerprint,
                    signature      = signed.signature,
                    timestamp      = signed.timestamp,
                    nonce          = signed.nonce
                )
            )
            if (response.isSuccessful) {
                val body = response.body()!!
                context.deviceDataStore.edit { prefs ->
                    prefs[KEY_TOKEN]      = body.token
                    prefs[KEY_EXPIRES]    = body.expiresAt
                    prefs[KEY_SESSION_ID] = body.sessionId
                    prefs[KEY_STATUS]     = body.device.status
                    prefs[KEY_PLAN]       = body.device.plan
                    prefs[KEY_LABEL]      = body.device.label ?: ""
                }
                Log.i(TAG, "Authenticated — session: ${body.sessionId}")
                DeviceResult.Success(body)
            } else if (response.code() == 403) {
                DeviceResult.NotActivated
            } else {
                val code = parseErrorCode(response.errorBody()?.string())
                DeviceResult.Failure(code, errorMessage(code))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auth network error", e)
            DeviceResult.NetworkError
        }
    }

    // ── Heartbeat ─────────────────────────────────────────────────────────────

    /**
     * Maintient la session active.
     * Si le serveur renvoie un nouveau token (rotation), il est sauvegardé automatiquement.
     *
     * @return true si la session est toujours valide.
     */
    suspend fun heartbeat(): Boolean {
        val prefs     = context.deviceDataStore.data.first()
        val token     = prefs[KEY_TOKEN]      ?: return false
        val sessionId = prefs[KEY_SESSION_ID] ?: return false
        return try {
            val response = api.heartbeat(
                bearerToken = "Bearer $token",
                deviceId    = identity.deviceId,
                body        = HeartbeatRequest(identity.deviceId, sessionId)
            )
            if (response.isSuccessful) {
                val body = response.body()!!
                // Rotation de token si le serveur en fournit un nouveau
                if (body.newToken != null && body.expiresAt != null) {
                    context.deviceDataStore.edit { p ->
                        p[KEY_TOKEN]   = body.newToken
                        p[KEY_EXPIRES] = body.expiresAt
                    }
                    Log.d(TAG, "Token rotated")
                }
                true
            } else false
        } catch (e: Exception) {
            Log.w(TAG, "Heartbeat failed: ${e.message}")
            false
        }
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    suspend fun logout() {
        val prefs     = context.deviceDataStore.data.first()
        val token     = prefs[KEY_TOKEN]
        val sessionId = prefs[KEY_SESSION_ID]
        try {
            if (token != null && sessionId != null) {
                api.logout(
                    bearerToken = "Bearer $token",
                    deviceId    = identity.deviceId,
                    body        = LogoutRequest(identity.deviceId, sessionId)
                )
            }
        } catch (_: Exception) {}
        context.deviceDataStore.edit { it.clear() }
    }

    // ── Accesseurs ────────────────────────────────────────────────────────────

    suspend fun getToken(): String? =
        context.deviceDataStore.data.first()[KEY_TOKEN]

    suspend fun getSessionId(): String? =
        context.deviceDataStore.data.first()[KEY_SESSION_ID]

    suspend fun isSessionValid(): Boolean {
        val prefs   = context.deviceDataStore.data.first()
        val token   = prefs[KEY_TOKEN]   ?: return false
        val expires = prefs[KEY_EXPIRES] ?: return false
        return token.isNotBlank() && !isExpired(expires)
    }

    // ── Migration ─────────────────────────────────────────────────────────────

    /**
     * Retourne true si la migration MAC→Device ID a déjà été effectuée
     * ou si l'appareil a été enregistré directement avec le nouveau système.
     */
    suspend fun isMigrated(): Boolean =
        context.deviceDataStore.data.first()[KEY_MIGRATED] == true

    /**
     * Finalise la migration legacy : persiste le token reçu de l'ancien endpoint
     * et marque l'appareil comme migré.
     * Appelé après un `POST /auth/activate` réussi avec MAC.
     */
    suspend fun completeLegacyMigration(
        token:     String,
        expiresAt: String,
        sessionId: String,
        plan:      String,
        label:     String,
    ) {
        context.deviceDataStore.edit { prefs ->
            prefs[KEY_TOKEN]      = token
            prefs[KEY_EXPIRES]    = expiresAt
            prefs[KEY_SESSION_ID] = sessionId   // UUID de session (nouveau format)
            prefs[KEY_PLAN]       = plan
            prefs[KEY_LABEL]      = label
            prefs[KEY_REGISTERED] = true
            prefs[KEY_MIGRATED]   = true
        }
        Log.i(TAG, "Legacy migration completed — session saved")
    }

    private suspend fun isRegistered(): Boolean =
        context.deviceDataStore.data.first()[KEY_REGISTERED] == true

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun isExpired(isoDate: String): Boolean = try {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        sdf.parse(isoDate)?.before(java.util.Date()) ?: true
    } catch (_: Exception) { false }

    private fun parseErrorCode(body: String?): String = try {
        com.google.gson.JsonParser.parseString(body ?: "").asJsonObject["code"]?.asString ?: "UNKNOWN"
    } catch (_: Exception) { "UNKNOWN" }

    private fun errorMessage(code: String) = when (code) {
        "DEVICE_NOT_FOUND"   -> "Appareil non enregistré. Contactez votre administrateur."
        "DEVICE_INACTIVE"    -> "Appareil non activé. Contactez votre administrateur."
        "DEVICE_SUSPENDED"   -> "Appareil suspendu. Contactez le support."
        "DEVICE_EXPIRED"     -> "Abonnement expiré. Veuillez renouveler."
        "SIGNATURE_INVALID"  -> "Signature invalide. Réinstallez l'application."
        "REPLAY_DETECTED"    -> "Requête rejouée détectée. Vérifiez l'heure système."
        "CLONE_DETECTED"     -> "Appareil cloné détecté. Accès refusé."
        "MAX_DEVICES"        -> "Nombre maximum d'appareils atteint."
        else                 -> "Accès refusé. Contactez votre administrateur."
    }
}
