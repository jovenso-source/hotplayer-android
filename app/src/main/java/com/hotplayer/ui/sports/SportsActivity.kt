package com.hotplayer.ui.sports

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.hotplayer.HotPlayerApp
import com.hotplayer.R
import com.hotplayer.data.model.Channel
import com.hotplayer.data.model.Match
import com.hotplayer.data.repository.SportsRepository
import com.hotplayer.databinding.ActivitySportsBinding
import com.hotplayer.ui.home.TvLinearLayoutManager
import com.hotplayer.ui.player.PlayerActivity

class SportsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySportsBinding
    private lateinit var vm: SportsViewModel

    private lateinit var liveAdapter:     LiveMatchCardAdapter
    private lateinit var categoryAdapter: SportCategoryAdapter
    private lateinit var upcomingAdapter: UpcomingCardAdapter
    private lateinit var channelAdapter:  WatchChannelAdapter

    // ─── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySportsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val repo = HotPlayerApp.instance.sessionRepo
        vm = ViewModelProvider(this, SportsViewModelFactory(SportsRepository(), repo))[SportsViewModel::class.java]

        setupAdapters()
        setupButtons()
        observe()

        vm.setLiveBadgeCallback { count ->
            binding.tvLiveBadge.text    = "● $count EN DIRECT"
            binding.tvLiveBadge.visibility = View.VISIBLE
        }

        vm.load()
    }

    // ─── Setup ─────────────────────────────────────────────────────────────────

    private fun setupAdapters() {
        liveAdapter = LiveMatchCardAdapter { match ->
            vm.selectMatch(match)
            showDetail()
        }
        binding.rvLive.apply {
            layoutManager = TvLinearLayoutManager(this@SportsActivity).also { it.orientation = LinearLayoutManager.HORIZONTAL }
            adapter = liveAdapter
            isFocusable = false
        }

        categoryAdapter = SportCategoryAdapter { category ->
            vm.filterByCategory(category)
            showDetail(categoryMode = true, categoryName = category.name)
        }
        binding.rvCategories.apply {
            layoutManager = TvLinearLayoutManager(this@SportsActivity).also { it.orientation = LinearLayoutManager.HORIZONTAL }
            adapter = categoryAdapter
            isFocusable = false
        }

        upcomingAdapter = UpcomingCardAdapter { match ->
            vm.selectMatch(match)
            showDetail()
        }
        binding.rvUpcoming.apply {
            layoutManager = TvLinearLayoutManager(this@SportsActivity).also { it.orientation = LinearLayoutManager.HORIZONTAL }
            adapter = upcomingAdapter
            isFocusable = false
        }

        channelAdapter = WatchChannelAdapter { channel -> openPlayer(channel) }
        binding.rvChannels.apply {
            layoutManager = TvLinearLayoutManager(this@SportsActivity)
            adapter = channelAdapter
        }
    }

    private fun setupButtons() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnRetry.setOnClickListener { vm.load() }
        binding.btnCloseDetail.setOnClickListener { hideDetail() }

        binding.btnBack.setOnKeyListener(okKeyListener { finish() })
        binding.btnRetry.setOnKeyListener(okKeyListener { vm.load() })
        binding.btnCloseDetail.setOnKeyListener(okKeyListener { hideDetail() })
    }

    // ─── Observe ───────────────────────────────────────────────────────────────

    private fun observe() {
        vm.state.observe(this) { state ->
            when (state) {
                is SportsViewModel.State.Loading -> showLoading(true)
                is SportsViewModel.State.Error   -> showError(state.msg)
                is SportsViewModel.State.Ready   -> {
                    showLoading(false)
                    bindLive(state.liveMatches)
                    categoryAdapter.setData(state.categories)
                    bindUpcoming(state.upcomingMatches)
                    if (!state.isFromRefresh) {
                        if (state.liveMatches.isEmpty()) {
                            binding.rvCategories.post { binding.rvCategories.requestFocus() }
                        } else {
                            binding.rvLive.post { binding.rvLive.requestFocus() }
                        }
                    }
                }
            }
        }

        vm.selected.observe(this) { match ->
            if (match == null) return@observe
            binding.tvDetailHome.text  = match.homeTeam
            binding.tvDetailAway.text  = match.awayTeam
            binding.tvDetailLeague.text = match.leagueName
            if (match.isLive) {
                binding.tvDetailScore.visibility = View.VISIBLE
                binding.tvDetailScore.text       = match.scoreText()
                binding.tvDetailStatus.text      = match.timeLabel
                setTint(binding.tvDetailStatus, "#e53935")
            } else {
                binding.tvDetailScore.visibility = View.GONE
                binding.tvDetailStatus.text      = match.timeLabel
                setTint(binding.tvDetailStatus, "#1a2235")
            }
            Glide.with(this).load(match.leagueLogo).into(binding.ivDetailLogo)
        }

        vm.channels.observe(this) { channels ->
            if (channels.isEmpty()) {
                binding.rvChannels.visibility       = View.GONE
                binding.tvChannelsHeader.visibility = View.GONE
                binding.layoutNoChannels.visibility = View.VISIBLE
            } else {
                binding.rvChannels.visibility       = View.VISIBLE
                binding.tvChannelsHeader.visibility = View.VISIBLE
                binding.layoutNoChannels.visibility = View.GONE
                channelAdapter.setData(channels)
            }
        }
    }

    // ─── Live section ──────────────────────────────────────────────────────────

    private fun bindLive(matches: List<Match>) {
        if (matches.isEmpty()) {
            binding.rvLive.visibility    = View.GONE
            binding.tvNoLive.visibility  = View.VISIBLE
            binding.tvLiveCount.visibility = View.GONE
        } else {
            binding.rvLive.visibility    = View.VISIBLE
            binding.tvNoLive.visibility  = View.GONE
            binding.tvLiveCount.text     = matches.size.toString()
            binding.tvLiveCount.visibility = View.VISIBLE
            liveAdapter.setData(matches)
        }
    }

    private fun bindUpcoming(matches: List<Match>) {
        if (matches.isEmpty()) {
            binding.sectionUpcoming.visibility = View.GONE
        } else {
            binding.sectionUpcoming.visibility = View.VISIBLE
            binding.tvUpcomingCount.text       = matches.size.toString()
            binding.tvUpcomingCount.visibility = View.VISIBLE
            upcomingAdapter.setData(matches)
        }
    }

    // ─── Detail panel ──────────────────────────────────────────────────────────

    private fun showDetail(categoryMode: Boolean = false, categoryName: String = "") {
        binding.layoutDetail.visibility = View.VISIBLE
        binding.layoutDetail.animate()
            .translationX(0f).alpha(1f)
            .setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        if (categoryMode) {
            binding.tvDetailLeague.text      = categoryName
            binding.tvDetailHome.text        = ""
            binding.tvDetailAway.text        = ""
            binding.tvDetailScore.visibility = View.GONE
            binding.tvDetailStatus.text      = ""
        }
        binding.rvChannels.post { binding.rvChannels.requestFocus() }
    }

    private fun hideDetail() {
        binding.layoutDetail.animate()
            .translationX(binding.layoutDetail.width.toFloat()).alpha(0f)
            .setDuration(180).withEndAction {
                binding.layoutDetail.visibility = View.GONE
                binding.layoutDetail.translationX = 0f
                binding.layoutDetail.alpha = 1f
            }.start()
        vm.clearSelection()
        binding.rvCategories.requestFocus()
    }

    // ─── Player ────────────────────────────────────────────────────────────────

    private fun openPlayer(channel: Channel) {
        val channelList = vm.channels.value ?: emptyList()
        val idx = channelList.indexOfFirst { it.url == channel.url }.coerceAtLeast(0)
        HotPlayerApp.instance.liveTvChannels     = channelList.ifEmpty { listOf(channel) }
        HotPlayerApp.instance.liveTvChannelIndex = idx
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_CHANNEL_URL,   channel.url)
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME,  channel.name)
            putExtra(PlayerActivity.EXTRA_CHANNEL_LOGO,  channel.logo)
            putExtra(PlayerActivity.EXTRA_CHANNEL_GROUP, channel.group)
            putExtra(PlayerActivity.EXTRA_CHANNEL_INDEX, idx)
        })
    }

    // ─── UI helpers ────────────────────────────────────────────────────────────

    private fun showLoading(show: Boolean) {
        binding.layoutLoading.visibility = if (show) View.VISIBLE else View.GONE
        binding.progressBar.visibility   = if (show) View.VISIBLE else View.GONE
        binding.tvError.visibility       = View.GONE
        binding.btnRetry.visibility      = View.GONE
    }

    private fun showError(msg: String) {
        binding.layoutLoading.visibility = View.VISIBLE
        binding.progressBar.visibility   = View.GONE
        binding.tvError.visibility       = View.VISIBLE
        binding.tvError.text             = msg
        binding.btnRetry.visibility      = View.VISIBLE
        binding.btnRetry.post { binding.btnRetry.requestFocus() }
    }

    private fun setTint(v: TextView, hex: String) {
        v.backgroundTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor(hex)
        )
    }

    // ─── Back key ──────────────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE)) {
            if (binding.layoutDetail.visibility == View.VISIBLE) {
                hideDetail(); return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ─── Util ──────────────────────────────────────────────────────────────────

    private fun okKeyListener(action: () -> Unit) = View.OnKeyListener { _, keyCode, event ->
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return@OnKeyListener false
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> { action(); true }
            else -> false
        }
    }
}

