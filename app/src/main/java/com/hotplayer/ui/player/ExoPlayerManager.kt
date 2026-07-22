package com.hotplayer.ui.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.hotplayer.utils.ChannelUtils
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class ExoPlayerManager(context: Context) {

    companion object {
        private const val TAG = "ExoPlayerManager"

        // Media3 groups PlaybackException.errorCode by range: 2000-2999 = IO/network errors
        // (transient, worth retrying), 3000+ = parsing/decoder/DRM errors (permanent — retrying
        // a malformed stream 3 times just wastes 4.5s before showing the same fatal error).
        private fun isRetryableNetworkError(code: Int) = code in 2000..2999
    }

    var onBuffering : ((Boolean) -> Unit)?       = null
    var onReady     : (() -> Unit)?              = null
    var onError     : ((String) -> Unit)?        = null
    var onVideoSize : ((Int, Int) -> Unit)?      = null

    private val appContext = context.applicationContext
    private var exo        : ExoPlayer? = null
    private var retryCount = 0
    private var currentUrl = ""

    private val handler = Handler(Looper.getMainLooper())

    private val dsFactory = OkHttpDataSource.Factory(
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    ).setDefaultRequestProperties(mapOf("User-Agent" to "HotPlayer/1.0 Android"))

    // DefaultMediaSourceFactory auto-detects: HLS (.m3u8), MPEG-TS (.ts), DASH, etc.
    private val sourceFactory = DefaultMediaSourceFactory(dsFactory)

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            // ExoPlayer calls listeners on the main thread — no dispatch needed
            onBuffering?.invoke(state == Player.STATE_BUFFERING)
            if (state == Player.STATE_READY) {
                retryCount = 0
                onReady?.invoke()
            }
        }
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0)
                onVideoSize?.invoke(videoSize.width, videoSize.height)
        }
        override fun onPlayerError(error: PlaybackException) {
            // Capture the URL this error actually belongs to. A straggling ExoPlayer
            // callback can in rare cases be delivered just after play() has already
            // moved on to a different channel — everything below must act on the URL
            // that failed, not on whatever `currentUrl` happens to be *now*.
            val failedUrl = currentUrl
            Log.w(TAG, "onPlayerError code=${error.errorCode} (${error.errorCodeName}) url=${ChannelUtils.redactUrl(failedUrl)}")
            val retryable = isRetryableNetworkError(error.errorCode)
            if (retryable && retryCount < 3 && failedUrl.isNotEmpty()) {
                retryCount++
                onBuffering?.invoke(true)  // keep spinner visible — user sees "loading" not blank
                handler.postDelayed({
                    if (currentUrl != failedUrl) return@postDelayed  // user already switched channel — stale retry, ignore
                    try {
                        val player = exo ?: return@postDelayed
                        player.setMediaItem(MediaItem.fromUri(failedUrl))
                        player.prepare()
                        player.playWhenReady = true
                    } catch (e: Exception) {
                        Log.e(TAG, "retry failed url=${ChannelUtils.redactUrl(failedUrl)}: ${e.message}", e)
                        if (currentUrl == failedUrl) {
                            onBuffering?.invoke(false)
                            onError?.invoke("Erreur de lecture. Vérifiez votre connexion.")
                        }
                    }
                }, 1_500L)
            } else if (currentUrl == failedUrl) {
                onBuffering?.invoke(false)
                val msg = if (retryable) "Erreur de lecture (${error.errorCode}). Vérifiez votre connexion."
                          else "Impossible de lire cette chaîne (${error.errorCode})."
                onError?.invoke(msg)
            }
        }
    }

    fun init(playerView: PlayerView) {
        exo?.removeListener(listener)
        exo?.release()
        exo = ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(sourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus= */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { player ->
                playerView.player = player
                player.addListener(listener)
            }
    }

    val currentPlayingUrl: String get() = currentUrl

    // Never lets an exception escape to the caller: a malformed URL or an unexpected
    // ExoPlayer/MediaCodec state must surface as onError, not crash the host Activity.
    fun play(url: String) {
        if (url.isBlank()) {
            Log.w(TAG, "play() called with blank URL")
            onError?.invoke("Chaîne invalide.")
            return
        }
        val player = exo ?: return
        try {
            if (url == currentUrl && player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED) return
            currentUrl = url
            retryCount = 0
            handler.removeCallbacksAndMessages(null)
            player.stop()
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            Log.e(TAG, "play() failed url=${ChannelUtils.redactUrl(url)}: ${e.message}", e)
            onError?.invoke("Erreur lors du démarrage de la lecture.")
        }
    }

    fun pause()   { exo?.pause() }
    fun resume()  { exo?.play() }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        exo?.removeListener(listener)
        exo?.release()
        exo = null
    }

    val isPlaying get() = exo?.isPlaying ?: false
}
