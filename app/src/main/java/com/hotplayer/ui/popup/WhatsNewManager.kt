package com.hotplayer.ui.popup

import android.app.Dialog
import android.content.Context
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.hotplayer.databinding.DialogWhatsNewBinding

object WhatsNewManager {

    private const val PREFS            = "whats_new_prefs"
    private const val KEY_FIRST_LAUNCH = "first_launch_done"

    fun checkAndShow(activity: AppCompatActivity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_FIRST_LAUNCH, false)) return

        prefs.edit().putBoolean(KEY_FIRST_LAUNCH, true).apply()
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
        dialog.setCancelable(false)

        binding.btnClose.setOnClickListener { dialog.dismiss() }
        binding.btnClose.post { binding.btnClose.requestFocus() }

        dialog.show()
    }
}