// ══ Adapter: Live Match Cards ════════════════════════════════════════════════

class LiveMatchCardAdapter(
    private val onClick: (Match) -> Unit
) : RecyclerView.Adapter<LiveMatchCardAdapter.VH>() {

    private var items = listOf<Match>()
    fun setData(data: List<Match>) { items = data; notifyDataSetChanged() }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val ivLeague:  ImageView = v.findViewById(R.id.ivLeagueLogo)
        val tvLeague:  TextView  = v.findViewById(R.id.tvLeagueName)
        val ivHome:    ImageView = v.findViewById(R.id.ivHomeLogo)
        val tvHome:    TextView  = v.findViewById(R.id.tvHomeTeam)
        val ivAway:    ImageView = v.findViewById(R.id.ivAwayLogo)
        val tvAway:    TextView  = v.findViewById(R.id.tvAwayTeam)
        val tvScore:   TextView  = v.findViewById(R.id.tvScore)
        val tvStatus:  TextView  = v.findViewById(R.id.tvStatus)
        val focusBorder: View    = v.findViewById(R.id.viewFocusBorder)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_live_match_card, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val m = items[pos]
        h.tvLeague.text   = m.leagueName
        h.tvHome.text     = m.homeTeam.take(10)
        h.tvAway.text     = m.awayTeam.take(10)
        h.tvScore.text    = "${m.homeScore} - ${m.awayScore}"
        h.tvStatus.text   = m.timeLabel

        Glide.with(h.itemView.context).load(m.leagueLogo)
            .placeholder(R.drawable.ic_channel_placeholder).into(h.ivLeague)
        Glide.with(h.itemView.context).load(m.homeLogo())
            .placeholder(R.drawable.ic_channel_placeholder).into(h.ivHome)
        Glide.with(h.itemView.context).load(m.awayLogo())
            .placeholder(R.drawable.ic_channel_placeholder).into(h.ivAway)

        h.itemView.setOnFocusChangeListener { v, hasFocus ->
            h.focusBorder.visibility = if (hasFocus) View.VISIBLE else View.GONE
            v.animate().scaleX(if (hasFocus) 1.05f else 1f)
                .scaleY(if (hasFocus) 1.05f else 1f)
                .setDuration(130).setInterpolator(DecelerateInterpolator()).start()
            v.elevation = if (hasFocus) 12f else 2f
        }
        h.itemView.setOnClickListener { onClick(m) }
        h.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> { onClick(m); true }
                else -> false
            }
        }
        h.itemView.isFocusable = true
    }

    override fun getItemCount() = items.size
}

