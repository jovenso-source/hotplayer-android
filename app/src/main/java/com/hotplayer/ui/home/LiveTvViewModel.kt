package com.hotplayer.ui.home

import android.util.Log
import androidx.lifecycle.*
import com.hotplayer.data.filter.ChannelFilterRepository
import com.hotplayer.data.filter.ChannelVisibilityFilter
import com.hotplayer.data.model.Channel
import com.hotplayer.data.model.ChannelType
import com.hotplayer.data.model.EpgItem
import com.hotplayer.data.repository.SessionRepository
import com.hotplayer.data.repository.SessionRepository.PlaylistCredentials
import com.hotplayer.utils.ChannelUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LiveTvViewModel(
    private val repo: SessionRepository,
    private val filterRepo: ChannelFilterRepository
) : ViewModel() {

    companion object {
        private const val TAG = "LiveTvVM"

        // Decoupled from the playlist's own refresh cadence (SessionRepository's 1h stale
        // threshold, untouched) — the filter payload is tiny, so it can be checked far more
        // often without the risk/cost that governs full playlist reloads.
        private const val MIN_FILTER_CHECK_INTERVAL_MS = 12 * 60 * 1000L   // Live TV re-entry gate: 10-15min
        private const val PERIODIC_FILTER_CHECK_INTERVAL_MS = 45 * 60 * 1000L // prolonged session: 30-60min
    }

    sealed class State {
        object Loading : State()
        data class Ready(val channels: List<Channel>, val cats: List<Pair<String, Int>>, val isFromRefresh: Boolean = false) : State()
        data class Error(val msg: String) : State()
    }

    private val _state = MutableLiveData<State>(State.Loading)
    val state: LiveData<State> = _state

    private val _index = MutableLiveData(-1)
    val currentIndex: LiveData<Int> = _index

    private val _epg = MutableLiveData<List<EpgItem>>(emptyList())
    val epg: LiveData<List<EpgItem>> = _epg

    private var allChannels = listOf<Channel>()
    var hideFhd   = false
    var hideBe    = false
    var hideAdult = false

    // Backend-driven "hidden channels" filter — fail-open (see ChannelVisibilityFilter),
    // never blocks display: starts as PASSTHROUGH, refined from local cache then network.
    private var visibilityFilter: ChannelVisibilityFilter = ChannelVisibilityFilter.PASSTHROUGH

    // Per-category pre-sorted channel lists. Key = group name; "" = Tous.
    // Built once on Dispatchers.Default after load → filterByCategory becomes O(1).
    private var channelsByGroup: Map<String, List<Channel>> = emptyMap()
    private var prebuiltCats = listOf<Pair<String, Int>>()

    var displayed = listOf<Channel>(); private set
    val currentChannel get() = displayed.getOrNull(_index.value ?: -1)
    var currentCat = "Tous"; private set
    private var currentQuery = ""

    private var epgJob: Job? = null
    private var filterPeriodicJob: Job? = null

    // ── Load ──────────────────────────────────────────────────────────────────

    fun load(forceRefresh: Boolean = false) = viewModelScope.launch {
        currentCat = "Tous"

        // Last known-good visibility filter, applied synchronously before the first frame —
        // fail-open to PASSTHROUGH (show everything) on any cache-read/parse failure.
        visibilityFilter = try {
            withContext(Dispatchers.IO) { ChannelVisibilityFilter.from(filterRepo.loadCachedConfigOrNull()) }
        } catch (_: Throwable) {
            ChannelVisibilityFilter.PASSTHROUGH
        }
        launchFilterConfigRefresh()
        launchPeriodicFilterCheck()

        // Cache-first: show local data instantly, without waiting on any network round-trip —
        // not even the one to refresh credentials. A weak/slow connection must never delay the
        // first frame when we already have something usable on disk.
        if (!forceRefresh) {
            val shown = tryShowFromLocalCache()
            if (shown) return@launch
        }

        // No usable local cache (first launch, cleared cache, or an explicit forced refresh):
        // fall back to the network-first path — there is genuinely nothing else to show yet.
        _state.value = State.Loading
        try {
            val creds = repo.getPlaylistCredentials()
            if (creds is PlaylistCredentials.None) {
                _state.value = State.Error("Aucune playlist configurée.\nContactez votre administrateur.")
                return@launch
            }
            val channels = fetchFromNetwork(creds) ?: return@launch
            withContext(Dispatchers.IO) { repo.saveChannelCache(channels, creds) }
            withContext(Dispatchers.Default) { buildIndex(channels) }
            allChannels = channels
            applyFilter("Tous")
        } catch (e: Throwable) {
            _state.value = State.Error("Erreur inattendue : ${e.message}")
        }
    }

    // Returns true if local cache existed and was used to populate the UI immediately.
    // Never touches the network to decide whether to show (A) — only to decide, afterwards,
    // whether a background refresh (B) is worth triggering.
    private suspend fun tryShowFromLocalCache(): Boolean {
        val localCreds = try {
            repo.getPlaylistCredentialsFromCache()
        } catch (_: Throwable) {
            return false
        }
        if (localCreds is PlaylistCredentials.None) return false

        val cached = withContext(Dispatchers.IO) { repo.loadChannelCache(localCreds) } ?: return false

        withContext(Dispatchers.Default) { buildIndex(cached) }
        allChannels = cached
        applyFilter("Tous")

        val stale = withContext(Dispatchers.IO) { repo.isChannelCacheStale(localCreds) }
        if (stale) launchBackgroundRefresh()
        return true
    }

    // Fetches the current server playlist and merges it in if non-empty (see silentRefresh),
    // without resetting scroll position, focus or the active category filter.
    private fun launchBackgroundRefresh() {
        viewModelScope.launch {
            try {
                val freshCreds = repo.getPlaylistCredentials()
                if (freshCreds !is PlaylistCredentials.None) silentRefresh(freshCreds)
            } catch (_: Throwable) {}
        }
    }

    // Manual "refresh now" — same background merge as an automatic stale refresh, just
    // triggered on demand instead of gated by cache age. Does not reset State.Loading:
    // whatever is currently displayed stays up until (and unless) the refresh succeeds.
    fun refreshNow() = launchBackgroundRefresh()

    // Best-effort background check of the backend's hidden-channels config, throttled to at
    // most once per MIN_FILTER_CHECK_INTERVAL_MS per process (refreshIfDue's first-call-always
    // exemption means this behaves as "always check" right after app/session launch, then backs
    // off on subsequent Live TV re-entries — see ChannelFilterRepository.refreshIfDue()).
    // Never blocks display, never touches State.Loading/State.Error — a failure or a throttled
    // skip both just leave the last known-good visibilityFilter (or PASSTHROUGH) in effect.
    private fun launchFilterConfigRefresh() {
        viewModelScope.launch {
            try {
                val fresh = filterRepo.refreshIfDue(MIN_FILTER_CHECK_INTERVAL_MS) ?: return@launch
                applyVisibilityFilterAndRefreshUi(ChannelVisibilityFilter.from(fresh))
            } catch (_: Throwable) {}
        }
    }

    // Lightweight background re-check while Live TV stays open for a long session — completely
    // independent of the playlist's own refresh cadence (SessionRepository, untouched). Started
    // once per ViewModel instance; auto-cancelled with viewModelScope when the screen is torn
    // down, never a stray long-lived timer. Deliberately NOT aggressive polling: one conditional
    // GET every 30-60min, most of which resolve to a cheap 304 (see refreshFromNetwork()).
    private fun launchPeriodicFilterCheck() {
        if (filterPeriodicJob != null) return
        filterPeriodicJob = viewModelScope.launch {
            while (true) {
                delay(PERIODIC_FILTER_CHECK_INTERVAL_MS)
                try {
                    val fresh = filterRepo.refreshIfDue(MIN_FILTER_CHECK_INTERVAL_MS) ?: continue
                    applyVisibilityFilterAndRefreshUi(ChannelVisibilityFilter.from(fresh))
                } catch (_: Throwable) {}
            }
        }
    }

    // Atomic swap: visibilityFilter is only ever reassigned once a fetch has fully succeeded
    // and parsed — a failed/throttled/304 refresh (see callers) never reaches this function, so
    // the previous filter stays active until a fully-valid replacement is ready. Only touches
    // LiveData (state/index) — never LiveTvActivity, never ExoPlayer/playerMgr, so a channel
    // becoming hidden while it's actively playing (preview or fullscreen PlayerActivity) never
    // interrupts playback; it only stops appearing in the next-computed visible list.
    private suspend fun applyVisibilityFilterAndRefreshUi(newFilter: ChannelVisibilityFilter) {
        visibilityFilter = newFilter
        if (allChannels.isEmpty()) return
        withContext(Dispatchers.Default) { buildIndex(allChannels) }
        val prevUrl = currentChannel?.url
        displayed = computeVisible(currentCat)
        _state.value = State.Ready(displayed, visibleCats(), isFromRefresh = true)
        restoreSelectionOrClamp(prevUrl)
    }

    // Keeps the current selection if it's still visible; otherwise clamps to a valid index
    // (0, or -1 if the list is now empty) instead of leaving a stale/out-of-bounds _index that
    // would make currentChannel resolve to the wrong channel after a filter/playlist refresh.
    private fun restoreSelectionOrClamp(prevUrl: String?) {
        _index.value = ChannelUtils.resolveSelectionIndex(displayed, prevUrl)
    }

    // ── Index builder (Dispatchers.Default — never on main thread) ─────────────
    // Sorts every category list ONCE. filterByCategory becomes a HashMap lookup.

    private fun buildIndex(channels: List<Channel>) {
        val t0 = System.currentTimeMillis()
        // Ordre playlist source — aucun tri appliqué sur les chaînes ni les catégories.
        // channelsByGroup reste indexé sur les données SOURCE, jamais modifiées par le
        // filtre de visibilité (celui-ci n'agit qu'en aval, dans computeVisible()).
        val byGroup = channels.groupBy { it.group ?: "" }
        channelsByGroup = buildMap {
            put("", channels)                                // "" = Tous, ordre original
            byGroup.forEach { (g, list) ->
                if (g.isNotEmpty()) put(g, list)
            }
        }
        // Catégories/compteurs calculés après filtre de visibilité : une catégorie entièrement
        // masquée n'a alors plus aucune chaîne dans visibleChannels et disparaît naturellement
        // de prebuiltCats — aucune logique d'exclusion supplémentaire n'est nécessaire.
        val visibleChannels = visibilityFilter.apply(channels)
        prebuiltCats = ChannelUtils.buildCatsInOrder(visibleChannels, "Tous")
        Log.d(TAG, "buildIndex: ${channels.size} ch (${visibleChannels.size} visible) → ${channelsByGroup.size} groups in ${System.currentTimeMillis() - t0}ms")
    }

    // ── Filter — O(1) HashMap lookup, safe to call synchronously on main thread ─
    // Single source of truth for the display pipeline: applyFilter() and search() both
    // route through this function so the filter chain (hideFhd/hideBe/hideAdult, the backend
    // visibility filter, and the free-text query) is never duplicated.

    private fun computeVisible(cat: String): List<Channel> {
        val key = if (cat == "Tous") "" else cat
        var list = channelsByGroup[key] ?: emptyList()
        if (hideFhd)   list = list.filter { !it.name.contains("FHD", ignoreCase = true) }
        if (hideBe)    list = list.filter { it.group?.contains("|BE|", ignoreCase = true) != true }
        if (hideAdult) list = list.filter { !ChannelUtils.isAdultCategory(it.group ?: "") }
        list = visibilityFilter.apply(list)
        if (currentQuery.isNotEmpty()) {
            list = list.filter {
                it.name.contains(currentQuery, ignoreCase = true) ||
                it.group?.contains(currentQuery, ignoreCase = true) == true
            }
        }
        return list
    }

    private fun applyFilter(cat: String, isRefresh: Boolean = false) {
        val t0  = System.currentTimeMillis()
        displayed = computeVisible(cat)
        val dt = System.currentTimeMillis() - t0
        Log.d(TAG, "applyFilter: cat='$cat' → ${displayed.size} ch in ${dt}ms")
        _state.value = State.Ready(displayed, visibleCats(), isRefresh)
        _index.value = if (displayed.isNotEmpty()) 0 else -1
    }

    // Bug #4: "Tous" count must reflect what is actually visible after filters
    private fun visibleCats(): List<Pair<String, Int>> {
        if (!hideBe && !hideAdult) return prebuiltCats
        val filtered = prebuiltCats.drop(1).filter { (cat, _) ->
            if (hideBe    && cat.contains("|BE|", ignoreCase = true)) return@filter false
            if (hideAdult && ChannelUtils.isAdultCategory(cat))       return@filter false
            true
        }
        val totalVisible = filtered.sumOf { it.second }
        return listOf("Tous" to totalVisible) + filtered
    }

    fun applyFhdFilter(hide: Boolean) {
        if (hide == hideFhd) return
        hideFhd = hide
        applyFilter(currentCat)
    }

    fun applyBeFilter(hide: Boolean) {
        if (hide == hideBe) return
        hideBe = hide
        applyFilter(currentCat)
    }

    fun applyAdultFilter(hide: Boolean) {
        if (hide == hideAdult) return
        hideAdult = hide
        applyFilter(currentCat)
    }

    // Bug #3: batch all 3 filters in one call → single applyFilter / single UI redraw
    fun syncFilters(hideFhd: Boolean, hideBe: Boolean, hideAdult: Boolean) {
        val changed = hideFhd != this.hideFhd ||
                      hideBe  != this.hideBe  ||
                      hideAdult != this.hideAdult
        this.hideFhd   = hideFhd
        this.hideBe    = hideBe
        this.hideAdult = hideAdult
        if (changed) applyFilter(currentCat)
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    fun filterByCategory(cat: String) {
        Log.d(TAG, "filterByCategory: '$cat' (was '${currentCat}') — StateFlow emit triggered")
        currentCat = cat
        currentQuery = ""
        applyFilter(cat)
    }

    fun clearSearch() { currentQuery = ""; applyFilter(currentCat) }

    // Bug #1 + #2: search must apply active filters and use visibleCats()
    fun search(query: String) {
        currentQuery = query.trim()
        applyFilter(currentCat)
    }

    fun setIndex(idx: Int) {
        if (idx < 0) return
        _index.value = idx
    }

    fun triggerEpg(idx: Int) {
        fetchEpg(displayed.getOrNull(idx) ?: return)
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun fetchEpg(ch: Channel) {
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            val id = extractStreamId(ch.url) ?: return@launch
            _epg.value = repo.fetchShortEpg(id)
        }
    }

    private suspend fun fetchFromNetwork(creds: PlaylistCredentials): List<Channel>? {
        val channels: List<Channel> = when (creds) {
            is PlaylistCredentials.Xtream -> {
                val list = repo.loadChannelsXtream(creds.server, creds.user, creds.pass)
                if (list.isEmpty()) {
                    _state.value = State.Error(
                        "Impossible de contacter le serveur Xtream.\nVérifiez les identifiants et la connexion."
                    )
                    return null
                }
                list
            }
            is PlaylistCredentials.M3u -> {
                val all = repo.loadChannels(creds.url)
                if (all.isEmpty()) {
                    _state.value = State.Error(
                        "Impossible de charger la playlist M3U.\nVérifiez votre connexion."
                    )
                    return null
                }
                all.filter { it.type == ChannelType.LIVE }
            }
            is PlaylistCredentials.None -> {
                _state.value = State.Error("Aucune playlist configurée.\nContactez votre administrateur.")
                return null
            }
        }
        if (channels.isEmpty()) {
            _state.value = State.Error("Aucune chaîne live trouvée.")
            return null
        }
        return channels
    }

    private suspend fun silentRefresh(creds: PlaylistCredentials) {
        val channels = when (creds) {
            is PlaylistCredentials.Xtream -> repo.loadChannelsXtream(creds.server, creds.user, creds.pass)
            is PlaylistCredentials.M3u    -> repo.loadChannels(creds.url).filter { it.type == ChannelType.LIVE }
            is PlaylistCredentials.None   -> return
        }
        if (channels.isNotEmpty()) {
            withContext(Dispatchers.IO) { repo.saveChannelCache(channels, creds) }
            withContext(Dispatchers.Default) { buildIndex(channels) }
            allChannels = channels
            val prevUrl = currentChannel?.url
            displayed = computeVisible(currentCat)
            _state.value = State.Ready(displayed, visibleCats(), isFromRefresh = true)
            restoreSelectionOrClamp(prevUrl)
        }
    }

    private fun extractStreamId(url: String): String? =
        Regex("""/(\d+)\.(?:ts|m3u8|mp4)""").find(url)?.groupValues?.get(1)
            ?: Regex("""/(\d+)\z""").find(url)?.groupValues?.get(1)
}

class LiveTvViewModelFactory(
    private val repo: SessionRepository,
    private val filterRepo: ChannelFilterRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(c: Class<T>): T = LiveTvViewModel(repo, filterRepo) as T
}
