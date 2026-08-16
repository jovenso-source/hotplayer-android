package com.hotplayer.ui.settings

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hotplayer.BuildConfig
import com.hotplayer.HotPlayerApp
import com.hotplayer.data.model.PlaylistReloadProgress
import com.hotplayer.data.model.PlaylistReloadStep
import com.hotplayer.databinding.ActivitySettingsBinding
import com.hotplayer.databinding.DialogReloadProgressBinding
import com.hotplayer.ui.activation.ActivationActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val repo by lazy { HotPlayerApp.instance.sessionRepo }

    private var reloadJob: Job? = null
    private var reloadDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        loadInfo()
        setupActions()
    }

    // Reload is driven by lifecycleScope, which auto-cancels on destroy — but the blocking
    // network read loop inside SessionRepository has no suspension point to notice that
    // cancellation mid-chunk, so it can keep firing onProgress after the Activity is gone.
    // Explicitly dismissing here (and guarding every UI touch with isActive/isFinishing/
    // isDestroyed in startPlaylistReload()) is what actually prevents a callback from reaching
    // a destroyed Activity or leaking the dialog's window token.
    override fun onDestroy() {
        super.onDestroy()
        try { reloadDialog?.takeIf { it.isShowing }?.dismiss() } catch (_: Exception) {}
        reloadDialog = null
    }

    private fun loadInfo() {
        lifecycleScope.launch {
            val info = repo.getDeviceInfo().first()
            binding.tvMacValue.text  = info["device_id"] ?: "—"
            binding.tvPlanValue.text = when (info["plan"]?.lowercase()) {
                "monthly"  -> "Mensuel"
                "yearly"   -> "Annuel"
                "lifetime" -> "À vie"
                else       -> info["plan"]?.replaceFirstChar { it.uppercase() } ?: "—"
            }
            binding.tvVersionValue.text = BuildConfig.VERSION_NAME
        }
    }

    private fun setupFilters() {
        val prefs = HotPlayerApp.instance.prefs

        binding.switchHideFhd.isChecked = prefs.getBoolean("hide_fhd", false)
        binding.switchHideFhd.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("hide_fhd", checked).apply()
        }

        binding.switchHideBe.isChecked = prefs.getBoolean("hide_be", false)
        binding.switchHideBe.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("hide_be", checked).apply()
        }

        binding.switchHideAdult.isChecked = prefs.getBoolean("hide_adult", false)
        binding.switchHideAdult.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("hide_adult", checked).apply()
        }
    }

    private fun setupActions() {
        setupFilters()
        binding.btnBack.setOnClickListener { finish() }
        binding.btnLogout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Déconnexion")
                .setMessage("Voulez-vous vraiment vous déconnecter ?")
                .setPositiveButton("Se déconnecter") { _, _ ->
                    lifecycleScope.launch {
                        repo.logout()
                        startActivity(Intent(this@SettingsActivity, ActivationActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        })
                    }
                }
                .setNegativeButton("Annuler", null)
                .show()
        }
        binding.btnReload.setOnClickListener {
            // Belt-and-suspenders double-tap guard: the modal dialog (setCancelable(false) while
            // running) already blocks re-tapping, this just protects against a stray extra click
            // landing before the dialog is actually shown.
            if (reloadJob?.isActive == true) return@setOnClickListener
            startPlaylistReload()
        }
    }

    // ─── Playlist reload with real progress ───────────────────────────────────

    private fun startPlaylistReload() {
        val dialogBinding = DialogReloadProgressBinding.inflate(layoutInflater)
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.7f)
        }
        dialog.setCancelable(false) // re-enabled once a result (success/failure) is shown
        reloadDialog = dialog

        fun renderProgress(progress: PlaylistReloadProgress) {
            dialogBinding.tvReloadStep.text = when (progress.step) {
                PlaylistReloadStep.DOWNLOADING -> "Téléchargement de la playlist..."
                PlaylistReloadStep.PARSING     -> "Analyse des chaînes..."
                PlaylistReloadStep.FINALIZING  -> "Finalisation..."
            }
            val fraction = progress.fraction
            if (fraction != null) {
                dialogBinding.pbReloadDeterminate.visibility = android.view.View.VISIBLE
                dialogBinding.pbReloadIndeterminate.visibility = android.view.View.GONE
                val pct = (fraction * 100).toInt()
                dialogBinding.pbReloadDeterminate.progress = pct
                dialogBinding.tvReloadPercent.text = "$pct%"
            } else {
                // Honest fallback: no real progress signal for this step (see reloadPlaylist()
                // doc for exactly which steps can/can't report a real fraction) — indeterminate,
                // never a made-up percentage.
                dialogBinding.pbReloadDeterminate.visibility = android.view.View.GONE
                dialogBinding.pbReloadIndeterminate.visibility = android.view.View.VISIBLE
                dialogBinding.tvReloadPercent.text = ""
            }
        }

        fun showResult(success: Boolean, message: String) {
            dialogBinding.layoutReloadProgress.visibility = android.view.View.GONE
            dialogBinding.layoutReloadResult.visibility = android.view.View.VISIBLE
            dialogBinding.tvReloadResult.text = message
            dialogBinding.tvReloadResult.setTextColor(
                android.graphics.Color.parseColor(if (success) "#22d3a5" else "#ff4466")
            )
            dialogBinding.btnReloadRetry.visibility = if (success) android.view.View.GONE else android.view.View.VISIBLE
            dialog.setCancelable(true)
        }

        dialogBinding.btnReloadClose.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnReloadRetry.setOnClickListener {
            dialog.dismiss()
            startPlaylistReload()
        }

        if (!isFinishing && !isDestroyed) dialog.show()

        binding.btnReload.isEnabled = false
        renderProgress(PlaylistReloadProgress(PlaylistReloadStep.DOWNLOADING))

        reloadJob = lifecycleScope.launch {
            val count = try {
                repo.reloadPlaylist { progress ->
                    // reloadPlaylist() calls this from Dispatchers.IO — its blocking read loop
                    // has no suspension point to observe cancellation mid-chunk, so this callback
                    // can still fire after the coroutine (or the Activity) is gone. isActive +
                    // isFinishing/isDestroyed together make sure no view is ever touched then.
                    if (isActive) runOnUiThread {
                        if (!isFinishing && !isDestroyed) renderProgress(progress)
                    }
                }
            } catch (e: Throwable) {
                -1
            }
            if (!isActive) return@launch
            binding.btnReload.isEnabled = true
            if (count >= 0) {
                showResult(true, "Playlist mise à jour avec succès.\n$count chaînes chargées.")
                dialogBinding.root.postDelayed({
                    if (dialog.isShowing) dialog.dismiss()
                    if (!isFinishing && !isDestroyed) finish()
                }, 1500)
            } else {
                showResult(false, "Impossible de recharger la playlist.\nVotre playlist actuelle a été conservée.")
            }
        }
    }
}
