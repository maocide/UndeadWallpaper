package org.maocide.undeadwallpaper

import android.content.SharedPreferences
import android.net.Uri
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT
import androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer

class UndeadWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine {
        return MyWallpaperEngine()
    }

    private inner class MyWallpaperEngine : Engine() {

        private var mediaPlayer: ExoPlayer? = null
        private var surfaceHolder: SurfaceHolder? = null
        private var playheadTime: Long = 0L
        private val TAG: String = javaClass.simpleName
        private lateinit var sharedPrefs: SharedPreferences


        @OptIn(UnstableApi::class)
        private fun initializePlayer() {
            if (mediaPlayer != null) {
                releasePlayer()
            }

            val holder = surfaceHolder
            if (holder == null) {
                Log.w(TAG, "Cannot initialize player: surface is not ready.")
                return
            }

            Log.i(TAG, "Initializing ExoPlayer...")
            sharedPrefs = applicationContext.getSharedPreferences("DEFAULT", MODE_PRIVATE)

            // --- Load Settings from SharedPreferences ---
            val isAudioEnabled = sharedPrefs.getBoolean(getString(R.string.video_audio_enabled), false)
            val scalingModeId = sharedPrefs.getInt(getString(R.string.video_scaling_mode), R.id.radio_scale_crop)
            val zoomLevel = sharedPrefs.getFloat(getString(R.string.video_zoom_level), 1.0f)
            Log.d(TAG, "Loaded Settings: Audio=$isAudioEnabled, ScalingModeID=$scalingModeId, Zoom=$zoomLevel")
            // Note: Zoom is logged but not implemented visually, as it's complex with SurfaceView.

            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    15 * 1000,
                    30 * 1000,
                    500,
                    100
                )
                .setTargetBufferBytes(DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES / 4)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            val player = ExoPlayer.Builder(baseContext)
                .setLoadControl(loadControl)
                .build().apply {
                    val mediaItem = getMediaUri()?.let { MediaItem.fromUri(it) }
                    if (mediaItem == null) {
                        Log.e(TAG, "Media URI is null, cannot play video.")
                        return
                    }
                    setMediaItem(mediaItem)
                    repeatMode = Player.REPEAT_MODE_ONE

                    // --- Apply Loaded Settings ---
                    videoScalingMode = when (scalingModeId) {
                        R.id.radio_scale_fit -> VIDEO_SCALING_MODE_SCALE_TO_FIT
                        else -> VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                    }
                    volume = if (isAudioEnabled) 1f else 0f

                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_ENDED) {
                                this@apply.seekTo(0)
                                this@apply.play()
                            }
                        }
                    })

                    seekTo(playheadTime)
                    setVideoSurface(holder.surface)
                    prepare()
                    play()
                }

            mediaPlayer = player
        }

        private fun releasePlayer() {
            mediaPlayer?.let { player ->
                Log.i(TAG, "Releasing ExoPlayer...")
                playheadTime = player.currentPosition
                player.clearMediaItems()
                player.release()
            }
            mediaPlayer = null
        }

        private fun getMediaUri(): Uri? {
            val uriString = getSharedPreferences("DEFAULT", MODE_PRIVATE)
                .getString(getString(R.string.video_uri), null)

            return if (uriString.isNullOrEmpty()) {
                Log.w(TAG, "Video URI is null or empty.")
                null
            } else {
                Log.i(TAG, "Found URI: $uriString")
                uriString.toUri()
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            Log.i(TAG, "onSurfaceCreated")
            this.surfaceHolder = holder
            initializePlayer()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            Log.i(TAG, "onSurfaceChanged: New dimensions ${width}x${height}")
            this.surfaceHolder = holder
            initializePlayer()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            Log.i(TAG, "onSurfaceDestroyed")
            releasePlayer()
            this.surfaceHolder = null
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            Log.i(TAG, "onVisibilityChanged: visible = $visible")
            if (visible) {
                if (mediaPlayer == null) {
                    initializePlayer()
                } else {
                    mediaPlayer?.play()
                }
            } else {
                mediaPlayer?.pause()
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            Log.i(TAG, "Engine onDestroy")
            releasePlayer()
        }
    }
}