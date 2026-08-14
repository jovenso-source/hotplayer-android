package com.hotplayer.utils

import com.hotplayer.data.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelUtilsSelectionTest {

    private fun ch(idx: Int, name: String = "Chan $idx") =
        Channel(id = "ch_$idx", name = name, url = "http://p/$idx.ts", logo = null, group = "France")

    @Test
    fun `previously selected channel still present keeps its new index`() {
        val list = listOf(ch(1), ch(2), ch(3))
        assertEquals(2, ChannelUtils.resolveSelectionIndex(list, "http://p/3.ts"))
    }

    // Spec: chaîne actuellement sélectionnée devient masquée → position cohérente, pas d'index
    // périmé pointant sur la mauvaise chaîne dans la liste raccourcie.
    @Test
    fun `selected channel removed clamps to index 0 when list is non-empty`() {
        val list = listOf(ch(1), ch(2))
        assertEquals(0, ChannelUtils.resolveSelectionIndex(list, "http://p/hidden.ts"))
    }

    // Spec: catégorie devient vide → disparaît proprement, aucun index invalide.
    @Test
    fun `empty list clamps to -1`() {
        assertEquals(-1, ChannelUtils.resolveSelectionIndex(emptyList(), "http://p/1.ts"))
        assertEquals(-1, ChannelUtils.resolveSelectionIndex(emptyList(), null))
    }

    @Test
    fun `null prevUrl defaults to first item when list is non-empty`() {
        val list = listOf(ch(1), ch(2))
        assertEquals(0, ChannelUtils.resolveSelectionIndex(list, null))
    }
}
