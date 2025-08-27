package org.maocide.undeadwallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.MediaCodec
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT
import androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.LoopingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource

class UndeadWallpaperService : WallpaperService() {

    // <<< ABBY'S ADDITION: Our secret passphrase >>>
    companion object {
        const val ACTION_VIDEO_URI_CHANGED = "org.maocide.undeadwallpaper.VIDEO_URI_CHANGED"
    }

    override fun onCreateEngine(): Engine {
        return MyWallpaperEngine()
    }




    private inner class MyWallpaperEngine : Engine() {

        private var mediaPlayer: ExoPlayer? = null
        private var surfaceHolder: SurfaceHolder? = null
        private var playheadTime: Long = 0L
        private val TAG: String = javaClass.simpleName
        private lateinit var sharedPrefs: SharedPreferences
        private var isScalingModeSet = false



        // <<< ABBY'S FIX: The radio receiver that listens for our signal >>>
        private val videoChangeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_VIDEO_URI_CHANGED) {
                    Log.i(TAG, "Broadcast received! Re-initializing player with new video.")
                    // Reset playhead position to the beginning of the clip whenever the
                    // video source or its parameters (like trimming) are changed.
                    playheadTime = 0L
                    initializePlayer()
                }
            }
        }


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
            // We'll read the scaling mode inside the listener now.

            val loadControl = DefaultLoadControl.Builder()
                // ... (your loadControl settings are fine) ...
                .build()

            val player = ExoPlayer.Builder(baseContext)
                .setLoadControl(loadControl)
                .setSeekParameters(SeekParameters.CLOSEST_SYNC)
                .build().apply {
                    val mediaUri = getMediaUri()
                    if (mediaUri == null) {
                        Log.e(TAG, "Media URI is null, cannot play video.")
                        return
                    }

                    // 1. Build a NORMAL MediaItem WITHOUT the clipping config.
                    val mediaItem = MediaItem.fromUri(mediaUri)

                    // 2. Create the base MediaSource from that item.
                    val mediaSource = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(baseContext))
                        .createMediaSource(mediaItem)

                    // 3. Load the clipping times from preferences.
                    val startMs = sharedPrefs.getLong(getString(R.string.video_start_ms), 0L)
                    val endMs = sharedPrefs.getLong(getString(R.string.video_end_ms), C.TIME_END_OF_SOURCE)

                    // 4. Use ClippingMediaSource to "cut" the video to the desired segment.
                    val startUs = startMs * 1000
                    val endUs = if (endMs == C.TIME_END_OF_SOURCE) C.TIME_END_OF_SOURCE else endMs * 1000
                    val clippedSource = ClippingMediaSource(mediaSource, startUs, endUs)

                    // 5. NOW, wrap the already-clipped source in the LoopingMediaSource.
                    val loopingMediaSource = LoopingMediaSource(clippedSource)

                    // 6. Set the final, composite source on the player.
                    setMediaSource(loopingMediaSource)
                    volume = if (isAudioEnabled) 1f else 0f

                    addListener(object : Player.Listener {
                        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                            super.onVideoSizeChanged(videoSize)

                            // Sanity check for 0x0 size
                            if (videoSize.width == 0 || videoSize.height == 0) {
                                Log.w(TAG, "Ignoring invalid 0x0 video size change.")
                                return
                            }

                            // Only run this logic if we haven't already set the scaling mode for this video
                            if (!isScalingModeSet) {
                                Log.i(TAG, "Valid video size detected: ${videoSize.width}x${videoSize.height}. Setting scaling mode ONCE.")

                                val videoAspectRatio = videoSize.width.toFloat() / videoSize.height.toFloat()
                                val isHorizontalVideo = videoAspectRatio > 1.0

                                this@apply.videoScalingMode = if (isHorizontalVideo) {
                                    VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                                } else {
                                    VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                                }

                                isScalingModeSet = true // SET THE FLAG SO THIS DOESN'T RUN AGAIN
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
            isScalingModeSet = false
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

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            Log.i(TAG, "Engine onCreate")
            // <<< ABBY'S FIX: Turn on the radio and start listening >>>
            val intentFilter = IntentFilter(ACTION_VIDEO_URI_CHANGED)
            // Using `registerReceiver` with the `RECEIVER_NOT_EXPORTED` flag is the
            // modern, secure way for Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(videoChangeReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag") // Flag not needed for older APIs
                registerReceiver(videoChangeReceiver, intentFilter)
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            Log.i(TAG, "Engine onDestroy")
            releasePlayer()
            unregisterReceiver(videoChangeReceiver)
        }

        @Deprecated("Deprecated in Java") // This is needed for older Android versions
        override fun onCommand(
            action: String?,
            x: Int,
            y: Int,
            z: Int,
            extras: Bundle?,
            resultRequested: Boolean
        ): Bundle? {

            super.onCommand(action, x, y, z, extras, resultRequested)
            Log.d(TAG, "onCommand received: $action")

            if (action == ACTION_VIDEO_URI_CHANGED || action == "android.wallpaper.reapply") {
                Log.i(TAG, "Video URI changed command received! Re-initializing player.")
                // The most robust way to handle the change is a full reset.
                // This ensures all old data is cleared and the new URI is loaded.
                initializePlayer()
            }
            return null
        }
    }
}