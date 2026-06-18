package com.hotplayer.utils

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.Locale

private val Context.dataStore by preferencesDataStore(name = "hotplayer_prefs")

class MacAddressHelper(private val context: Context) {

    companion object {
        private val KEY_MAC         = stringPreferencesKey("device_mac")
        private val KEY_FINGERPRINT = stringPreferencesKey("device_fingerprint")
    }

    // FIX #1 (CRITICAL — DEADLOCK PREVENTION):
    // getDeviceMac() and getFingerprint() are called from suspend functions running on
    // Dispatchers.Main (viewModelScope). The previous runBlocking{} blocked the main thread
    // while DataStore dispatched work — on slow devices this caused a deadlock / ANR that
    // Android resolved by killing the process (the "app closes by itself" crash).
    //
    // Fix strategy:
    //   1. Cache the value in memory (@Volatile) — only the very first call hits DataStore.
    //   2. Force that first call onto Dispatchers.IO via runBlocking(Dispatchers.IO), so the
    //      main thread is never blocked waiting for DataStore IO.
    //
    @Volatile private var cachedMac: String?         = null
    @Volatile private var cachedFingerprint: String? = null

    fun getDeviceMac(): String =
        cachedMac ?: initMac().also { cachedMac = it }

    fun getFingerprint(mac: String): String =
        cachedFingerprint ?: initFingerprint(mac).also { cachedFingerprint = it }

    fun getDeviceModel(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    // ─── Private initializers (called at most once each) ──────────────────────

    private fun initMac(): String = runBlocking(Dispatchers.IO) {
        // Try persisted value first (fastest path on subsequent cold starts)
        val saved = context.dataStore.data.first()[KEY_MAC]
        if (!saved.isNullOrBlank()) return@runBlocking saved

        val mac = getRealMac() ?: generateStableMac()
        try {
            context.dataStore.edit { it[KEY_MAC] = mac }
        } catch (_: Exception) {}
        mac
    }

    private fun initFingerprint(mac: String): String = runBlocking(Dispatchers.IO) {
        val saved = context.dataStore.data.first()[KEY_FINGERPRINT]
        if (!saved.isNullOrBlank()) return@runBlocking saved

        val raw = buildString {
            append(mac); append("|")
            append(Build.MANUFACTURER); append("|")
            append(Build.MODEL); append("|")
            @Suppress("DEPRECATION")
            append(Build.SERIAL.take(8)); append("|")
            append(Build.BOARD); append("|")
            append(Build.HARDWARE)
        }
        val fp = sha256(raw).take(32)
        try {
            context.dataStore.edit { it[KEY_FINGERPRINT] = fp }
        } catch (_: Exception) {}
        fp
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
