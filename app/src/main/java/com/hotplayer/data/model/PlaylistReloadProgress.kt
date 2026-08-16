package com.hotplayer.data.model

enum class PlaylistReloadStep { DOWNLOADING, PARSING, FINALIZING }

/**
 * [fraction] is 0f..1f when real progress is known (currently: M3U download, tracked against
 * the response's Content-Length), or null when it genuinely isn't — the UI must render an
 * indeterminate bar for a null fraction rather than inventing a percentage.
 */
data class PlaylistReloadProgress(
    val step: PlaylistReloadStep,
    val fraction: Float? = null
)
