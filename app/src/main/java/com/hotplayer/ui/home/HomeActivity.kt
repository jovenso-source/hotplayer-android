package com.hotplayer.ui.home

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.hotplayer.HotPlayerApp
import com.hotplayer.data.model.ChannelType
import com.hotplayer.data.repository.SessionRepository
import com.hotplayer.databinding.ActivityHomeBinding
import com.hotplayer.ui.popup.PopupManager
import com.hotplayer.ui.settings.SettingsActivity
import com.hotplayer.ui.sports.SportsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChannelCounts(val live: Int, val movies: Int, val radios: Int)

class HomeViewModel(private val repo: SessionRepository) : ViewModel() {
    fun getMac(): String = repo.macAddress
    fun sendHeartbeat() = viewModelScope.launch { repo.sendHeartbeat() }

    private val _counts = MutableLiveData<ChannelCounts>()
    val counts: LiveData<ChannelCounts> = _counts

    fun loadCounts() = viewModelScope.launch {
        val creds = repo.getPlaylistCredentials()
        // loadChannelCache reads from disk — must run on IO dispatcher to avoid ANR
        val channels = withContext(Dispatchers.IO) { repo.loadChannelCache(creds) } ?: return@launch
        val live = channels.count { it.type == ChannelType.LIVE }
        val movies = channels.count { it.type == ChannelType.MOVIE || it.type == ChannelType.SERIES }
        val radios = channels.count { ch ->
            val g = ch.group?.lowercase() ?: ""
            val n = ch.name.lowercase()
            g.contains("radio") || n.contains("radio")
        }
        _counts.value = ChannelCounts(live, movies, radios)
    }
}

class HomeViewModelFactory(private val repo: SessionRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(repo) as T
}

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var vm: HomeViewModel

    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            vm.sendHeartbeat()
            heartbeatHandler.postDelayed(this, 90_000)
        }
    }

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 60_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        vm = ViewModelProvider(this, HomeViewModelFactory(HotPlayerApp.instance.sessionRepo))[HomeViewModel::class.java]
        setContentView(binding.root)

        binding.tvMacFooter.text    = "MAC: ${vm.getMac()}"
        binding.tvVersionFooter.text = "v${com.hotplayer.BuildConfig.VERSION_NAME}"

        setupHeader()
        setupCards()
        setupFocus()
        updateClock()
        observeCounts()

        binding.cardLive.post { binding.cardLive.requestFocus() }

        heartbeatHandler.postDelayed(heartbeatRunnable, 90_000)
        clockHandler.postDelayed(clockRunnable, 10_000)

        vm.loadCounts()

        lifecycleScope.launch { PopupManager.checkAndShow(this@HomeActivity) }
        com.hotplayer.ui.popup.WhatsNewManager.checkAndShow(this)
        SportCardManager.bind(this, binding)
    }

    // ─── Clock ─────────────────────────────────────────────────────────────────

    private fun updateClock() {
        val cal = java.util.Calendar.getInstance()
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val m = cal.get(java.util.Calendar.MINUTE)
        binding.tvClock.text = "%02d:%02d".format(h, m)
        val sdf = SimpleDateFormat("EEEE, MMM d", Locale.ENGLISH)
        binding.tvDate.text = sdf.format(Date()).replaceFirstChar { it.uppercaseChar() }
    }

    // ─── Counts ────────────────────────────────────────────────────────────────

    private fun observeCounts() {
        vm.counts.observe(this) { c ->
            binding.tvSubtitleLive.text = if (c.live > 0) "+${c.live} Channels" else "+5000 Channels"
            binding.tvSubtitleMovies.text = if (c.movies > 0) "+${c.movies} Titles" else "VOD Catalog"
            binding.tvSubtitleRadios.text = if (c.radios > 0) "+${c.radios} Stations" else "Live Matches"
        }
    }

    // ─── Header ────────────────────────────────────────────────────────────────

    private fun setupHeader() {
        binding.navSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnSearch.setOnClickListener {
            startActivity(Intent(this, LiveTvActivity::class.java))
        }
        binding.btnAvatar.setOnClickListener {
            Toast.makeText(this, "Profile — Coming soon", Toast.LENGTH_SHORT).show()
        }
        listOf(binding.navSettings, binding.btnSearch, binding.btnAvatar).forEach { btn ->
            btn.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_BUTTON_A -> { btn.performClick(); true }
                    else -> false
                }
            }
        }
    }

    // ─── Cards ─────────────────────────────────────────────────────────────────

    private fun setupCards() {
        binding.cardLive.setOnClickListener {
            startActivity(Intent(this, LiveTvActivity::class.java))
        }
        binding.cardSports.setOnClickListener {
            startActivity(Intent(this, com.hotplayer.ui.sports.SportsActivity::class.java))
        }
        binding.cardFilms.setOnClickListener {
            Toast.makeText(this, "Movies & Series — Coming soon", Toast.LENGTH_SHORT).show()
        }

        listOf(binding.cardLive, binding.cardSports, binding.cardFilms).forEach { card ->
            card.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_BUTTON_A -> { card.performClick(); true }
                    else -> false
                }
            }
        }
    }

    // ─── Focus animations ──────────────────────────────────────────────────────

    private fun setupFocus() {
        val cardData = listOf(
            binding.cardLive    to binding.indicatorLive,
            binding.cardSports  to binding.indicatorSports,
            binding.cardFilms   to binding.indicatorFilms
        )
        val headerBtns = listOf(binding.btnSearch, binding.navSettings, binding.btnAvatar)

        cardData.forEach { (card, indicator) ->
            card.setOnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) 1.06f else 1f)
                    .scaleY(if (hasFocus) 1.06f else 1f)
                    .setDuration(160)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
                v.elevation = if (hasFocus) 16f else 3f
                indicator.visibility = if (hasFocus) View.VISIBLE else View.GONE
            }
        }

        headerBtns.forEach { btn ->
            btn.setOnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) 1.12f else 1f)
                    .scaleY(if (hasFocus) 1.12f else 1f)
                    .setDuration(130)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
                v.elevation = if (hasFocus) 8f else 1f
            }
        }
    }

    // ─── Back key ──────────────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE)) {
            // From header: go back to cards
            val headerFocused = listOf(binding.btnSearch, binding.navSettings, binding.btnAvatar)
                .any { it.isFocused }
            if (headerFocused) {
                binding.cardLive.requestFocus()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ─── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        PopupManager.onActivityResumed(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        clockHandler.removeCallbacks(clockRunnable)
    }
}
