package com.hotplayer.utils

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.Locale

private val Context.dataStore by preferencesDataStore(name = "hotplayer_prefs")

class MacAddressHelper(private val context: Context) {

    companion object {
        private val KEY_MAC = stringPreferencesKey("device_mac")
    }

    @Volatile private var cachedMac: String? = null

    /**
     * Returns the device MAC address for legacy authentication only.
     * New devices use DeviceIdentityManager (UUID-based). This class exists solely to
     * support CAS A migration for devices registered before v1.5.2.
     */
    @Deprecated("Use DeviceIdentityManager for device identification. MAC lookup is for legacy migration only.")
    suspend fun getDeviceMac(): String =
        cachedMac ?: initMac().also { cachedMac = it }

    // ─── Private initializer ──────────────────────────────────────────────────

    private suspend fun initMac(): String = withContext(Dispatchers.IO) {
        val saved = context.dataStore.data.first()[KEY_MAC]
        if (!saved.isNullOrBlank()) return@withContext saved
        val mac = getRealMac() ?: generateStableMac()
        try { context.dataStore.edit { it[KEY_MAC] = mac } } catch (_: Exception) {}
        mac
    }

    // ─── Hardware MAC detection ───────────────────────────────────────────────

    private fun getRealMac(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            for (iface in interfaces) {
                if (iface.name !in listOf("eth0", "eth1", "wlan0", "wlan1")) continue
                val mac = iface.hardwareAddress ?: continue
                if (mac.size != 6) continue
                val macStr = mac.joinToString(":") { "%02X".format(it) }
                if (macStr != "00:00:00:00:00:00" && macStr != "02:00:00:00:00:00")
                    return macStr
            }
        } catch (_: Exception) {}

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            try {
                @Suppress("DEPRECATION")
                val wm = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                val mac = wm.connectionInfo?.macAddress
                if (!mac.isNullOrBlank() && mac != "02:00:00:00:00:00")
                    return mac.uppercase(Locale.ROOT)
            } catch (_: Exception) {}
        }
        return null
    }

    private fun generateStableMac(): String {
        val seed = buildString {
            append(Build.BOARD); append(Build.BOOTLOADER); append(Build.DEVICE)
            append(Build.DISPLAY); append(Build.FINGERPRINT.take(20))
            append(Build.HARDWARE); append(Build.HOST); append(Build.ID)
            append(Build.MANUFACTURER); append(Build.MODEL); append(Build.PRODUCT)
        }
        return sha256(seed).take(12).chunked(2).joinToString(":").uppercase(Locale.ROOT)
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
