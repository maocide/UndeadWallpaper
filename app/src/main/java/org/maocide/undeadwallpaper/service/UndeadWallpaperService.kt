package org.maocide.undeadwallpaper.service

import org.maocide.undeadwallpaper.BuildConfig

import org.maocide.undeadwallpaper.data.PlaylistManager
import org.maocide.undeadwallpaper.data.PreferencesManager
import org.maocide.undeadwallpaper.model.PlaybackMode
import org.maocide.undeadwallpaper.model.ScalingMode
import org.maocide.undeadwallpaper.model.StartTime
import org.maocide.undeadwallpaper.model.StatusBarColor
import org.maocide.undeadwallpaper.utils.FileLogger
import org.maocide.undeadwallpaper.utils.HapticHelper

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maocide.undeadwallpaper.event.WallpaperEvent
import org.maocide.undeadwallpaper.event.WallpaperEventBus
import org.maocide.undeadwallpaper.input.WallpaperGestureManager
import org.maocide.undeadwallpaper.model.GestureType
import org.maocide.undeadwallpaper.model.VideoSettings
import org.maocide.undeadwallpaper.model.WallpaperAction


import kotlin.math.log
import kotlin.random.Random


class UndeadWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return MyWallpaperEngine()
    }


    private inner class MyWallpaperEngine : Engine(), WallpaperPlayerListener {

        private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        // Lazy instantiation for performance reuse
        private val prefs by lazy { PreferencesManager(baseContext) }
        private val playlistManager by lazy { PlaylistManager(baseContext, prefs) }
        private lateinit var currentScalingMode: ScalingMode

        private val wallpaperPlayer = WallpaperPlayer(baseContext, this)
        private val fsm = EngineFSM(baseContext)

        private fun processEvent(event: EngineEvent) {
            val effect = fsm.transition(event)
            when (effect) {
                is EngineEffect.StartInitialization -> initializePlayer(effect.lumaScale)
                is EngineEffect.ApplyPlayWhenReady -> wallpaperPlayer.setPlayWhenReadyAsync(effect.play)
                is EngineEffect.StartRelease -> releasePlayer()
                is EngineEffect.None -> { /* no-op */
                }
            }
        }

        private var surfaceHolder: SurfaceHolder? = null
        private var playheadTime: Long = 0L
        private val TAG: String = javaClass.simpleName
        private var isScalingModeSet = false
        private var useFallbackSurface = false
        private var activeLumaScale: Float = 1.0f

        private var currentPlaybackMode = PlaybackMode.LOOP

        private var speed: Float = 1f

        private var loadedVideoUriString = ""
        private var hasPlaybackCompleted = false

        private var renderer: GLVideoRenderer? = null

        // Hardware Info

        private var playerSetupJob: kotlinx.coroutines.Job? = null

        private val playbackWatchdog = PlaybackWatchdog(wallpaperPlayer.playbackDispatcher) {
            FileLogger.e(TAG, "Watchdog: STALL CONFIRMED. Restarting player.")
            processEvent(EngineEvent.InitializeRequested) // Force restart
        }

        // Touch Interaction State
        private var isCurrentlyListeningForTouch = true // Engine onCreate defaults to true
        private val parallaX: Float = 0.5f - (Random.nextFloat() * 0.30f - 0.15f)

        // Initialize gesture manager
        private val gestureManager = WallpaperGestureManager(
            context = baseContext,
            prefs = prefs,
            onActionTriggered = { action ->
                executeGestureAction(action)
            }
        )

        /**
         * Resets the internal playback timeline variables.
         * @param seekPlayerToStart If true, also forces the active ExoPlayer instance to rewind to the beginning.
         */
        private fun resetPlaybackTimeline(seekPlayerToStart: Boolean = false) {
            playheadTime = 0L
            hasPlaybackCompleted = false
            if (seekPlayerToStart) {
                wallpaperPlayer.seekToDefaultPositionAsync()
            }
        }

        /**
         * Applies VideoSettings that don't require a GL Renderer Update. Volume, speed...
         * @param settings VideoSettings object from which to apply non-visual settings
         */
        private fun applyNonVisualSettings(settings: VideoSettings) {
            wallpaperPlayer.applyNonVisualSettingsAsync(settings.getPerceivedVolume(), settings.speed)
        }

        /**
         * Retrieves VideoSettings for the provided uri String.
         * @param uriString the uri string of the video to get settings from.
         */
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
            val tripleTapAction = prefs.getActionForGesture(GestureType.TRIPLE_TAP)

            // EDGE CASE: If the video is manually paused, but the user just removed
            // the PLAY_PAUSE action from all gestures, we must unpause it so they don't get stuck
            val canPause =
                doubleTapAction == WallpaperAction.PLAY_PAUSE || tripleTapAction == WallpaperAction.PLAY_PAUSE

            val currentState = fsm.state as? EngineState.Ready
            if (currentState?.isManuallyPaused == true && !canPause) {
                processEvent(EngineEvent.UserTogglePause)
                FileLogger.i(TAG, "Play/Pause action unbound. Clearing manual pause state.")
            }

            val wantsTouchEvents = (doubleTapAction != WallpaperAction.NONE || tripleTapAction != WallpaperAction.NONE)

            // ONLY tell the OS to change the touch state if it's different from the current state.
            if (isCurrentlyListeningForTouch != wantsTouchEvents) {
                // BUGFIX: We MUST update our local state flag BEFORE calling setTouchEventsEnabled.
                // On some Android devices, setTouchEventsEnabled triggers a synchronous updateSurface()
                // which instantly fires onVisibilityChanged -> initializePlayer -> updateTouchListeningState.
                // If we don't update the flag first, we enter an infinite recursion loop .
                isCurrentlyListeningForTouch = wantsTouchEvents
                setTouchEventsEnabled(wantsTouchEvents)
                FileLogger.i(TAG, "Touch events enabled changed to: $wantsTouchEvents")
            }
        }

        private var lastActionExecutionTimeMs = 0L
        private val ACTION_DEBOUNCE_MS = 700L

        private fun executeGestureAction(action: WallpaperAction) {
            if (action == WallpaperAction.NONE) return

            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastActionExecutionTimeMs < ACTION_DEBOUNCE_MS) {
                FileLogger.d(TAG, "Action debounce active. Ignoring gesture.")
                return
            }
            lastActionExecutionTimeMs = now

            when (action) {
                WallpaperAction.NONE -> return // Already handled above, but needed for exhaustiveness
                WallpaperAction.SKIP_NEXT -> {
                    if (fsm.state !is EngineState.Ready) return

                    serviceScope.launch {
                        val playlistUris = playlistManager.getPlaylistUris()
                        if (playlistUris.size <= 1) {
                            FileLogger.d(
                                TAG,
                                "Gesture SKIP_NEXT ignored: active page only has ${playlistUris.size} video(s)."
                            )
                            return@launch
                        }

                        // Provide Haptic Feedback for successful gesture
                        HapticHelper.performGestureFeedback(this@UndeadWallpaperService)

                        // UX Rule: If they skip, they want to see the new video. Unpause it.
                        val currentState = fsm.state as? EngineState.Ready
                        if (currentState?.isManuallyPaused == true) {
                            processEvent(EngineEvent.UserTogglePause)
                        }
                        skipNextVideo(isManualSkip = true)
                    }
                }

                WallpaperAction.PLAY_PAUSE -> {
                    if (fsm.state !is EngineState.Ready) return

                    // Provide Haptic Feedback for successful gesture
                    HapticHelper.performGestureFeedback(this@UndeadWallpaperService)

                    // ONE_SHOT CASE: If they "Play" a finished video, restart it!
                    if (currentPlaybackMode == PlaybackMode.ONE_SHOT && hasPlaybackCompleted) {
                        FileLogger.i(TAG, "User triggered Play on a finished ONE_SHOT video. Replaying.")
                        resetPlaybackTimeline(seekPlayerToStart = true)

                        val currentState = fsm.state as EngineState.Ready
                        if (currentState.isManuallyPaused) {
                            processEvent(EngineEvent.UserTogglePause)
                        }
                    } else {
                        // Normal toggle behavior
                        processEvent(EngineEvent.UserTogglePause)
                    }
                }
            }
        }

        @OptIn(UnstableApi::class)
        private suspend fun bindPlaylistToPlayer(keepCurrentPlayback: Boolean) {
            val dataSourceFactory = DefaultDataSource.Factory(baseContext)
            val mediaSourceFactory = ProgressiveMediaSource.Factory(dataSourceFactory)

            val mediaUri = getMediaUri() ?: return

            val playlistUris = playlistManager.getPlaylistUris()

            // Hybrid Gapless Batching:
            // Fetch the chunk of consecutive URIs that share identical visual settings.
            val chunkUris = playlistManager.getGaplessChunkUris(loadedVideoUriString, currentPlaybackMode, playlistUris)

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
            val canLoopAll = currentPlaybackMode == PlaybackMode.LOOP_ALL
                    && playlistUris.isNotEmpty() && chunkUris.size == playlistUris.size

            // Single-Item Shuffle Optimization:
            // With only 1 video in the playlist, shuffling has no sequence to regenerate.
            // We can safely loop it seamlessly instead of hitting STATE_ENDED and re-initializing the decoder.
            // NOTE: Multi-video SHUFFLE (> 1) MUST hit STATE_ENDED with REPEAT_MODE_OFF to regenerate a newly randomized sequence loop.
            val canLoopSingleVideoShuffle = currentPlaybackMode == PlaybackMode.SHUFFLE
                    && playlistUris.size == 1 && chunkUris.isNotEmpty()

            if (canLoopAll || canLoopSingleVideoShuffle) {
                wallpaperPlayer.setRepeatModeAsync(Player.REPEAT_MODE_ALL)
            } else if (currentPlaybackMode == PlaybackMode.LOOP_ALL || currentPlaybackMode == PlaybackMode.SHUFFLE) {
                wallpaperPlayer.setRepeatModeAsync(Player.REPEAT_MODE_OFF)
            }

            wallpaperPlayer.setMediaSourcesAsync(mediaSources)

            val pos = if (keepCurrentPlayback) {
                wallpaperPlayer.getCurrentPositionSuspend()
            } else {
                playheadTime
            }
            wallpaperPlayer.seekToAsync(0, pos)

            // If the player died from a background buffer error on the deleted file,
            // setMediaSources won't automatically restart it. We MUST call prepare!
            // (If it's already playing, prepare() is a safe no-op).
            wallpaperPlayer.prepareAsync()
        }


        // Listen for internal configuration & playback events securely in-process
        private fun observeWallpaperEvents() {
            serviceScope.launch {
                WallpaperEventBus.events.collect { event ->
                    when (event) {
                        is WallpaperEvent.VideoUriChanged -> {
                            FileLogger.i(TAG, "Event received: Video uri changed, full re-initialization requested.")
                            val currentState = fsm.state as? EngineState.Ready
                            if (currentState?.isManuallyPaused == true) {
                                processEvent(EngineEvent.UserTogglePause)
                            }
                            resetPlaybackTimeline()
                            processEvent(EngineEvent.InitializeRequested) // force Reinit
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                notifyColorsChanged()
                            }
                        }

                        is WallpaperEvent.PlaybackModeChanged -> {
                            FileLogger.i(TAG, "Event received: Playback mode change, full re-initialization requested.")
                            val currentState = fsm.state as? EngineState.Ready
                            if (currentState?.isManuallyPaused == true) {
                                processEvent(EngineEvent.UserTogglePause)
                            }
                            resetPlaybackTimeline()
                            processEvent(EngineEvent.InitializeRequested) // force Reinit
                        }

                        is WallpaperEvent.StatusBarColorChanged -> {
                            FileLogger.i(TAG, "Event received: Color changed -> Update just sys colors.")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                notifyColorsChanged()
                            }
                        }

                        is WallpaperEvent.PlaylistReordered -> {
                            FileLogger.i(TAG, "Event received: Playlist reordered. Syncing ExoPlayer timeline.")
                            if (fsm.state is EngineState.Ready) {
                                bindPlaylistToPlayer(keepCurrentPlayback = true)
                            }
                        }

                        is WallpaperEvent.VideoSettingsChanged -> {
                            FileLogger.i(
                                TAG,
                                "Event received: Video settings changed for ${event.fileName}"
                            )
                            if (currentPlaybackMode == PlaybackMode.LOOP_ALL || currentPlaybackMode == PlaybackMode.SHUFFLE) {
                                val currentState = fsm.state as? EngineState.Ready
                                if (currentState?.isManuallyPaused == true) {
                                    processEvent(EngineEvent.UserTogglePause)
                                }
                                resetPlaybackTimeline()
                                processEvent(EngineEvent.InitializeRequested) // force Reinit
                            } else {
                                val activeFileName = loadedVideoUriString.toUri().lastPathSegment
                                if (activeFileName == event.fileName) {
                                    val activeSettings = prefs.getVideoSettings(event.fileName)
                                    refreshRenderer()
                                    applyNonVisualSettings(activeSettings)
                                    renderer?.requestRender()
                                }
                            }
                        }

                        is WallpaperEvent.TouchControlsChanged -> {
                            FileLogger.i(TAG, "Event received: Touch controls changed.")
                            updateTouchListeningState()
                        }

                        is WallpaperEvent.ParallaxChanged -> {
                            FileLogger.i(TAG, "Event received: Parallax changed.")
                            if (!prefs.isParallaxEnabled()) {
                                renderer?.setParallaxOffset(0f)
                            }
                        }
                    }
                }
            }
        }

        // The receiver that listens exclusively for OS-level Direct Boot unlock
        private val userUnlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_USER_UNLOCKED) {
                    FileLogger.i(
                        TAG,
                        "Broadcast received: User Unlocked. Initializing player safely and restoring colors."
                    )
                    processEvent(EngineEvent.InitializeRequested)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        notifyColorsChanged()
                    }
                }
            }
        }

        /**
         * Skips current video and moves to next using chunks to buffer video sharing settings
         * @param isManualSkip when true, means that the user initiated the action and
         * matrix changes are not applied on call, but applied later on first frame of the new video.
         */
        private fun skipNextVideo(isManualSkip: Boolean) {
            if (fsm.state !is EngineState.Ready) return

            serviceScope.launch {
                val nextUriString = playlistManager.getNextUri(loadedVideoUriString, currentPlaybackMode)

                if (nextUriString == null) {
                    FileLogger.w(TAG, "Transition aborted: Next URI is null.")
                    return@launch
                }

                FileLogger.i(TAG, "Transitioning across boundary to: $nextUriString (Manual: $isManualSkip)")

                // ONE_SHOT / LOOP Reset (These still need a hard re-init to reset their single-video state)
                if (currentPlaybackMode == PlaybackMode.ONE_SHOT || currentPlaybackMode == PlaybackMode.LOOP) {
                    loadedVideoUriString = nextUriString
                    prefs.saveActiveVideoUri(nextUriString)

                    FileLogger.i(TAG, "Single-video mode detected. Performing hard re-initialization for skip.")
                    resetPlaybackTimeline()
                    processEvent(EngineEvent.InitializeRequested)
                    return@launch
                }

                // Now update the state
                updateActiveVideoState(nextUriString)
                resetPlaybackTimeline()

                // Lock out manual/parallax render requests during transition across boundary
                renderer?.setIsTransitioning(true)

                // ALWAYS Hot-swap via Chunking!
                bindPlaylistToPlayer(keepCurrentPlayback = false)

                // Stage the new video's matrix transforms BEFORE the first frame arrives
                if (!isManualSkip) { // Skip if done by user
                    refreshRenderer()
                }

                val activeSettings = getSettingsForUri(loadedVideoUriString)
                applyNonVisualSettings(activeSettings)

                wallpaperPlayer.prepareAsync()
                wallpaperPlayer.setPlayWhenReadyAsync(if (isManualSkip) isVisible else true)
            }
        }

        override fun onTouchEvent(event: MotionEvent?) {
            super.onTouchEvent(event)

            // If we actively decided we don't want touches, ignore any ghost touches
            // the Android OS accidentally forwards to us anyway.
            // Most likely needed too for VIVO and CHINESE phones.
            if (!isCurrentlyListeningForTouch) return

            gestureManager.onTouchEvent(event)
        }

        /**
         * Uses the VideoSettings of the current uri to recompute matrix/uniforms on GL Renderer
         * It is done here to be as late and synced to playback as possible.
         */
        private fun refreshRenderer() {
            if (loadedVideoUriString.isBlank()) return

            val activeSettings = getSettingsForUri(loadedVideoUriString)

            val w = activeSettings.width ?: 0
            val h = activeSettings.height ?: 0
            if (w > 0 && h > 0) {
                renderer?.setVideoSize(w, h)
            }

            currentScalingMode = activeSettings.scalingMode
            renderer?.setScalingMode(currentScalingMode)
            renderer?.setTransforms(
                x = activeSettings.positionX,
                y = activeSettings.positionY,
                zoom = activeSettings.zoom,
                rotation = activeSettings.rotation,
                flipHorizontal = activeSettings.flipHorizontal,
                flipVertical = activeSettings.flipVertical
            )

            val lumaOffset = -100.0f * (1.0f - activeLumaScale)
            renderer?.setBrightness((activeSettings.brightness * activeLumaScale) + lumaOffset)
        }

        // WallpaperPlayerListener implementations
        override fun onPlayerError(error: PlaybackException) {
            renderer?.setIsTransitioning(false)

            // Check if the file vanished (happens during UUID migration).
            // We suppress the toast because VideoFileManager will instantly emit
            // WallpaperEvent.PlaylistReordered to force a seamless reload.
            if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) {
                FileLogger.i(TAG, "Suppressed IO_FILE_NOT_FOUND error (likely a UUID migration in progress).")
                return
            }

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
                    processEvent(EngineEvent.InitializeRequested)
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
                    FileLogger.i(
                        TAG,
                        "Valid video size detected: ${width}x${height}. Setting scaling mode ONCE for fallback surface."
                    )

                    val videoAspectRatio = width.toFloat() / height.toFloat()
                    val isHorizontalVideo = videoAspectRatio > 1.0

                    val mode = if (isHorizontalVideo) {
                        VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                    } else {
                        VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                    }
                    wallpaperPlayer.setVideoScalingModeAsync(mode)

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
                        wallpaperPlayer.pauseAsync()
                    } else if (currentPlaybackMode == PlaybackMode.LOOP_ALL || currentPlaybackMode == PlaybackMode.SHUFFLE) {
                        // Same flow as skip, inside the playback mode is handled
                        skipNextVideo(isManualSkip = false)
                    }
                }

                Player.STATE_READY -> {
                    if (currentPlaybackMode == PlaybackMode.ONE_SHOT && hasPlaybackCompleted) {
                        wallpaperPlayer.pauseAsync()
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

            // Sync GL renderer matrix and uniforms to the incoming chunk item without forcing a draw
            refreshRenderer()

            val activeSettings = getSettingsForUri(loadedVideoUriString)
            applyNonVisualSettings(activeSettings)
        }

        override fun onRenderedFirstFrame() {
            FileLogger.i(TAG, "SUCCESS: onRenderedFirstFrame called. Decoder actually pushed a frame to the screen!")
            renderer?.setIsTransitioning(false)
        }

        /**
         * Handles parallax by processing launcher events.
         */
        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float,
            xOffsetStep: Float, yOffsetStep: Float,
            xPixelOffset: Int, yPixelOffset: Int
        ) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset)

            if (!prefs.isParallaxEnabled()) return

            val distanceFromCenter = kotlin.math.abs(xOffset - 0.5f)

            // Multiplier 1.0 at center, 0.0 at edges
            val safeMultiplier = 1.0f - (distanceFromCenter * 2.0f)

            val rOffset = parallaX - 0.5f

            // Fade offset near boundaries
            val safeCenter = 0.5f + (rOffset * safeMultiplier)

            val shiftX = (safeCenter - xOffset) * prefs.getParallaxStrength()
            renderer?.setParallaxOffset(shiftX)
        }

        @OptIn(UnstableApi::class)
        private fun initializePlayer(lumaScale: Float) {
            this.activeLumaScale = lumaScale

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

            if (wallpaperPlayer.getPlayerInstance() != null) {
                releasePlayer()
            }

            // Allow the OS to send MotionEvents to this engine
            updateTouchListeningState()

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

            if (mediaUri == null) {
                FileLogger.e(TAG, "Media URI is null, cannot play video.")
                return
            }

            val fileName = mediaUri.lastPathSegment ?: ""
            val activeSettings = prefs.getVideoSettings(fileName)
            val initialVolume = activeSettings.getPerceivedVolume()
            speed = activeSettings.speed

            FileLogger.i(TAG, "Initializing ExoPlayer...")
            val isPreview = this.isPreview

            playerSetupJob?.cancel()

            playerSetupJob = serviceScope.launch {
                // To prevent race conditions on the playbackDispatcher, we MUST wait for the old 
                // player to be fully released before we initialize the new one.
                if (wallpaperPlayer.getPlayerInstance() != null) {
                    playheadTime = wallpaperPlayer.getCurrentPositionSuspend()
                    wallpaperPlayer.releaseAsync().join()
                }
                isScalingModeSet = false

                wallpaperPlayer.initializeAsync(null, initialVolume, speed, currentPlaybackMode).join()
                if (wallpaperPlayer.getPlayerInstance() == null) return@launch

                // Call the helper to load playlist
                bindPlaylistToPlayer(keepCurrentPlayback = false)
                refreshRenderer()

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
                        releaseRenderer()
                        processEvent(EngineEvent.InitializeRequested) // force Reinit
                        return@launch
                    }
                } else {
                    finalSurface = surfaceHolder?.surface
                }

                // If this job was cancelled, video switch or anything, STOP.
                if (!isActive) return@launch

                // Check if player is alive, surface is valid, surface is ready.
                if (fsm.state !is EngineState.Initializing || surfaceHolder == null || surfaceHolder?.surface == null || !surfaceHolder?.surface?.isValid!!) {
                    FileLogger.w(TAG, "Engine destroyed or surface invalid before player setup completed. Aborting.")
                    return@launch
                }

                if (finalSurface != null) {
                    if (finalSurface.isValid) {
                        wallpaperPlayer.setVideoSurfaceAsync(finalSurface)
                        wallpaperPlayer.prepareAsync()

                        // DO NOT call play() here manually.
                        // We tell the FSM that initialization is complete.
                        processEvent(EngineEvent.InitializationComplete)

                        // We then feed the current visibility state to the FSM so it can 
                        // compute the correct playWhenReady flag based on its rules!
                        processEvent(EngineEvent.VisibilityChanged(isVisible))

                    } else {
                        FileLogger.e(
                            TAG,
                            "Surface became invalid before player setup finished. Aborting setVideoSurface."
                        )
                    }
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
            renderer?.setIsTransitioning(false)

            // Stop any startup jobs
            playerSetupJob?.cancel()

            serviceScope.launch {
                // Fetch the position safely on the background thread before release
                if (wallpaperPlayer.getPlayerInstance() != null) {
                    playheadTime = wallpaperPlayer.getCurrentPositionSuspend()
                }

                wallpaperPlayer.releaseAsync().join()
            }

            isScalingModeSet = false
        }

        /**
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
            processEvent(EngineEvent.HardwareFailure(reason))
        }


        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            FileLogger.i(TAG, "onSurfaceCreated")
            this.surfaceHolder = holder

            if (renderer != null) {
                FileLogger.w(
                    TAG,
                    "INFO: Non-standard lifecycle detected! onSurfaceCreated called without onSurfaceDestroyed. Renderer already exists."
                )
                renderer?.setIsPreview(this.isPreview)
                return
            }

            if (!useFallbackSurface) {
                renderer = GLVideoRenderer(applicationContext) {
                    FileLogger.e(TAG, "GLVideoRenderer reported Context/Surface Lost! Forcing Engine Restart.")
                    processEvent(EngineEvent.HardwareFailure("GL Context Lost"))
                    processEvent(EngineEvent.InitializeRequested)
                }
                renderer?.setIsPreview(this.isPreview)
                renderer?.onSurfaceCreated(holder)
            } else {
                FileLogger.i(TAG, "Using fallback surface, skipping GL Renderer creation")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                notifyColorsChanged()
            }

            processEvent(EngineEvent.InitializeRequested)
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
            playbackWatchdog.stop()
            processEvent(EngineEvent.ReleaseRequested)

            // Synchronously detach surface from ExoPlayer before destroying GL renderer/context
            try {
                runBlocking {
                    withTimeoutOrNull(250) {
                        wallpaperPlayer.clearVideoSurfaceSuspend()
                    }
                }
            } catch (e: Exception) {
                FileLogger.w(TAG, "Timeout or error clearing video surface on surface destroyed", e)
            }

            releaseRenderer()
            this.surfaceHolder = null
        }

        override fun onDestroy() {
            super.onDestroy()
            FileLogger.i(TAG, "Engine onDestroy")
            playbackWatchdog.stop() // Kill the playback watchdog
            processEvent(EngineEvent.ReleaseRequested)

            try {
                runBlocking {
                    withTimeoutOrNull(250) {
                        wallpaperPlayer.clearVideoSurfaceSuspend()
                    }
                }
            } catch (e: Exception) {
                FileLogger.w(TAG, "Timeout or error clearing video surface on engine destroy", e)
            }

            releaseRenderer()
            gestureManager.destroy()
            try {
                unregisterReceiver(userUnlockReceiver)
            } catch (_: IllegalArgumentException) {
                FileLogger.w(TAG, "Receiver was not registered, skipping unregister.")
            }
            serviceScope.cancel()
        }


        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)

            FileLogger.i(
                TAG,
                "onVisibilityChanged: visible = $visible isPreview = $isPreview, playbackMode = $currentPlaybackMode"
            )

            if (visible) {
                renderer?.setIsPreview(this.isPreview)
                val currentUriOnDisk = getMediaUri().toString()
                val isSurfaceDead = surfaceHolder?.surface?.isValid != true
                var wasJustInitialized = false

                // Check if player is initialized AND if the internal thread survived the sleep
                val isThreadDead = fsm.state is EngineState.Ready && !wallpaperPlayer.isPlaybackThreadAlive

                // We only force a restart if we are in Ready state and something is corrupted.
                val forceRestart =
                    fsm.state is EngineState.Ready && (currentUriOnDisk != loadedVideoUriString || isSurfaceDead || isThreadDead)

                if (forceRestart) {
                    if (isThreadDead) {
                        FileLogger.e(TAG, "WakeUp Check: ExoPlayer internal thread was killed by OS. Forcing restart.")
                    } else if (currentUriOnDisk != loadedVideoUriString) {
                        FileLogger.i(TAG, "WakeUp Check: URI changed while sleeping! Reloading.")
                    } else if (isSurfaceDead) {
                        FileLogger.w(TAG, "WakeUp Check: Surface died silently. Forcing restart.")
                    }

                    // If the user wants a restart, reset playhead BEFORE init
                    if (prefs.getStartTime() == StartTime.RESTART) {
                        resetPlaybackTimeline()
                    }

                    processEvent(EngineEvent.VisibilityChanged(isVisible = true, forceRestart = true))
                    wasJustInitialized = true
                }

                // Handle Timeline
                // We only apply timeline manipulations if the player wasn't just freshly initialized.
                if (!wasJustInitialized) {
                    val startTimePref = prefs.getStartTime()
                    when (startTimePref) {
                        StartTime.RESUME -> {
                            if (currentPlaybackMode == PlaybackMode.ONE_SHOT && hasPlaybackCompleted && !isPreview()) {
                                resetPlaybackTimeline(seekPlayerToStart = true)
                            }
                        }

                        StartTime.RESTART -> {
                            resetPlaybackTimeline(seekPlayerToStart = true)
                        }

                        StartTime.RANDOM -> {
                            serviceScope.launch {
                                val duration = wallpaperPlayer.getDurationSuspend()
                                if (duration > 0 && duration != androidx.media3.common.C.TIME_UNSET) {
                                    val randomPos = kotlin.random.Random.nextLong(0, duration)
                                    playheadTime = randomPos
                                    wallpaperPlayer.seekToAsync(0, randomPos)
                                } else {
                                    resetPlaybackTimeline()
                                }
                                hasPlaybackCompleted = false
                            }
                        }
                    }
                }

                // Always refresh the renderer settings before resuming playback in case
                // the user edited them in the UI while the wallpaper was hidden.
                refreshRenderer()

                try {
                    // Tell FSM we are visible
                    if (!wasJustInitialized) {
                        processEvent(EngineEvent.VisibilityChanged(isVisible = true, forceRestart = false))
                    }

                    wallpaperPlayer.getPlayerInstance()?.let { playerInstance ->
                        playbackWatchdog.start(playerInstance, renderer) // Monitor for playback running
                    }
                } catch (e: IllegalStateException) {
                    FileLogger.e(
                        TAG,
                        "WakeUp Crash Prevented: ExoPlayer thread died silently during sleep. Forcing re-init."
                    )
                    processEvent(EngineEvent.InitializeRequested)
                }

            } else {
                playbackWatchdog.stop()
                gestureManager.destroy()
                if (isPreview) {
                    FileLogger.i(TAG, "Preview hidden. Releasing player to save decoders.")
                    processEvent(EngineEvent.ReleaseRequested)
                } else { // It's the live wallpaper
                    // EXPLICIT CANCELLATION HOOK: Abort any pending async initialization!
                    if (playerSetupJob?.isActive == true) {
                        FileLogger.w(TAG, "Screen turned off while player was initializing. Aborting and releasing.")
                        processEvent(EngineEvent.ReleaseRequested)
                    } else {
                        processEvent(EngineEvent.VisibilityChanged(isVisible = false))
                        if (fsm.state is EngineState.Ready) {
                            serviceScope.launch {
                                playheadTime = wallpaperPlayer.getCurrentPositionSuspend()
                            }
                        }
                    }
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            FileLogger.i(TAG, "Engine onCreate")
            if (prefs.isUndead()) {
                FileLogger.i(TAG, "Brains and parts detected...")
            }

            // Start listening for in-process UI configuration events
            observeWallpaperEvents()

            // OS-level Direct Boot broadcast
            val intentFilter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
            ContextCompat.registerReceiver(
                this@UndeadWallpaperService,
                userUnlockReceiver,
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

            if (action == "android.wallpaper.reapply") {

                FileLogger.i(TAG, "Command received -> Re-initializing player.")
                // Full reset for major changes
                processEvent(EngineEvent.InitializeRequested)

            }

            return null
        }
    }
}