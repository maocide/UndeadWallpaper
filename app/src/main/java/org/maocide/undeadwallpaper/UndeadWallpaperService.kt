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
import org.maocide.undeadwallpaper.FileLogger
import org.maocide.undeadwallpaper.BuildConfig
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
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
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.exoplayer.upstream.DefaultAllocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.maocide.undeadwallpaper.model.PlaybackMode
import org.maocide.undeadwallpaper.model.ScalingMode
import org.maocide.undeadwallpaper.model.StartTime
import org.maocide.undeadwallpaper.model.StatusBarColor
import kotlin.math.log
import kotlin.random.Random


class UndeadWallpaperService : WallpaperService() {

    // FILTERING: Our secret passphrase
    companion object {
        const val ACTION_VIDEO_URI_CHANGED = "org.maocide.undeadwallpaper.VIDEO_URI_CHANGED"
        const val ACTION_PLAYBACK_MODE_CHANGED = "org.maocide.undeadwallpaper.ACTION_PLAYBACK_MODE_CHANGED"
        // for testing trimming
        const val ACTION_TRIM_TIMES_CHANGED = "org.maocide.undeadwallpaper.TRIM_TIMES_CHANGED"
        const val ACTION_STATUS_BAR_COLOR_CHANGED = "org.maocide.undeadwallpaper.STATUS_BAR_COLOR_CHANGED"
        const val ACTION_PLAYLIST_REORDERED = "org.maocide.undeadwallpaper.PLAYLIST_REORDERED"
    }

    override fun onCreateEngine(): Engine {
        return MyWallpaperEngine()
    }




