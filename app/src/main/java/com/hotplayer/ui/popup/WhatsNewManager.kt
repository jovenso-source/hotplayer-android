package com.hotplayer.ui.popup

import android.app.Dialog
import android.content.Context
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.hotplayer.BuildConfig
import com.hotplayer.databinding.DialogWhatsNewBinding

object WhatsNewManager {

    private const val PREFS        = "whats_new_prefs"
    private const val KEY_LAST_VER = "last_seen_version_code"

    // Appeler depuis HomeActivity.onCreate() — affiche le popup une seule fois après mise à jour.
    fun checkAndShow(activity: AppCompatActivity) {
        val prefs      = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSeen   = prefs.getInt(KEY_LAST_VER, 0)
        val current    = BuildConfig.VERSION_CODE

        if (lastSeen >= current) return   // déjà vu pour cette version

        prefs.edit().putInt(KEY_LAST_VER, current).apply()
        showDialog(activity)
    }

    private fun showDialog(activity: AppCompatActivity) {
        if (activity.isFinishing || activity.isDestroyed) return

        val binding = DialogWhatsNewBinding.inflate(activity.layoutInflater)
        val dialog  = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setContentView(binding.root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.75f)
        }
        dialog.setCancelable(true)

        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME}"
        binding.btnClose.setOnClickListener { dialog.dismiss() }
        binding.btnClose.post { binding.btnClose.requestFocus() }

        dialog.show()
    }
}
