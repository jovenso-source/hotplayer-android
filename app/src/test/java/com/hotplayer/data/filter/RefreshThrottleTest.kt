package com.hotplayer.data.filter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshThrottleTest {

    // Spec: app/session launch (never checked yet in this process) → always check regardless
    // of the configured interval.
    @Test
    fun `never checked before is always due, regardless of interval`() {
        assertTrue(RefreshThrottle.isDue(now = 1_000_000L, lastCheckedAtMs = 0L, minIntervalMs = 12 * 60_000L))
        assertTrue(RefreshThrottle.isDue(now = 1_000_000L, lastCheckedAtMs = 0L, minIntervalMs = 60 * 60_000L))
    }

    // Spec: Live TV re-entry within 10-15min of the last check → do nothing, no network call.
    @Test
    fun `checked recently is not due`() {
        val minInterval = 12 * 60_000L
        val lastChecked = 1_000_000L
        val now = lastChecked + minInterval - 1
        assertFalse(RefreshThrottle.isDue(now, lastChecked, minInterval))
    }

    @Test
    fun `checked past the interval is due again`() {
        val minInterval = 12 * 60_000L
        val lastChecked = 1_000_000L
        val now = lastChecked + minInterval + 1
        assertTrue(RefreshThrottle.isDue(now, lastChecked, minInterval))
    }

    @Test
    fun `exactly at the interval boundary is due`() {
        val minInterval = 12 * 60_000L
        val lastChecked = 1_000_000L
        assertTrue(RefreshThrottle.isDue(lastChecked + minInterval, lastChecked, minInterval))
    }
}
