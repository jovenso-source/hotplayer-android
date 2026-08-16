package com.hotplayer.utils

// Pure decision extracted out of SessionRepository.downloadM3uWithProgress() so the throttle
// math is unit-testable: reports at ~2% steps (or the terminal 100%) instead of flooding the UI
// with a callback per 16KB chunk on a fast connection.
object DownloadProgressThrottle {
    fun shouldReport(fraction: Float, lastReported: Float, stepFraction: Float = 0.02f): Boolean =
        fraction - lastReported >= stepFraction || fraction >= 1f
}
