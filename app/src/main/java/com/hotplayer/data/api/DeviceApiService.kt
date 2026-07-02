package com.hotplayer.data.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Interface Retrofit pour le système d'authentification par Device ID.
 *
 * Tous les endpoints utilisent X-Device-ID dans le header (loggué côté serveur
 * même en cas d'erreur 4xx) et le body JSON pour le payload signé.
 *
 * Pourquoi des headers + body plutôt que tout en body ?
 * → Les headers permettent de logguer/filtrer l'appareil au niveau du reverse proxy
 *   sans parser le body — utile pour le rate-limiting anti-fraude.
 */
interface DeviceApiService {

    /**
     * Enregistre l'appareil sur le serveur.
     * → Appel unique au premier lancement ou après réinstallation.
     */
    @POST("device/register")
    suspend fun register(
        @Header("X-Device-ID") deviceId: String,
        @Body body: DeviceRegisterRequest
    ): Response<DeviceRegisterResponse>

    /**
     * Authentifie l'appareil et obtient un JWT de session.
     * → Appelé à chaque démarrage de l'app si la session est expirée.
     */
    @POST("device/authenticate")
    suspend fun authenticate(
        @Header("X-Device-ID") deviceId: String,
        @Body body: DeviceAuthRequest
    ): Response<DeviceAuthResponse>

    /**
     * Heartbeat : maintient la session active et reçoit un token renouvelé si nécessaire.
     * → Appelé toutes les 5 minutes pendant l'utilisation.
     */
    @POST("device/heartbeat")
    suspend fun heartbeat(
        @Header("Authorization") bearerToken: String,
        @Header("X-Device-ID")   deviceId: String,
        @Body body: HeartbeatRequest
    ): Response<DeviceHeartbeatResponse>

    /**
     * Invalide la session côté serveur.
     */
    @POST("device/logout")
    suspend fun logout(
        @Header("Authorization") bearerToken: String,
        @Header("X-Device-ID")   deviceId: String,
        @Body body: LogoutRequest
    ): Response<Unit>
}
