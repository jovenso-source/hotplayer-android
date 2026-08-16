package com.hotplayer.utils

import com.hotplayer.data.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelQualitySortTest {

    private fun ch(name: String, group: String? = "France") =
        Channel(id = "ch_$name", name = name, url = "http://p/$name.ts", logo = null, group = group)

    @Test
    fun `detects SD by token`() {
        listOf("Channel A SD", "Channel A 480", "Channel A 480p", "Channel A 576", "Channel A 576P")
            .forEach { assertEquals(it, ChannelQualitySort.Quality.SD, ChannelQualitySort.detect(ch(it))) }
    }

    @Test
    fun `detects HD by token`() {
        listOf("Channel A HD", "Channel A 720", "Channel A 720p")
            .forEach { assertEquals(it, ChannelQualitySort.Quality.HD, ChannelQualitySort.detect(ch(it))) }
    }

    @Test
    fun `detects FHD by token, never as HD`() {
        listOf("Channel A FHD", "Channel A Full HD", "Channel A FULLHD", "Channel A 1080", "Channel A 1080p")
            .forEach { assertEquals(it, ChannelQualitySort.Quality.FHD, ChannelQualitySort.detect(ch(it))) }
    }

    @Test
    fun `detects 4K-UHD by token`() {
        listOf("Channel A 4K", "Channel A UHD", "Channel A 2160", "Channel A 2160p")
            .forEach { assertEquals(it, ChannelQualitySort.Quality.UHD, ChannelQualitySort.detect(ch(it))) }
    }

    @Test
    fun `unknown when no quality token present`() {
        assertEquals(ChannelQualitySort.Quality.UNKNOWN, ChannelQualitySort.detect(ch("Channel A")))
    }

    @Test
    fun `word-token matching avoids false positives inside unrelated words`() {
        // "CHAD" / "GOOD" / "STADIUM" must not be misdetected via naive substring matching.
        assertEquals(ChannelQualitySort.Quality.UNKNOWN, ChannelQualitySort.detect(ch("Chad TV")))
        assertEquals(ChannelQualitySort.Quality.UNKNOWN, ChannelQualitySort.detect(ch("Good Channel")))
        assertEquals(ChannelQualitySort.Quality.UNKNOWN, ChannelQualitySort.detect(ch("Stadium Sports")))
    }

    @Test
    fun `sorts SD, HD, FHD, UHD, UNKNOWN in that order`() {
        val channels = listOf(
            ch("Chan UHD"), ch("Chan Unknown"), ch("Chan FHD"), ch("Chan SD"), ch("Chan HD")
        )
        val sorted = ChannelQualitySort.sortedByQuality(channels).map { it.name }
        assertEquals(listOf("Chan SD", "Chan HD", "Chan FHD", "Chan UHD", "Chan Unknown"), sorted)
    }

    @Test
    fun `sort is stable - equal-quality channels keep original relative order`() {
        val channels = listOf(
            ch("B SD"), ch("A SD"), ch("Z HD"), ch("Y HD"), ch("C SD")
        )
        val sorted = ChannelQualitySort.sortedByQuality(channels).map { it.name }
        // All SD entries first, in original order (B, A, C) — not alphabetically re-sorted.
        assertEquals(listOf("B SD", "A SD", "C SD", "Z HD", "Y HD"), sorted)
    }

    @Test
    fun `sort never changes the channel count`() {
        val channels = listOf(ch("A SD"), ch("B HD"), ch("C FHD"), ch("D 4K"), ch("E Unknown"))
        assertEquals(channels.size, ChannelQualitySort.sortedByQuality(channels).size)
    }

    @Test
    fun `sort never drops or duplicates a channel`() {
        val channels = listOf(ch("A SD"), ch("B HD"), ch("C FHD"), ch("D 4K"), ch("E Unknown"))
        val sorted = ChannelQualitySort.sortedByQuality(channels)
        assertEquals(channels.toSet(), sorted.toSet())
    }

    @Test
    fun `100 channels in, 100 channels out, only reordered`() {
        val channels = (0 until 100).map {
            val q = listOf("SD", "HD", "FHD", "4K", "")[it % 5]
            ch("Chan $it $q".trim())
        }
        val sorted = ChannelQualitySort.sortedByQuality(channels)
        assertEquals(100, sorted.size)
        assertEquals(channels.toSet(), sorted.toSet())
        // Non-decreasing quality order.
        for (i in 0 until sorted.size - 1) {
            assert(ChannelQualitySort.detect(sorted[i]).order <= ChannelQualitySort.detect(sorted[i + 1]).order)
        }
    }
}
