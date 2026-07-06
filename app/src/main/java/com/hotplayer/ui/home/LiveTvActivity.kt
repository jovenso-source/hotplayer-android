package com.hotplayer.ui.home

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hotplayer.HotPlayerApp
import com.hotplayer.data.model.Channel
import com.hotplayer.data.model.EpgItem
import com.hotplayer.databinding.ActivityLivetvBinding
import com.hotplayer.ui.player.ExoPlayerManager
import com.hotplayer.ui.player.PlayerActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LiveTvActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LiveTvActivity"
    }

    private lateinit var binding    : ActivityLivetvBinding
    private lateinit var vm         : LiveTvViewModel
    private lateinit var playerMgr  : ExoPlayerManager
    private lateinit var catAdapter : LiveCategoryAdapter
    private lateinit var chAdapter  : LiveChannelAdapter

    private var channelJob         : Job? = null
    private var searchMode         = false
    private var focusedChannelPos  = 0
    private var selectedChannel    : Channel? = null
    private var selectedChannelIdx : Int = -1

    // ─── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLivetvBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val repo = HotPlayerApp.instance.sessionRepo
        vm = ViewModelProvider(this, LiveTvViewModelFactory(repo))[LiveTvViewModel::class.java]
        vm.hideFhd   = HotPlayerApp.instance.prefs.getBoolean("hide_fhd",   false)
        vm.hideBe    = HotPlayerApp.instance.prefs.getBoolean("hide_be",    false)
        vm.hideAdult = HotPlayerApp.instance.prefs.getBoolean("hide_adult", false)

        setupPlayer()
        setupRecyclers()
        setupNavigation()
        setupSearch()
        observe()

        showLoading(true)
        vm.load()
    }

    override fun onResume() {
        super.onResume()
        playerMgr.resume()
        val p = HotPlayerApp.instance.prefs
        vm.syncFilters(
            hideFhd   = p.getBoolean("hide_fhd",   false),
            hideBe    = p.getBoolean("hide_be",    false),
            hideAdult = p.getBoolean("hide_adult", false)
        )
    }
    override fun onPause()   { super.onPause();   playerMgr.pause() }
    override fun onDestroy() {
        super.onDestroy()
        channelJob?.cancel()
        playerMgr.release()
    }

    // ─── Player ────────────────────────────────────────────────────────────────

    private fun setupPlayer() {
        playerMgr = ExoPlayerManager(this)
        playerMgr.onBuffering = { show ->
            binding.pbPlayerBuffering.visibility = if (show) View.VISIBLE else View.GONE
        }
        playerMgr.onError = { /* ExoPlayer retries automatically */ }
        playerMgr.init(binding.playerView)

        binding.playerFrame.setOnFocusChangeListener { _, hasFocus ->
            binding.tvPlayerHint.visibility = if (hasFocus) View.VISIBLE else View.GONE
        }
        binding.playerFrame.setOnClickListener {
            selectedChannel?.let { openPlayer(it, selectedChannelIdx) }
        }
    }

    private fun selectChannel(ch: Channel, idx: Int) {
        selectedChannel    = ch
        selectedChannelIdx = idx
        chAdapter.setSelectedUrl(ch.url)
        scheduleChannelLoad(ch, idx)
    }

    // ─── RecyclerViews ─────────────────────────────────────────────────────────

    private fun setupRecyclers() {
        catAdapter = LiveCategoryAdapter(
            onFocused = { pos, cat ->
                catAdapter.selectAt(pos)
                binding.tvCurrentCategory.text = cat
                if (cat != vm.currentCat) vm.filterByCategory(cat)
            },
            onSelected = { cat ->
                if (cat != vm.currentCat) vm.filterByCategory(cat)
            }
        )
        binding.rvCats.apply {
            layoutManager = TvLinearLayoutManager(this@LiveTvActivity)
            adapter       = catAdapter
            setHasFixedSize(true)
            itemAnimator  = null
        }

        chAdapter = LiveChannelAdapter(
            onChClick = { ch, idx -> selectChannel(ch, idx) },
            onChFocus = { ch, idx ->
                focusedChannelPos = idx
                vm.setIndex(idx)
                updatePlayerInfo(ch, idx)
            }
        )
        binding.rvChannels.apply {
            layoutManager = TvLinearLayoutManager(this@LiveTvActivity)
            adapter       = chAdapter
            itemAnimator  = null
        }
    }

    // ─── D-pad navigation ──────────────────────────────────────────────────────

    private fun setupNavigation() {
        binding.btnBack.setOnClickListener { finish() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        when (event.keyCode) {

            // OK / ENTER: confirm category → move focus to channels
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> {
                val focusedInCats = binding.rvCats.findFocus()
                if (focusedInCats != null) {
                    val catPos = if (focusedInCats !== binding.rvCats) {
                        binding.rvCats.findContainingViewHolder(focusedInCats)
                            ?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
                    } else {
                        catAdapter.selectedPos
                    }
                    if (catPos != RecyclerView.NO_POSITION) {
                        val cat = catAdapter.getCategoryAt(catPos) ?: return super.dispatchKeyEvent(event)
                        catAdapter.selectAt(catPos)
                        vm.filterByCategory(cat)
                        focusChannels()
                        return true
                    }
                }
            }

            // UP/DOWN in categories: intercept BEFORE RecyclerView so that
            // TvLinearLayoutManager.onFocusSearchFailed can never swallow the event.
            // When the target item is off-screen, RecyclerView scrolls but focus
            // stays on the current item — intercepting here owns both.
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (binding.rvCats.hasFocus()) {
                    val newPos = (catAdapter.selectedPos - 1).coerceAtLeast(0)
                    applyFocusedCategory(newPos)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (binding.rvCats.hasFocus()) {
                    val newPos = (catAdapter.selectedPos + 1)
                        .coerceAtMost((catAdapter.itemCount - 1).coerceAtLeast(0))
                    applyFocusedCategory(newPos)
                    return true
                }
            }

            // RIGHT: cats → channels → player
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                when {
                    binding.rvCats.findFocus() != null     -> { focusChannels(); return true }
                    binding.rvChannels.findFocus() != null -> { binding.playerFrame.requestFocus(); return true }
                }
            }

            // LEFT: player/back → channels → cats
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                when {
                    currentFocus === binding.playerFrame    -> { focusChannels(); return true }
                    currentFocus === binding.btnBack        -> { focusChannels(); return true }
                    binding.rvChannels.findFocus() != null -> { focusSelectedCategory(); return true }
                }
            }

            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (searchMode) { hideSearch(); return true }
                finish(); return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    // ─── Focus helpers ──────────────────────────────────────────────────────────

    // Focus position 0 of a RecyclerView after next layout pass.
    private fun focusFirst(rv: RecyclerView) {
        if (rv.adapter?.itemCount == 0) { rv.requestFocus(); return }
        rv.post {
            if (isFinishing || isDestroyed) return@post
            try {
                rv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    ?: rv.requestFocus()
            } catch (e: Exception) {
                rv.requestFocus()
            }
        }
    }

    private fun focusChannels() {
        val count = chAdapter.itemCount
        if (count == 0) { focusSelectedCategory(); return }
        val pos = focusedChannelPos.coerceIn(0, count - 1)
        val lm  = binding.rvChannels.layoutManager as? LinearLayoutManager
            ?: run { focusFirst(binding.rvChannels); return }
        val first = lm.findFirstVisibleItemPosition()
        val last  = lm.findLastVisibleItemPosition()
        if (first != RecyclerView.NO_POSITION && (pos < first || pos > last)) {
            lm.scrollToPositionWithOffset(pos, 0)
        }
        binding.rvChannels.post {
            if (isFinishing || isDestroyed) return@post
            try {
                binding.rvChannels.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                    ?: focusFirst(binding.rvChannels)
            } catch (e: Exception) {
                focusFirst(binding.rvChannels)
            }
        }
    }

    private fun focusSelectedCategory() {
        val pos = catAdapter.selectedPos.coerceAtLeast(0)
        val lm  = binding.rvCats.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last  = lm.findLastVisibleItemPosition()
        if (first != RecyclerView.NO_POSITION && (pos < first || pos > last)) {
            lm.scrollToPositionWithOffset(pos, 0)
        }
        binding.rvCats.post {
            if (isFinishing || isDestroyed) return@post
            try {
                binding.rvCats.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                    ?: binding.rvCats.requestFocus()
            } catch (e: Exception) {
                binding.rvCats.requestFocus()
            }
        }
    }

    // Called from DPAD_UP/DOWN intercept: updates category + channel list + focus atomically.
    private fun applyFocusedCategory(pos: Int) {
        val cat = catAdapter.getCategoryAt(pos) ?: return
        catAdapter.selectAt(pos)
        binding.tvCurrentCategory.text = cat
        if (cat != vm.currentCat) vm.filterByCategory(cat)   // synchronous O(1) HashMap → onReady fires here

        val lm = binding.rvCats.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last  = lm.findLastVisibleItemPosition()
        if (first != RecyclerView.NO_POSITION && (pos < first || pos > last)) {
            lm.scrollToPositionWithOffset(pos, 0)
        }
        binding.rvCats.post {
            if (isFinishing || isDestroyed) return@post
            try {
                binding.rvCats.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                    ?: binding.rvCats.requestFocus()
            } catch (e: Exception) {
                binding.rvCats.requestFocus()
            }
        }
    }

    // ─── Search ────────────────────────────────────────────────────────────────

    private fun setupSearch() {
        binding.btnSearch.setOnClickListener { if (searchMode) hideSearch() else showSearch() }
        binding.btnSearchClose.setOnClickListener { hideSearch() }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString() ?: ""
                if (q.isEmpty()) vm.clearSearch() else vm.search(q)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { focusFirst(binding.rvChannels); true }
            else false
        }
    }

    private fun showSearch() {
        searchMode = true
        binding.layoutSearchBar.visibility = View.VISIBLE
        binding.etSearch.requestFocus()
    }

    private fun hideSearch() {
        searchMode = false
        binding.layoutSearchBar.visibility = View.GONE
        binding.etSearch.text.clear()
        vm.clearSearch()
        focusFirst(binding.rvChannels)
    }

    // ─── Observers ─────────────────────────────────────────────────────────────

    private fun observe() {
        vm.state.observe(this) { state ->
            when (state) {
                is LiveTvViewModel.State.Loading -> showLoading(true)
                is LiveTvViewModel.State.Error   -> showError(state.msg)
                is LiveTvViewModel.State.Ready   -> onReady(state)
            }
        }
        vm.epg.observe(this) epgObserver@{ epgList ->
            val ch = vm.currentChannel ?: return@epgObserver
            val now = epgList.firstOrNull { it.isNow }
            chAdapter.updateEpg(ch.url, now)
            binding.tvPlayerEpgNow.visibility = if (now != null) View.VISIBLE else View.GONE
            if (now != null) binding.tvPlayerEpgNow.text = now.title
            updateEpgStrip(epgList)
        }
    }

    private fun onReady(state: LiveTvViewModel.State.Ready) {
        showLoading(false)
        Log.d(TAG, "onReady: ${state.channels.size} ch cat='${vm.currentCat}' refresh=${state.isFromRefresh}")

        binding.tvCurrentCategory.text = vm.currentCat
        binding.tvChannelCount.text    =
            "${state.channels.size} chaîne${if (state.channels.size > 1) "s" else ""}"

        catAdapter.setData(state.cats)

        if (state.isFromRefresh) {
            // Background refresh: update adapter data only.
            // scrollToPosition(0) and focusFirst are intentionally skipped to preserve
            // the user's current scroll position and focus.
            chAdapter.setData(state.channels)
            return
        }

        focusedChannelPos  = 0
        selectedChannel    = null
        selectedChannelIdx = -1
        channelJob?.cancel()
        chAdapter.setSelectedUrl(null)

        // Synchronous update — channel list is ready in the same frame, no DiffUtil delay.
        chAdapter.setData(state.channels)
        binding.rvChannels.scrollToPosition(0)

        // Keep focus in categories while navigating (hover filter).
        // Only move focus to channels on initial load or after explicit search.
        if (!searchMode && !binding.rvCats.hasFocus()) {
            focusFirst(binding.rvChannels)
        }
    }

    // ─── Player overlay info ───────────────────────────────────────────────────

    private fun updatePlayerInfo(ch: Channel, idx: Int) {
        binding.tvPlayerName.text = ch.name
        binding.tvPlayerNum.text  = "N° %02d".format(idx + 1)
        binding.tvPlayerCat.text  = ch.group ?: "EN DIRECT"
        binding.tvPlayerEpgNow.visibility = View.GONE
    }

    private fun scheduleChannelLoad(ch: Channel, idx: Int) {
        channelJob?.cancel()
        channelJob = lifecycleScope.launch {
            delay(300)
            vm.triggerEpg(idx)
            delay(200)
            if (!isFinishing && !isDestroyed) playerMgr.play(ch.url)
        }
    }

    private fun openPlayer(ch: Channel, idx: Int) {
        HotPlayerApp.instance.liveTvChannels     = vm.displayed
        HotPlayerApp.instance.liveTvChannelIndex = idx
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_CHANNEL_URL,   ch.url)
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME,  ch.name)
            putExtra(PlayerActivity.EXTRA_CHANNEL_LOGO,  ch.logo)
            putExtra(PlayerActivity.EXTRA_CHANNEL_GROUP, ch.group)
            putExtra(PlayerActivity.EXTRA_CHANNEL_INDEX, idx)
        })
    }

    // ─── EPG strip ─────────────────────────────────────────────────────────────

    private fun updateEpgStrip(epgList: List<EpgItem>) {
        val now  = epgList.firstOrNull { it.isNow }
        val next = epgList.filter { !it.isNow }.take(2)

        if (now == null && next.isEmpty()) {
            binding.tvEpgEmpty.visibility     = View.VISIBLE
            binding.pbEpgLoading.visibility   = View.GONE
            binding.layoutEpgNow.visibility   = View.GONE
            binding.dividerEpg.visibility     = View.GONE
            binding.layoutEpgNext1.visibility = View.GONE
            binding.layoutEpgNext2.visibility = View.GONE
            return
        }

        binding.tvEpgEmpty.visibility   = View.GONE
        binding.pbEpgLoading.visibility = View.GONE

        if (now != null) {
            binding.layoutEpgNow.visibility = View.VISIBLE
            binding.tvEpgNowTitle.text      = now.title
            binding.tvEpgNowStart.text      = now.startFormatted()
            binding.tvEpgNowEnd.text        = now.stopFormatted()
            binding.pbEpgNow.progress       = now.progress()
            binding.tvEpgNowDesc.text       = now.description
            binding.tvEpgNowDesc.visibility = if (now.description.isNotBlank()) View.VISIBLE else View.GONE
        } else {
            binding.layoutEpgNow.visibility = View.GONE
        }

        binding.dividerEpg.visibility =
            if (now != null && next.isNotEmpty()) View.VISIBLE else View.GONE

        if (next.isNotEmpty()) {
            binding.layoutEpgNext1.visibility = View.VISIBLE
            binding.tvEpgNext1Title.text = next[0].title
            binding.tvEpgNext1Start.text = next[0].startFormatted()
            binding.tvEpgNext1End.text   = next[0].stopFormatted()
        } else {
            binding.layoutEpgNext1.visibility = View.GONE
        }

        if (next.size >= 2) {
            binding.layoutEpgNext2.visibility = View.VISIBLE
            binding.tvEpgNext2Title.text = next[1].title
            binding.tvEpgNext2Start.text = next[1].startFormatted()
            binding.tvEpgNext2End.text   = next[1].stopFormatted()
        } else {
            binding.layoutEpgNext2.visibility = View.GONE
        }
    }

    // ─── Loading / Error ───────────────────────────────────────────────────────

    private fun showLoading(show: Boolean) {
        binding.layoutLoading.visibility = if (show) View.VISIBLE else View.GONE
        binding.progressBar.visibility   = if (show) View.VISIBLE else View.GONE
        binding.tvError.visibility       = View.GONE
        binding.btnRetry.visibility      = View.GONE
    }

    private fun showError(msg: String) {
        binding.layoutLoading.visibility = View.VISIBLE
        binding.progressBar.visibility   = View.GONE
        binding.tvError.text             = msg
        binding.tvError.visibility       = View.VISIBLE
        binding.btnRetry.visibility      = View.VISIBLE
        binding.btnRetry.setOnClickListener { vm.load(forceRefresh = true) }
        binding.btnRetry.post { binding.btnRetry.requestFocus() }
    }
}
