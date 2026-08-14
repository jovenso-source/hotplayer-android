package com.hotplayer.data.filter

// Pure timing decision extracted out of ChannelFilterRepository so the throttle math is
// unit-testable without an android.content.Context. 0L for lastCheckedAtMs means "never
// checked in this process" — always due regardless of minIntervalMs, which is what makes
// the very first check per process behave as an unconditional launch-time check while every
// later call is throttled (see ChannelFilterRepository.refreshIfDue()).
object RefreshThrottle {
    fun isDue(now: Long, lastCheckedAtMs: Long, minIntervalMs: Long): Boolean =
        lastCheckedAtMs == 0L || now - lastCheckedAtMs >= minIntervalMs
}
