package org.maocide.undeadwallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.maocide.undeadwallpaper.model.PlaybackMode
import org.maocide.undeadwallpaper.model.ScalingMode

class UndeadWallpaperService : WallpaperService() {

    // FILTERING: Our secret passphrase
    companion object {
        const val ACTION_VIDEO_URI_CHANGED = "org.maocide.undeadwallpaper.VIDEO_URI_CHANGED"
        const val ACTION_PLAYBACK_MODE_CHANGED = "org.maocide.undeadwallpaper.ACTION_PLAYBACK_MODE_CHANGED"
        const val ACTION_TRIM_TIMES_CHANGED = "org.maocide.undeadwallpaper.TRIM_TIMES_CHANGED"
    }

    override fun onCreateEngine(): Engine {
        return MyWallpaperEngine()
    }




    private inner class MyWallpaperEngine : Engine() {

        private var isAudioEnabled: Boolean = false
        private lateinit var currentScalingMode: ScalingMode
        private var mediaPlayer: ExoPlayer? = null
        private var surfaceHolder: SurfaceHolder? = null
        private var playheadTime: Long = 0L
        private val TAG: String = javaClass.simpleName
        private lateinit var sharedPrefs: SharedPreferences
        private var isScalingModeSet = false

        private var currentPlaybackMode = PlaybackMode.LOOP
        private var hasPlaybackCompleted = false

        private var renderer: GLVideoRenderer? = null // <--- NEW RENDERER





        // The receiver that listens for our signal
        private val videoChangeReceiver = object : BroadcastReceiver() {
            @OptIn(UnstableApi::class)
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_VIDEO_URI_CHANGED -> {
                        Log.i(TAG, "Broadcast received: Video uri changed, full re-initialization requested.")
                        playheadTime = 0L
                        initializePlayer()
                    }

                    ACTION_PLAYBACK_MODE_CHANGED -> {
                        Log.i(TAG, "Broadcast received: Playback mode change, full re-initialization requested.")
                        playheadTime = 0L
                        initializePlayer()
                    }
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

            val loadControl = DefaultLoadControl.Builder()
                .build()

            val preferenceManager = PreferencesManager(baseContext)
            isAudioEnabled = preferenceManager.isAudioEnabled()
            currentPlaybackMode = preferenceManager.getPlaybackMode()
            currentScalingMode = preferenceManager.getScalingMode()

            hasPlaybackCompleted = false

            val player = ExoPlayer.Builder(baseContext)
                .setLoadControl(loadControl)
                .setSeekParameters(SeekParameters.CLOSEST_SYNC)
                .build().apply {
                    val mediaUri = getMediaUri()
                    if (mediaUri == null) {
                        Log.e(TAG, "Media URI is null, cannot play video.")
                        return
                    }

                    // Build a MediaItem WITHOUT the clipping config.
                    val mediaItem = MediaItem.fromUri(mediaUri)

                    // Create the base MediaSource from that item.
                    val mediaSource = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(baseContext))
                        .createMediaSource(mediaItem)

                    // Set the clipped source and configure proper looping.
                    setMediaSource(mediaSource)
                    volume = if (isAudioEnabled) 1f else 0f

                    if(currentPlaybackMode == PlaybackMode.LOOP)
                        repeatMode = Player.REPEAT_MODE_ONE // Use built-in looping
                    else
                        repeatMode = Player.REPEAT_MODE_OFF // Use one shot

                    Log.d(TAG, "repeatMode: $repeatMode")

                    renderer?.setScalingMode(currentScalingMode)


                    addListener(object : Player.Listener {
                        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                            super.onVideoSizeChanged(videoSize)

                            // Send video size to Renderer for Matrix Calculation
                            renderer?.setVideoSize(videoSize.width, videoSize.height)
                            renderer?.setScalingMode(currentScalingMode)

                            // Sanity check for 0x0 size
                            if (videoSize.width == 0 || videoSize.height == 0) {
                                Log.w(TAG, "Ignoring invalid 0x0 video size change.")
                                return
                            }

                            // Only run this logic if we haven't already set the scaling mode for this video
                            /* should be only needed when using exoplayer surface
                            if (!isScalingModeSet) {
                                Log.i(TAG, "Valid video size detected: ${videoSize.width}x${videoSize.height}. Setting scaling mode ONCE.")

                                val videoAspectRatio = videoSize.width.toFloat() / videoSize.height.toFloat()
                                val isHorizontalVideo = videoAspectRatio > 1.0

                                this@apply.videoScalingMode = if (isHorizontalVideo) {
                                    VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                                } else {
                                    VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                                } // The same gets applied because the other scaling modes resulted problematic in a surface
                                // need to clean this app when implementing user defined scaling, but might be not completely possible in a surface

                                isScalingModeSet = true // SET THE FLAG SO THIS DOESN'T RUN AGAIN
                            }

                             */
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            super.onPlaybackStateChanged(playbackState)
                            // detecting one shot playback and pausing
                            if (currentPlaybackMode == PlaybackMode.ONE_SHOT) {
                                when (playbackState) {
                                    Player.STATE_ENDED -> {
                                        Log.i(TAG, "Playback ended!")
                                        hasPlaybackCompleted = true
                                        pause()
                                        /*
                                        Log.d(TAG, "safeDuration: $safeDuration")
                                        */
                                    }
                                    Player.STATE_READY -> {
                                        if (hasPlaybackCompleted) pause()
                                    }
                                }
                            }
                        }
                    })

                    // WAIT for the GL Surface, then attach
                    // We need a coroutine here because waitForVideoSurface is suspend
                    val currentScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main)
                    currentScope.launch {
                        val glSurface = renderer?.waitForVideoSurface()
                        if (glSurface != null) {
                            seekTo(playheadTime)
                            setVideoSurface(glSurface) // <--- glSurface is actually set and becomes effective
                            prepare()
                            play()
                        }
                    }
                    /*
                    seekTo(playheadTime)
                    setVideoSurface(holder.surface)
                    prepare()
                    play()

                     */
                }

            mediaPlayer = player
        }

        /**
         * Releases the ExoPlayer instance.
         *
         * This function safely stops, clears, and releases the `mediaPlayer`. It stores the current
         * playback position (`playheadTime`) so that playback can be resumed from the same spot later.
         * It also resets the `isScalingModeSet` flag to ensure video scaling is recalculated when a
         * new player is initialized. The `mediaPlayer` instance is set to null after release.
         */
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

        /**
         * Helper method to keep it DRY (Don't Repeat Yourself)
         * Releases the [GLVideoRenderer] and its associated resources.
         * This should be called when the underlying surface is destroyed.
         * It ensures that OpenGL contexts and other graphics-related
         * resources are properly cleaned up to prevent memory leaks.
         *
         */
        private fun releaseRenderer() {
            if (renderer != null) {
                Log.i(TAG, "Releasing GlRenderer...")
                renderer?.release()
                renderer = null
            }
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

            // 1. Start the GL Renderer
            renderer = GLVideoRenderer(applicationContext)
            renderer?.onSurfaceCreated(holder)

            initializePlayer()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            Log.i(TAG, "onSurfaceChanged: New dimensions ${width}x${height}")
            this.surfaceHolder = holder

            // 2. Tell Renderer the screen size
            renderer?.onSurfaceChanged(width, height)

            // initializePlayer() // MIGHT BE OVERKILL! TRY WITHOUT
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            Log.i(TAG, "onSurfaceDestroyed")
            releasePlayer()
            releaseRenderer() // 3. Kill the GL Renderer
            this.surfaceHolder = null
        }

        override fun onDestroy() {
            super.onDestroy()
            Log.i(TAG, "Engine onDestroy")
            releasePlayer()
            releaseRenderer()
            unregisterReceiver(videoChangeReceiver)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            Log.i(TAG, "onVisibilityChanged: visible = $visible isPreview = $isPreview, playbackMode = $currentPlaybackMode")

            if (visible) {
                if (mediaPlayer == null) {
                    initializePlayer()
                } else {
                    if (currentPlaybackMode == PlaybackMode.ONE_SHOT && hasPlaybackCompleted && !isPreview()) {
                        Log.i(TAG, "Seeking back to 0 on change visibility for one shot")
                        mediaPlayer?.seekTo(0)
                        hasPlaybackCompleted = false
                    }
                    mediaPlayer?.playWhenReady = true
                    mediaPlayer?.play()
                }
            } else {
                mediaPlayer?.pause()
                mediaPlayer?.playWhenReady = false

            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            Log.i(TAG, "Engine onCreate")
            // Turn on filter to start listening
            val intentFilter = IntentFilter().apply {
                addAction(ACTION_VIDEO_URI_CHANGED)
                addAction(ACTION_PLAYBACK_MODE_CHANGED)
            }
            // Using `registerReceiver` with the `RECEIVER_NOT_EXPORTED` flag is the
            // modern, secure way for Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(videoChangeReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag") // Flag not needed for older APIs
                registerReceiver(videoChangeReceiver, intentFilter)
            }
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

            if (action == ACTION_PLAYBACK_MODE_CHANGED ||
                action == ACTION_VIDEO_URI_CHANGED ||
                action == "android.wallpaper.reapply") {
                Log.i(TAG, "Command received -> Re-initializing player.")
                // The most robust way to handle the change is a full reset.
                // This ensures all old data is cleared and the new URI is loaded.
                initializePlayer()
            }
            return null
        }
    }
}