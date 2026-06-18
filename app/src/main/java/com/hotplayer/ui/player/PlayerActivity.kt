package com.hotplayer.ui.player

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.hotplayer.HotPlayerApp
import com.hotplayer.data.model.EpgItem
import com.hotplayer.databinding.ActivityPlayerBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANNEL_URL   = "channel_url"
        const val EXTRA_CHANNEL_NAME  = "channel_name"
        const val EXTRA_CHANNEL_LOGO  = "channel_logo"
        const val EXTRA_CHANNEL_GROUP = "channel_group"
        const val EXTRA_CHANNEL_INDEX = "channel_index"
        private const val OSD_HIDE_DELAY    = 5_000L
        private const val NAV_OVERLAY_DELAY = 2_500L
    }

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var playerMgr: ExoPlayerManager
    private val repo by lazy { HotPlayerApp.instance.sessionRepo }

    private var channelUrl   = ""
    private var currentIndex = -1
    private var lastSwitchMs = 0L

    private val hideHandler     = Handler(Looper.getMainLooper())
    private val hideOsdRunnable = Runnable { hideOsd() }
    private val hideNavRunnable = Runnable { binding.layoutChannelNav.visibility = View.GONE }

    private var epgJob:   Job? = null
    private var clockJob: Job? = null

    private val fmtTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val fmtDate = SimpleDateFormat("EEE dd MMM", Locale.getDefault())

    // ─── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        channelUrl   = intent.getStringExtra(EXTRA_CHANNEL_URL) ?: run { finish(); return }
        currentIndex = intent.getIntExtra(EXTRA_CHANNEL_INDEX, -1)

        playerMgr = ExoPlayerManager(this)
        playerMgr.init(binding.playerView)
        playerMgr.onBuffering = { show ->
            binding.progressBuffering.visibility = if (show) View.VISIBLE else View.GONE
        }
        playerMgr.onError = { msg ->
            binding.tvError.text       = msg
            binding.tvError.visibility = View.VISIBLE
        }
        playerMgr.onReady = { binding.tvError.visibility = View.GONE }
        playerMgr.onVideoSize = { w, h ->
            binding.osdResolution.text       = "${w} × ${h}"
            binding.osdResolution.visibility = View.VISIBLE
        }

        playerMgr.play(channelUrl)
        setupOsd()
        startClock()
        showOsd()
        fetchEpg(channelUrl)
    }

    override fun onPause()   { super.onPause();   playerMgr.pause() }
    override fun onResume()  { super.onResume();  playerMgr.resume() }
    override fun onDestroy() {
        super.onDestroy()
        epgJob?.cancel()
        clockJob?.cancel()
        hideHandler.removeCallbacksAndMessages(null)
        playerMgr.release()
    }

    // ─── Clock ─────────────────────────────────────────────────────────────────

    private fun startClock() {
        clockJob = lifecycleScope.launch {
            while (true) {
                val now = Date()
                binding.tvClock.text     = fmtTime.format(now)
                binding.tvClockDate.text = fmtDate.format(now)
                delay(1000)
            }
        }
    }

    // ─── OSD setup ─────────────────────────────────────────────────────────────

    private fun setupOsd() {
        val name  = intent.getStringExtra(EXTRA_CHANNEL_NAME)  ?: ""
        val logo  = intent.getStringExtra(EXTRA_CHANNEL_LOGO)
        val group = intent.getStringExtra(EXTRA_CHANNEL_GROUP) ?: ""

        binding.osdChName.text  = name
        binding.osdChNum.text   = if (currentIndex >= 0) "%03d".format(currentIndex + 1) else ""
        binding.tvOsdGroup.text = group

        applyLogo(logo, name)
        binding.btnBack.setOnClickListener { finish() }
    }

    private fun applyLogo(logo: String?, name: String) {
        if (!logo.isNullOrBlank()) {
            Glide.with(this).load(logo)
                .placeholder(android.R.drawable.ic_media_play)
                .error(android.R.drawable.ic_media_play)
                .into(binding.osdLogo)
            binding.osdLogoFallback.visibility = View.GONE
        } else {
            binding.osdLogoFallback.text       = name.take(3).uppercase()
            binding.osdLogoFallback.visibility = View.VISIBLE
        }
    }

    // ─── OSD show / hide with animation ────────────────────────────────────────

    private fun showOsd() {
        hideHandler.removeCallbacks(hideOsdRunnable)
        if (binding.layoutOsd.visibility == View.VISIBLE) {
            scheduleHideOsd()
            return
        }
        val slideDistance = 80f * resources.displayMetrics.density
        binding.layoutOsd.translationY = slideDistance
        binding.layoutOsd.alpha        = 0f
        binding.layoutOsd.visibility   = View.VISIBLE
        binding.layoutOsd.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .start()
        scheduleHideOsd()
    }

    private fun hideOsd() {
        if (binding.layoutOsd.visibility == View.GONE) return
        val slideDistance = 80f * resources.displayMetrics.density
        binding.layoutOsd.animate()
            .translationY(slideDistance)
            .alpha(0f)
            .setDuration(200)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { binding.layoutOsd.visibility = View.GONE }
            .start()
    }

    private fun scheduleHideOsd() {
        hideHandler.removeCallbacks(hideOsdRunnable)
        hideHandler.postDelayed(hideOsdRunnable, OSD_HIDE_DELAY)
    }

    // ─── EPG ───────────────────────────────────────────────────────────────────

    private fun fetchEpg(url: String) {
        epgJob?.cancel()
        epgJob = lifecycleScope.launch {
            delay(600)
            val streamId = extractStreamId(url) ?: return@launch
            val epgList  = repo.fetchShortEpg(streamId)
            updateOsdEpg(
                epgList.firstOrNull { it.isNow },
                epgList.firstOrNull { !it.isNow }
            )
        }
    }

    private fun updateOsdEpg(now: EpgItem?, next: EpgItem?) {
        if (now == null) {
            binding.osdEpgTitle.visibility       = View.GONE
            binding.layoutOsdTimeline.visibility = View.GONE
            binding.layoutOsdNext.visibility     = View.GONE
            return
        }
        binding.osdEpgTitle.text      = now.title
        binding.osdEpgTitle.visibility = View.VISIBLE
        binding.osdTimeStart.text     = now.startFormatted()
        binding.osdTimeEnd.text       = now.stopFormatted()
        binding.osdProgress.progress  = now.progress()
        binding.layoutOsdTimeline.visibility = View.VISIBLE

        if (next != null) {
            binding.tvEpgNextTime.text       = "${next.startFormatted()} – ${next.stopFormatted()}"
            binding.tvEpgNextTitle.text      = next.title
            binding.layoutOsdNext.visibility = View.VISIBLE
        } else {
            binding.layoutOsdNext.visibility = View.GONE
        }
    }

    private fun extractStreamId(url: String): String? =
        Regex("""/(\d+)\.(?:ts|m3u8|mp4)""").find(url)?.groupValues?.get(1)
            ?: Regex("""/(\d+)\z""").find(url)?.groupValues?.get(1)

    // ─── Channel switching ──────────────────────────────────────────────────────

    private fun switchChannel(delta: Int) {
        val now = System.currentTimeMillis()
        if (now - lastSwitchMs < 600) return
        lastSwitchMs = now

        val channels = HotPlayerApp.instance.liveTvChannels
        if (channels.isEmpty() || currentIndex < 0) return

        val newIndex = (currentIndex + delta).coerceIn(0, channels.lastIndex)
        if (newIndex == currentIndex) return
        currentIndex = newIndex
        HotPlayerApp.instance.liveTvChannelIndex = currentIndex

        val ch = channels[currentIndex]
        channelUrl = ch.url

        binding.osdChName.text  = ch.name
        binding.osdChNum.text   = "%03d".format(currentIndex + 1)
        binding.tvOsdGroup.text = ch.group ?: ""
        applyLogo(ch.logo, ch.name)
        binding.osdEpgTitle.visibility       = View.GONE
        binding.layoutOsdTimeline.visibility = View.GONE
        binding.layoutOsdNext.visibility     = View.GONE
        binding.osdResolution.visibility     = View.GONE

        binding.tvNavChannelNum.text        = (currentIndex + 1).toString()
        binding.tvNavChannelName.text       = ch.name
        binding.tvNavChannelGroup.text      = ch.group ?: ""
        binding.layoutChannelNav.visibility = View.VISIBLE
        hideHandler.removeCallbacks(hideNavRunnable)
        hideHandler.postDelayed(hideNavRunnable, NAV_OVERLAY_DELAY)

        showOsd()
        fetchEpg(ch.url)
        playerMgr.play(ch.url)
    }

    // ─── Key handling ───────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.repeatCount != 0) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_CHANNEL_UP   -> { switchChannel(-1); true }

            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { switchChannel(+1); true }

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A     -> { showOsd(); true }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { showOsd(); true }
            KeyEvent.KEYCODE_MEDIA_PLAY       -> { playerMgr.resume(); showOsd(); true }
            KeyEvent.KEYCODE_MEDIA_PAUSE      -> { playerMgr.pause();  showOsd(); true }
            KeyEvent.KEYCODE_MEDIA_STOP       -> { finish(); true }
            KeyEvent.KEYCODE_MEDIA_NEXT       -> { switchChannel(+1); true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS   -> { switchChannel(-1); true }

            KeyEvent.KEYCODE_BACK -> { finish(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
