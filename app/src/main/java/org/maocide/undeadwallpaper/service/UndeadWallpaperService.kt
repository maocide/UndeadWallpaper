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
import android.service.wallpaper.WallpaperService
import android.util.Log


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
                val parsedUri = Uri.parse(uriStr)
                val mediaItem = MediaItem.Builder().setUri(parsedUri).setMediaId(uriStr).build()
                mediaSourceFactory.createMediaSource(mediaItem)
            }

            // Intelligent Full-Playlist Loop Optimization:
            // If the chunk we built contains every video in the playlist, they all share settings!
            // We can safely enable ExoPlayer's internal REPEAT_MODE_ALL. This gives perfect gapless looping
            // without ever hitting STATE_ENDED and incurring the manual flush pause.
            val playlistUris = playlistManager.getPlaylistUris()
            if ((currentPlaybackMode == PlaybackMode.LOOP_ALL || currentPlaybackMode == PlaybackMode.SHUFFLE)
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
                }

            }
        }

        private fun refreshRenderer() {
            if (loadedVideoUriString.isBlank()) return

            val activeUri = Uri.parse(loadedVideoUriString)
            val fileName = activeUri.lastPathSegment ?: ""
            val activeSettings = prefs.getVideoSettings(fileName)

            // Send the whole package to the renderer
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

            // refresh all user values to renderer
            refreshRenderer()

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
                        // Manual playlist boundary handling
                        // The playback ended because we reached the end of a chunk of identical-settings videos.
                        // We must load the NEXT chunk starting from the video after the one that just finished.
                        val nextUriString = playlistManager.getNextUri(loadedVideoUriString, currentPlaybackMode)
                        if (nextUriString != null) {
                            FileLogger.i(TAG, "Manually transitioning across settings boundary to: $nextUriString")

                            loadedVideoUriString = nextUriString
                            prefs.saveActiveVideoUri(nextUriString)

                            playheadTime = 0L
                            hasPlaybackCompleted = false

                            // Load the next chunk and flush the player
                            bindPlaylistToPlayer(keepCurrentPlayback = false)
                            refreshRenderer()

                            val activeUri = Uri.parse(loadedVideoUriString)
                            val fileName = activeUri.lastPathSegment ?: ""
                            val activeSettings = prefs.getVideoSettings(fileName)
                            wallpaperPlayer.getPlayerInstance()?.let { player ->
                                player.volume = activeSettings.volume
                                player.setPlaybackSpeed(activeSettings.speed)
                            }

                            wallpaperPlayer.prepare()
                            wallpaperPlayer.playWhenReady = true
                        }
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
            // Track chunk advancement internally
            val nextUriString = mediaItem?.mediaId
            if (nextUriString != null && nextUriString != loadedVideoUriString) {
                FileLogger.i(TAG, "Chunk transition: advancing internally to $nextUriString")
                loadedVideoUriString = nextUriString
                prefs.saveActiveVideoUri(nextUriString)
            }

            // Update non-visual settings (volume, speed) dynamically during the chunk
            val activeUri = Uri.parse(loadedVideoUriString)
            val fileName = activeUri.lastPathSegment ?: ""
            val activeSettings = prefs.getVideoSettings(fileName)

            wallpaperPlayer.getPlayerInstance()?.let { player ->
                player.volume = activeSettings.volume
                player.setPlaybackSpeed(activeSettings.speed)
            }
        }

        override fun onRenderedFirstFrame() {
            FileLogger.i(TAG, "SUCCESS: onRenderedFirstFrame called. Decoder actually pushed a frame to the screen!")
        }

        @OptIn(UnstableApi::class)
        private fun initializePlayer() {
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

            // Status bar color refresh
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                notifyColorsChanged()
            }

            val mediaUri = getMediaUri()
            loadedVideoUriString = mediaUri?.toString() ?: ""

            if (mediaUri == null) {
                FileLogger.e(TAG, "Media URI is null, cannot play video.")
                return
            }

            val fileName = mediaUri.lastPathSegment ?: ""
            val activeSettings = prefs.getVideoSettings(fileName)
            val initialVolume = activeSettings.volume
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

            /*
            // If the player isn't set up yet (because it was deferred by the 0x0 check),
            // kick off initialization now that we have real dimensions.
            if (mediaPlayer?.playbackState == Player.STATE_IDLE || mediaPlayer == null) {
                initializePlayer()
            }
            */
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
                    } else {
                        wallpaperPlayer.pause()
                        wallpaperPlayer.playWhenReady = false
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
                addAction(ACTION_VIDEO_SETTINGS_CHANGED)
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