    private inner class MyWallpaperEngine : Engine() {

        // Lazy instantiation for performance reuse
        private val prefs by lazy { PreferencesManager(baseContext) }
        private val playlistManager by lazy { PlaylistManager(baseContext, prefs) }
        private var isAudioEnabled: Boolean = false
        private lateinit var currentScalingMode: ScalingMode
        private var mediaPlayer: ExoPlayer? = null
        private var surfaceHolder: SurfaceHolder? = null
        private var playheadTime: Long = 0L
        private val TAG: String = javaClass.simpleName
        private var isScalingModeSet = false
        private var useFallbackSurface = prefs.requiresFallback()

        private var currentPlaybackMode = PlaybackMode.LOOP

        private var speed: Float = 1f

        private var loadedVideoUriString = ""
        private var hasPlaybackCompleted = false

        private var renderer: GLVideoRenderer? = null
        private var recoveryAttempts = 0 // Counter for error recovery retry attempts

        // Hardware Info
        private val isVivoDevice = Build.MANUFACTURER.equals("vivo", ignoreCase = true)

        private var playerSetupJob: kotlinx.coroutines.Job? = null
        private var vivoBounceState = 0 // 0: Not started, 1: Shrunk (waiting for callback), 2: Restored (waiting for callback), 3: Done

        // Stall Watchdog vars
        private var lastPosition: Long = 0
        private var lastRenderTimestamp: Long = 0
        private var stallCount: Int = 0
        private val watchdogHandler = Handler(Looper.getMainLooper())
        private val watchdogRunnable = object : Runnable {
            override fun run() {
                checkPlaybackStall()
                // Re-run continuously while visible
                watchdogHandler.postDelayed(this, 2000) // Check every 2 seconds
            }
        }
        private var visibilityJob: kotlinx.coroutines.Job? = null

        /**
         * Checks if the player claims to be playing but isn't advancing.
         * Used by the Stall Watchdog
         */
        private fun checkPlaybackStall() {
            val player = mediaPlayer ?: return
            val renderer = renderer ?: return

            // We only care if we SHOULD be playing
            if (player.isPlaying && player.playbackState == Player.STATE_READY) {
                val currentPos = player.currentPosition
                val currentRenderTime = renderer.getSurfaceDrawTimestamp()

                val isPlayerStuck = (currentPos == lastPosition)
                val isScreenFrozen = (currentRenderTime == lastRenderTimestamp)

                // If EITHER is true, the player is stuck with no error.
                if ((isPlayerStuck || isScreenFrozen) && player.duration > 2000) {
                    stallCount++
                    FileLogger.w(TAG, "Watchdog: Stall detected! PlayerStuck=$isPlayerStuck, ScreenFrozen=$isScreenFrozen ($stallCount/2)")


                    if (stallCount >= 2) { // Stalled for ~4 seconds
                        FileLogger.e(TAG, "Watchdog: STALL CONFIRMED. Restarting player.")
                        stallCount = 0
                        initializePlayer() // Force restart
                    }
                } else {
                    // It moved! Reset counters.
                    stallCount = 0
                    lastPosition = currentPos
                    lastRenderTimestamp = currentRenderTime
                }
            } else {
                // If not playing or not ready, reset the watchdog counters to avoid false positives.
                stallCount = 0
                lastPosition = player.currentPosition
                lastRenderTimestamp = renderer.getSurfaceDrawTimestamp()
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

        @OptIn(UnstableApi::class)
        private fun bindPlaylistToPlayer(player: ExoPlayer, keepCurrentPlayback: Boolean) {
            val dataSourceFactory = DefaultDataSource.Factory(baseContext)
            val mediaSourceFactory = ProgressiveMediaSource.Factory(dataSourceFactory)

            if (currentPlaybackMode == PlaybackMode.LOOP_ALL || currentPlaybackMode == PlaybackMode.SHUFFLE) {
                val playlistUris = playlistManager.getPlaylistUris()

                if (playlistUris.isNotEmpty()) {
                    val mediaSources = playlistUris.map { uriStr ->
                        val mediaItem = MediaItem.Builder()
                            .setUri(uriStr)
                            .setMediaId(uriStr)
                            .build()
                        mediaSourceFactory.createMediaSource(mediaItem)
                    }

                    var targetIndex = playlistUris.indexOf(loadedVideoUriString)
                    if (targetIndex == -1) targetIndex = 0

                    // If we are just reordering, keep the exact millisecond we are currently at
                    val targetPosition = if (keepCurrentPlayback) player.currentPosition else playheadTime

                    player.setMediaSources(mediaSources)
                    player.seekTo(targetIndex, targetPosition)
                } else {
                    // Fallback for empty list
                    val mediaUri = getMediaUri() ?: return
                    val mediaItem = MediaItem.Builder().setUri(mediaUri).setMediaId(loadedVideoUriString).build()
                    player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
                    player.seekTo(if (keepCurrentPlayback) player.currentPosition else playheadTime)
                }
            } else {
                // Single file modes
                val mediaUri = getMediaUri() ?: return
                val mediaItem = MediaItem.Builder().setUri(mediaUri).setMediaId(loadedVideoUriString).build()
                player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
                player.seekTo(if (keepCurrentPlayback) player.currentPosition else playheadTime)
            }
        }


        // The receiver that listens for our signal
        private val videoChangeReceiver = object : BroadcastReceiver() {
            @OptIn(UnstableApi::class)
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    // Will be called by changing video
                    ACTION_VIDEO_URI_CHANGED -> {
                        FileLogger.i(TAG, "Broadcast received: Video uri changed, full re-initialization requested.")
                        playheadTime = 0L
                        initializePlayer() // force Reinit
                    }

                    // Will be called by changing scaling, playback mode, all things requiring a reinit
                    ACTION_PLAYBACK_MODE_CHANGED -> {
                        FileLogger.i(TAG, "Broadcast received: Playback mode change, full re-initialization requested.")
                        playheadTime = 0L
                        initializePlayer() // force Reinit
                    }

                    ACTION_STATUS_BAR_COLOR_CHANGED -> {
                        FileLogger.i(TAG, "Broadcast received: Color changed -> Update just sys colors.")
                        // Only notify the system, DO NOT restart the player
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            notifyColorsChanged()
                        }
                    }

                    ACTION_PLAYLIST_REORDERED -> {
                        FileLogger.i(TAG, "Playlist reordered. Syncing ExoPlayer timeline.")
                        mediaPlayer?.let {
                            // Call the helper (Keep playing seamlessly)
                            bindPlaylistToPlayer(it, keepCurrentPlayback = true)
                        }
                    }
                }

            }
        }

        private fun refreshRenderer() {
            // Send the whole package to the renderer
            currentScalingMode = prefs.getScalingMode()
            renderer?.setScalingMode(currentScalingMode)
            renderer?.setTransforms(
                x = prefs.getPositionX(),
                y = prefs.getPositionY(),
                zoom = prefs.getZoom(),
                rotation = prefs.getRotation()
            )
            renderer?.setBrightness(prefs.getBrightness())
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
                FileLogger.w(TAG, "Cannot initialize player: surface is not ready.")
                return
            }