// ══ Adapter: Sport Category Cards ═══════════════════════════════════════════

class SportCategoryAdapter(
    private val onClick: (SportCategory) -> Unit
) : RecyclerView.Adapter<SportCategoryAdapter.VH>() {

    private var items = listOf<SportCategory>()
    fun setData(data: List<SportCategory>) { items = data; notifyDataSetChanged() }

    private val bgMap = mapOf(
        "football"   to R.drawable.bg_sport_football,
        "basketball" to R.drawable.bg_sport_basketball,
        "tennis"     to R.drawable.bg_sport_tennis,
        "mma"        to R.drawable.bg_sport_mma,
        "baseball"   to R.drawable.bg_sport_baseball,
        "f1"         to R.drawable.bg_sport_f1,
        "other"      to R.drawable.bg_sport_other
    )

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val viewBg:      View     = v.findViewById(R.id.viewBg)
        val tvEmoji:     TextView = v.findViewById(R.id.tvSportEmojiBig)
        val tvName:      TextView = v.findViewById(R.id.tvSportName)
        val tvChannels:  TextView = v.findViewById(R.id.tvChannelCount)
        val tvMatches:   TextView = v.findViewById(R.id.tvMatchCount)
        val focusBorder: View     = v.findViewById(R.id.viewFocusBorder)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_sport_category, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val cat = items[pos]

        // Background gradient
        val bgRes = bgMap[cat.id] ?: R.drawable.bg_sport_other
        h.viewBg.setBackgroundResource(bgRes)

        h.tvEmoji.text    = cat.emoji
        h.tvName.text     = cat.name
        h.tvChannels.text = if (cat.channelCount > 0) "+${cat.channelCount} chaînes" else "Aucune chaîne"

        // Show live match badge
        if (cat.matchCount > 0) {
            h.tvMatches.visibility = View.VISIBLE
            h.tvMatches.text       = "● ${cat.matchCount} LIVE"
        } else {
            h.tvMatches.visibility = View.GONE
        }

        h.itemView.setOnFocusChangeListener { v, hasFocus ->
            h.focusBorder.visibility = if (hasFocus) View.VISIBLE else View.GONE
            v.animate().scaleX(if (hasFocus) 1.07f else 1f)
                .scaleY(if (hasFocus) 1.07f else 1f)
                .setDuration(150).setInterpolator(DecelerateInterpolator()).start()
            v.elevation = if (hasFocus) 16f else 3f
        }
        h.itemView.setOnClickListener { onClick(cat) }
        h.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> { onClick(cat); true }
                else -> false
            }
        }
        h.itemView.isFocusable = true
    }

    override fun getItemCount() = items.size
}

