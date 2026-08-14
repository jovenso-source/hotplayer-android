package com.hotplayer.data.filter

import com.hotplayer.data.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelVisibilityFilterTest {

    private fun m3uChannel(idx: Int, name: String, group: String, tvgId: String? = null) = Channel(
        id = "ch_$idx", name = name, url = "http://provider/live/$idx.ts", logo = null, group = group, tvgId = tvgId
    )

    private fun xtreamChannel(streamId: Int, name: String, group: String) = Channel(
        id = "xt_$streamId", name = name, url = "http://server/live/u/p/$streamId.ts", logo = null, group = group
    )

    // 100 chaînes, 10 masquées France, 5 masquées Sports → 85 visibles (spec §18)
    @Test
    fun `filters exactly the configured count across two categories`() {
        val franceHidden = (1..10).map { m3uChannel(it, "France Chan $it", "France") }
        val franceVisible = (11..60).map { m3uChannel(it, "France Chan $it", "France") }
        val sportsHidden = (61..65).map { m3uChannel(it, "Sport Chan $it", "Sports") }
        val sportsVisible = (66..100).map { m3uChannel(it, "Sport Chan $it", "Sports") }
        val all = franceHidden + franceVisible + sportsHidden + sportsVisible
        assertEquals(100, all.size)

        val response = ChannelFilterResponse(
            enabled = true, globalVersion = 1,
            lists = listOf(
                ChannelFilterListDto(id = "france", name = "France", playlistCategory = "France", version = 1,
                    hiddenChannels = franceHidden.map { "name:${it.name.lowercase()}" }),
                ChannelFilterListDto(id = "sports", name = "Sports", playlistCategory = "Sports", version = 1,
                    hiddenChannels = sportsHidden.map { "name:${it.name.lowercase()}" }),
            )
        )
        val filter = ChannelVisibilityFilter.from(response)
        val visible = filter.apply(all)
        assertEquals(85, visible.size)
        assertTrue(visible.none { it in franceHidden })
        assertTrue(visible.none { it in sportsHidden })
    }

    @Test
    fun `null response is passthrough (no cache received yet)`() {
        val filter = ChannelVisibilityFilter.from(null)
        val channels = listOf(m3uChannel(1, "Any Channel", "AnyCat"))
        assertEquals(channels, filter.apply(channels))
        assertTrue(filter.isVisible(channels[0]))
    }

    @Test
    fun `enabled=false globally is passthrough even with non-empty hidden_channels`() {
        val response = ChannelFilterResponse(
            enabled = false, globalVersion = 1,
            lists = listOf(ChannelFilterListDto(id = "x", name = "X", playlistCategory = "France", version = 1, hiddenChannels = listOf("name:france chan 1")))
        )
        val filter = ChannelVisibilityFilter.from(response)
        val ch = m3uChannel(1, "France Chan 1", "France")
        assertTrue("killswitch off must never hide anything", filter.isVisible(ch))
    }

    @Test
    fun `disabled list (absent from response) means its channels reappear`() {
        // Le backend exclut lui-même les listes désactivées de la réponse publique —
        // côté Android, "liste désactivée" = "liste absente" (voir channelFiltersPublic.js).
        val response = ChannelFilterResponse(enabled = true, globalVersion = 2, lists = emptyList())
        val filter = ChannelVisibilityFilter.from(response)
        val ch = m3uChannel(1, "France Chan 1", "France")
        assertTrue(filter.isVisible(ch))
    }

    @Test
    fun `removing a channel from the hidden list makes it reappear on next version`() {
        val ch = m3uChannel(1, "France Chan 1", "France")
        val v1 = ChannelFilterResponse(enabled = true, globalVersion = 1, lists = listOf(
            ChannelFilterListDto(id = "france", name = "France", playlistCategory = "France", version = 1, hiddenChannels = listOf("name:france chan 1"))
        ))
        assertFalse(ChannelVisibilityFilter.from(v1).isVisible(ch))

        val v2 = ChannelFilterResponse(enabled = true, globalVersion = 2, lists = listOf(
            ChannelFilterListDto(id = "france", name = "France", playlistCategory = "France", version = 2, hiddenChannels = emptyList())
        ))
        assertTrue(ChannelVisibilityFilter.from(v2).isVisible(ch))
    }

    // Une catégorie entièrement masquée doit disparaître, sans qu'on ait besoin d'une
    // logique dédiée : buildCatsInOrder() ne verra simplement plus aucune chaîne "Sports".
    @Test
    fun `fully-hidden category yields zero visible channels for that category`() {
        val sports = (1..5).map { m3uChannel(it, "Sport $it", "Sports") }
        val response = ChannelFilterResponse(enabled = true, globalVersion = 1, lists = listOf(
            ChannelFilterListDto(id = "sports", name = "Sports", playlistCategory = "Sports", version = 1,
                hiddenChannels = sports.map { "name:${it.name.lowercase()}" })
        ))
        val visible = ChannelVisibilityFilter.from(response).apply(sports)
        assertTrue(visible.isEmpty())
    }

    // Une chaîne d'une catégorie ne doit jamais être masquée par une liste d'une autre catégorie
    // (partitionnement strict par catégorie, spec §9).
    @Test
    fun `category partitioning prevents cross-category leakage`() {
        val franceChannel = m3uChannel(1, "Same Name", "France")
        val sportsChannel = m3uChannel(2, "Same Name", "Sports")
        val response = ChannelFilterResponse(enabled = true, globalVersion = 1, lists = listOf(
            ChannelFilterListDto(id = "france", name = "France", playlistCategory = "France", version = 1, hiddenChannels = listOf("name:same name"))
        ))
        val filter = ChannelVisibilityFilter.from(response)
        assertFalse(filter.isVisible(franceChannel))
        assertTrue("même nom mais catégorie différente ne doit pas être masqué", filter.isVisible(sportsChannel))
    }

    @Test
    fun `xtream stable id matches by id key`() {
        val ch = xtreamChannel(1234, "ESPN HD", "Sports")
        val response = ChannelFilterResponse(enabled = true, globalVersion = 1, lists = listOf(
            ChannelFilterListDto(id = "sports", name = "Sports", playlistCategory = "Sports", version = 1, hiddenChannels = listOf("id:1234"))
        ))
        assertFalse(ChannelVisibilityFilter.from(response).isVisible(ch))
    }

    @Test
    fun `m3u sequential id is never used as a matching key`() {
        // Un id M3U ("ch_0") ne doit jamais servir de clé — il est régénéré à chaque parsing.
        val ch = m3uChannel(0, "Some Channel", "France")
        val response = ChannelFilterResponse(enabled = true, globalVersion = 1, lists = listOf(
            ChannelFilterListDto(id = "france", name = "France", playlistCategory = "France", version = 1, hiddenChannels = listOf("id:0"))
        ))
        // "id:0" ne doit matcher AUCUNE clé candidate d'un channel M3U (seul name: est généré)
        assertTrue(ChannelVisibilityFilter.from(response).isVisible(ch))
    }

    @Test
    fun `tvg-id match works independently of channel name`() {
        val ch = m3uChannel(1, "Some Random Name", "France", tvgId = "France2.fr")
        val response = ChannelFilterResponse(enabled = true, globalVersion = 1, lists = listOf(
            ChannelFilterListDto(id = "france", name = "France", playlistCategory = "France", version = 1, hiddenChannels = listOf("tvg:france2.fr"))
        ))
        assertFalse(ChannelVisibilityFilter.from(response).isVisible(ch))
    }

    @Test
    fun `malformed response never throws and falls back to passthrough`() {
        val weird = ChannelFilterResponse(enabled = true, globalVersion = 1, lists = listOf(
            ChannelFilterListDto(id = "x", name = "X", playlistCategory = "", version = 1, hiddenChannels = listOf("name:whatever"))
        ))
        // Catégorie vide ignorée — ne doit jamais planter
        val filter = ChannelVisibilityFilter.from(weird)
        val ch = m3uChannel(1, "Whatever", "SomeCategory")
        assertTrue(filter.isVisible(ch))
    }

    @Test
    fun `isVisible never throws even against a degenerate channel`() {
        val degenerate = Channel(id = "", name = "", url = "", logo = null, group = null, tvgId = null)
        val filter = ChannelVisibilityFilter.from(
            ChannelFilterResponse(enabled = true, globalVersion = 1, lists = listOf(
                ChannelFilterListDto(id = "x", name = "X", playlistCategory = "Cat", version = 1, hiddenChannels = listOf("name:"))
            ))
        )
        // Ne doit jamais lever d'exception, quel que soit le résultat
        filter.isVisible(degenerate)
    }
}
