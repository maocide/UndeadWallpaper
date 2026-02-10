package org.maocide.undeadwallpaper

import android.app.WallpaperColors
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultAllocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.maocide.undeadwallpaper.model.PlaybackMode
import org.maocide.undeadwallpaper.model.ScalingMode
import org.maocide.undeadwallpaper.model.StatusBarColor
import kotlin.math.log

class UndeadWallpaperService : WallpaperService() {

    // FILTERING: Our secret passphrase
    companion object {
        const val ACTION_VIDEO_URI_CHANGED = "org.maocide.undeadwallpaper.VIDEO_URI_CHANGED"
        const val ACTION_PLAYBACK_MODE_CHANGED = "org.maocide.undeadwallpaper.ACTION_PLAYBACK_MODE_CHANGED"
        // for testing trimming
        const val ACTION_TRIM_TIMES_CHANGED = "org.maocide.undeadwallpaper.TRIM_TIMES_CHANGED"
        const val ACTION_STATUS_BAR_COLOR_CHANGED = "org.maocide.undeadwallpaper.STATUS_BAR_COLOR_CHANGED"
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
        private var isScalingModeSet = false

        private var currentPlaybackMode = PlaybackMode.LOOP

        private var loadedVideoUriString = ""
        private var hasPlaybackCompleted = false

        private var renderer: GLVideoRenderer? = null
        private var recoveryAttempts = 0 // Counter for error recovery retry attempts


        private var playerSetupJob: kotlinx.coroutines.Job? = null

        // Stall Watchdog vars
        private var lastPosition: Long = 0
        private var stallCount: Int = 0
        private val watchdogHandler = Handler(Looper.getMainLooper())
        private val watchdogRunnable = object : Runnable {
            override fun run() {
                checkPlaybackStall()
                // Re-run continuously while visible
                watchdogHandler.postDelayed(this, 2000) // Check every 2 seconds
            }
        }

        /**
         * Checks if the player claims to be playing but isn't advancing.
         * Used by the Stall Watchdog
         */
        private fun checkPlaybackStall() {
            val player = mediaPlayer ?: return

            // We only care if we SHOULD be playing
            if (player.isPlaying) {
                val currentPos = player.currentPosition

                // If position hasn't changed since last check (2000ms ago)
                if (currentPos == lastPosition && (mediaPlayer?.duration ?: 0) > 2000) {
                    stallCount++
                    Log.w(TAG, "Watchdog: Playback stalled? ($stallCount/2)")

                    if (stallCount >= 2) { // Stalled for ~4 seconds
                        Log.e(TAG, "Watchdog: STALL CONFIRMED. Restarting player.")
                        stallCount = 0
                        initializePlayer() // Force restart
                    }
                } else {
                    // It moved! Reset counters.
                    stallCount = 0
                    lastPosition = currentPos
                }
            }
        }

        private fun startStallWatchdog() {
            stopStallWatchdog() // Ensure we don't double-post
            watchdogHandler.post(watchdogRunnable)
        }

        private fun stopStallWatchdog() {
            watchdogHandler.removeCallbacks(watchdogRunnable)
            stallCount = 0
        }


        // The receiver that listens for our signal
        private val videoChangeReceiver = object : BroadcastReceiver() {
            @OptIn(UnstableApi::class)
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    // Will be called by changing video
                    ACTION_VIDEO_URI_CHANGED -> {
                        Log.i(TAG, "Broadcast received: Video uri changed, full re-initialization requested.")
                        playheadTime = 0L
                        initializePlayer() // force Reinit
                    }

                    // Will be called by changing scaling, playback mode, all things requiring a reinit
                    ACTION_PLAYBACK_MODE_CHANGED -> {
                        Log.i(TAG, "Broadcast received: Playback mode change, full re-initialization requested.")
                        playheadTime = 0L
                        initializePlayer() // force Reinit
                    }

                    ACTION_STATUS_BAR_COLOR_CHANGED -> {
                        Log.i(TAG, "Broadcast received: Color changed -> Update just sys colors.")
                        // Only notify the system, DO NOT restart the player
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            notifyColorsChanged()
                        }
                    }
                }

            }
        }

        private fun refreshRenderer() {
            // Send the whole package to the renderer
            val prefManager = PreferencesManager(baseContext)
            currentScalingMode = prefManager.getScalingMode()
            renderer?.setScalingMode(currentScalingMode)
            renderer?.setTransforms(
                x = prefManager.getPositionX(),
                y = prefManager.getPositionY(),
                zoom = prefManager.getZoom(),
                rotation = prefManager.getRotation()
            )
            renderer?.setBrightness(prefManager.getBrightness())
        }

        @OptIn(UnstableApi::class)
        private fun initializePlayer() {
            // Cancel any startup issued, avoid race conditions
            playerSetupJob?.cancel()

            if (mediaPlayer != null) {
                releasePlayer()
            }

            // Get a surface
            val holder = surfaceHolder
            if (holder == null) {
                Log.w(TAG, "Cannot initialize player: surface is not ready.")
                return
            }

            Log.i(TAG, "Initializing ExoPlayer...")


            // Load prefs
            val preferenceManager = PreferencesManager(baseContext)
            isAudioEnabled = preferenceManager.isAudioEnabled()
            currentPlaybackMode = preferenceManager.getPlaybackMode()

            hasPlaybackCompleted = false

            // Status bar color refresh
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                notifyColorsChanged()
            }

            // Define a 32MB Memory Cap
            val targetBufferBytes = 32 * 1024 * 1024

            // Configure the LoadControl
            // Force the buffer duration defaults (50 is default for network streams)
            val loadControl = DefaultLoadControl.Builder()
                .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
                .setBufferDurationsMs(
                    15_000, // Min buffer 15 // lowered from default 50
                    30_000, // Max buffer 30
                    2_500,  // Buffer to start playback
                    5_000   // Buffer for rebuffer
                )
                .setTargetBufferBytes(targetBufferBytes)
                .setPrioritizeTimeOverSizeThresholds(false) // !! Enforce the 32MB cap strictly, otherwise size is priority
                .build()

            // Factory to give on creation to enable a fallback for non standard res, possible very hi res.
            val renderersFactory = DefaultRenderersFactory(baseContext).apply {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                setEnableDecoderFallback(true) // Crucial for non-standard resolutions
            }

            val player = ExoPlayer.Builder(baseContext, renderersFactory)
                .setLoadControl(loadControl)
                .setSeekParameters(SeekParameters.CLOSEST_SYNC)
                .build()
                .apply {
                    val mediaUri = getMediaUri()
                    loadedVideoUriString = mediaUri.toString()

                    if (mediaUri == null) {
                        Log.e(TAG, "Media URI is null, cannot play video.")
                        return
                    }

                    // Build a MediaItem WITHOUT the clipping config.
                    val mediaItem = MediaItem.fromUri(mediaUri)

                    // Create the base MediaSource from that item.
                    val mediaSource = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(baseContext))
                        .createMediaSource(mediaItem)

                    // Set source and configure proper looping.
                    setMediaSource(mediaSource)

                    volume = if (isAudioEnabled) 1f else 0f

                    // Looping or One shot mode
                    if(currentPlaybackMode == PlaybackMode.LOOP)
                        repeatMode = Player.REPEAT_MODE_ONE // Use built-in looping
                    else
                        repeatMode = Player.REPEAT_MODE_OFF // Use one shot

                    Log.d(TAG, "repeatMode: $repeatMode")

                    // Send all values to renderer updating it, will be used for matrix calc.
                    refreshRenderer()

                    // Listen for size changes and errors
                    addListener(object : Player.Listener {

                        // Listener for error recovery
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            Log.e(TAG, "ExoPlayer Error: ${error.errorCodeName} - ${error.message}")

                            // Identify if this is a Decoder/Hardware issue
                            val isDecoderError = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
                                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED

                            if (isDecoderError) {
                                // Check for strings that confirm NO_MEMORY
                                val msg = error.message ?: ""
                                val causeMsg = error.cause?.message ?: ""
                                if (msg.contains("NO_MEMORY") || causeMsg.contains("NO_MEMORY")) {
                                    handleCriticalError("Memory limit exceeded.")
                                    return
                                }

                                // RETRY ATTEMPT
                                if (recoveryAttempts < 3) {
                                    recoveryAttempts++
                                    Log.w(TAG, "Hardware Decoder lost (Attempt $recoveryAttempts/3). Auto-recovering...")

                                    releasePlayer()

                                    // Wait 2 seconds, then try again
                                    kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                                        kotlinx.coroutines.delay(2000)
                                        if (isVisible) {
                                            initializePlayer()
                                        }
                                    }
                                } else {
                                    // WE TRIED 3 TIMES AND FAILED -> IT'S A BAD FILE
                                    recoveryAttempts = 0
                                    handleCriticalError("Persistent hardware failure (Loop detected).")
                                }
                            } else {
                                // Generic non-hardware error
                                Handler(Looper.getMainLooper()).post {
                                    Toast.makeText(baseContext, "Error: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }

                        // Handle size changes
                        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                            super.onVideoSizeChanged(videoSize)

                            // Send video size to Renderer for Matrix Calculation
                            renderer?.setVideoSize(videoSize.width, videoSize.height)

                            // refresh all user values to renderer
                            refreshRenderer()

                            // Check for 0x0 size
                            if (videoSize.width == 0 || videoSize.height == 0) {
                                Log.w(TAG, "Ignoring invalid 0x0 video size change.")
                                return
                            }

                            // Old code for exoplayer surface
                            /* Should be only needed when using exoplayer surface
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

                        // Listener for status change
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            super.onPlaybackStateChanged(playbackState)

                            // If the player actually gets STATE_READY, reset recovery attempts counter.
                            if (playbackState == Player.STATE_READY) {
                                recoveryAttempts = 0
                            }

                            // detecting one shot playback and pausing
                            if (currentPlaybackMode == PlaybackMode.ONE_SHOT) {
                                when (playbackState) {
                                    Player.STATE_ENDED -> {
                                        Log.i(TAG, "Playback ended!")
                                        hasPlaybackCompleted = true
                                        pause()
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
                    playerSetupJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                        val glSurface = renderer?.waitForVideoSurface()

                        // If this job was cancelled, video switch or anything, STOP.
                        if (!isActive) return@launch

                        if (glSurface != null) {
                            seekTo(playheadTime)
                            setVideoSurface(glSurface)
                            prepare()
                            play()
                        }
                    }


                    /* Old launch code, might cause race condition, changed with coroutine above
                    val currentScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main)
                    currentScope.launch {
                        val glSurface = renderer?.waitForVideoSurface()
                        if (glSurface != null) {
                            seekTo(playheadTime)
                            setVideoSurface(glSurface) // <--- glSurface is actually set and becomes effective
                            prepare()
                            play()
                        }
                    } */


                    /* Old ExoPlayer surface code
                    seekTo(playheadTime)
                    setVideoSurface(holder.surface)
                    prepare()
                    play() */
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
            // Stop any startup jobs
            playerSetupJob?.cancel()

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
            playerSetupJob?.cancel() // Startup job waiting for GL surface cancelled

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
                Log.i(TAG, "Found URI.")
                uriString.toUri()
            }
        }

        /**
         * Called when the video file is "illegal" for the hardware (too large/unsupported).
         * This prevents a boot loop of the service crashing and restarting.
         */
        private fun handleCriticalError(reason: String) {
            Log.e(TAG, "CRITICAL ERROR: $reason. Disabling wallpaper.")

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(baseContext, "Wallpaper Disabled: $reason", Toast.LENGTH_LONG).show()
            }

            // Clear the Preference so it doesn't try to load again on restart
            val prefs = PreferencesManager(baseContext)
            prefs.saveVideoUri("")

            // Kill the player and DO NOT restart it.
            releasePlayer()
        }


        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            Log.i(TAG, "onSurfaceCreated")
            this.surfaceHolder = holder

            // Start the GL Renderer
            renderer = GLVideoRenderer(applicationContext)
            renderer?.onSurfaceCreated(holder)

            initializePlayer()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            Log.i(TAG, "onSurfaceChanged: New dimensions ${width}x${height}")
            this.surfaceHolder = holder

            // Tell Renderer the screen size
            renderer?.onSurfaceChanged(width, height)

            // initializePlayer() // MIGHT BE OVERKILL! TRY WITHOUT
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            Log.i(TAG, "onSurfaceDestroyed")
            releasePlayer()
            releaseRenderer()
            this.surfaceHolder = null
        }

        override fun onDestroy() {
            super.onDestroy()
            Log.i(TAG, "Engine onDestroy")
            stopStallWatchdog() // Kill the playback watchdog
            releasePlayer()
            releaseRenderer()
            unregisterReceiver(videoChangeReceiver)
        }


        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            Log.i(TAG, "onVisibilityChanged: visible = $visible isPreview = $isPreview, playbackMode = $currentPlaybackMode")

            if (visible) {
                startStallWatchdog() // Monitor for playback running, running at intervals only when visible

                // Check if the URI in memory matches the one on disk/prefs
                val currentUriOnDisk = getMediaUri().toString()

                // If they don't match, force a reload!
                if (currentUriOnDisk != loadedVideoUriString) {
                    Log.i(TAG, "WakeUp Check: URI changed while sleeping! Reloading.")
                    initializePlayer()
                }

                if (mediaPlayer == null) {
                    initializePlayer()
                } else {
                    // Check if one shot mode needs restating
                    if (currentPlaybackMode == PlaybackMode.ONE_SHOT && hasPlaybackCompleted && !isPreview()) {
                        Log.i(TAG, "Seeking back to 0 on change visibility for one shot")
                        mediaPlayer?.seekTo(0)
                        hasPlaybackCompleted = false
                    }
                    mediaPlayer?.playWhenReady = true
                    mediaPlayer?.play()
                }
            } else {
                stopStallWatchdog() // Stop monitoring for playback running

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
                addAction(ACTION_STATUS_BAR_COLOR_CHANGED)
            }
            // Using registerReceiver with the RECEIVER_NOT_EXPORTED flag is the way...
            // more modern, secure for Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(videoChangeReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag") // Flag not needed for older APIs
                registerReceiver(videoChangeReceiver, intentFilter)
            }
        }


        override fun onComputeColors(): WallpaperColors? {
            val preferenceManager = PreferencesManager(baseContext)
            val mode = preferenceManager.getStatusBarColor()

            // If Auto, let system decide
            if (mode == StatusBarColor.AUTO) return null

            val isLightText = (mode == StatusBarColor.LIGHT)

            // Light Text (White icons) Wallpaper is BLACK
            // Dark Text (Black icons) Wallpaper is WHITE
            val baseColor = if (isLightText) Color.BLACK else Color.WHITE
            val colorObj = Color.valueOf(baseColor)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // API 31+: We MUST provide the Hint
                var hints = 0
                if (!isLightText) {
                    hints = WallpaperColors.HINT_SUPPORTS_DARK_TEXT
                }

                // TRICK: Pass the same color for Primary, Secondary, and Tertiary.
                // This prevents Samsung/OneUI from "mixing" colors and ignoring the contrast.
                return WallpaperColors(colorObj, colorObj, colorObj, hints)
            } else {
                // API 27-30: Use the old constructor (No hints available, relies on contrast)
                return WallpaperColors(colorObj, colorObj, colorObj)
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
            //Log.d(TAG, "onCommand received: $action")

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