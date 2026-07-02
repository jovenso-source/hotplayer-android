package com.hotplayer.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Source unique de vérité pour l'identité de l'appareil.
 *
 * ┌─────────────────┬──────────────────────────────────────────────────────────┐
 * │ deviceId        │ UUID v4 généré une seule fois, permanent même après MAJ  │
 * │ installationId  │ UUID v4 généré par installation — change après réinst.   │
 * │ deviceFingerprint│ SHA-256 des propriétés hardware (non-PII)               │
 * └─────────────────┴──────────────────────────────────────────────────────────┘
 *
 * Pourquoi ne pas utiliser l'adresse MAC ?
 * → Depuis Android 10+, la MAC est randomisée par réseau Wi-Fi (retourne 02:00:00:00:00:00).
 * → Sur Android 6–9, NetworkInterface retourne aussi 02:00:00:00:00:00 pour des raisons de vie privée.
 * → Un UUID v4 cryptographiquement sécurisé est 100 % fiable sur tous les OS et versions.
 *
 * Pourquoi ne pas utiliser Android ID ?
 * → Android ID change lors d'une réinitialisation d'usine, d'un changement de compte,
 *   et diffère par application depuis Android 8. Peu fiable pour identifier un appareil.
 *
 * Stockage :
 * → API 23+ : EncryptedSharedPreferences (AES-256-GCM) — clé maîtresse dans le Keystore.
 * → API 21-22 : SharedPreferences ordinaire (fallback, moins de 0.2 % du marché en 2026).
 */
@Singleton
class DeviceIdentityManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val PREFS_FILE   = "hotplayer_device_identity"
        private const val KEY_DEVICE_ID      = "device_id_v1"
        private const val KEY_INSTALLATION_ID = "installation_id_v1"
    }

    private val prefs: SharedPreferences by lazy { openPrefs() }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Device ID stable, survit aux mises à jour de l'app.
     * Utilisé comme champ `by lazy` avec cache volatile pour permettre la mise à jour
     * lors de la migration (le serveur peut retourner un UUID différent si le device
     * avait déjà un ID assigné).
     */
    @Volatile private var cachedDeviceId: String? = null

    val deviceId: String
        get() = cachedDeviceId
            ?: (prefs.getString(KEY_DEVICE_ID, null) ?: generateAndStore(KEY_DEVICE_ID))
                .also { cachedDeviceId = it }

    /**
     * Remplace le device ID local par celui retourné par le serveur lors de la migration.
     * N'est appelé qu'une seule fois, lors de la première connexion post-migration.
     */
    fun assignServerDeviceId(serverDeviceId: String) {
        prefs.edit().putString(KEY_DEVICE_ID, serverDeviceId).apply()
        cachedDeviceId = serverDeviceId
    }

    /** Installation ID — change à chaque réinstallation. */
    val installationId: String by lazy {
        prefs.getString(KEY_INSTALLATION_ID, null) ?: generateAndStore(KEY_INSTALLATION_ID)
    }

    /**
     * Empreinte hardware : SHA-256 de propriétés non-PII de l'appareil.
     * Utilisée côté serveur pour détecter les clonages (même deviceId, empreinte différente).
     */
    val deviceFingerprint: String by lazy {
        val raw = listOf(
            Build.BOARD,
            Build.BOOTLOADER,
            Build.DEVICE,
            Build.HARDWARE,
            Build.MANUFACTURER,
            Build.MODEL,
            Build.PRODUCT,
            Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        ).joinToString("|")
        sha256(raw).take(32)
    }

    /** Modèle lisible pour affichage côté admin. */
    val deviceModel: String get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    /** Plateforme et version OS. */
    val osVersion: String get() = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    // ── Private ───────────────────────────────────────────────────────────────

    private fun generateAndStore(key: String): String {
        val uuid = UUID.randomUUID().toString()
        prefs.edit().putString(key, uuid).apply()
        return uuid
    }

    private fun openPrefs(): SharedPreferences {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return try {
                openEncryptedPrefs()
            } catch (e: Exception) {
                // Fallback en cas d'erreur Keystore (ex. appareil rooté ayant perdu l'enclave)
                context.getSharedPreferences(PREFS_FILE + "_plain", Context.MODE_PRIVATE)
            }
        }
        return context.getSharedPreferences(PREFS_FILE + "_plain", Context.MODE_PRIVATE)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun openEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
