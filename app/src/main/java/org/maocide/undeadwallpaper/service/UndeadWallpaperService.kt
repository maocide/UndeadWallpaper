package org.maocide.undeadwallpaper.service

import org.maocide.undeadwallpaper.BuildConfig

import org.maocide.undeadwallpaper.data.PlaylistManager
import org.maocide.undeadwallpaper.data.PreferencesManager
import org.maocide.undeadwallpaper.model.PlaybackMode
import org.maocide.undeadwallpaper.model.ScalingMode
import org.maocide.undeadwallpaper.model.StartTime
import org.maocide.undeadwallpaper.model.StatusBarColor
import org.maocide.undeadwallpaper.utils.FileLogger

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
import android.os.UserManager
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.MotionEvent


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
import org.maocide.undeadwallpaper.model.GestureType
import org.maocide.undeadwallpaper.model.VideoSettings
import org.maocide.undeadwallpaper.model.WallpaperAction


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
        const val ACTION_VIDEO_SETTINGS_CHANGED = "org.maocide.undeadwallpaper.VIDEO_SETTINGS_CHANGED"
    }

    override fun onCreateEngine(): Engine {
        return MyWallpaperEngine()
    }




    private inner class MyWallpaperEngine : Engine(), WallpaperPlayerListener {

        // Lazy instantiation for performance reuse
        private val prefs by lazy { PreferencesManager(baseContext) }
        private val playlistManager by lazy { PlaylistManager(baseContext, prefs) }
        private lateinit var currentScalingMode: ScalingMode

        private val wallpaperPlayer = WallpaperPlayer(baseContext, this)
        private val isPlayerInitialized: Boolean
            get() = wallpaperPlayer.getPlayerInstance() != null

        private var surfaceHolder: SurfaceHolder? = null
        private var playheadTime: Long = 0L
        private val TAG: String = javaClass.simpleName
        private var isScalingModeSet = false
        private var useFallbackSurface = false

        private var currentPlaybackMode = PlaybackMode.LOOP

        private var speed: Float = 1f

        private var loadedVideoUriString = ""
        private var hasPlaybackCompleted = false

        private var renderer: GLVideoRenderer? = null

        // Hardware Info
        private val isVivoDevice = Build.MANUFACTURER.equals("vivo", ignoreCase = true)

        private var playerSetupJob: kotlinx.coroutines.Job? = null

        private val playbackWatchdog = PlaybackWatchdog {
            FileLogger.e(TAG, "Watchdog: STALL CONFIRMED. Restarting player.")
            initializePlayer() // Force restart
        }

        private var visibilityJob: kotlinx.coroutines.Job? = null

        // Touch Interaction State
        private var lastTapTimeMs: Long = 0L
        private var tapCount: Int = 0
        private val DOUBLE_TAP_TIMEOUT_MS = 300L // Standard Android double-tap window
        private val touchHandler = Handler(Looper.getMainLooper())

        private val resetTapCountRunnable = Runnable {
            tapCount = 0
            lastTapTimeMs = 0L
        }

        private fun applyNonVisualSettings(settings: VideoSettings) {
            wallpaperPlayer.getPlayerInstance()?.let { player ->
                player.volume = settings.getPerceivedVolume()
                player.setPlaybackSpeed(settings.speed)
            }
        }

        private fun getSettingsForUri(uriString: String): VideoSettings {
            val fileName = uriString.toUri().lastPathSegment ?: ""
            return prefs.getVideoSettings(fileName)
        }

        private fun updateActiveVideoState(newUriString: String) {
            loadedVideoUriString = newUriString
            prefs.saveActiveVideoUri(newUriString)
        }

        private fun updateTouchListeningState() {
            val doubleTapAction = prefs.getActionForGesture(GestureType.DOUBLE_TAP)
            val longPressAction = prefs.getActionForGesture(GestureType.LONG_PRESS)

            // Only turn on the Android touch pipeline if the user actually bound an action
            val wantsTouchEvents = doubleTapAction != WallpaperAction.NONE || longPressAction != WallpaperAction.NONE

            setTouchEventsEnabled(wantsTouchEvents)
            FileLogger.i(TAG, "Touch events enabled: $wantsTouchEvents")
        }


        @OptIn(UnstableApi::class)
        private fun bindPlaylistToPlayer(keepCurrentPlayback: Boolean) {
            val dataSourceFactory = DefaultDataSource.Factory(baseContext)
            val mediaSourceFactory = ProgressiveMediaSource.Factory(dataSourceFactory)

            val mediaUri = getMediaUri() ?: return

            // Hybrid Gapless Batching:
            // Fetch the chunk of consecutive URIs that share identical visual settings.
            val chunkUris = playlistManager.getGaplessChunkUris(loadedVideoUriString, currentPlaybackMode)

            // If the chunk is empty for some reason, fallback to the single mediaUri
            val urisToLoad = if (chunkUris.isNotEmpty()) chunkUris else listOf(loadedVideoUriString)

            val mediaSources = urisToLoad.map { uriStr ->
                val parsedUri = uriStr.toUri()
                val mediaItem = MediaItem.Builder().setUri(parsedUri).setMediaId(uriStr).build()
                mediaSourceFactory.createMediaSource(mediaItem)
            }

            // Full-Playlist Loop Optimization:
            // If the chunk we built contains every video in the playlist, they all share settings!
            // We can safely enable ExoPlayer's internal REPEAT_MODE_ALL. This gives perfect gapless looping
            // without ever hitting STATE_ENDED and incurring the manual flush pause.
            // NOTE: SHUFFLE mode is excluded because it MUST hit STATE_ENDED to trigger a newly randomized sequence loop.
            val playlistUris = playlistManager.getPlaylistUris()
            if (currentPlaybackMode == PlaybackMode.LOOP_ALL
                && playlistUris.isNotEmpty() && chunkUris.size == playlistUris.size) {
                wallpaperPlayer.setRepeatMode(Player.REPEAT_MODE_ALL)
            } else if (currentPlaybackMode == PlaybackMode.LOOP_ALL || currentPlaybackMode == PlaybackMode.SHUFFLE) {
                // If chunk is smaller than playlist, we MUST disable repeat mode so it naturally hits STATE_ENDED.
                wallpaperPlayer.setRepeatMode(Player.REPEAT_MODE_OFF)
            }

            wallpaperPlayer.setMediaSources(mediaSources)
            wallpaperPlayer.seekTo(0, if (keepCurrentPlayback) wallpaperPlayer.currentPosition else playheadTime)
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
                        if (isPlayerInitialized) {
                            // Call the helper (Keep playing seamlessly)
                            bindPlaylistToPlayer(keepCurrentPlayback = true)
                        }
                    }

                    ACTION_VIDEO_SETTINGS_CHANGED -> {
                        FileLogger.i(TAG, "Broadcast received: Video settings changed, full re-initialization requested.")
                        // Ensure settings apply completely identical to a URI change to avoid syncing bugs
                        playheadTime = 0L
                        initializePlayer() // force Reinit
                    }

                    Intent.ACTION_USER_UNLOCKED -> {
                        FileLogger.i(TAG, "Broadcast received: User Unlocked. Initializing player safely.")
                        initializePlayer()
                    }
                }

            }
        }

        /**
         * Skips current video and moves to next using chunks to buffer video sharing settings
         */
        private fun skipNextVideo(isManualSkip: Boolean) {
            if (!isPlayerInitialized) return

            val nextUriString = playlistManager.getNextUri(loadedVideoUriString, currentPlaybackMode)

            if (nextUriString == null) {
                FileLogger.w(TAG, "Transition aborted: Next URI is null.")
                return
            }

            FileLogger.i(TAG, "Transitioning across boundary to: $nextUriString (Manual: $isManualSkip)")

            // ONE_SHOT / LOOP Reset (These still need a hard re-init to reset their single-video state)
            if (currentPlaybackMode == PlaybackMode.ONE_SHOT || currentPlaybackMode == PlaybackMode.LOOP) {
                loadedVideoUriString = nextUriString
                prefs.saveActiveVideoUri(nextUriString)

                FileLogger.i(TAG, "Single-video mode detected. Performing hard re-initialization for skip.")
                releasePlayer()
                playheadTime = 0L
                hasPlaybackCompleted = false
                initializePlayer()
                return
            }

            // Now update the state
            loadedVideoUriString = nextUriString
            prefs.saveActiveVideoUri(nextUriString)
            playheadTime = 0L
            hasPlaybackCompleted = false

            // ALWAYS Hot-swap via Chunking!
            bindPlaylistToPlayer(keepCurrentPlayback = false)

            if (!isManualSkip) {
                // AUTOMATIC TRANSITION (STATE_ENDED):
                // The decoder buffer is completely empty. We MUST apply the matrix NOW
                // so the very first frame of the new video draws perfectly.
                FileLogger.i(TAG, "Auto-transition: Applying matrix immediately.")
                refreshRenderer()
            } else {
                // MANUAL SKIP:
                // The buffer has old frames. We DELAY the matrix update until
                // onRenderedFirstFrame fires to prevent a visual snap.
                FileLogger.i(TAG, "Manual skip: Delaying matrix update to onRenderedFirstFrame.")
            }

            val activeUri = loadedVideoUriString.toUri()
            val fileName = activeUri.lastPathSegment ?: ""
            val activeSettings = prefs.getVideoSettings(fileName)

            wallpaperPlayer.getPlayerInstance()?.let { player ->
                player.volume = activeSettings.getPerceivedVolume()
                player.setPlaybackSpeed(activeSettings.speed)
            }

            wallpaperPlayer.prepare()
            wallpaperPlayer.playWhenReady = if (isManualSkip) isVisible else true
        }

        override fun onTouchEvent(event: MotionEvent?) {
            super.onTouchEvent(event)

            // We only care about the moment the finger hits the screen
            if (event?.action == MotionEvent.ACTION_DOWN) {
                val currentTapTimeMs = System.currentTimeMillis()

                // If this tap is within the timeout window of the previous tap
                if (currentTapTimeMs - lastTapTimeMs < DOUBLE_TAP_TIMEOUT_MS) {
                    tapCount++
                } else {
                    // First tap, or too much time passed. Reset count.
                    tapCount = 1
                }

                lastTapTimeMs = currentTapTimeMs

                // Cancel any pending reset
                touchHandler.removeCallbacks(resetTapCountRunnable)

                if (tapCount == 2) {
                    // DOUBLE TAP DETECTED!
                    FileLogger.i(TAG, "Double-tap detected at X:${event.x}, Y:${event.y}")

                    // Reset immediately so a third rapid tap doesn't trigger it again
                    tapCount = 0
                    lastTapTimeMs = 0L

                    skipNextVideo(true)
                } else {
                    // If it's just a single tap, wait to see if a second one comes.
                    // If not, reset the counter.
                    touchHandler.postDelayed(resetTapCountRunnable, DOUBLE_TAP_TIMEOUT_MS)
                }
            }
        }

        /**
         * Uses the VideoSettings of the current uri to recompute matrix/uniforms on GL Renderer
         * It is done here to be as late and synced to playback as possible.
         */
        private fun refreshRenderer() {
            if (loadedVideoUriString.isBlank()) return

            val activeSettings = getSettingsForUri(loadedVideoUriString)

            currentScalingMode = activeSettings.scalingMode
            renderer?.setScalingMode(currentScalingMode)
            renderer?.setTransforms(
                x = activeSettings.positionX,
                y = activeSettings.positionY,
                zoom = activeSettings.zoom,
                rotation = activeSettings.rotation
            )
            renderer?.setBrightness(activeSettings.brightness)
        }

        // WallpaperPlayerListener implementations

        override fun onPlayerError(error: PlaybackException) {
            // Handled mostly by WallpaperPlayer, this is just for non-hardware errors or restart triggers
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(baseContext, "Error: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
            }

            FileLogger.e(TAG, "PLAYER ERROR: ${error.errorCodeName}.")

            // Re-initialize if visible and retry limit not reached (retries handled by wallpaperPlayer but triggering re-init here)
            // If the wallpaperPlayer triggers a generic error, we just notify user.
            // If it triggered an auto-recovering hardware error, we might need to recreate the surface/player.
            // But wallpaperPlayer handles the retry delay internally and calls this. We actually need to initializePlayer here.
            val isDecoderError = error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED

            if (isDecoderError) {
                if (isVisible) {
                    initializePlayer()
                }
            }
        }

        override fun onHardwareFailure(reason: String) {
            handleCriticalError(reason)
        }

        @OptIn(UnstableApi::class)
        override fun onVideoSizeChanged(width: Int, height: Int) {
            // Send video size to Renderer for Matrix Calculation
            renderer?.setVideoSize(width, height)

            // No more refreshed here, just on new frame
            // refreshRenderer()

            // Use ExoPlayer's scaling only if fallback surface is used
            if (useFallbackSurface) {
                if (!isScalingModeSet) {
                    FileLogger.i(TAG, "Valid video size detected: ${width}x${height}. Setting scaling mode ONCE for fallback surface.")

                    val videoAspectRatio = width.toFloat() / height.toFloat()
                    val isHorizontalVideo = videoAspectRatio > 1.0

                    wallpaperPlayer.videoScalingMode = if (isHorizontalVideo) {
                        VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                    } else {
                        VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                    }

                    isScalingModeSet = true // SET THE FLAG SO THIS DOESN'T RUN AGAIN
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_ENDED -> {
                    FileLogger.i(TAG, "Playback ended!")

                    if (currentPlaybackMode == PlaybackMode.ONE_SHOT) {
                        hasPlaybackCompleted = true
                        wallpaperPlayer.pause()
                    } else if (currentPlaybackMode == PlaybackMode.LOOP_ALL || currentPlaybackMode == PlaybackMode.SHUFFLE) {
                        // Same flow as skip, inside the playback mode is handled
                        skipNextVideo(isManualSkip = false)
                    }
                }
                Player.STATE_READY -> {
                    if (currentPlaybackMode == PlaybackMode.ONE_SHOT && hasPlaybackCompleted) {
                        wallpaperPlayer.pause()
                    }
                }
            }
        }

        @OptIn(UnstableApi::class)
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Track chunk advancement internally for optimized batching
            val nextUriString = mediaItem?.mediaId
            if (nextUriString != null && nextUriString != loadedVideoUriString) {
                updateActiveVideoState(nextUriString)
            }

            val activeSettings = getSettingsForUri(loadedVideoUriString)
            applyNonVisualSettings(activeSettings)
        }

        override fun onRenderedFirstFrame() {
            FileLogger.i(TAG, "SUCCESS: onRenderedFirstFrame called. Decoder actually pushed a frame to the screen!")
            refreshRenderer() // Applies as late as possible a matrix/uniform recomputation on openGL engine
        }

        @OptIn(UnstableApi::class)
        private fun initializePlayer() {
            // Direct Boot Check (FBE)
            // If the user hasn't unlocked the phone after a reboot, storage is heavily encrypted.
            // Do not attempt to read preferences or initialize the player.
            val userManager = getSystemService(Context.USER_SERVICE) as UserManager
            if (!userManager.isUserUnlocked) {
                FileLogger.w(TAG, "Phone is locked (Direct Boot). Aborting player initialization.")
                return
            }

            // Cancel any startup issued, avoid race conditions
            playerSetupJob?.cancel()

            if (isPlayerInitialized) {
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
            currentPlaybackMode = prefs.getPlaybackMode()

            hasPlaybackCompleted = false

            val mediaUri = getMediaUri()
            loadedVideoUriString = mediaUri?.toString() ?: ""

            // Status bar color refresh, material you notify
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                FileLogger.i(TAG, "notifyColorsChanged Called. Current URI: $loadedVideoUriString")
                notifyColorsChanged()
            }

            if (mediaUri == null) {
                FileLogger.e(TAG, "Media URI is null, cannot play video.")
                return
            }

            val fileName = mediaUri.lastPathSegment ?: ""
            val activeSettings = prefs.getVideoSettings(fileName)
            val initialVolume = activeSettings.getPerceivedVolume()
            speed = activeSettings.speed

            wallpaperPlayer.initialize(null, initialVolume, speed, currentPlaybackMode)

            if (!isPlayerInitialized) return

            // Call the helper to load playlist
            bindPlaylistToPlayer(keepCurrentPlayback = false)

            // Send all values to renderer updating it, will be used for matrix calc.
            refreshRenderer()

            // WAIT for the GL Surface, then attach
            playerSetupJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                var finalSurface: android.view.Surface? = null

                if (!useFallbackSurface) {
                    try {
                        // Give it 3.0 seconds to provide a surface, otherwise timeout
                        finalSurface = kotlinx.coroutines.withTimeoutOrNull(3000L) {
                            renderer?.waitForVideoSurface()
                        }

                        // If it returns null, the timeout was hit
                        if (finalSurface == null) {
                            FileLogger.w(TAG, "GL Surface timeout (1.5s)! OS blocked it. Triggering fallback.")
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
                if (!isPlayerInitialized || surfaceHolder == null || surfaceHolder?.surface == null || !surfaceHolder?.surface?.isValid!!) {
                    FileLogger.w(TAG, "Engine destroyed or surface invalid before player setup completed. Aborting.")
                    return@launch
                }

                if (finalSurface != null) {

                    // Apply starting position
                    if (currentPlaybackMode == PlaybackMode.LOOP_ALL || currentPlaybackMode == PlaybackMode.SHUFFLE) {
                        wallpaperPlayer.seekTo(wallpaperPlayer.currentMediaItemIndex, playheadTime)
                    } else {
                        wallpaperPlayer.seekTo(playheadTime)
                    }

                    wallpaperPlayer.setVideoSurface(finalSurface)
                    wallpaperPlayer.prepare()

                    // DO NOT call play() here.
                    // Just sync the playWhenReady flag with the current visibility state.
                    val shouldPlay = if (wallpaperPlayer.playWhenReady) true else isVisible

                    FileLogger.i(TAG, "Setup complete. isVisible: $isVisible, playWhenReady: ${wallpaperPlayer.playWhenReady}, shouldPlay: $shouldPlay")

                    wallpaperPlayer.playWhenReady = shouldPlay
                }
            }
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

            if (isPlayerInitialized) {
                playheadTime = wallpaperPlayer.currentPosition
            }
            wallpaperPlayer.release()
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
            val uriString = prefs.getActiveVideoUri()

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
            prefs.saveActiveVideoUri("")

            // Kill the player and DO NOT restart it.
            releasePlayer()
        }


        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            FileLogger.i(TAG, "onSurfaceCreated")
            this.surfaceHolder = holder

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

            if (!useFallbackSurface) {
                renderer?.onSurfaceChanged(width, height)
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            FileLogger.i(TAG, "onSurfaceDestroyed")
            visibilityJob?.cancel()
            playbackWatchdog.stop()
            releasePlayer()
            releaseRenderer()
            this.surfaceHolder = null
        }

        override fun onDestroy() {
            super.onDestroy()
            FileLogger.i(TAG, "Engine onDestroy")
            visibilityJob?.cancel()
            playbackWatchdog.stop() // Kill the playback watchdog
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
                    val currentUriOnDisk = getMediaUri().toString()
                    val isSurfaceDead = surfaceHolder?.surface?.isValid != true
                    var wasJustInitialized = false

                    // Check if we need to (re)initialize
                    if (currentUriOnDisk != loadedVideoUriString || !isPlayerInitialized || isSurfaceDead) {
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

                    // Handle Timeline
                    // We only apply timeline manipulations if the player wasn't just freshly initialized.
                    if (!wasJustInitialized) {
                        val startTimePref = prefs.getStartTime()
                        when (startTimePref) {
                            StartTime.RESUME -> {
                                if (currentPlaybackMode == PlaybackMode.ONE_SHOT && hasPlaybackCompleted && !isPreview()) {
                                    playheadTime = 0L
                                    wallpaperPlayer.seekToDefaultPosition()
                                    hasPlaybackCompleted = false
                                }
                            }
                            StartTime.RESTART -> {
                                playheadTime = 0L
                                wallpaperPlayer.seekToDefaultPosition()
                                hasPlaybackCompleted = false
                            }
                            StartTime.RANDOM -> {
                                val duration = wallpaperPlayer.duration
                                if (duration > 0 && duration != C.TIME_UNSET) {
                                    val randomPos = Random.nextLong(0, duration)
                                    playheadTime = randomPos
                                    wallpaperPlayer.seekTo(wallpaperPlayer.currentMediaItemIndex, randomPos)
                                } else {
                                    playheadTime = 0L
                                    wallpaperPlayer.seekToDefaultPosition()
                                }
                                hasPlaybackCompleted = false
                            }
                        }
                    }

                    // Always refresh the renderer settings before resuming playback in case
                    // the user edited them in the UI while the wallpaper was hidden.
                    refreshRenderer()

                    // The "Play" Command: Just set the flag.
                    // ExoPlayer will start natively as soon as it reaches STATE_READY.
                    wallpaperPlayer.playWhenReady = true

                    wallpaperPlayer.getPlayerInstance()?.let { playerInstance ->
                        playbackWatchdog.start(playerInstance, renderer) // Monitor for playback running
                    }

                } else {
                    playbackWatchdog.stop()

                    if (isPreview) {
                        FileLogger.i(TAG, "Preview hidden. Releasing player to save decoders.")
                        releasePlayer()
                    } else { // It's the live wallpaper
                        wallpaperPlayer.pause()
                        wallpaperPlayer.playWhenReady = false
                        if (isPlayerInitialized) {
                            playheadTime = wallpaperPlayer.currentPosition
                        }
                    }
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            FileLogger.i(TAG, "Engine onCreate")

            // Allow the OS to send MotionEvents to this engine
            setTouchEventsEnabled(true)

            // Turn on filter to start listening
            val intentFilter = IntentFilter().apply {
                addAction(ACTION_VIDEO_URI_CHANGED)
                addAction(ACTION_PLAYBACK_MODE_CHANGED)
                addAction(ACTION_STATUS_BAR_COLOR_CHANGED)
                addAction(ACTION_PLAYLIST_REORDERED)
                addAction(ACTION_VIDEO_SETTINGS_CHANGED)
                addAction(Intent.ACTION_USER_UNLOCKED)
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


        private fun getCachedColorsForActiveVideo(): WallpaperColors? {
            val activeUriString = prefs.getActiveVideoUri() ?: return null
            val activeUri = activeUriString.toUri()
            val fileName = activeUri.lastPathSegment ?: return null

            val settings = prefs.getVideoSettings(fileName)
            val primaryColorInt = settings.primaryColor ?: return null
            val secondaryColorInt = settings.secondaryColor
            val tertiaryColorInt = settings.tertiaryColor
            val colorHints = settings.colorHints ?: 0

            val primaryColor = Color.valueOf(primaryColorInt)
            val secondaryColor = secondaryColorInt?.let { Color.valueOf(it) }
            val tertiaryColor = tertiaryColorInt?.let { Color.valueOf(it) }

            FileLogger.i(javaClass.simpleName, "Cached colors: $primaryColor, $secondaryColor, $tertiaryColor")

            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                WallpaperColors(primaryColor, secondaryColor, tertiaryColor, colorHints)
            } else {
                WallpaperColors(primaryColor, secondaryColor, tertiaryColor)
            }
        }

        override fun onComputeColors(): WallpaperColors? {
            val mode = prefs.getStatusBarColor()
            val cachedColors = getCachedColorsForActiveVideo() // Retrieve from model

            // AUTO MODE: Best case scenario.
            // Give the OS the real video colors and let it figure out the contrast.
            if (mode == StatusBarColor.AUTO) {
                return cachedColors ?: super.onComputeColors()
            }

            val isLightText = (mode == StatusBarColor.LIGHT)

            // SAMSUNG CASE
            // Samsung ignores hints and averages colors. If a Samsung user forces a
            // status bar color, we use the old trick (sacrificing Material You theming).
            val isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

            if (isSamsung) {
                val baseColor = if (isLightText) Color.BLACK else Color.WHITE
                val colorObj = Color.valueOf(baseColor)

                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val hints = if (!isLightText) WallpaperColors.HINT_SUPPORTS_DARK_TEXT else 0
                    // TRICK: Pass identical colors to prevent Samsung from mixing
                    WallpaperColors(colorObj, colorObj, colorObj, hints)
                } else {
                    WallpaperColors(colorObj, colorObj, colorObj)
                }
            }

            // STANDARD MODERN ANDROID (API 31+)
            // Keep the beautiful real colors for Material You, but forcibly inject or
            // remove the Dark Text hint based on the user's setting.
            if (cachedColors != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                var hints = cachedColors.colorHints
                hints = if (!isLightText) {
                    hints or WallpaperColors.HINT_SUPPORTS_DARK_TEXT // Force Black Icons
                } else {
                    hints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT.inv() // Force White Icons
                }

                return WallpaperColors(
                    cachedColors.primaryColor,
                    cachedColors.secondaryColor,
                    cachedColors.tertiaryColor,
                    hints
                )
            }

            // Fallback for standard Android APIs 27-30 (which don't support explicit hints)
            return cachedColors ?: super.onComputeColors()
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
                action == ACTION_VIDEO_SETTINGS_CHANGED ||
                action == "android.wallpaper.reapply") {

                FileLogger.i(TAG, "Command received -> Re-initializing player.")
                // Full reset for major changes
                initializePlayer()

            } else if (action == ACTION_PLAYLIST_REORDERED) {

                FileLogger.i(TAG, "Command received -> Playlist reordered. Syncing silently.")

                if (isPlayerInitialized) {
                    bindPlaylistToPlayer(keepCurrentPlayback = true)
                }

            }

            return null
        }
    }
}