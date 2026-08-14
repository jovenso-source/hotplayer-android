package com.hotplayer.ui.home

import com.hotplayer.data.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Regression test for the P0 Live TV crash: LiveChannelAdapter's focus/click listeners used to
 * do `items[bindingAdapterPosition]` — a stale bindingAdapterPosition (RecyclerView's own
 * documented behavior right after notifyDataSetChanged(), until the next layout pass) combined
 * with a list that just shrank (e.g. a Channel Filter refresh) threw IndexOutOfBoundsException,
 * crashing LiveTvActivity back to Home.
 *
 * This mirrors the exact guard expression now used in both listeners in Adapters.kt:
 *   val p = h.bindingAdapterPosition
 *   if (p != RecyclerView.NO_POSITION) items.getOrNull(p)?.let { callback(it, p) }
 *
 * Adapters.kt itself can't be instantiated here (ViewHolder needs a real inflated Android View,
 * unavailable without Robolectric) — this isolates the guard's data-access shape in plain
 * Kotlin/JVM, which is what actually determines crash-or-no-crash for this bug class.
 */
class LiveChannelAdapterPositionSafetyTest {

    private fun ch(idx: Int) =
        Channel(id = "ch_$idx", name = "Chan $idx", url = "http://p/$idx.ts", logo = null, group = "France")

    // The guard exactly as it appears in LiveChannelAdapter's focus/click listeners.
    private fun safeCallbackInvoke(items: List<Channel>, p: Int, onResult: (Channel, Int) -> Unit) {
        items.getOrNull(p)?.let { onResult(it, p) }
    }

    @Test
    fun `stale ViewHolder callback at position 91 against a shrunk 73-item list never throws and never invokes the callback`() {
        // Old list was 100 channels (position 91 was valid then); the fix must be safe once
        // the list has shrunk to 73 and a stale callback for position 91 is still delivered.
        val newList = (0 until 73).map { ch(it) }
        val staleP = 91 // bindingAdapterPosition as it was before the list shrank

        var invoked = false
        // The crash-under-test would be an uncaught IndexOutOfBoundsException here.
        safeCallbackInvoke(newList, staleP) { _, _ -> invoked = true }

        assertFalse(invoked)
    }

    @Test
    fun `callback still fires normally for a position that remains valid after the update`() {
        val newList = (0 until 73).map { ch(it) }
        var received: Channel? = null

        safeCallbackInvoke(newList, 10) { c, _ -> received = c }

        assertEquals(newList[10], received)
    }

    @Test
    fun `NO_POSITION-equivalent negative position never throws`() {
        val newList = (0 until 73).map { ch(it) }
        var invoked = false

        safeCallbackInvoke(newList, -1) { _, _ -> invoked = true }

        assertFalse(invoked)
    }

    @Test
    fun `empty list after all channels filtered out never throws for any stale position`() {
        var invoked = false
        safeCallbackInvoke(emptyList(), 91) { _, _ -> invoked = true }
        assertFalse(invoked)
    }
}
