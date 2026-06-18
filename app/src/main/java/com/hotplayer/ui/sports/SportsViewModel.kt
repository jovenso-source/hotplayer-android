package com.hotplayer.ui.sports

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.*
import com.hotplayer.R
import com.hotplayer.data.model.Channel
import com.hotplayer.data.model.Match
import com.hotplayer.data.repository.SessionRepository
import com.hotplayer.data.repository.SportsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Sport category model ──────────────────────────────────────────────────────

data class SportCategory(
    val id: String,
    val name: String,
    val emoji: String,
    val bgDrawableRes: Int,
    val keywords: List<String>,
    val channelCount: Int = 0,
    val matchCount: Int = 0
)

// ── ViewModel ────────────────────────────────────────────────────────────────

class SportsViewModel(
    private val sportsRepo:  SportsRepository,
    private val sessionRepo: SessionRepository
) : ViewModel() {

    sealed class State {
        object Loading : State()
        data class Ready(
            val liveMatches:    List<Match>,
            val upcomingMatches: List<Match>,
            val categories:     List<SportCategory>
        ) : State()
        data class Error(val msg: String) : State()
    }

    private val _state        = MutableLiveData<State>(State.Loading)
    val state: LiveData<State> = _state

    private val _selected          = MutableLiveData<Match?>()
    val selected: LiveData<Match?> = _selected

    private val _channels              = MutableLiveData<List<Channel>>(emptyList())
    val channels: LiveData<List<Channel>> = _channels

    private var allChannels = listOf<Channel>()
    private var allMatches  = listOf<Match>()

    private val refreshHandler  = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { silentRefresh() }

    // ── Base categories (no counts yet) ──────────────────────────────────────

    private val BASE_CATEGORIES = listOf(
        SportCategory("football",   "Football",      "⚽", R.drawable.bg_sport_football,
            listOf("foot", "soccer", "liga", "ligue", "premier", "serie", "bundesliga",
                   "champions", "copa", "cup", "calcio", "eredivisie", "primera",
                   "super lig", "coupe", "fa cup", "ligue 1", "ligue 2", "mls",
                   "euro", "world cup", "nations league")),

        SportCategory("basketball", "Basketball",    "🏀", R.drawable.bg_sport_basketball,
            listOf("basket", "nba", "ncaa", "euroleague", "bball", "basketball")),

        SportCategory("tennis",     "Tennis",        "🎾", R.drawable.bg_sport_tennis,
            listOf("tennis", "atp", "wta", "roland", "wimbledon", "open", "davis cup")),

        SportCategory("mma",        "MMA / Combat",  "🥊", R.drawable.bg_sport_mma,
            listOf("mma", "ufc", "boxe", "boxing", "combat", "fight", "k-1",
                   "wrestling", "kick", "muay")),

        SportCategory("baseball",   "Baseball",      "⚾", R.drawable.bg_sport_baseball,
            listOf("baseball", "mlb", "softball")),

        SportCategory("f1",         "Formule 1",     "🏎️", R.drawable.bg_sport_f1,
            listOf("formula", "f1", "motogp", "moto gp", "motorsport", "nascar",
                   "indycar", "wrc", "rally", "superbike")),

        SportCategory("other",      "Autres Sports", "🏅", R.drawable.bg_sport_other,
            listOf("rugby", "golf", "cycling", "velo", "natation", "swim", "athl",
                   "handball", "volleyball", "hockey", "ski", "winter", "olympic",
                   "sport"))
    )

    // ── Public API ────────────────────────────────────────────────────────────

    fun load() = viewModelScope.launch {
        _state.value = State.Loading
        try {
            val creds = sessionRepo.getPlaylistCredentials()
            allChannels = withContext(Dispatchers.IO) {
                sessionRepo.loadChannelCache(creds) ?: emptyList()
            }
            allMatches = sportsRepo.getLiveMatches(force = true)
            publish()
            scheduleRefresh()
        } catch (e: Throwable) {
            _state.value = State.Error("Connexion impossible\n${e.message}")
        }
    }

    fun selectMatch(match: Match) {
        if (_selected.value?.id == match.id) return
        _selected.value = match
        _channels.value = SportsChannelMapper.findChannels(match, allChannels)
    }

    fun clearSelection() {
        _selected.value = null
        _channels.value = emptyList()
    }

    fun filterByCategory(category: SportCategory) {
        _selected.value = null
        _channels.value = emptyList()
        // Find channels for this sport category
        val sportChannels = allChannels.filter { ch ->
            val name = ch.name.lowercase()
            val grp  = ch.group?.lowercase() ?: ""
            category.keywords.any { kw -> name.contains(kw) || grp.contains(kw) }
        }
        _channels.value = sportChannels.take(20)
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun publish() {
        val live     = allMatches.filter { it.isLive }
        val upcoming = allMatches.filter { it.isUpcoming }.take(20)
        val cats     = buildCategories()
        _state.value = State.Ready(live, upcoming, cats)
        if (live.isNotEmpty()) updateLiveBadge(live.size)
    }

    private fun buildCategories(): List<SportCategory> {
        return BASE_CATEGORIES.map { cat ->
            val chCount = allChannels.count { ch ->
                val name = ch.name.lowercase()
                val grp  = ch.group?.lowercase() ?: ""
                cat.keywords.any { kw -> name.contains(kw) || grp.contains(kw) }
            }
            val mCount = allMatches.count { m ->
                val league = m.leagueName.lowercase()
                cat.keywords.any { kw -> league.contains(kw) }
            }
            cat.copy(channelCount = chCount, matchCount = mCount)
        }
    }

    private var liveBadgeCallback: ((Int) -> Unit)? = null
    fun setLiveBadgeCallback(cb: (Int) -> Unit) { liveBadgeCallback = cb }
    private fun updateLiveBadge(count: Int) { liveBadgeCallback?.invoke(count) }

    private fun silentRefresh() {
        viewModelScope.launch {
            try {
                allMatches = sportsRepo.getLiveMatches(force = true)
                publish()
                _selected.value?.let { sel ->
                    allMatches.find { it.id == sel.id }?.let { _selected.value = it }
                }
            } catch (_: Throwable) {}
        }
        scheduleRefresh()
    }

    private fun scheduleRefresh() {
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, 60_000L)
    }

    override fun onCleared() {
        super.onCleared()
        refreshHandler.removeCallbacks(refreshRunnable)
    }
}

class SportsViewModelFactory(
    private val sportsRepo:  SportsRepository,
    private val sessionRepo: SessionRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(c: Class<T>): T =
        SportsViewModel(sportsRepo, sessionRepo) as T
}
