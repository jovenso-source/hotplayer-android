package com.hotplayer.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.deviceDataStore by preferencesDataStore(name = "hotplayer_device_auth")

/**
 * Local session storage for device authentication.
 *
 * Responsibilities (storage only — no network):
 *   - Persist the JWT token received from POST /auth/activate
 *   - Track whether the device has ever authenticated (KEY_MIGRATED)
 *   - Provide getToken() for authenticated calls (heartbeat, playlist, logout)
 *   - Clear local state on logout
 *
 * All authentication logic lives in SessionRepository.
 */
@Singleton
class DeviceRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "DeviceRepo"
        private val KEY_TOKEN      = stringPreferencesKey("device_token")
        private val KEY_EXPIRES    = stringPreferencesKey("device_token_expires")
        private val KEY_SESSION_ID = stringPreferencesKey("device_session_id")
        private val KEY_MIGRATED   = booleanPreferencesKey("device_migrated")
        private val KEY_PLAN       = stringPreferencesKey("device_plan")
        private val KEY_LABEL      = stringPreferencesKey("device_label")
    }

    /**
     * Persists session data after a successful POST /auth/activate response.
     * Called by SessionRepository for both legacy MAC auth (CAS A) and UUID-only auth (CAS B).
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
            prefs[KEY_SESSION_ID] = sessionId
            prefs[KEY_PLAN]       = plan
            prefs[KEY_LABEL]      = label
            prefs[KEY_MIGRATED]   = true
        }
        Log.i(TAG, "Session persisted")
    }

    /** Returns the stored JWT token, or null if not yet authenticated. */
    suspend fun getToken(): String? =
        context.deviceDataStore.data.first()[KEY_TOKEN]

    /**
     * Returns true if this device has completed at least one successful authentication.
     * false = first launch or after factory reset / app reinstall.
     */
    suspend fun isMigrated(): Boolean =
        context.deviceDataStore.data.first()[KEY_MIGRATED] == true

    /** Clears all local session data. Called by SessionRepository.logout(). */
    suspend fun clearLocalSession() {
        context.deviceDataStore.edit { it.clear() }
    }
}
