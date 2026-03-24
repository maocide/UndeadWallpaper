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
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.maocide.undeadwallpaper.model.AppState
import org.maocide.undeadwallpaper.model.PlaybackMode
import org.maocide.undeadwallpaper.model.PlaylistItemState
import org.maocide.undeadwallpaper.model.ScalingMode
import org.maocide.undeadwallpaper.model.StartTime
import org.maocide.undeadwallpaper.model.StatusBarColor
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
        const val ACTION_PLAYLIST_STATE_CHANGED = "org.maocide.undeadwallpaper.PLAYLIST_STATE_CHANGED"
        const val ACTION_ACTIVE_ITEM_SETTINGS_CHANGED = "org.maocide.undeadwallpaper.ACTIVE_ITEM_SETTINGS_CHANGED"
    }

    override fun onCreateEngine(): Engine {
        return MyWallpaperEngine()
    }




    private inner class MyWallpaperEngine : Engine() {

        // Lazy instantiation for performance reuse
        private val prefs by lazy { PreferencesManager(baseContext) }
        private val appStateRepository by lazy { AppStateRepository.getInstance(baseContext) }
        private var isAudioEnabled: Boolean = false
        private lateinit var currentScalingMode: ScalingMode
        private var mediaPlayer: ExoPlayer? = null
        private var surfaceHolder: SurfaceHolder? = null
        private var playheadTime: Long = 0L
        private val TAG: String = javaClass.simpleName
        private var isScalingModeSet = false
        private var useFallbackSurface = false

        private var currentPlaybackMode = PlaybackMode.LOOP

        private var speed: Float = 1f

        // Service-side copy of the current state.
        private var currentState: AppState? = null
        private var loadedItemId = ""
        private var shuffleOrder: MutableList<String> = mutableListOf()
        private var currentShuffleIndex = 0
        private var currentItemCompletedLoops = 0
        private var hasPlaybackCompleted = false

        private var renderer: GLVideoRenderer? = null
        private var recoveryAttempts = 0 // Counter for error recovery retry attempts


        private var playerSetupJob: kotlinx.coroutines.Job? = null
        private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var stateObservationJob: Job? = null

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

        /**
         * Checks if the player claims to be playing but isn't advancing.
         * Used by the Stall Watchdog
         */
        private fun checkPlaybackStall() {
            val player = mediaPlayer ?: return
            val renderer = renderer ?: return

            // We only care if we SHOULD be playing
            if (player.isPlaying) {
                val currentPos = player.currentPosition
                val currentRenderTime = renderer.getSurfaceDrawTimestamp()

                val isPlayerStuck = (currentPos == lastPosition)
                val isScreenFrozen = (currentRenderTime == lastRenderTimestamp)

                // If EITHER is true, the player is stuck with no error.
                if ((isPlayerStuck || isScreenFrozen) && player.duration > 2000) {
                    stallCount++
                    Log.w(TAG, "Watchdog: Stall detected! PlayerStuck=$isPlayerStuck, ScreenFrozen=$isScreenFrozen ($stallCount/2)")


                    if (stallCount >= 2) { // Stalled for ~4 seconds
                        Log.e(TAG, "Watchdog: STALL CONFIRMED. Restarting player.")
                        stallCount = 0
                        initializePlayer() // Force restart
                    }
                } else {
                    // It moved! Reset counters.
                    stallCount = 0
                    lastPosition = currentPos
                    lastRenderTimestamp = currentRenderTime
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

        private fun observeRepositoryState() {
            stateObservationJob?.cancel()
            stateObservationJob = serviceScope.launch {
                // Keep a warm copy of state instead of reading DataStore in hot paths.
                appStateRepository.state.collect { state ->
                    val previousActiveId = currentState?.activeItemId
                    currentState = state

                    if (surfaceHolder == null) return@collect

                    val activeItemId = activeOrFallbackItem(state)?.id
                    when {
                        mediaPlayer == null && activeItemId != null -> initializePlayer()
                        previousActiveId != activeItemId && isVisible && activeItemId != loadedItemId -> initializePlayer()
                    }
                }
            }
        }

        private fun resolveVideoFile(item: PlaylistItemState): File? {
            val moviesDir = baseContext.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
                ?: return null
            val file = File(File(moviesDir, "videos"), item.fileName)
            return file.takeIf(File::exists)
        }

        private fun resolveUri(item: PlaylistItemState): Uri? {
            return resolveVideoFile(item)?.let(Uri::fromFile)
        }

        private fun enabledPlaylistItems(state: AppState? = currentState): List<PlaylistItemState> {
            if (state == null) return emptyList()
            return state.playlist.filter { item ->
                item.enabled && resolveVideoFile(item) != null
            }
        }

        private fun activeOrFallbackItem(state: AppState? = currentState): PlaylistItemState? {
            if (state == null) return null
            return enabledPlaylistItems(state).firstOrNull { it.id == state.activeItemId }
                ?: enabledPlaylistItems(state).firstOrNull()
        }

        private fun loadedItem(state: AppState? = currentState): PlaylistItemState? {
            if (state == null) return null
            return state.playlist.firstOrNull { it.id == loadedItemId }
        }

        private fun ensureShuffleOrder(state: AppState? = currentState) {
            // Shuffle unique items, not fake duplicates for loop counts.
            val enabledIds = enabledPlaylistItems(state).map(PlaylistItemState::id)
            if (enabledIds.isEmpty()) {
                shuffleOrder.clear()
                currentShuffleIndex = 0
                return
            }

            val needsReset = shuffleOrder.isEmpty() ||
                shuffleOrder.size != enabledIds.size ||
                shuffleOrder.toSet() != enabledIds.toSet()

            if (needsReset) {
                val preferredId = state?.activeItemId ?: loadedItemId.takeIf { it.isNotBlank() } ?: enabledIds.first()
                val shuffled = enabledIds.shuffled().toMutableList()
                val preferredIndex = shuffled.indexOf(preferredId).takeIf { it >= 0 } ?: 0
                shuffleOrder = (shuffled.drop(preferredIndex) + shuffled.take(preferredIndex)).toMutableList()
                currentShuffleIndex = 0
            } else if (loadedItemId.isNotBlank()) {
                currentShuffleIndex = shuffleOrder.indexOf(loadedItemId).takeIf { it >= 0 } ?: currentShuffleIndex
            }
        }

        private fun resetCurrentLoopCounter() {
            currentItemCompletedLoops = 0
        }

        private fun rememberActiveItem(itemId: String) {
            if (currentState?.activeItemId == itemId) return

            currentState = currentState?.copy(activeItemId = itemId)
            serviceScope.launch {
                appStateRepository.selectItem(itemId)
            }
        }

        @OptIn(UnstableApi::class)
        private fun setSingleItemSource(
            player: ExoPlayer,
            mediaSourceFactory: ProgressiveMediaSource.Factory,
            item: PlaylistItemState,
            positionMs: Long
        ) {
            /*
             * Playlist modes load one item at a time here. The full ExoPlayer playlist path looked
             * cleaner, but it caused the next wallpaper to flash between repeats.
             */
            val itemUri = resolveUri(item) ?: return
            val mediaItem = MediaItem.Builder()
                .setUri(itemUri)
                .setMediaId(item.id)
                .build()
            player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
            player.seekTo(positionMs)
            loadedItemId = item.id
            rememberActiveItem(item.id)
            resolveUri(item)?.let { prefs.saveVideoUri(it.toString()) }
            applyCurrentItemSettings()
        }

        @OptIn(UnstableApi::class)
        private fun advancePlaylistPlayback(player: ExoPlayer) {
            // Handle repeat counts here so loops stay consecutive.
            val enabledItems = enabledPlaylistItems()
            if (enabledItems.isEmpty()) return

            val currentItem = loadedItem() ?: activeOrFallbackItem() ?: return
            val targetLoops = currentItem.loopCount.coerceAtLeast(1)

            if (currentItemCompletedLoops + 1 < targetLoops) {
                currentItemCompletedLoops++
                playheadTime = 0L
                player.seekTo(player.currentMediaItemIndex, 0L)
                player.playWhenReady = true
                player.play()
                return
            }

            currentItemCompletedLoops = 0

            when (currentPlaybackMode) {
                PlaybackMode.LOOP_ALL -> {
                    val currentIndex = enabledItems.indexOfFirst { it.id == currentItem.id }.takeIf { it >= 0 } ?: 0
                    val nextItem = enabledItems[(currentIndex + 1) % enabledItems.size]
                    playheadTime = 0L
                    val dataSourceFactory = DefaultDataSource.Factory(baseContext)
                    val mediaSourceFactory = ProgressiveMediaSource.Factory(dataSourceFactory)
                    setSingleItemSource(player, mediaSourceFactory, nextItem, 0L)
                    player.playWhenReady = true
                    player.prepare()
                    player.play()
                }
                PlaybackMode.SHUFFLE -> {
                    ensureShuffleOrder()
                    if (shuffleOrder.isEmpty()) return
                    currentShuffleIndex = shuffleOrder.indexOf(currentItem.id).takeIf { it >= 0 } ?: currentShuffleIndex
                    currentShuffleIndex++
                    if (currentShuffleIndex >= shuffleOrder.size) {
                        shuffleOrder = shuffleOrder.shuffled().toMutableList()
                        currentShuffleIndex = 0
                    }
                    val nextItemId = shuffleOrder.getOrNull(currentShuffleIndex) ?: return
                    val nextItem = enabledItems.firstOrNull { it.id == nextItemId } ?: return
                    playheadTime = 0L
                    val dataSourceFactory = DefaultDataSource.Factory(baseContext)
                    val mediaSourceFactory = ProgressiveMediaSource.Factory(dataSourceFactory)
                    setSingleItemSource(player, mediaSourceFactory, nextItem, 0L)
                    player.playWhenReady = true
                    player.prepare()
                    player.play()
                }
                else -> Unit
            }
        }

        @OptIn(UnstableApi::class)
        private fun bindPlaylistToPlayer(player: ExoPlayer, keepCurrentPlayback: Boolean) {
            val dataSourceFactory = DefaultDataSource.Factory(baseContext)
            val mediaSourceFactory = ProgressiveMediaSource.Factory(dataSourceFactory)

            if (currentPlaybackMode == PlaybackMode.LOOP_ALL || currentPlaybackMode == PlaybackMode.SHUFFLE) {
                // Single-item source on purpose.
                val playlistItems = enabledPlaylistItems()

                if (playlistItems.isNotEmpty()) {
                    if (currentPlaybackMode == PlaybackMode.SHUFFLE) {
                        ensureShuffleOrder()
                    }

                    val targetItem = if (currentPlaybackMode == PlaybackMode.SHUFFLE) {
                        val targetId = shuffleOrder.getOrNull(currentShuffleIndex) ?: currentState?.activeItemId
                        playlistItems.firstOrNull { it.id == targetId }
                    } else {
                        playlistItems.firstOrNull { it.id == currentState?.activeItemId }
                    } ?: playlistItems.first()

                    // If we are just reordering, keep the exact millisecond we are currently at
                    val targetPosition = if (keepCurrentPlayback) player.currentPosition else playheadTime

                    setSingleItemSource(player, mediaSourceFactory, targetItem, targetPosition)
                } else {
                    // Fallback for empty list
                    val mediaUri = getMediaUri() ?: return
                    val fallbackItemId = activeOrFallbackItem()?.id ?: return
                    val mediaItem = MediaItem.Builder().setUri(mediaUri).setMediaId(fallbackItemId).build()
                    player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
                    player.seekTo(if (keepCurrentPlayback) player.currentPosition else playheadTime)
                }
            } else {
                // Single file modes
                val mediaUri = getMediaUri() ?: return
                val activeItemId = activeOrFallbackItem()?.id ?: return
                val mediaItem = MediaItem.Builder().setUri(mediaUri).setMediaId(activeItemId).build()
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

                    ACTION_PLAYLIST_REORDERED -> {
                        Log.i(TAG, "Playlist reordered. Syncing ExoPlayer timeline.")
                        currentState = appStateRepository.snapshot() ?: currentState
                        ensureShuffleOrder()
                        mediaPlayer?.let {
                            // Call the helper (Keep playing seamlessly)
                            bindPlaylistToPlayer(it, keepCurrentPlayback = true)
                        }
                    }

                    ACTION_PLAYLIST_STATE_CHANGED -> {
                        Log.i(TAG, "Playlist state changed. Syncing player with repository state.")
                        currentState = appStateRepository.snapshot() ?: currentState
                        ensureShuffleOrder()
                        // One path for enable/disable, reorder, and active-item changes.
                        val currentLoaded = loadedItem()
                        if (currentLoaded == null || !currentLoaded.enabled || currentState?.activeItemId != loadedItemId) {
                            initializePlayer()
                        } else {
                            mediaPlayer?.let {
                                bindPlaylistToPlayer(it, keepCurrentPlayback = true)
                            } ?: initializePlayer()
                        }
                    }

                    ACTION_ACTIVE_ITEM_SETTINGS_CHANGED -> {
                        Log.i(TAG, "Active item settings changed. Refreshing current item settings.")
                        currentState = appStateRepository.snapshot() ?: currentState
                        applyCurrentItemSettings()
                    }
                }

            }
        }

        private fun refreshRenderer() {
            val currentItemSettings = loadedItem()?.settings ?: return
            // Pull transforms from the active item.
            currentScalingMode = currentItemSettings.scalingMode
            renderer?.setScalingMode(currentScalingMode)
            renderer?.setTransforms(
                x = currentItemSettings.positionX,
                y = currentItemSettings.positionY,
                zoom = currentItemSettings.zoom,
                rotation = currentItemSettings.rotation
            )
            renderer?.setBrightness(currentItemSettings.brightness)
        }

        private fun applyCurrentItemSettings() {
            // Speed is per-item now too.
            val currentItem = loadedItem() ?: return
            speed = currentItem.settings.speed
            mediaPlayer?.setPlaybackSpeed(speed)
            refreshRenderer()
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

            // Build the player from the current repository state.
            currentState = currentState ?: appStateRepository.snapshot()
            val state = currentState
            if (state == null) {
                Log.w(TAG, "App state not ready yet; delaying player initialization.")
                return
            }
            val activeItem = activeOrFallbackItem()
            val activeItemUri = activeItem?.let(::resolveUri)
            if (activeItem == null || activeItemUri == null) {
                Log.w(TAG, "No enabled wallpaper items available for playback.")
                prefs.saveVideoUri("")
                return
            }

            // Load prefs
            isAudioEnabled = state.global.audioEnabled
            currentPlaybackMode = state.global.playbackMode
            speed = activeItem.settings.speed
            ensureShuffleOrder(state)
            currentShuffleIndex = shuffleOrder.indexOf(activeItem.id).takeIf { it >= 0 } ?: 0
            val shouldResumeLoopProgress = state.global.startTime == StartTime.RESUME &&
                (currentPlaybackMode == PlaybackMode.LOOP_ALL || currentPlaybackMode == PlaybackMode.SHUFFLE) &&
                loadedItemId == activeItem.id

            if (!shouldResumeLoopProgress) {
                resetCurrentLoopCounter()
            }


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
                .setLoadControl(loadControl)
                .setSeekParameters(SeekParameters.NEXT_SYNC)
                .build()
                .apply {
                    loadedItemId = activeItem.id
                    rememberActiveItem(activeItem.id)
                    prefs.saveVideoUri(activeItemUri.toString())

                    // Call the helper to load playlist
                    bindPlaylistToPlayer(this, keepCurrentPlayback = false)

                    // Apply volume
                    volume = if (isAudioEnabled) 1f else 0f

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
                            repeatMode = Player.REPEAT_MODE_OFF
                            shuffleModeEnabled = false
                        }
                        PlaybackMode.SHUFFLE -> {
                            repeatMode = Player.REPEAT_MODE_OFF
                            shuffleModeEnabled = false
                        }
                    }

                    Log.d(TAG, "repeatMode: $repeatMode, shuffleModeEnabled: $shuffleModeEnabled")

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

                            // Check for 0x0 size
                            if (videoSize.width == 0 || videoSize.height == 0) {
                                Log.w(TAG, "Ignoring invalid 0x0 video size change.")
                                return
                            }

                            // Send video size to Renderer for Matrix Calculation
                            renderer?.setVideoSize(videoSize.width, videoSize.height)

                            // refresh all user values to renderer
                            refreshRenderer()

                            // Use ExoPlayer's scaling only if fallback surface is used
                            if (useFallbackSurface) {
                                if (!isScalingModeSet) {
                                    Log.i(TAG, "Valid video size detected: ${videoSize.width}x${videoSize.height}. Setting scaling mode ONCE for fallback surface.")

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

                            // Playlist modes only advance after the current item finishes its repeat count.
                            if (currentPlaybackMode == PlaybackMode.LOOP_ALL || currentPlaybackMode == PlaybackMode.SHUFFLE) {
                                if (playbackState == Player.STATE_ENDED) {
                                    advancePlaylistPlayback(this@apply)
                                }
                            } else if (currentPlaybackMode == PlaybackMode.ONE_SHOT) {
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

                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            super.onMediaItemTransition(mediaItem, reason)
                            // Just keep local state in sync here.
                            val nextItemId = mediaItem?.mediaId
                            if (nextItemId != null && nextItemId != loadedItemId) {
                                Log.i(TAG, "Transitioning to next video in playlist item: $nextItemId")
                                loadedItemId = nextItemId
                                resetCurrentLoopCounter()
                                loadedItem()?.let { playlistItem ->
                                    resolveUri(playlistItem)?.let { prefs.saveVideoUri(it.toString()) }
                                }
                                applyCurrentItemSettings()
                                kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                                    appStateRepository.selectItem(nextItemId)
                                }
                            }
                        }
                    })

                    // WAIT for the GL Surface, then attach
                    // We need a coroutine here because waitForVideoSurface is suspend
                    playerSetupJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                        var finalSurface: android.view.Surface? = null

                        if (!useFallbackSurface) {
                            try {
                                finalSurface = renderer?.waitForVideoSurface()
                            } catch (e: Exception) {
                                Log.e(TAG, "GL Renderer failed to provide surface, falling back to default surface", e)
                                useFallbackSurface = true
                                releasePlayer()
                                releaseRenderer()
                                initializePlayer()
                                return@launch
                            }
                        } else {
                            finalSurface = surfaceHolder?.surface
                        }

                        // If this job was cancelled, video switch or anything, STOP.
                        if (!isActive) return@launch

                        // Check if player is alive, surface is valid, surface is ready.
                        if (mediaPlayer == null || surfaceHolder == null || surfaceHolder?.surface == null || !surfaceHolder?.surface?.isValid!!) {
                            Log.w(TAG, "Engine destroyed or surface invalid before player setup completed. Aborting.")
                            return@launch
                        }

                        if (finalSurface != null) {
                            if (currentPlaybackMode == PlaybackMode.LOOP_ALL || currentPlaybackMode == PlaybackMode.SHUFFLE) {
                                seekTo(currentMediaItemIndex, playheadTime)
                            } else {
                                seekTo(playheadTime)
                            }
                            setVideoSurface(finalSurface)
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
            // Resolve the current item from state, then map it back to a file.
            val state = currentState ?: return null
            val item = activeOrFallbackItem(state) ?: return null
            return resolveUri(item)
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
            prefs.saveVideoUri("")

            // Kill the player and DO NOT restart it.
            releasePlayer()
        }


        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            Log.i(TAG, "onSurfaceCreated")
            this.surfaceHolder = holder

            if (!useFallbackSurface) {
                // Start the GL Renderer
                renderer = GLVideoRenderer(applicationContext)
                renderer?.onSurfaceCreated(holder)
            } else {
                Log.i(TAG, "Using fallback surface, skipping GL Renderer creation")
            }

            initializePlayer()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            Log.i(TAG, "onSurfaceChanged: New dimensions ${width}x${height}")
            this.surfaceHolder = holder

            if (!useFallbackSurface) {
                // Tell Renderer the screen size
                renderer?.onSurfaceChanged(width, height)
            }

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
            stateObservationJob?.cancel()
            serviceScope.coroutineContext.cancel()
            releasePlayer()
            releaseRenderer()
            unregisterReceiver(videoChangeReceiver)
        }


        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            val state = currentState ?: return
            val startTimePref = state.global.startTime

            Log.i(TAG, "onVisibilityChanged: visible = $visible isPreview = $isPreview, playbackMode = $currentPlaybackMode, startTime = $startTimePref")

            if (visible) {
                startStallWatchdog() // Monitor for playback running

                // Compare by item ID now, not just URI.
                val currentItemId = activeOrFallbackItem(state)?.id.orEmpty()

                // If they don't match, or the player is missing, initialize it!
                if (currentItemId != loadedItemId || mediaPlayer == null) {
                    if (currentItemId != loadedItemId) {
                        Log.i(TAG, "WakeUp Check: active item changed while sleeping. Reloading.")
                    }
                    initializePlayer()
                }

                // Safely grab the player instance. If it's still null somehow, exit gracefully.
                val player = mediaPlayer ?: return

                // Apply timeline behavior
                when (startTimePref) {
                    StartTime.RESUME -> {
                        if (currentPlaybackMode == PlaybackMode.ONE_SHOT && hasPlaybackCompleted && !isPreview()) {
                            Log.i(TAG, "One Shot completed previously, restarting from 0")
                            playheadTime = 0L // Sync state
                            player.seekToDefaultPosition()
                            hasPlaybackCompleted = false
                        }
                    }
                    StartTime.RESTART -> {
                        playheadTime = 0L // Sync state
                        player.seekToDefaultPosition()
                        hasPlaybackCompleted = false
                    }
                    StartTime.RANDOM -> {
                        val duration = player.duration
                        if (duration > 0 && duration != C.TIME_UNSET) {
                            val randomPos = Random.nextLong(0, duration)
                            Log.d(TAG, "Seeking to random pos: $randomPos")
                            playheadTime = randomPos // Sync state so coroutine to hook to GLRenderer doesn't overwrite it!
                            player.seekTo(player.currentMediaItemIndex, randomPos)
                        } else {
                            playheadTime = 0L
                            player.seekToDefaultPosition()
                        }
                        hasPlaybackCompleted = false
                    }
                }

                player.playWhenReady = true
                player.play()

            } else {
                stopStallWatchdog() // Stop monitoring for playback running
                mediaPlayer?.pause()
                mediaPlayer?.playWhenReady = false
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            Log.i(TAG, "Engine onCreate")
            // Start watching state before broadcasts start coming in.
            observeRepositoryState()
            // Turn on filter to start listening
            val intentFilter = IntentFilter().apply {
                addAction(ACTION_VIDEO_URI_CHANGED)
                addAction(ACTION_PLAYBACK_MODE_CHANGED)
                addAction(ACTION_STATUS_BAR_COLOR_CHANGED)
                addAction(ACTION_PLAYLIST_REORDERED)
                addAction(ACTION_PLAYLIST_STATE_CHANGED)
                addAction(ACTION_ACTIVE_ITEM_SETTINGS_CHANGED)
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
            val mode = currentState?.global?.statusBarColor ?: return null

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

                Log.i(TAG, "Command received -> Re-initializing player.")
                // Full reset for major changes
                initializePlayer()

            } else if (action == ACTION_PLAYLIST_REORDERED) {

                Log.i(TAG, "Command received -> Playlist reordered. Syncing silently.")

                mediaPlayer?.let {
                    bindPlaylistToPlayer(it, keepCurrentPlayback = true)
                }

            }

            return null
        }
    }
}
