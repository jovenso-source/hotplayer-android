package com.hotplayer.ui.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class ExoPlayerManager(context: Context) {

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
            if (retryCount < 3 && currentUrl.isNotEmpty()) {
                retryCount++
                onBuffering?.invoke(true)  // keep spinner visible — user sees "loading" not blank
                handler.postDelayed({
                    val player = exo ?: return@postDelayed
                    player.setMediaItem(MediaItem.fromUri(currentUrl))
                    player.prepare()
                    player.playWhenReady = true
                }, 1_500L)
            } else {
                onBuffering?.invoke(false)
                onError?.invoke("Erreur de lecture (${error.errorCode}). Vérifiez votre connexion.")
            }
        }
    }

    fun init(playerView: PlayerView) {
        exo?.removeListener(listener)
        exo?.release()
        exo = ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(sourceFactory)
            .build()
            .also { player ->
                playerView.player = player
                player.addListener(listener)
            }
    }

    val currentPlayingUrl: String get() = currentUrl

    fun play(url: String) {
        val player = exo ?: return
        if (url == currentUrl && player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED) return
        currentUrl = url
        retryCount = 0
        handler.removeCallbacksAndMessages(null)
        player.stop()
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
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
