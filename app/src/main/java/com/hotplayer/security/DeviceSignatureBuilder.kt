package com.hotplayer.security

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Construit la signature cryptographique envoyée à chaque requête d'authentification.
 *
 * Payload signé : "<deviceId>:<installationId>:<timestamp>:<nonce>"
 *
 * Le timestamp (Unix ms) et le nonce (UUID aléatoire) rendent chaque signature unique —
 * une signature interceptée ne peut pas être rejouée car le serveur vérifie que le
 * timestamp est récent (fenêtre de ±5 minutes) et que le nonce n'a pas déjà été utilisé.
 */
@Singleton
class DeviceSignatureBuilder @Inject constructor(
    private val keystore: AndroidKeystoreManager
) {

    data class SignedPayload(
        val timestamp: Long,
        val nonce: String,
        val signature: String   // HMAC-SHA256 hex (64 chars)
    )

    fun build(deviceId: String, installationId: String): SignedPayload {
        val timestamp = System.currentTimeMillis()
        val nonce     = UUID.randomUUID().toString()
        val raw       = "$deviceId:$installationId:$timestamp:$nonce"
        return SignedPayload(
            timestamp = timestamp,
            nonce     = nonce,
            signature = keystore.signHex(raw)
        )
    }
}
