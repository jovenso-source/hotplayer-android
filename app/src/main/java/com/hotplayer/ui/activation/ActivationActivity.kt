package com.hotplayer.ui.activation

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hotplayer.HotPlayerApp
import com.hotplayer.data.repository.SessionRepository
import com.hotplayer.databinding.ActivityActivationBinding
import com.hotplayer.ui.home.HomeActivity
import kotlinx.coroutines.launch

class ActivationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityActivationBinding
    private val repo by lazy { HotPlayerApp.instance.sessionRepo }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityActivationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        checkActivation()
    }

    /* ── Check existing session first ── */
    private fun checkActivation() {
        showScreen(Screen.LOADING)
        lifecycleScope.launch {
            if (repo.isSessionValid()) {
                goToHome()
            } else {
                activate()
            }
        }
    }

    /* ── Attempt activation ── */
    private suspend fun activate() {
        showScreen(Screen.LOADING)

        // Show MAC while loading
        val mac = repo.macAddress
        binding.macValue.text  = mac
        binding.macValue2.text = mac

        when (val result = repo.activate()) {
            is SessionRepository.ActivationResult.Success -> goToHome()

            is SessionRepository.ActivationResult.Failure -> {
                showScreen(Screen.NOT_ACTIVATED)
                binding.statusMessage.text = result.message
            }

            is SessionRepository.ActivationResult.NetworkError -> {
                showScreen(Screen.NOT_ACTIVATED)
                binding.statusMessage.text =
                    "Impossible de contacter le serveur. Vérifiez votre connexion."
            }
        }
    }

    private fun goToHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    /* ── UI helpers ── */
    private fun showScreen(screen: Screen) {
        binding.screenLoading.visibility      = View.GONE
        binding.screenNotActivated.visibility = View.GONE
        when (screen) {
            Screen.LOADING       -> binding.screenLoading.visibility      = View.VISIBLE
            Screen.NOT_ACTIVATED -> binding.screenNotActivated.visibility = View.VISIBLE
        }
    }

    /* ── Retry button ── */
    fun onRetryClick(v: View) {
        lifecycleScope.launch { activate() }
    }

    enum class Screen { LOADING, NOT_ACTIVATED }
}
