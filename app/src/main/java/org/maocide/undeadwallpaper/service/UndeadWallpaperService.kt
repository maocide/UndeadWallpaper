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
import android.view.Display
import android.view.MotionEvent


import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.RawResourceDataSource
import org.maocide.undeadwallpaper.R
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.maocide.undeadwallpaper.event.WallpaperEvent
import org.maocide.undeadwallpaper.event.WallpaperEventBus
import org.maocide.undeadwallpaper.input.WallpaperGestureManager
import org.maocide.undeadwallpaper.model.VideoSettings
import org.maocide.undeadwallpaper.kinetic.KineticDeltas
import org.maocide.undeadwallpaper.kinetic.KineticTransformPipeline
import org.maocide.undeadwallpaper.model.KineticEffectType


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
                is EngineEffect.StartInitialization -> {
                    renderer?.setThanatosisZeroDecoder(false)
                    initializePlayer(effect.lumaScale)
                }

                is EngineEffect.ApplyPlayWhenReady -> wallpaperPlayer.setPlayWhenReadyAsync(effect.play)
                is EngineEffect.StartThanatosis -> handleThanatosis(effect.cause, effect.isZeroDecoder)
                is EngineEffect.StartRelease -> {
                    renderer?.setThanatosisZeroDecoder(false)
                    releasePlayer()
                }

                is EngineEffect.None -> { /* no-op */
                }
            }
        }

        @OptIn(UnstableApi::class)
        private fun handleThanatosis(cause: String, isZeroDecoder: Boolean) {
            FileLogger.e(TAG, "Entering THANATOSIS (Apparent Death). Cause: $cause, ZeroDecoder: $isZeroDecoder")

            playbackWatchdog.stop()
            gestureManager.destroy()

            if (isZeroDecoder || wallpaperPlayer.getPlayerInstance() == null) {
                // Type B: Hardware decoders starved or player instance missing.
                // Release WallpaperPlayer to return VPU decoders to the OS and render static WebP texture directly via GL.
                wallpaperPlayer.releaseAsync()
                renderer?.setThanatosisZeroDecoder(true)
            } else {
                // Type A: Media/container corruption with healthy VPU.
                // Reset renderer transforms so CRT static loop fills the screen cleanly.
                renderer?.setThanatosisZeroDecoder(false)
                renderer?.publishTransforms(
                    scalingMode = ScalingMode.FILL,
                    translateX = 0f,
                    translateY = 0f,
                    zoom = 1.0f,
                    rotation = 0f,
                    brightness = 0f,
                    contrast = 1.0f,
                    saturation = 1.0f,
                    flipHorizontal = false,
                    flipVertical = false
                )

                // Loop raw thanatosis_static.mp4 directly via ExoPlayer without disk allocation.
                val thanatosisUri = RawResourceDataSource.buildRawResourceUri(R.raw.thanatosis_static)
                loadedVideoUriString = thanatosisUri.toString()

                serviceScope.launch {
                    wallpaperPlayer.setPlayWhenReadyAsync(false)

                    val dataSourceFactory = DefaultDataSource.Factory(baseContext)
                    val mediaSourceFactory = ProgressiveMediaSource.Factory(dataSourceFactory)
                    val mediaItem = MediaItem.Builder()
                        .setUri(thanatosisUri)
                        .setMediaId(thanatosisUri.toString())
                        .build()
                    val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)

                    wallpaperPlayer.setRepeatModeAsync(Player.REPEAT_MODE_ALL)
                    wallpaperPlayer.setMediaSourcesAsync(listOf(mediaSource))
                    wallpaperPlayer.seekToAsync(0, 0L)
                    wallpaperPlayer.prepareAsync()
                    wallpaperPlayer.setPlayWhenReadyAsync(isVisible)
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

        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private val currentKineticDeltas = KineticDeltas()

        private val kineticPipeline = KineticTransformPipeline(
            onDeltasUpdated = { deltas ->
                currentKineticDeltas.copyFrom(deltas)
                if (renderer?.isTransitioning() != true) {
                    refreshRenderer()
                }
            },
            requestRender = {
                renderer?.requestRender()
            }
        )

        // Initialize gesture manager
        private val gestureManager = WallpaperGestureManager(
            context = baseContext,
            isDisplayActive = { isDefaultDisplay() && activeLumaScale > 0.0f },
            onKineticTouch = { x, y ->
                val triggered = kineticPipeline.onTouch(x, y, surfaceWidth, surfaceHeight)
                if (triggered && prefs.isKineticHapticsEnabled()) {
                    HapticHelper.performKineticImpactFeedback(applicationContext, kineticPipeline.effectType)
                }
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
            val wantsTouchEvents = kineticPipeline.isEnabled

            // ONLY tell the OS to change the touch state if it's different from the current state.
            if (isCurrentlyListeningForTouch != wantsTouchEvents) {
                // BUGFIX: We MUST update our local state flag BEFORE calling setTouchEventsEnabled.
                // On some Android devices, setTouchEventsEnabled triggers a synchronous updateSurface()
                // which instantly fires onVisibilityChanged -> initializePlayer -> updateTouchListeningState.
                // If we don't update the flag first, we enter an infinite recursion loop.
                isCurrentlyListeningForTouch = wantsTouchEvents
                setTouchEventsEnabled(wantsTouchEvents)
                FileLogger.i(TAG, "Touch events enabled changed to: $wantsTouchEvents")
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
                            resetPlaybackTimeline()
                            processEvent(EngineEvent.InitializeRequested) // force Reinit
                            notifyColorsChanged()
                        }

                        is WallpaperEvent.PlaybackModeChanged -> {
                            FileLogger.i(TAG, "Event received: Playback mode change, full re-initialization requested.")
                            resetPlaybackTimeline()
                            processEvent(EngineEvent.InitializeRequested) // force Reinit
                        }

                        is WallpaperEvent.StatusBarColorChanged -> {
                            FileLogger.i(TAG, "Event received: Color changed -> Update just sys colors.")
                            notifyColorsChanged()
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
                                resetPlaybackTimeline()
                                processEvent(EngineEvent.InitializeRequested) // force Reinit
                            } else {
                                val activeFileName = loadedVideoUriString.toUri().lastPathSegment
                                if (activeFileName == event.fileName) {
                                    refreshRenderer()
                                    renderer?.requestRender()
                                }
                            }
                        }

                        is WallpaperEvent.KineticSettingsChanged -> {
                            FileLogger.i(TAG, "Event received: Kinetic settings changed.")
                            kineticPipeline.effectType = prefs.getKineticEffectType()
                            kineticPipeline.intensity = prefs.getKineticIntensity()
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
                    notifyColorsChanged()
                }
            }
        }

        /**
         * Advances playlist to the next chunk of consecutive videos sharing visual settings.
         */
        private fun skipNextVideo() {
            if (fsm.state !is EngineState.Ready) return

            serviceScope.launch {
                val nextUriString = playlistManager.getNextUri(loadedVideoUriString, currentPlaybackMode)

                if (nextUriString == null) {
                    FileLogger.w(TAG, "Transition aborted: Next URI is null.")
                    return@launch
                }

                FileLogger.i(TAG, "Transitioning across boundary to: $nextUriString")

                // Lock out manual/parallax/kinetic render requests and stop active somatic animations
                // BEFORE updating loadedVideoUriString so outgoing frames never latch incoming settings
                renderer?.setIsTransitioning(true)
                kineticPipeline.reset()

                // Now update the state
                updateActiveVideoState(nextUriString)
                resetPlaybackTimeline()

                // ALWAYS Hot-swap via Chunking!
                bindPlaylistToPlayer(keepCurrentPlayback = false)

                // Stage the new video's matrix transforms BEFORE the first frame arrives
                refreshRenderer()

                wallpaperPlayer.prepareAsync()
                wallpaperPlayer.setPlayWhenReadyAsync(true)
            }
        }

        override fun onTouchEvent(event: MotionEvent?) {
            super.onTouchEvent(event)

            // If we actively decided we don't want touches, ignore any ghost touches
            // the Android OS accidentally forwards to us anyway.
            // Most likely needed too for VIVO and CHINESE phones.
            if (!isCurrentlyListeningForTouch || event == null) return

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

            val isConfined = isDefaultDisplay()
            val displayLumaModifier = if (isPreview && !isConfined) 0.0f else 1.0f
            val effectiveLumaScale = activeLumaScale * displayLumaModifier

            if (BuildConfig.DEBUG) {
                FileLogger.d(
                    TAG,
                    "DisplayConfinement: isPreview=$isPreview isConfined=$isConfined effectiveLumaScale=$effectiveLumaScale"
                )
            }

            currentScalingMode = activeSettings.scalingMode
            renderer?.publishTransforms(activeSettings, currentKineticDeltas, effectiveLumaScale)
        }

        // WallpaperPlayerListener implementations
        override fun onPlayerError(error: PlaybackException) {
            renderer?.setIsTransitioning(false)

            // Check if the file vanished (unlinked from disk, deleted, or path missing).
            // Do NOT silently return here — a missing video file must enter Thanatosis (apparent death)
            // instead of stalling ExoPlayer and freezing on the last decoded frame!
            if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) {
                FileLogger.w(TAG, "Active video unlinked or missing (IO_FILE_NOT_FOUND). Entering Thanatosis.")
                processEvent(EngineEvent.EnterThanatosis(error.errorCodeName, isZeroDecoder = false))
                return
            }

            // Handled mostly by WallpaperPlayer, this is just for non-hardware errors or restart triggers
            if (BuildConfig.DEBUG) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(baseContext, "Error: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
                }
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
                if (fsm.state is EngineState.Thanatosis) {
                    // Decoder failed even on the fallback asset! Elevate to Type B Zero-Decoder!
                    processEvent(EngineEvent.EnterThanatosis(error.errorCodeName, isZeroDecoder = true))
                } else if (isVisible) {
                    processEvent(EngineEvent.InitializeRequested)
                }
            } else {
                // Non-decoder error (corrupted media, missing file, etc.)
                if (fsm.state is EngineState.Thanatosis) {
                    // Non-decoder error occurred while already in Thanatosis; escalate to Type B static texture
                    processEvent(EngineEvent.EnterThanatosis(error.errorCodeName, isZeroDecoder = true))
                } else {
                    // Enter Type A Thanatosis!
                    processEvent(EngineEvent.EnterThanatosis(error.errorCodeName, isZeroDecoder = false))
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

            // No more refreshed here, just on transition
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
                        skipNextVideo()
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

            val hasExistingFrame = wallpaperPlayer.getPlayerInstance() != null
            if (hasExistingFrame) {
                releasePlayer()
                renderer?.setIsTransitioning(true)
            }

            // Allow the OS to send MotionEvents to this engine
            kineticPipeline.reset()
            kineticPipeline.effectType = prefs.getKineticEffectType()
            kineticPipeline.intensity = prefs.getKineticIntensity()
            updateTouchListeningState()

            // Get a surface
            val holder = surfaceHolder
            if (holder == null) {
                renderer?.setIsTransitioning(false)
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
                renderer?.setIsTransitioning(false)
                FileLogger.e(TAG, "Media URI is null, cannot play video.")
                return
            }

            val fileName = mediaUri.lastPathSegment ?: ""
            val activeSettings = prefs.getVideoSettings(fileName)

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

                val playlistSettings = prefs.getPlaylistSettings()
                val bufferConfig = BufferPolicyCalculator.compute(
                    playbackMode = currentPlaybackMode,
                    activeVideo = activeSettings,
                    playlist = playlistSettings
                )

                wallpaperPlayer.initializeAsync(null, currentPlaybackMode, bufferConfig).join()
                if (wallpaperPlayer.getPlayerInstance() == null) {
                    renderer?.setIsTransitioning(false)
                    return@launch
                }

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
                        if (e is CancellationException) {
                            ensureActive()
                        }
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
                if (!isActive) {
                    renderer?.setIsTransitioning(false)
                    return@launch
                }

                // Check if player is alive, surface is valid, surface is ready.
                if (fsm.state !is EngineState.Initializing || surfaceHolder == null || surfaceHolder?.surface == null || !surfaceHolder?.surface?.isValid!!) {
                    FileLogger.w(TAG, "Engine destroyed or surface invalid before player setup completed. Aborting.")
                    renderer?.setIsTransitioning(false)
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
                        renderer?.setIsTransitioning(false)
                        FileLogger.e(
                            TAG,
                            "Surface became invalid before player setup finished. Aborting setVideoSurface."
                        )
                    }
                } else {
                    renderer?.setIsTransitioning(false)
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
            FileLogger.e(TAG, "CRITICAL ERROR: $reason. Entering Thanatosis (Type B Zero-Decoder).")

            if (BuildConfig.DEBUG) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(baseContext, "Wallpaper Disabled: $reason", Toast.LENGTH_LONG).show()
                }
            }

            // Clear the Preference so it doesn't try to load again on restart
            prefs.saveActiveVideoUri("")

            // Transition FSM to Thanatosis (Type B: Hardware decoder starved)
            processEvent(EngineEvent.EnterThanatosis(reason, isZeroDecoder = true))
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

            notifyColorsChanged()

            processEvent(EngineEvent.InitializeRequested)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            FileLogger.i(TAG, "onSurfaceChanged: New dimensions ${width}x${height}")

            surfaceWidth = width
            surfaceHeight = height
            prefs.saveRawScreenDimensions(width, height)

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
            kineticPipeline.reset()
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
                notifyColorsChanged()
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
                // We only apply timeline manipulations if the player wasn't just freshly initialized and engine is in Ready state.
                if (!wasJustInitialized && fsm.state is EngineState.Ready) {
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

                    // Always refresh the renderer settings before resuming playback in case
                    // the user edited them in the UI while the wallpaper was hidden.
                    refreshRenderer()
                }

                try {
                    // Tell FSM we are visible
                    if (!wasJustInitialized) {
                        processEvent(EngineEvent.VisibilityChanged(isVisible = true, forceRestart = false))
                    }

                    if (fsm.state is EngineState.Ready) {
                        wallpaperPlayer.getPlayerInstance()?.let { playerInstance ->
                            playbackWatchdog.start(playerInstance, renderer) // Monitor for playback running
                        }
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
                kineticPipeline.reset()
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
            val anchorUriString = prefs.getAnchorVideoUri()
            val activeUriString = prefs.getActiveVideoUri()

            val primarySettings = anchorUriString?.let { uriStr ->
                uriStr.toUri().lastPathSegment?.let { prefs.getVideoSettings(it) }
            }
            val fallbackSettings = activeUriString?.let { uriStr ->
                uriStr.toUri().lastPathSegment?.let { prefs.getVideoSettings(it) }
            }

            val settings = if (primarySettings?.primaryColor != null) {
                primarySettings
            } else {
                fallbackSettings
            } ?: return null

            val primaryColorInt = settings.primaryColor ?: return null
            val secondaryColorInt = settings.secondaryColor
            val tertiaryColorInt = settings.tertiaryColor
            val colorHints = settings.colorHints ?: 0

            val primaryColor = Color.valueOf(primaryColorInt)
            val secondaryColor = secondaryColorInt?.let { Color.valueOf(it) }
            val tertiaryColor = tertiaryColorInt?.let { Color.valueOf(it) }

            FileLogger.i(
                javaClass.simpleName,
                "Cached colors (source=${settings.fileName}): $primaryColor, $secondaryColor, $tertiaryColor"
            )

            return WallpaperColors(primaryColor, secondaryColor, tertiaryColor, colorHints)
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
                val hints = if (!isLightText) WallpaperColors.HINT_SUPPORTS_DARK_TEXT else 0
                // TRICK: Pass identical colors to prevent Samsung from mixing
                return WallpaperColors(colorObj, colorObj, colorObj, hints)
            }

            // STANDARD MODERN ANDROID (API 31+)
            // Keep the beautiful real colors for Material You, but forcibly inject or
            // remove the Dark Text hint based on the user's setting.
            if (cachedColors != null) {
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

            return super.onComputeColors()
        }

        private fun isDefaultDisplay(): Boolean {
            val displayId = displayContext?.display?.displayId ?: Display.DEFAULT_DISPLAY
            if (BuildConfig.DEBUG && displayId != Display.DEFAULT_DISPLAY) {
                FileLogger.d(TAG, "isDefaultDisplay: detected displayId=$displayId (non-default)")
            }
            return displayId == Display.DEFAULT_DISPLAY
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