// ══ Adapter: Upcoming Event Cards ═══════════════════════════════════════════

class UpcomingCardAdapter(
    private val onClick: (Match) -> Unit
) : RecyclerView.Adapter<UpcomingCardAdapter.VH>() {

    private var items = listOf<Match>()
    fun setData(data: List<Match>) { items = data; notifyDataSetChanged() }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvTime:    TextView = v.findViewById(R.id.tvTime)
        val tvLeague:  TextView = v.findViewById(R.id.tvLeagueName)
        val tvHome:    TextView = v.findViewById(R.id.tvHomeTeam)
        val tvAway:    TextView = v.findViewById(R.id.tvAwayTeam)
        val focusBorder: View   = v.findViewById(R.id.viewFocusBorder)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_upcoming_card, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val m = items[pos]
        h.tvTime.text   = m.timeLabel
        h.tvLeague.text = m.leagueName
        h.tvHome.text   = m.homeTeam
        h.tvAway.text   = m.awayTeam

        h.itemView.setOnFocusChangeListener { v, hasFocus ->
            h.focusBorder.visibility = if (hasFocus) View.VISIBLE else View.GONE
            v.animate().scaleX(if (hasFocus) 1.05f else 1f)
                .scaleY(if (hasFocus) 1.05f else 1f)
                .setDuration(130).setInterpolator(DecelerateInterpolator()).start()
            v.elevation = if (hasFocus) 10f else 2f
        }
        h.itemView.setOnClickListener { onClick(m) }
        h.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> { onClick(m); true }
                else -> false
            }
        }
        h.itemView.isFocusable = true
    }

    override fun getItemCount() = items.size
}

// ══ Adapter: Watch Channel ═══════════════════════════════════════════════════

class WatchChannelAdapter(
    private val onWatch: (Channel) -> Unit
) : RecyclerView.Adapter<WatchChannelAdapter.VH>() {

    private var items = listOf<Channel>()
    fun setData(data: List<Channel>) { items = data; notifyDataSetChanged() }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val ivLogo: ImageView = v.findViewById(R.id.ivChLogo)
        val tvAbbr: TextView  = v.findViewById(R.id.tvChLogoText)
        val tvName: TextView  = v.findViewById(R.id.tvChName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_watch_channel, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val ch = items[pos]
        h.tvName.text = ch.name
        if (!ch.logo.isNullOrBlank()) {
            h.ivLogo.visibility = View.VISIBLE
            h.tvAbbr.visibility = View.GONE
            Glide.with(h.itemView.context).load(ch.logo)
                .placeholder(R.drawable.ic_channel_placeholder).into(h.ivLogo)
        } else {
            Glide.with(h.itemView.context).clear(h.ivLogo)
            h.ivLogo.visibility = View.GONE
            h.tvAbbr.visibility = View.VISIBLE
            h.tvAbbr.text = ch.name.take(4).uppercase()
        }
        h.itemView.setOnFocusChangeListener { v, hasFocus ->
            h.tvName.setTextColor(
                android.graphics.Color.parseColor(if (hasFocus) "#1a1200" else "#e2e8f0")
            )
            v.elevation = if (hasFocus) 6f else 0f
        }
        h.itemView.setOnClickListener { onWatch(ch) }
        h.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> { onWatch(ch); true }
                else -> false
            }
        }
        h.itemView.isFocusable = true
    }

    override fun getItemCount() = items.size
}
