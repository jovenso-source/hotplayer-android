package com.hotplayer.ui.home

import android.util.Log
import androidx.lifecycle.*
import com.hotplayer.data.model.Channel
import com.hotplayer.data.model.ChannelType
import com.hotplayer.data.model.EpgItem
import com.hotplayer.data.repository.SessionRepository
import com.hotplayer.data.repository.SessionRepository.PlaylistCredentials
import com.hotplayer.utils.ChannelUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LiveTvViewModel(private val repo: SessionRepository) : ViewModel() {

    companion object {
        private const val TAG = "LiveTvVM"
    }

    sealed class State {
        object Loading : State()
        data class Ready(val channels: List<Channel>, val cats: List<Pair<String, Int>>) : State()
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

    // Per-category pre-sorted channel lists. Key = group name; "" = Tous.
    // Built once on Dispatchers.Default after load → filterByCategory becomes O(1).
    private var channelsByGroup: Map<String, List<Channel>> = emptyMap()
    private var prebuiltCats = listOf<Pair<String, Int>>()

    var displayed = listOf<Channel>(); private set
    val currentChannel get() = displayed.getOrNull(_index.value ?: -1)
    var currentCat = "Tous"; private set

    private var epgJob: Job? = null

    // ── Load ──────────────────────────────────────────────────────────────────

    fun load(forceRefresh: Boolean = false) = viewModelScope.launch {
        _state.value = State.Loading
        currentCat = "Tous"
        try {
            val creds = repo.getPlaylistCredentials()
            if (creds is PlaylistCredentials.None) {
                _state.value = State.Error("Aucune playlist configurée.\nContactez votre administrateur.")
                return@launch
            }
            if (!forceRefresh) {
                val cached = withContext(Dispatchers.IO) { repo.loadChannelCache(creds) }
                if (cached != null) {
                    withContext(Dispatchers.Default) { buildIndex(cached) }
                    allChannels = cached
                    applyFilter("Tous")
                    viewModelScope.launch {
                        try { silentRefresh(creds) } catch (_: Throwable) {}
                    }
                    return@launch
                }
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

    // ── Index builder (Dispatchers.Default — never on main thread) ─────────────
    // Sorts every category list ONCE. filterByCategory becomes a HashMap lookup.

    private fun buildIndex(channels: List<Channel>) {
        val t0 = System.currentTimeMillis()
        val sorted = ChannelUtils.sortChannels(channels)
        val byGroup = sorted.groupBy { it.group ?: "" }
        channelsByGroup = buildMap {
            put("", sorted)                                  // "" = Tous
            byGroup.forEach { (g, list) ->
                if (g.isNotEmpty()) put(g, list)
            }
        }
        prebuiltCats = ChannelUtils.buildSortedCats(channels, "Tous")
        Log.d(TAG, "buildIndex: ${channels.size} ch → ${channelsByGroup.size} groups in ${System.currentTimeMillis() - t0}ms")
    }

    // ── Filter — O(1) HashMap lookup, safe to call synchronously on main thread ─

    private fun applyFilter(cat: String) {
        val t0  = System.currentTimeMillis()
        val key = if (cat == "Tous") "" else cat
        var raw = channelsByGroup[key] ?: emptyList()
        if (hideFhd)   raw = raw.filter { !it.name.contains("FHD", ignoreCase = true) }
        if (hideBe)    raw = raw.filter { it.group?.contains("|BE|", ignoreCase = true) != true }
        if (hideAdult) raw = raw.filter { !ChannelUtils.isAdultCategory(it.group ?: "") }
        displayed = raw
        val dt = System.currentTimeMillis() - t0
        Log.d(TAG, "applyFilter: cat='$cat' → ${displayed.size} ch in ${dt}ms")
        _state.value = State.Ready(displayed, visibleCats())
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
        applyFilter(cat)
    }

    fun clearSearch() { applyFilter(currentCat) }

    // Bug #1 + #2: search must apply active filters and use visibleCats()
    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) { applyFilter(currentCat); return }
        val key  = if (currentCat == "Tous") "" else currentCat
        var base = channelsByGroup[key] ?: emptyList()
        if (hideFhd)   base = base.filter { !it.name.contains("FHD", ignoreCase = true) }
        if (hideBe)    base = base.filter { it.group?.contains("|BE|", ignoreCase = true) != true }
        if (hideAdult) base = base.filter { !ChannelUtils.isAdultCategory(it.group ?: "") }
        displayed = base.filter {
            it.name.contains(q, ignoreCase = true) ||
            it.group?.contains(q, ignoreCase = true) == true
        }
        _state.value = State.Ready(displayed, visibleCats())
        _index.value = if (displayed.isNotEmpty()) 0 else -1
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
            applyFilter(currentCat)
        }
    }

    private fun extractStreamId(url: String): String? =
        Regex("""/(\d+)\.(?:ts|m3u8|mp4)""").find(url)?.groupValues?.get(1)
            ?: Regex("""/(\d+)\z""").find(url)?.groupValues?.get(1)
}

class LiveTvViewModelFactory(private val repo: SessionRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(c: Class<T>): T = LiveTvViewModel(repo) as T
}
