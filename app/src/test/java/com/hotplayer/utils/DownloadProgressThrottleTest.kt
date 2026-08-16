package com.hotplayer.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressThrottleTest {

    @Test
    fun `first meaningful jump of 2 percent or more is reported`() {
        assertTrue(DownloadProgressThrottle.shouldReport(fraction = 0.02f, lastReported = 0f))
        assertTrue(DownloadProgressThrottle.shouldReport(fraction = 0.10f, lastReported = 0f))
    }

    @Test
    fun `sub-threshold change is not reported`() {
        assertFalse(DownloadProgressThrottle.shouldReport(fraction = 0.19f, lastReported = 0.18f))
    }

    @Test
    fun `100 percent is always reported even if the last step was tiny`() {
        assertTrue(DownloadProgressThrottle.shouldReport(fraction = 1f, lastReported = 0.995f))
    }

    @Test
    fun `never called before (lastReported -1) always reports the first real fraction`() {
        assertTrue(DownloadProgressThrottle.shouldReport(fraction = 0.0f + 0.02f, lastReported = -1f))
    }
}
