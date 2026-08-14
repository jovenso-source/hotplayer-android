package com.hotplayer.data.filter

import com.google.gson.Gson
import com.hotplayer.data.model.Channel
import com.hotplayer.data.model.ChannelType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end verification using REAL fixtures, not synthetic ones:
 *  - [M3U_FIXTURE] is a representative HotPlayer playlist (France/Sports/USA channels).
 *  - [REAL_RESPONSE_*] were captured verbatim from a live run of the actual backend
 *    (POST /api/admin/channel-filters/:id/import with the JSON files provided by the user,
 *    then GET /api/channel-filters) — not hand-written, not mocked.
 *
 * [parseM3UMirror] duplicates SessionRepository.parseM3U() line-for-line (verified identical
 * against the source at the time this test was written — see git blame if it drifts) because
 * that method is private on a class requiring an android.content.Context to construct, which
 * this plain JVM unit test environment cannot provide without Robolectric/instrumentation.
 * Everything downstream of parsing — [ChannelVisibilityFilter], [ChannelFilterResponse], Gson
 * deserialization — is the actual, unmodified production code.
 */
class ChannelFilterEndToEndTest {

    companion object {
        private val M3U_LOGO_REGEX  = Regex("""tvg-logo="([^"]+)"""")
        private val M3U_GROUP_REGEX = Regex("""group-title="([^"]+)"""")
        private val M3U_TVGID_REGEX = Regex("""tvg-id="([^"]+)"""")

        private val M3U_FIXTURE = """
            #EXTM3U
            #EXTINF:-1 tvg-id="tf1.fr" tvg-logo="http://img.example/tf1.png" group-title="France",TF1 HD
            http://provider.example/live/user1/pass1/101.ts
            #EXTINF:-1 tvg-id="france2.fr" tvg-logo="http://img.example/f2.png" group-title="France",France 2
            http://provider.example/live/user1/pass1/102.ts
            #EXTINF:-1 tvg-id="france3.fr" group-title="France",France 3
            http://provider.example/live/user1/pass1/103.ts
            #EXTINF:-1 tvg-id="canalplus.fr" group-title="France",Canal+
            http://provider.example/live/user1/pass1/104.ts
            #EXTINF:-1 tvg-id="bfmtv.fr" group-title="France",BFM TV
            http://provider.example/live/user1/pass1/105.ts
            #EXTINF:-1 tvg-id="espn.us" group-title="Sports",ESPN HD
            http://provider.example/live/user1/pass1/201.ts
            #EXTINF:-1 tvg-id="beinsport1.fr" group-title="Sports",beIN Sport 1
            http://provider.example/live/user1/pass1/202.ts
            #EXTINF:-1 tvg-id="beinsport2.fr" group-title="Sports",beIN Sport 2
            http://provider.example/live/user1/pass1/203.ts
            #EXTINF:-1 group-title="Sports",RMC Sport 1
            http://provider.example/live/user1/pass1/204.ts
            #EXTINF:-1 tvg-id="cnn.us" group-title="USA",CNN International
            http://provider.example/live/user1/pass1/301.ts
            #EXTINF:-1 tvg-id="fox.us" group-title="USA",FOX News
            http://provider.example/live/user1/pass1/302.ts
        """.trimIndent()

        // Capturé le 2026-08-14 via: import de france_inactive.json (format {channels:[...]}
        // fourni tel quel par l'utilisateur, previous hidden_count=0) + sports_inactive.json
        // (idem), puis GET /api/channel-filters sur le serveur réellement démarré.
        private const val REAL_RESPONSE_BOTH_ACTIVE = """
            {"enabled":true,"global_version":4,"lists":[{"id":"france-chaines-inactives","name":"France - chaines inactives","playlist_category":"France","version":1,"hidden_channels":["id:999","tvg:france3.fr","name:france 3","name:bfm tv"]},{"id":"sports-chaines-inactives","name":"Sports - chaines inactives","playlist_category":"Sports","version":1,"hidden_channels":["id:9001","name:old sports feed","tvg:beinsport2.fr","name:bein sport 2","name:rmc sport 1","name:cnn international"]}]}
        """

        // Capturé après POST /api/admin/channel-filters/france-chaines-inactives/deactivate.
        private const val REAL_RESPONSE_FRANCE_DEACTIVATED = """
            {"enabled":true,"global_version":5,"lists":[{"id":"sports-chaines-inactives","name":"Sports - chaines inactives","playlist_category":"Sports","version":1,"hidden_channels":["id:9001","name:old sports feed","tvg:beinsport2.fr","name:bein sport 2","name:rmc sport 1","name:cnn international"]}]}
        """

        // Mirror of SessionRepository.parseM3U() — see class doc.
        private fun parseM3UMirror(content: String): List<Channel> {
            val channels  = mutableListOf<Channel>()
            var id        = 0
            var name      = ""
            var logo      = ""
            var group     = ""
            var tvgId     = ""
            var hasExtInf = false

            for (line in content.lines()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("#EXTINF:")) {
                    hasExtInf = true
                    name  = trimmed.substringAfterLast(",").trim()
                    logo  = M3U_LOGO_REGEX.find(trimmed)?.groupValues?.get(1) ?: ""
                    group = M3U_GROUP_REGEX.find(trimmed)?.groupValues?.get(1) ?: ""
                    tvgId = M3U_TVGID_REGEX.find(trimmed)?.groupValues?.get(1) ?: ""
                } else if (hasExtInf && (trimmed.startsWith("http") || trimmed.startsWith("rtmp"))) {
                    val isSeparator = name.startsWith("#") || group.startsWith("#") ||
                        name.isBlank() || name.all { it == '-' || it == '=' || it == '+' || it == '*' || it == '_' }
                    if (!isSeparator) {
                        val g    = group.lowercase()
                        val type = when {
                            g.contains("movie") || g.contains("film") || g.contains("vod") -> ChannelType.MOVIE
                            g.contains("serie") || g.contains("show")                       -> ChannelType.SERIES
                            else                                                             -> ChannelType.LIVE
                        }
                        channels.add(Channel(
                            id    = "ch_${id++}",
                            name  = name.ifEmpty { "Channel $id" },
                            url   = trimmed,
                            logo  = logo.ifEmpty { null },
                            group = group.ifEmpty { null },
                            tvgId = tvgId.ifEmpty { null },
                            type  = type
                        ))
                    }
                    hasExtInf = false
                    name = ""; logo = ""; group = ""; tvgId = ""
                }
            }
            return channels
        }
    }

    private val gson = Gson()

    private fun byName(channels: List<Channel>, name: String) =
        channels.first { it.name == name }

    @Test
    fun `real backend response hides exactly the expected channels, nothing else`() {
        val channels = parseM3UMirror(M3U_FIXTURE)
        assertTrue("fixture sanity check", channels.size == 11)

        val response = gson.fromJson(REAL_RESPONSE_BOTH_ACTIVE, ChannelFilterResponse::class.java)
        val filter = ChannelVisibilityFilter.from(response)
        val visible = filter.apply(channels)

        // France : masquées via tvg-id (France 3) et fallback nom (BFM TV) — TF1/France2/Canal+ (ACTIVE/SHOW/REVIEW) visibles
        assertFalse(filter.isVisible(byName(channels, "France 3")))
        assertFalse(filter.isVisible(byName(channels, "BFM TV")))
        assertTrue(filter.isVisible(byName(channels, "TF1 HD")))
        assertTrue(filter.isVisible(byName(channels, "France 2")))
        assertTrue(filter.isVisible(byName(channels, "Canal+")))

        // Sports : masquées via tvg-id (beIN Sport 2) et fallback nom (RMC Sport 1) — ESPN/beIN1 (ACTIVE/REVIEW) visibles
        assertFalse(filter.isVisible(byName(channels, "beIN Sport 2")))
        assertFalse(filter.isVisible(byName(channels, "RMC Sport 1")))
        assertTrue(filter.isVisible(byName(channels, "ESPN HD")))
        assertTrue(filter.isVisible(byName(channels, "beIN Sport 1")))

        // Le JSON Sports contenait volontairement une entrée "CNN International" (nom identique
        // à une vraie chaîne USA) pour vérifier qu'une liste "Sports" ne peut jamais masquer une
        // chaîne d'une autre catégorie — partitionnement strict par catégorie (spec §9).
        assertTrue("pas de fuite cross-catégorie", filter.isVisible(byName(channels, "CNN International")))
        assertTrue(filter.isVisible(byName(channels, "FOX News")))

        // "id:999" et "id:9001" (channel_id fournis dans les JSON) ne doivent JAMAIS matcher une
        // chaîne M3U : Channel.id y est un compteur séquentiel ("ch_0", "ch_1"...) régénéré à
        // chaque parsing, jamais utilisé comme clé candidate (voir ChannelVisibilityFilter).
        assertTrue(channels.all { !it.id.startsWith("xt_") })

        assertEqualsCount(7, visible.size, channels.size)
    }

    @Test
    fun `deactivating the France list restores its channels while Sports stays filtered`() {
        val channels = parseM3UMirror(M3U_FIXTURE)
        val response = gson.fromJson(REAL_RESPONSE_FRANCE_DEACTIVATED, ChannelFilterResponse::class.java)
        val filter = ChannelVisibilityFilter.from(response)

        // France désactivée côté backend => absente de la réponse => tous les channels France reviennent
        assertTrue(filter.isVisible(byName(channels, "France 3")))
        assertTrue(filter.isVisible(byName(channels, "BFM TV")))
        assertTrue(filter.isVisible(byName(channels, "TF1 HD")))

        // Sports reste indépendamment filtrée (activation indépendante par liste, spec §14)
        assertFalse(filter.isVisible(byName(channels, "beIN Sport 2")))
        assertFalse(filter.isVisible(byName(channels, "RMC Sport 1")))
    }

    // ChannelFilterRepository requires an android.content.Context (for cacheFile/Log), which this
    // plain JVM unit test cannot construct — so the "backend down → cache utilisé" guarantee can't
    // be exercised end-to-end here. What IS verified: the exact Gson contract the repository uses
    // to write/read channel_filters_cache.json round-trips the real captured response losslessly —
    // i.e. a cache written from a real backend response is guaranteed to be read back identically.
    // The "never touch the cache / never overwrite visibilityFilter on failure" guarantee itself is
    // structural (ChannelFilterRepository.kt:53-65 — every path in refreshFromNetwork() returns null
    // on any Throwable *before* any cache write; LiveTvViewModel.kt's launchFilterConfigRefresh() does
    // `filterRepo.refreshFromNetwork() ?: return@launch`, so a null result never reassigns
    // `visibilityFilter`, leaving the cache-derived filter set at the top of load() in effect).
    @Test
    fun `cache file round-trips the real captured response losslessly (Gson contract used by ChannelFilterRepository)`() {
        val original = gson.fromJson(REAL_RESPONSE_BOTH_ACTIVE, ChannelFilterResponse::class.java)
        val writtenToDisk = gson.toJson(original)          // == what refreshFromNetwork() would cacheFile.writeText()
        val readBack = gson.fromJson(writtenToDisk, ChannelFilterResponse::class.java) // == loadCachedConfigOrNull()

        assertTrue(readBack.enabled == original.enabled)
        assertTrue(readBack.globalVersion == original.globalVersion)
        assertTrue(readBack.lists.size == original.lists.size)
        assertTrue(readBack.lists.map { it.hiddenChannels }.toString() == original.lists.map { it.hiddenChannels }.toString())

        // Et le filtre reconstruit à partir du cache relu produit exactement le même résultat
        // qu'à partir de la réponse réseau d'origine.
        val channels = parseM3UMirror(M3U_FIXTURE)
        val visibleFromOriginal = ChannelVisibilityFilter.from(original).apply(channels)
        val visibleFromCache    = ChannelVisibilityFilter.from(readBack).apply(channels)
        assertTrue(visibleFromOriginal.map { it.name } == visibleFromCache.map { it.name })
    }

    private fun assertEqualsCount(expectedVisible: Int, actualVisible: Int, total: Int) {
        org.junit.Assert.assertEquals(
            "attendu $expectedVisible chaînes visibles sur $total (4 masquées: France3, BFM TV, beIN Sport 2, RMC Sport 1)",
            expectedVisible, actualVisible
        )
    }
}
