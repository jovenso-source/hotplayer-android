package com.hotplayer.data.monitoring

/**
 * Types d'erreurs surveillés par le système de monitoring (voir MonitoringReporter).
 *
 * Générique par construction : ERROR_3003 est le premier type surveillé, pas le seul prévu.
 * Ajouter un nouveau type plus tard (auth, lecture, playlist, serveur, réseau…) ne demande
 * qu'une nouvelle valeur ici — aucun changement de route backend ni de schéma de base de
 * données, `error_code` étant une colonne/valeur libre des deux côtés.
 */
enum class MonitoredError(val code: String) {
    ERROR_3003("ERROR_3003");

    companion object {
        /**
         * Traduit un code d'erreur ExoPlayer (PlaybackException.errorCode) vers un type
         * surveillé, ou null si ce code n'est pas monitoré. Seul point à modifier pour
         * surveiller un nouveau code ExoPlayer.
         */
        fun fromExoPlayerErrorCode(errorCode: Int): MonitoredError? = when (errorCode) {
            3003 -> ERROR_3003
            else -> null
        }
    }
}
