package com.hotplayer.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gère la clé HMAC-SHA256 stockée dans l'Android Keystore (hardware-backed sur les appareils
 * compatibles). La clé ne quitte jamais le Keystore — aucune copie en mémoire ou sur disque.
 *
 * Pourquoi le Keystore plutôt qu'une clé stockée en clair ?
 * → Même si l'appareil est rooté, la clé reste protégée par l'enclave sécurisée.
 * → Rend impossible la copie de l'identité device sur un autre appareil sans accès physique.
 */
@Singleton
class AndroidKeystoreManager @Inject constructor() {

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val HMAC_KEY_ALIAS    = "hotplayer_device_hmac_key_v1"
        private const val HMAC_ALGORITHM    = "HmacSHA256"
    }

    /** Calcule HMAC-SHA256 du payload avec la clé du Keystore. */
    fun sign(payload: String): ByteArray {
        val key = getOrCreateHmacKey()
        return Mac.getInstance(HMAC_ALGORITHM).run {
            init(key)
            doFinal(payload.toByteArray(Charsets.UTF_8))
        }
    }

    /** Encode la signature en hex (64 caractères). */
    fun signHex(payload: String): String =
        sign(payload).joinToString("") { "%02x".format(it) }

    /** Retourne true si la clé existe déjà dans le Keystore. */
    fun hasKey(): Boolean = KeyStore.getInstance(KEYSTORE_PROVIDER).run {
        load(null)
        containsAlias(HMAC_KEY_ALIAS)
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun getOrCreateHmacKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

        keyStore.getKey(HMAC_KEY_ALIAS, null)?.let { return it as SecretKey }

        val spec = KeyGenParameterSpec.Builder(
            HMAC_KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setKeySize(256)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()

        return KeyGenerator.getInstance(HMAC_ALGORITHM, KEYSTORE_PROVIDER).run {
            init(spec)
            generateKey()
        }
    }
}