            FileLogger.i(TAG, "Initializing ExoPlayer...")


            // Load prefs
            isAudioEnabled = prefs.isAudioEnabled()
            currentPlaybackMode = prefs.getPlaybackMode()
            speed = prefs.getSpeed()


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
                .setPrioritizeTimeOverSizeThresholds(false) // !! Enforce the 32MB cap strictly, otherwise time is priority. The Above size will be the limit.
                .build()

            // Factory to give on creation to enable a fallback for non standard res, possible very hi res.
            val renderersFactory = DefaultRenderersFactory(baseContext).apply {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                setEnableDecoderFallback(true) // Crucial for non-standard resolutions
            }

            val player = ExoPlayer.Builder(baseContext, renderersFactory)
                .setLooper(Looper.getMainLooper())
                .setLoadControl(loadControl)
                .setSeekParameters(SeekParameters.NEXT_SYNC)
                .build()
                .apply {
                    val mediaUri = getMediaUri()
                    loadedVideoUriString = mediaUri.toString()

                    if (mediaUri == null) {
                        FileLogger.e(TAG, "Media URI is null, cannot play video.")
                        return
                    }

                    // Call the helper to load playlist
                    bindPlaylistToPlayer(this, keepCurrentPlayback = false)

                    // Apply volume & Track Selection
                    val actualAudioEnabled = isAudioEnabled // Possible to add && !isPreview() to force mute previews

                    if (!actualAudioEnabled) {
                        // Explicitly disable the audio track if we don't need it.
                        // This prevents OEM OS blocks from stalling the video clock.
                        trackSelectionParameters = trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                            .build()
                    } else {
                        trackSelectionParameters = trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                            .build()
                    }

                    volume = if (actualAudioEnabled) 1f else 0f

                    // Apply speed
                    setPlaybackSpeed(speed)

                    // Configure Repeat & Shuffle Modes
                    when (currentPlaybackMode) {
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

                    FileLogger.d(TAG, "repeatMode: $repeatMode, shuffleModeEnabled: $shuffleModeEnabled")

                    // Send all values to renderer updating it, will be used for matrix calc.
                    refreshRenderer()

                    // Listen for size changes and errors
                    addListener(object : Player.Listener {

                        // Listener for error recovery
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            FileLogger.e(TAG, "ExoPlayer Error: ${error.errorCodeName} - ${error.message}")

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
                                    FileLogger.w(TAG, "Hardware Decoder lost (Attempt $recoveryAttempts/3). Auto-recovering...")

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

                            // Check for 0x0 size
                            if (videoSize.width == 0 || videoSize.height == 0) {
                                FileLogger.w(TAG, "Ignoring invalid 0x0 video size change.")
                                return
                            }

                            // Send video size to Renderer for Matrix Calculation
                            renderer?.setVideoSize(videoSize.width, videoSize.height)

                            // refresh all user values to renderer
                            refreshRenderer()

                            // Use ExoPlayer's scaling only if fallback surface is used
                            if (useFallbackSurface) {
                                if (!isScalingModeSet) {
                                    FileLogger.i(TAG, "Valid video size detected: ${videoSize.width}x${videoSize.height}. Setting scaling mode ONCE for fallback surface.")

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
                                        FileLogger.i(TAG, "Playback ended!")
                                        hasPlaybackCompleted = true
                                        pause()
                                    }
                                    Player.STATE_READY -> {
                                        if (hasPlaybackCompleted) pause()
                                    }
                                }
                            }
                        }

                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            super.onMediaItemTransition(mediaItem, reason)
                            val nextUriString = mediaItem?.mediaId
                            if (nextUriString != null && nextUriString != loadedVideoUriString) {
                                FileLogger.i(TAG, "Transitioning to next video in playlist: $nextUriString")
                                loadedVideoUriString = nextUriString
                                prefs.saveVideoUri(nextUriString)
                            }

                            // If we hit the end of the shuffled playlist and it repeats, generate a new random order.
                            val isWrapAround = reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT ||
                                    (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                                            currentMediaItemIndex == currentTimeline.getFirstWindowIndex(shuffleModeEnabled))

                            if (isWrapAround && currentPlaybackMode == PlaybackMode.SHUFFLE) {
                                FileLogger.i(TAG, "Playlist repeated. Generating new shuffle order.")
                                if (mediaItemCount > 0) {
                                    val newOrder = DefaultShuffleOrder(mediaItemCount, Random.nextLong())
                                    (this@apply).setShuffleOrder(newOrder)
                                }
                            }
                        }

                        override fun onRenderedFirstFrame() {
                            super.onRenderedFirstFrame()
                            FileLogger.i(TAG, "SUCCESS: onRenderedFirstFrame called. Decoder actually pushed a frame to the screen!")
                        }
                    })

                    // WAIT for the GL Surface, then attach
                    playerSetupJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                        var finalSurface: android.view.Surface? = null

                        if (!useFallbackSurface) {
                            try {
                                // Give it 1.5 seconds to provide a surface, otherwise timeout
                                finalSurface = kotlinx.coroutines.withTimeoutOrNull(1500L) {
                                    renderer?.waitForVideoSurface()
                                }

                                // If it returns null, the timeout was hit
                                if (finalSurface == null) {
                                    FileLogger.w(TAG, "GL Surface timeout (1.5s)! OS blocked it. Triggering fallback.")
                                    prefs.setRequiresFallback(true) // Save fallback requirement for next launches
                                    throw java.util.concurrent.TimeoutException("Surface wait timed out")
                                }

                            } catch (e: Exception) {
                                FileLogger.e(TAG, "GL Renderer failed to provide surface, falling back to default surface", e)
                                useFallbackSurface = true
                                releasePlayer()
                                releaseRenderer()
                                initializePlayer() // Restart immediately using the fallback
                                return@launch
                            }
                        } else {
                            finalSurface = surfaceHolder?.surface
                        }

                        // If this job was cancelled, video switch or anything, STOP.
                        if (!isActive) return@launch

                        // Check if player is alive, surface is valid, surface is ready.
                        if (mediaPlayer == null || surfaceHolder == null || surfaceHolder?.surface == null || !surfaceHolder?.surface?.isValid!!) {
                            FileLogger.w(TAG, "Engine destroyed or surface invalid before player setup completed. Aborting.")
                            return@launch
                        }

                        if (finalSurface != null) {

                            // VIVO PROTECT: Don't attach if the surface exists but has no dimensions yet
                            val width = surfaceHolder?.surfaceFrame?.width() ?: 0
                            val height = surfaceHolder?.surfaceFrame?.height() ?: 0

                            if (width == 0 || height == 0) {
                                FileLogger.w(TAG, "Surface dimensions are 0x0. Deferring attachment.")
                                return@launch // Exit here to prevent hardware decoder crash
                            }

                            // Apply starting position
                            if (currentPlaybackMode == PlaybackMode.LOOP_ALL || currentPlaybackMode == PlaybackMode.SHUFFLE) {
                                seekTo(currentMediaItemIndex, playheadTime)
                            } else {
                                seekTo(playheadTime)
                            }

                            setVideoSurface(finalSurface)
                            prepare()

                            // DO NOT call play() here.
                            // Just sync the playWhenReady flag with the current visibility state.
                            val shouldPlay = if (playWhenReady) true else isVisible

                            FileLogger.i(TAG, "Setup complete. isVisible: $isVisible, playWhenReady: $playWhenReady, shouldPlay: $shouldPlay")

                            playWhenReady = shouldPlay
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
                FileLogger.i(TAG, "Releasing ExoPlayer...")
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
                FileLogger.i(TAG, "Releasing GlRenderer...")
                renderer?.release()
                renderer = null
            }
        }

        private fun getMediaUri(): Uri? {
            val uriString = prefs.getVideoUri()

            return if (uriString.isNullOrEmpty()) {
                FileLogger.w(TAG, "Video URI is null or empty.")
                null
            } else {
                if (BuildConfig.DEBUG) {
                    FileLogger.i(TAG, "Found URI: $uriString")
                } else {
                    FileLogger.i(TAG, "Found URI in preferences")
                }
                uriString.toUri()
            }
        }

        /**
         * Called when the video file is "illegal" for the hardware (too large/unsupported).
         * This prevents a boot loop of the service crashing and restarting.
         */
        private fun handleCriticalError(reason: String) {
            FileLogger.e(TAG, "CRITICAL ERROR: $reason. Disabling wallpaper.")

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(baseContext, "Wallpaper Disabled: $reason", Toast.LENGTH_LONG).show()
            }

            // Clear the Preference so it doesn't try to load again on restart
            prefs.saveVideoUri("")

            // Kill the player and DO NOT restart it.
            releasePlayer()
        }


        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            FileLogger.i(TAG, "onSurfaceCreated")
            this.surfaceHolder = holder

            // VIVO KICKSTART: Safe Size Bounce (Preview only)
            // If it's a Vivo device in preview mode, and we haven't bounced the size yet,
            // we DELAY initialization of the GL Renderer and Player to avoid crashes when
            // we alter the surface dimensions in onSurfaceChanged.
            if (isVivoDevice && isPreview && vivoBounceState == 0) {
                FileLogger.i(TAG, "Vivo Preview: Deferring initialization to perform safe size bounce.")
                return // Exit early
            }

            if (!useFallbackSurface) {
                // Start the GL Renderer
                renderer = GLVideoRenderer(applicationContext)
                renderer?.onSurfaceCreated(holder)
            } else {
                FileLogger.i(TAG, "Using fallback surface, skipping GL Renderer creation")
            }

            initializePlayer()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            FileLogger.i(TAG, "onSurfaceChanged: New dimensions ${width}x${height}")

            this.surfaceHolder = holder

            // VIVO KICKSTART: Execute the bounce safely relying on callbacks.
            // Vivo's UI requires a layout size or format change to dismiss its loading overlay
            // and enable the 'Set' button. We do this BEFORE the GL Renderer starts.
            if (isVivoDevice && isPreview) {
                if (vivoBounceState == 0) {
                    vivoBounceState = 1
                    FileLogger.i(TAG, "Vivo Preview: Shrinking surface by 1px to force layout change.")
                    try {
                        holder.setFixedSize(width, height - 1)
                    } catch (e: Exception) {
                        FileLogger.e(TAG, "Vivo size shrink failed", e)
                    }
                    return // Wait for the next onSurfaceChanged callback with the shrunk size
                } else if (vivoBounceState == 1) {
                    vivoBounceState = 2
                    FileLogger.i(TAG, "Vivo Preview: Restoring surface layout size.")
                    try {
                        holder.setSizeFromLayout()
                    } catch (e: Exception) {
                        FileLogger.e(TAG, "Vivo size restore failed", e)
                    }
                    return // Wait for the final onSurfaceChanged callback with the correct size
                } else if (vivoBounceState == 2) {
                    // The final callback arrived!
                    vivoBounceState = 3
                    FileLogger.i(TAG, "Vivo Preview: Safe Size Bounce complete. Initializing Renderer and Player.")
                    // Because we deferred in onSurfaceCreated, we must manually create the renderer here.
                    if (!useFallbackSurface && renderer == null) {
                        renderer = GLVideoRenderer(applicationContext)
                        renderer?.onSurfaceCreated(holder)
                    }
                    // Now fall through to normally update the renderer's size and initialize the player
                }
            }

            if (!useFallbackSurface) {
                renderer?.onSurfaceChanged(width, height)
            }

            // If the player isn't set up yet (because it was deferred by the 0x0 check),
            // kick off initialization now that we have real dimensions.
            if (mediaPlayer?.playbackState == Player.STATE_IDLE || mediaPlayer == null) {
                initializePlayer()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            FileLogger.i(TAG, "onSurfaceDestroyed")
            vivoBounceState = 0
            releasePlayer()
            releaseRenderer()
            this.surfaceHolder = null
        }

        override fun onDestroy() {
            super.onDestroy()
            FileLogger.i(TAG, "Engine onDestroy")
            stopStallWatchdog() // Kill the playback watchdog
            releasePlayer()
            releaseRenderer()
            unregisterReceiver(videoChangeReceiver)
        }


        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)

            // Cancel any previous visibility commands that haven't executed yet
            visibilityJob?.cancel()

            // Launch a new command with a 150ms delay
            visibilityJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                kotlinx.coroutines.delay(33L) // Give Device 33ms to stop spamming

                // If this job was cancelled by another rapid-fire event, stop here.
                if (!isActive) return@launch

                FileLogger.i(TAG, "onVisibilityChanged (Debounced): visible = $visible isPreview = $isPreview, playbackMode = $currentPlaybackMode")

                if (visible) {
                    startStallWatchdog() // Monitor for playback running

                    val currentUriOnDisk = getMediaUri().toString()
                    val isSurfaceDead = surfaceHolder?.surface?.isValid != true
                    var wasJustInitialized = false

                    // Check if we need to (re)initialize
                    if (currentUriOnDisk != loadedVideoUriString || mediaPlayer == null || isSurfaceDead) {
                        if (currentUriOnDisk != loadedVideoUriString) {
                            FileLogger.i(TAG, "WakeUp Check: URI changed while sleeping! Reloading.")
                        } else if (isSurfaceDead) {
                            FileLogger.w(TAG, "WakeUp Check: Surface died silently. Forcing restart.")
                        }

                        // If the user wants a restart, reset playhead BEFORE init
                        // so bindPlaylistToPlayer doesn't seek to the old paused position.
                        if (prefs.getStartTime() == StartTime.RESTART) {
                            playheadTime = 0L
                            hasPlaybackCompleted = false
                        }

                        initializePlayer()
                        wasJustInitialized = true
                    }

                    // Safely grab the player instance.
                    val player = mediaPlayer ?: return@launch

                    // Handle Timeline
                    // We only apply timeline manipulations if the player wasn't just freshly initialized.
                    if (!wasJustInitialized) {
                        val startTimePref = prefs.getStartTime()
                        when (startTimePref) {
                            StartTime.RESUME -> {
                                if (currentPlaybackMode == PlaybackMode.ONE_SHOT && hasPlaybackCompleted && !isPreview()) {
                                    playheadTime = 0L
                                    player.seekToDefaultPosition()
                                    hasPlaybackCompleted = false
                                }
                            }
                            StartTime.RESTART -> {
                                playheadTime = 0L
                                player.seekToDefaultPosition()
                                hasPlaybackCompleted = false
                            }
                            StartTime.RANDOM -> {
                                val duration = player.duration
                                if (duration > 0 && duration != C.TIME_UNSET) {
                                    val randomPos = Random.nextLong(0, duration)
                                    playheadTime = randomPos
                                    player.seekTo(player.currentMediaItemIndex, randomPos)
                                } else {
                                    playheadTime = 0L
                                    player.seekToDefaultPosition()
                                }
                                hasPlaybackCompleted = false
                            }
                        }
                    }

                    // The "Play" Command: Just set the flag.
                    // ExoPlayer will start natively as soon as it reaches STATE_READY.
                    player.playWhenReady = true

                } else {
                    stopStallWatchdog()

                    if (isPreview) {
                        FileLogger.i(TAG, "Preview hidden. Releasing player to save decoders.")
                        releasePlayer()
                    } else {
                        mediaPlayer?.pause()
                        mediaPlayer?.playWhenReady = false
                    }
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            FileLogger.i(TAG, "Engine onCreate")
            // Turn on filter to start listening
            val intentFilter = IntentFilter().apply {
                addAction(ACTION_VIDEO_URI_CHANGED)
                addAction(ACTION_PLAYBACK_MODE_CHANGED)
                addAction(ACTION_STATUS_BAR_COLOR_CHANGED)
                addAction(ACTION_PLAYLIST_REORDERED)
            }
            // Registering the broadcast receiver with ContextCompat.RECEIVER_NOT_EXPORTED
            // ensures it is secure across all API levels by preventing external intent injection.
            ContextCompat.registerReceiver(
                this@UndeadWallpaperService,
                videoChangeReceiver,
                intentFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }


        override fun onComputeColors(): WallpaperColors? {
            val mode = prefs.getStatusBarColor()

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

            if (action == ACTION_PLAYBACK_MODE_CHANGED ||
                action == ACTION_VIDEO_URI_CHANGED ||
                action == "android.wallpaper.reapply") {

                FileLogger.i(TAG, "Command received -> Re-initializing player.")
                // Full reset for major changes
                initializePlayer()

            } else if (action == ACTION_PLAYLIST_REORDERED) {

                FileLogger.i(TAG, "Command received -> Playlist reordered. Syncing silently.")

                mediaPlayer?.let {
                    bindPlaylistToPlayer(it, keepCurrentPlayback = true)
                }

            }

            return null
        }
    }
}