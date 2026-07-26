package com.hotplayer.data.monitoring

import android.util.Log
import com.hotplayer.data.api.HotPlayerApi
import com.hotplayer.data.api.MonitoringEventRequest
import com.hotplayer.security.DeviceIdentityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reporting générique d'erreurs vers le backend (POST /api/events) — entièrement
 * fire-and-forget, jamais sur le thread principal, jamais visible pour l'utilisateur.
 *
 * Garanties :
 *  - report() est sans effet de bord bloquant : au pire un accès à une ConcurrentHashMap,
 *    tout le reste (I/O réseau, lecture du Device UUID) tourne sur Dispatchers.IO ;
 *  - un échec de n'importe quelle nature (réseau, HTTP, autre) est intercepté et seulement
 *    loggué techniquement — jamais remonté à l'appelant, jamais affiché à l'utilisateur ;
 *  - le log ne contient jamais l'URL de la chaîne, un credential ou une donnée sensible —
 *    uniquement le code d'erreur surveillé et le message d'exception ;
 *  - un cooldown léger en mémoire (par type d'erreur) évite de solliciter le réseau en
 *    boucle si la même erreur se répète rapidement dans la même session ; il ne remplace
 *    pas l'anti-spam réel, qui est piloté par le backend (persistant, survit à un
 *    redémarrage de l'app) — voir POST /api/events côté serveur.
 */
@Singleton
class MonitoringReporter @Inject constructor(
    private val api: HotPlayerApi,
    private val identity: DeviceIdentityManager
) {
    companion object {
        private const val TAG = "MonitoringReporter"
        private const val CLIENT_COOLDOWN_MS = 60_000L
    }

    // Portée applicative (Singleton) : un rapport lancé depuis un écran survit même si
    // l'utilisateur quitte cet écran juste après l'erreur — pas de viewModelScope/lifecycleScope.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastSentAt = ConcurrentHashMap<String, Long>()

    /** Signale une erreur surveillée. Ne fait jamais rien de synchrone ni de bloquant. */
    fun report(error: MonitoredError, context: String? = null) {
        val now  = System.currentTimeMillis()
        val last = lastSentAt[error.code]
        if (last != null && now - last < CLIENT_COOLDOWN_MS) return
        lastSentAt[error.code] = now

        scope.launch {
            try {
                val body = MonitoringEventRequest(
                    eventId   = UUID.randomUUID().toString(),
                    errorCode = error.code,
                    context   = context
                )
                api.reportEvent(identity.deviceId, body)
            } catch (e: Throwable) {
                Log.w(TAG, "report(${error.code}) failed: ${e.message}")
            }
        }
    }
}
