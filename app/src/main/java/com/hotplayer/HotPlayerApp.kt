package com.hotplayer

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import com.hotplayer.data.model.Channel
import com.hotplayer.data.repository.DeviceRepository
import com.hotplayer.data.repository.SessionRepository
import com.hotplayer.security.DeviceIdentityManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Point d'entrée de l'application.
 * @HiltAndroidApp déclenche la génération du code Hilt et initialise le graphe de dépendances.
 */
@HiltAndroidApp
class HotPlayerApp : Application() {

    companion object {
        lateinit var instance: HotPlayerApp
            private set
    }

    // Injectés par Hilt — disponibles après super.onCreate()
    @Inject lateinit var sessionRepo: SessionRepository
    @Inject lateinit var deviceRepo: DeviceRepository
    @Inject lateinit var deviceIdentity: DeviceIdentityManager

    /** Préférences UI légères (thème, filtres) — non sensibles, pas d'EncryptedSharedPreferences. */
    val prefs: SharedPreferences by lazy { getSharedPreferences("hp_prefs", MODE_PRIVATE) }

    /** Liste de chaînes partagée pour la navigation UP/DOWN dans PlayerActivity. */
    var liveTvChannels: List<Channel> = emptyList()
    var liveTvChannelIndex: Int = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashLogger()
    }

    /**
     * Filet de diagnostic minimal en attendant un vrai crash reporter (Crashlytics —
     * nécessite un projet Firebase, absent du repo actuellement, voir rapport de phase 1).
     * Logue le crash complet AVANT de déléguer au handler par défaut : ne change rien
     * au comportement de crash existant, rend seulement visible ce qui se passe.
     */
    private fun installCrashLogger() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e("HotPlayerCrash", "FATAL on thread '${thread.name}': ${throwable.message}", throwable)
            } catch (_: Throwable) {
                // Never let the crash logger itself prevent the crash from being reported.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
