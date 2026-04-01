package org.maocide.undeadwallpaper.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.exoplayer.upstream.DefaultAllocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maocide.undeadwallpaper.model.PlaybackMode
import org.maocide.undeadwallpaper.utils.FileLogger
import kotlin.random.Random

interface WallpaperPlayerListener {
    fun onPlayerError(error: PlaybackException)
    fun onVideoSizeChanged(width: Int, height: Int)
    fun onPlaybackStateChanged(playbackState: Int)
    fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int)
    fun onRenderedFirstFrame()
    fun onHardwareFailure(reason: String)
}

class WallpaperPlayer(
    private val context: Context,
    private val listener: WallpaperPlayerListener
) {
    private val TAG: String = javaClass.simpleName

    private var player: ExoPlayer? = null

    private var recoveryAttempts = 0

    // Expose just enough surface area for UndeadWallpaperService to use

    val duration: Long
        get() = player?.duration ?: C.TIME_UNSET

    val currentPosition: Long
        get() = player?.currentPosition ?: 0L

    val currentMediaItemIndex: Int
        get() = player?.currentMediaItemIndex ?: 0

    var playWhenReady: Boolean
        get() = player?.playWhenReady ?: false
        set(value) {
            player?.playWhenReady = value
        }

    var videoScalingMode: Int
        @OptIn(UnstableApi::class)
        get() = player?.videoScalingMode ?: C.VIDEO_SCALING_MODE_DEFAULT
        @OptIn(UnstableApi::class)
        set(value) {
            player?.videoScalingMode = value
        }

    @OptIn(UnstableApi::class)
    fun setMediaSources(mediaSources: List<MediaSource>) {
        player?.setMediaSources(mediaSources)
    }

    @OptIn(UnstableApi::class)
    fun setMediaSource(mediaSource: MediaSource) {
        player?.setMediaSource(mediaSource)
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        player?.seekTo(mediaItemIndex, positionMs)
    }

    fun seekToDefaultPosition() {
        player?.seekToDefaultPosition()
    }

    fun setVideoSurface(surface: Surface) {
        player?.setVideoSurface(surface)
    }

    fun prepare() {
        player?.prepare()
    }

    fun play() {
        player?.play()
    }

    fun pause() {
        player?.pause()
    }

    fun getPlayerInstance(): Player? {
        return player
    }

    @OptIn(UnstableApi::class)
    fun initialize(
        surface: Surface?,
        initialVolume: Float,
        speed: Float,
        playbackMode: PlaybackMode
    ) {
        if (player != null) {
            release()
        }

        FileLogger.i(TAG, "Initializing ExoPlayer...")

        // Define a 32MB Memory Cap
        val targetBufferBytes = 32 * 1024 * 1024

        // Configure the LoadControl
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
            .setBufferDurationsMs(
                15_000, // Min buffer 15
                30_000, // Max buffer 30
                2_500,  // Buffer to start playback
                5_000   // Buffer for rebuffer
            )
            .setTargetBufferBytes(targetBufferBytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        // Factory to give on creation to enable a fallback for non standard res, possible very hi res.
        val renderersFactory = DefaultRenderersFactory(context).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            setEnableDecoderFallback(true) // Crucial for non-standard resolutions
        }

        player = ExoPlayer.Builder(context, renderersFactory)
            .setLooper(Looper.getMainLooper())
            .setLoadControl(loadControl)
            .setSeekParameters(SeekParameters.NEXT_SYNC)
            .build()
            .apply {
                if (initialVolume <= 0.0f) {
                    trackSelectionParameters = trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .build()
                } else {
                    trackSelectionParameters = trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .build()
                }

                volume = initialVolume
                setPlaybackSpeed(speed)

                when (playbackMode) {
                    PlaybackMode.LOOP -> {
                        repeatMode = Player.REPEAT_MODE_ONE
                        shuffleModeEnabled = false
                    }
                    PlaybackMode.ONE_SHOT -> {
                        repeatMode = Player.REPEAT_MODE_OFF
                        shuffleModeEnabled = false
                    }
                    PlaybackMode.LOOP_ALL -> {
                        repeatMode = Player.REPEAT_MODE_ALL
                        shuffleModeEnabled = false
                    }
                    PlaybackMode.SHUFFLE -> {
                        repeatMode = Player.REPEAT_MODE_ALL
                        shuffleModeEnabled = true
                    }
                }

                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        FileLogger.e(TAG, "ExoPlayer Error: ${error.errorCodeName} - ${error.message}")

                        val isDecoderError = error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                                error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
                                error.errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED

                        if (isDecoderError) {
                            val msg = error.message ?: ""
                            val causeMsg = error.cause?.message ?: ""
                            if (msg.contains("NO_MEMORY") || causeMsg.contains("NO_MEMORY")) {
                                listener.onHardwareFailure("Memory limit exceeded.")
                                return
                            }

                            if (recoveryAttempts < 3) {
                                recoveryAttempts++
                                FileLogger.w(TAG, "Hardware Decoder lost (Attempt $recoveryAttempts/3). Auto-recovering...")

                                release()

                                CoroutineScope(Dispatchers.Main).launch {
                                    delay(2000)
                                    // Trigger error callback so service can re-initialize if visible
                                    listener.onPlayerError(error)
                                }
                            } else {
                                recoveryAttempts = 0
                                listener.onHardwareFailure("Persistent hardware failure (Loop detected).")
                            }
                        } else {
                            listener.onPlayerError(error)
                        }
                    }

                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        super.onVideoSizeChanged(videoSize)
                        if (videoSize.width == 0 || videoSize.height == 0) {
                            FileLogger.w(TAG, "Ignoring invalid 0x0 video size change.")
                            return
                        }
                        listener.onVideoSizeChanged(videoSize.width, videoSize.height)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        super.onPlaybackStateChanged(playbackState)
                        if (playbackState == Player.STATE_READY) {
                            recoveryAttempts = 0
                        }
                        listener.onPlaybackStateChanged(playbackState)
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        super.onMediaItemTransition(mediaItem, reason)
                        listener.onMediaItemTransition(mediaItem, reason)

                        val isWrapAround = reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT ||
                                (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                                        currentMediaItemIndex == currentTimeline.getFirstWindowIndex(shuffleModeEnabled))

                        if (isWrapAround && playbackMode == PlaybackMode.SHUFFLE) {
                            FileLogger.i(TAG, "Playlist repeated. Generating new shuffle order.")
                            if (mediaItemCount > 0) {
                                val newOrder = DefaultShuffleOrder(mediaItemCount, Random.nextLong())
                                this@apply.setShuffleOrder(newOrder)
                            }
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        super.onRenderedFirstFrame()
                        listener.onRenderedFirstFrame()
                    }
                })

                if (surface != null) {
                    setVideoSurface(surface)
                    prepare()
                }
            }
    }

    fun release() {
        player?.let { p ->
            FileLogger.i(TAG, "Releasing ExoPlayer...")
            p.clearMediaItems()
            p.release()
        }
        player = null
    }
}