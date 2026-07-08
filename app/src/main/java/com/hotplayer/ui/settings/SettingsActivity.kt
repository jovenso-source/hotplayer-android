package com.hotplayer.ui.settings

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hotplayer.BuildConfig
import com.hotplayer.HotPlayerApp
import com.hotplayer.databinding.ActivitySettingsBinding
import com.hotplayer.ui.activation.ActivationActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val repo by lazy { HotPlayerApp.instance.sessionRepo }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        loadInfo()
        setupActions()
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
            binding.btnReload.isEnabled = false
            binding.btnReload.text = "⏳  Connexion au serveur..."
            lifecycleScope.launch {
                val count = repo.reloadPlaylist()
                if (count >= 0) {
                    binding.btnReload.text = "✓  $count chaînes rechargées"
                    binding.btnReload.postDelayed({ finish() }, 1500)
                } else {
                    binding.btnReload.text = "✗  Échec — vérifiez la connexion"
                    binding.btnReload.isEnabled = true
                }
            }
        }
    }
}
