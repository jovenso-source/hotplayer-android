package com.hotplayer.utils

import com.hotplayer.data.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // Reproduces the exact P0 scenario reported: focus sits at position 91 of a 100-channel
    // list, a Channel Filter refresh shrinks it to 73 — the position that was focused (91) no
    // longer exists. Confirms resolveSelectionIndex (used to recompute _index after any refresh)
    // never returns an out-of-bounds value; the actual crash lived in LiveChannelAdapter's click/
    // focus listeners indexing `items[bindingAdapterPosition]` directly instead of going through
    // this kind of bounds-safe resolution (fixed in Adapters.kt — see report).
    @Test
    fun `100 to 73 channels with focus at 91 clamps safely, never throws`() {
        val oldList = (0 until 100).map { ch(it) }
        val newList = (0 until 73).map { ch(it) }
        val focusedUrl = oldList[91].url // no longer present in newList

        val resolved = ChannelUtils.resolveSelectionIndex(newList, focusedUrl)

        assertEquals(0, resolved)
        assertTrue("resolved index must be a valid position in the new (shorter) list",
            resolved in newList.indices)
    }
}
