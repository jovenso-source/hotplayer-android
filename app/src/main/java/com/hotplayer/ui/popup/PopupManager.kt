package com.hotplayer.ui.popup

import android.app.Dialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.hotplayer.BuildConfig
import com.hotplayer.data.model.PopupConfig
import com.hotplayer.data.model.PopupResponse
import com.hotplayer.databinding.DialogPopupBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object PopupManager {

    private val POPUP_CONFIG_URL = BuildConfig.API_BASE_URL.trimEnd('/').substringBeforeLast("/api") + "/api/popup"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // APK en attente d'installation (après retour des settings de permission)
    private var pendingInstallFile: File? = null

    // ─── Public entry points ───────────────────────────────────────────────────

    suspend fun checkAndShow(activity: AppCompatActivity) {
        try {
            val config = fetchConfig() ?: return
            if (!shouldShow(activity, config)) return
            withContext(Dispatchers.Main) {
                showDialog(activity, config)
            }
        } catch (_: Throwable) {}
    }

    fun onActivityResumed(activity: AppCompatActivity) {
        val file = pendingInstallFile ?: return
        if (!file.exists()) { pendingInstallFile = null; return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            activity.packageManager.canRequestPackageInstalls()
        ) {
            pendingInstallFile = null
            installApk(activity, file)
        }
    }

    // ─── Fetch JSON config ─────────────────────────────────────────────────────

    private suspend fun fetchConfig(): PopupConfig? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(POPUP_CONFIG_URL).build()
            val body = http.newCall(req).execute().use { it.body?.string() } ?: return@withContext null
            Gson().fromJson(body, PopupResponse::class.java)?.popup
        } catch (_: Throwable) { null }
    }

    // ─── Seen-state helpers ────────────────────────────────────────────────────

    private fun shouldShow(activity: AppCompatActivity, config: PopupConfig): Boolean {
        val prefs = activity.getSharedPreferences("popup_prefs", Context.MODE_PRIVATE)

        if (config.type == "update" && config.newVersion != null) {
            // Vérifier la version RÉELLEMENT installée sur l'appareil (pas BuildConfig)
            val installedVersion = getInstalledVersion(activity)
            if (versionAtLeast(installedVersion, config.newVersion)) {
                // La nouvelle version est installée → marquer comme vu et ne pas afficher
                markSeen(activity, config.id)
                return false
            }
            // Version pas encore installée → toujours afficher, ignorer seen_ pour les updates
            return true
        }

        // Pour les autres types (annonce, maintenance) : vérifier seen normalement
        if (prefs.getBoolean("seen_${config.id}", false)) return false
        return true
    }

    private fun getInstalledVersion(context: android.content.Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        } catch (_: Exception) { "0" }
    }

    private fun versionAtLeast(current: String, target: String): Boolean {
        val c = current.trim().split(".").mapNotNull { it.toIntOrNull() }
        val t = target.trim().split(".").mapNotNull { it.toIntOrNull() }
        val len = maxOf(c.size, t.size)
        for (i in 0 until len) {
            val cv = c.getOrElse(i) { 0 }
            val tv = t.getOrElse(i) { 0 }
            if (cv > tv) return true
            if (cv < tv) return false
        }
        return true // versions égales
    }

    private fun markSeen(activity: AppCompatActivity, id: String) {
        activity.getSharedPreferences("popup_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("seen_$id", true).apply()
    }

    // ─── Dialog ────────────────────────────────────────────────────────────────

    private fun showDialog(activity: AppCompatActivity, config: PopupConfig) {
        val binding = DialogPopupBinding.inflate(activity.layoutInflater)
        val dialog  = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setContentView(binding.root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.7f)
        }
        dialog.setCancelable(!config.force)

        applyConfig(binding, config)
        wireButtons(activity, dialog, binding, config)

        val autoClose = config.autoCloseSec
        if (autoClose != null && autoClose > 0 && !config.force) {
            android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed({ if (dialog.isShowing) dialog.dismiss() }, autoClose * 1000L)
        }

        if (activity.isFinishing || activity.isDestroyed) return
        dialog.show()
        binding.btnPopupPrimary.post { binding.btnPopupPrimary.requestFocus() }
    }

    private fun applyConfig(binding: DialogPopupBinding, config: PopupConfig) {
        val (icon, badge, badgeColor) = when (config.type) {
            "update"       -> Triple("🚀", "MISE À JOUR", "#f5a623")
            "maintenance"  -> Triple("⚠️",  "MAINTENANCE",  "#e53e3e")
            else           -> Triple("📢", "ANNONCE",      "#00A8FF")
        }
        binding.tvPopupIcon.text  = icon
        binding.tvPopupBadge.text = badge
        binding.tvPopupBadge.backgroundTintList =
            android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(badgeColor))

        binding.tvPopupTitle.text   = config.title
        binding.tvPopupMessage.text = config.message

        if (config.type == "update" && (config.currentVersion != null || config.newVersion != null)) {
            binding.layoutVersionInfo.visibility = View.VISIBLE
            binding.tvCurrentVersion.text = config.currentVersion ?: BuildConfig.VERSION_NAME
            binding.tvNewVersion.text     = config.newVersion ?: "?"
        }

        binding.btnPopupSecondary.text = config.buttonOk
        if (config.force) binding.btnPopupSecondary.visibility = View.GONE

        val actionLabel = config.buttonAction
        if (actionLabel != null) {
            binding.btnPopupPrimary.text = actionLabel
        } else {
            binding.btnPopupPrimary.visibility = View.GONE
            binding.btnPopupSecondary.visibility = View.VISIBLE
        }
    }

    private fun wireButtons(
        activity: AppCompatActivity,
        dialog: Dialog,
        binding: DialogPopupBinding,
        config: PopupConfig
    ) {
        binding.btnPopupSecondary.setOnClickListener {
            if (!config.force) {
                markSeen(activity, config.id)
                dialog.dismiss()
            }
        }

        binding.btnPopupPrimary.setOnClickListener {
            val url = config.downloadUrl
            if (config.type == "update" && url != null) {
                startDownload(activity, dialog, binding, config, url)
            } else {
                markSeen(activity, config.id)
                dialog.dismiss()
            }
        }
    }

    // ─── Download via DownloadManager (système Android) ──────────────────────

    private fun startDownload(
        activity: AppCompatActivity,
        dialog: Dialog,
        binding: DialogPopupBinding,
        config: PopupConfig,
        url: String
    ) {
        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (dm == null) {
            Toast.makeText(activity, "Téléchargement non supporté sur cet appareil.", Toast.LENGTH_LONG).show()
            return
        }

        val destFile = File(
            activity.getExternalFilesDir(null) ?: activity.cacheDir,
            "hotplayer_update.apk"
        )
        if (destFile.exists()) destFile.delete()

        val downloadId = try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("HotPlayer mise à jour")
                .setDescription("Téléchargement en cours…")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(destFile))
                .addRequestHeader("User-Agent", "HotPlayer/1.0 Android")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
            dm.enqueue(request)
        } catch (e: Exception) {
            Toast.makeText(activity, "Impossible de démarrer le téléchargement : ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        binding.btnPopupPrimary.isEnabled = false
        binding.btnPopupPrimary.text      = "Téléchargement…"
        binding.layoutProgress.visibility = View.VISIBLE

        activity.lifecycleScope.launch {
            var running = true
            while (running) {
                delay(600)
                val cursor: Cursor? = try {
                    dm.query(DownloadManager.Query().setFilterById(downloadId))
                } catch (_: Exception) { null }

                if (cursor == null || !cursor.moveToFirst()) {
                    cursor?.close()
                    continue
                }

                val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val bytesIdx  = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIdx  = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val status = if (statusIdx >= 0) cursor.getInt(statusIdx) else -1
                val bytes  = if (bytesIdx  >= 0) cursor.getLong(bytesIdx)  else 0L
                val total  = if (totalIdx  >= 0) cursor.getLong(totalIdx)  else -1L
                cursor.close()

                if (total > 0) {
                    val pct = (bytes * 100 / total).toInt().coerceIn(0, 100)
                    binding.pbDownload.progress    = pct
                    binding.tvProgressPercent.text = "$pct%"
                }

                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        running = false
                        binding.pbDownload.progress    = 100
                        binding.tvProgressPercent.text = "100%"
                        binding.layoutProgress.visibility = View.GONE
                        binding.btnPopupPrimary.isEnabled = true
                        binding.btnPopupPrimary.text      = "Installer maintenant"
                        binding.btnPopupPrimary.requestFocus()
                        binding.btnPopupPrimary.setOnClickListener {
                            dialog.dismiss()
                            installApk(activity, destFile)
                        }
                    }
                    DownloadManager.STATUS_FAILED -> {
                        running = false
                        val reasonIdx = try {
                            dm.query(DownloadManager.Query().setFilterById(downloadId))
                                .use { c -> if (c.moveToFirst()) c.getInt(c.getColumnIndex(DownloadManager.COLUMN_REASON)) else -1 }
                        } catch (_: Exception) { -1 }
                        binding.layoutProgress.visibility = View.GONE
                        binding.btnPopupPrimary.isEnabled = true
                        binding.btnPopupPrimary.text      = config.buttonAction ?: "Réessayer"
                        Toast.makeText(activity, "Échec du téléchargement (code $reasonIdx).", Toast.LENGTH_LONG).show()
                    }
                    DownloadManager.STATUS_PAUSED -> {
                        binding.tvProgressPercent.text = "En attente…"
                    }
                }
            }
        }
    }

    // ─── APK install ───────────────────────────────────────────────────────────

    private fun installApk(activity: AppCompatActivity, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                pendingInstallFile = file
                Toast.makeText(
                    activity,
                    "Autorisez l'installation depuis sources inconnues dans les paramètres.",
                    Toast.LENGTH_LONG
                ).show()
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                )
                return
            }
        }
        pendingInstallFile = null
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
