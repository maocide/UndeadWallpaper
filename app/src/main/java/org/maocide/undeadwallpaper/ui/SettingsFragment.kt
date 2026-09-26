package org.maocide.undeadwallpaper.ui

import org.maocide.undeadwallpaper.databinding.FragmentSettingsBinding

import org.maocide.undeadwallpaper.R
import org.maocide.undeadwallpaper.BuildConfig

import org.maocide.undeadwallpaper.data.PreferencesManager
import org.maocide.undeadwallpaper.data.VideoFileManager
import org.maocide.undeadwallpaper.model.PlaybackMode
import org.maocide.undeadwallpaper.model.StartTime
import org.maocide.undeadwallpaper.model.StatusBarColor
import org.maocide.undeadwallpaper.event.WallpaperEvent
import org.maocide.undeadwallpaper.event.WallpaperEventBus
import org.maocide.undeadwallpaper.utils.BlurHelper
import org.maocide.undeadwallpaper.utils.FileLogger
import org.maocide.undeadwallpaper.utils.preventDoubleInput
import org.maocide.undeadwallpaper.utils.setSafeOnClickListener
import java.io.File

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.transition.TransitionManager
import java.io.FileNotFoundException


import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 * This fragment allows the user to select a video and set it as a live wallpaper.
 */
@UnstableApi
class SettingsFragment : Fragment() {

    private val tag = "UndeadWallpaperSettings"
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private var brainsTapCount = 0
    private var targetTapCount = 0
    private var lastBrainsTapTime = 0L
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var videoFileManager: VideoFileManager
    private var playlistController: PlaylistController? = null
    private var gestureControlsController: GestureControlsController? = null
    private var accordionController: AccordionController? = null
    private var previewPlayer: ExoPlayer? = null
    private var randomStartTimeWarned = false
    private var isUpdatingUi = false
    private var statusBarColorWarned = false

    private var undeadActivationJob: Job? = null
    private var lastUpdateVideoSourceTime = 0L
    private var floatingPreviewController: FloatingPreviewController? = null

    // Initialize the shared ViewModel
    private val sharedViewModel: SettingsViewModel by activityViewModels()

    companion object {
        private const val DEBOUNCE_PAGINATION_MS = 500L
        private const val DEBOUNCE_MANUAL_TAP_MS = 300L
    }


    /**
     * Launcher for picking media from the file system.
     */
    private val pickMediaLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    handleSelectedMedia(uri)
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        preferencesManager = PreferencesManager(requireContext())
        videoFileManager = VideoFileManager(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // UI SETUP this first
        playlistController = PlaylistController(
            binding = binding,
            preferencesManager = preferencesManager,
            videoFileManager = videoFileManager,
            coroutineScope = viewLifecycleOwner.lifecycleScope,
            fragmentManager = childFragmentManager,
            onVideoSelected = { uri, isManualTap ->
                val isAlreadySelected = (sharedViewModel.selectedVideoUri == uri &&
                    preferencesManager.getActiveVideoUri() == uri.toString())
                val needsRecovery = previewPlayer == null || previewPlayer?.playerError != null

                if (!isAlreadySelected || needsRecovery) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        updateVideoSource(uri, forceChange = true, isManualTap = isManualTap)
                    }
                }
                if (isManualTap) {
                    (activity as? MainActivity)?.onManualVideoInteraction()
                }
            },
            onAddVideoClick = {
                openFilePicker()
            },
            onEnsureDefaultVideo = {
                ensureDefaultVideoExists()
            },
            getActiveVideoUri = {
                sharedViewModel.selectedVideoUri?.toString() ?: preferencesManager.getActiveVideoUri()
            }
        ).apply {
            setup()
        }
        setupBatteryWarningCard()
        setupLandscapeInsetsBalancing()
        
        floatingPreviewController = FloatingPreviewController(binding, preferencesManager).apply {
            setup()
        }
        gestureControlsController = GestureControlsController(
            binding = binding,
            preferencesManager = preferencesManager,
            isUpdatingUi = { isUpdatingUi }
        ).apply {
            setup()
        }
        accordionController = AccordionController(
            rootView = binding.root as ViewGroup,
            binding = binding
        ).apply {
            setup()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                WallpaperEventBus.events.collect { event ->
                    if (event is WallpaperEvent.VideoSettingsChanged) {
                        playlistController?.notifyDataSetChanged()
                    }
                }
            }
        }

        // ASYNC TASKS (Data Loading)
        viewLifecycleOwner.lifecycleScope.launch {
            // This might take time on first run (copying file)
            ensureDefaultVideoExists()

            // load the data/settings
            syncUiState(savedInstanceState)
            setupListeners()
        }

    }

    /**
     * Centralized function to handle setting or changing the video source.
     * This function ensures that whenever a new video is selected, the UI and
     * preferences are reset and updated correctly.
     *
     * @param uri The URI of the new video file.
     * @param forceChange Weather to send a broadcast to the service to cause a video reload.
     */
    private suspend fun updateVideoSource(uri: Uri, forceChange: Boolean, isManualTap: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        val debounceThreshold = if (isManualTap) DEBOUNCE_MANUAL_TAP_MS else DEBOUNCE_PAGINATION_MS
        if (currentTime - lastUpdateVideoSourceTime < debounceThreshold) {
            // FileLogger.w(tag, "updateVideoSource debounced! Ignoring rapid clicks.")
            return
        }
        lastUpdateVideoSourceTime = currentTime


        // Store the new video URI as a shared value
        sharedViewModel.selectedVideoUri = uri

        // Update the active video highlight in the adapter
        playlistController?.setActiveVideoUri(uri.toString())
        // Only spawn a new player if the user actively clicked
        // If the app is booting up, onResume will handle it
        if (isResumed) {
            setupVideoPreview(uri)
        }

        // Save the preference and notify the service to reload the video from that value
        if (forceChange) {
            preferencesManager.saveActiveVideoUri(uri.toString())
            WallpaperEventBus.emit(WallpaperEvent.VideoUriChanged)
        }
    }



    private suspend fun ensureDefaultVideoExists() {
        if (preferencesManager.getActiveVideoUri().isNullOrEmpty()) {
            val preparingMessage = getString(R.string.preparing_assets)
            val defaultFileName = getString(R.string.default_video_filename)
            withLoadingOverlay(preparingMessage, cancellable = false) {
                val defaultUri = withContext(NonCancellable + Dispatchers.IO) {
                    val defaultFile = videoFileManager.createDefaultFileFromResource(R.raw.zombillie_default)

                    if (defaultFile != null) {
                        preferencesManager.updateVideoSettings(defaultFile.name) {
                            it.copy(displayName = defaultFileName, expectedFileSize = defaultFile.length())
                        }
                        val uri = Uri.fromFile(defaultFile)
                        preferencesManager.saveActiveVideoUri(uri.toString())
                        uri
                    } else {
                        null
                    }
                }

                if (defaultUri != null && _binding != null) {
                    updateVideoSource(defaultUri, forceChange = false)
                }
            }
        }
    }

    /**
     * Update all Buttons/Switches to match Preferences.
     * Calling this BEFORE listeners prevents accidental triggers.
     */
    private suspend fun syncUiState(savedInstanceState: Bundle? = null) {
        // SET to avoid overriding
        isUpdatingUi = true

        try {

            // Playback Mode
            when (preferencesManager.getPlaybackMode()) {
                PlaybackMode.LOOP -> binding.playbackModeGroup.check(binding.playbackModeLoop.id)
                PlaybackMode.ONE_SHOT -> binding.playbackModeGroup.check(binding.playbackModeOneshot.id)
                PlaybackMode.LOOP_ALL -> binding.playbackModeGroup.check(binding.playbackModeLoopAll.id)
                PlaybackMode.SHUFFLE -> binding.playbackModeGroup.check(binding.playbackModeShuffle.id)
            }

            // Start Time
            when (preferencesManager.getStartTime()) {
                StartTime.RESUME -> binding.startTimeGroup.check(binding.startTimeResume.id)
                StartTime.RESTART -> binding.startTimeGroup.check(binding.startTimeRestart.id)
                StartTime.RANDOM -> binding.startTimeGroup.check(binding.startTimeRandom.id)
            }

            // StatusBar Color
            when (preferencesManager.getStatusBarColor()) {
                StatusBarColor.AUTO -> binding.statusBarColorGroup.check(binding.statusBarAuto.id)
                StatusBarColor.DARK -> binding.statusBarColorGroup.check(binding.statusBarDark.id)
                StatusBarColor.LIGHT -> binding.statusBarColorGroup.check(binding.statusBarLight.id)
            }

            // Load Video Preview and set the video as selected
            val savedUri = preferencesManager.getActiveVideoUri()
            if (savedUri != null) {
                // onResume() will handle actually creating ExoPlayer safely, updating video source
                sharedViewModel.selectedVideoUri = savedUri.toUri()
            }

            // Sync Home Screen Gestures
            val hasActiveGestures = gestureControlsController?.syncFromPreferences() ?: false

            // Experimental Parallax
            val isParallaxEnabled = preferencesManager.isParallaxEnabled()
            binding.switchParallax.isChecked = isParallaxEnabled
            binding.sliderParallaxStrength.value = preferencesManager.getParallaxStrength()
            binding.layoutParallaxStrength.visibility = if (isParallaxEnabled) View.VISIBLE else View.GONE

            // Sync or Restore Accordion States
            val hasCustomGlobalSettings = preferencesManager.getStartTime() != StartTime.RESUME ||
                preferencesManager.getStatusBarColor() != StatusBarColor.AUTO
            accordionController?.restoreOrInitialize(
                savedInstanceState = savedInstanceState,
                defaultGlobal = hasCustomGlobalSettings,
                defaultTouch = hasActiveGestures,
                defaultParallax = isParallaxEnabled
            )

            // Regardless of having a selected video or not, we need to load the recent files
            // into the RecyclerView adapter ONCE during UI initialization.
            playlistController?.loadRecentFiles(performMaintenance = false)
        } finally {
            isUpdatingUi = false
        }

    }


    /**
     * Updates status for battery card controls.
     */
    private fun checkBatteryOptimization() {
        val context = context ?: return
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isOptimized = !powerManager.isIgnoringBatteryOptimizations(context.packageName)

        if (isOptimized) {
            binding.cardBatteryWarning.visibility = View.VISIBLE
            // Reset the card state in case the user returns without fixing it
            binding.btnFixBattery.visibility = View.VISIBLE
            binding.layoutBatteryInstructions.visibility = View.GONE
        } else {
            binding.cardBatteryWarning.visibility = View.GONE
        }
    }

    /**
     * Sets up the listeners for battery card controls.
     */
    private fun setupBatteryWarningCard() {
        binding.btnFixBattery.setSafeOnClickListener {
            // Expand the instructions and hide the fix button
            binding.btnFixBattery.visibility = View.GONE
            binding.layoutBatteryInstructions.visibility = View.VISIBLE
        }

        binding.btnGoToSettings.setSafeOnClickListener {
            try {
                // Drop them directly into Undead Wallpaper's specific App Info page
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", requireContext().packageName, null)
                }
                startActivity(intent)
            } catch (e: Exception) {
                FileLogger.e(tag, "Failed to open app info settings", e)
                Toast.makeText(requireContext(), getString(R.string.error_open_settings_failed), Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }


    private fun setupListeners() {
        // Helper to broadcast changes
        fun notifySettingsChanged() {
            WallpaperEventBus.emit(WallpaperEvent.PlaybackModeChanged)
        }

        // Playback Mode
        binding.playbackModeLoop.preventDoubleInput()
        binding.playbackModeOneshot.preventDoubleInput()
        binding.playbackModeLoopAll.preventDoubleInput()
        binding.playbackModeShuffle.preventDoubleInput()
        binding.playbackModeGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            // If nothing is selected, skip
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener

            val checkedId = checkedIds[0] // Get the single selected ID

            val newMode = when (checkedId) {
                binding.playbackModeOneshot.id -> PlaybackMode.ONE_SHOT
                binding.playbackModeLoopAll.id -> PlaybackMode.LOOP_ALL
                binding.playbackModeShuffle.id -> PlaybackMode.SHUFFLE
                else -> PlaybackMode.LOOP
            }

            preferencesManager.setPlaybackMode(newMode)

            // Forcing an update to current uri in case we switch back from playlist to single video.
            // We use the adapter's highlighted URI to guarantee the background service plays
            // the exact video the user is currently looking at in the UI list.
            val highlightedUri = playlistController?.currentVideoUriString
            if (!highlightedUri.isNullOrEmpty()) {
                sharedViewModel.selectedVideoUri = highlightedUri.toUri()
                preferencesManager.saveActiveVideoUri(highlightedUri)
            } else {
                val currentSelectedUri =
                    sharedViewModel.selectedVideoUri?.toString() ?: preferencesManager.getActiveVideoUri()
                if (currentSelectedUri != null) {
                    preferencesManager.saveActiveVideoUri(currentSelectedUri)
                }
            }

            notifySettingsChanged()
        }

        // StartTime preference
        binding.startTimeRestart.preventDoubleInput()
        binding.startTimeRandom.preventDoubleInput()
        binding.startTimeResume.preventDoubleInput()
        binding.startTimeGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener

            val checkedId = checkedIds[0] // Get the single selected ID

            val newMode = when (checkedId) {
                binding.startTimeRestart.id -> StartTime.RESTART
                binding.startTimeRandom.id -> StartTime.RANDOM
                else -> StartTime.RESUME
            }
            preferencesManager.saveStartTime(newMode)

            if (newMode == StartTime.RANDOM && !randomStartTimeWarned) {
                Toast.makeText(requireContext(), R.string.warning_random_start_time_delay, Toast.LENGTH_LONG).show()
                randomStartTimeWarned = true
            }

            // Specific event sent to apply
            WallpaperEventBus.emit(WallpaperEvent.PlaybackModeChanged)
        }

        // StatusBar Color
        binding.statusBarDark.preventDoubleInput()
        binding.statusBarLight.preventDoubleInput()
        binding.statusBarAuto.preventDoubleInput()
        binding.statusBarColorGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener

            val checkedId = checkedIds[0] // Get the single selected ID

            val newMode = when (checkedId) {
                binding.statusBarDark.id -> StatusBarColor.DARK
                binding.statusBarLight.id -> StatusBarColor.LIGHT
                else -> StatusBarColor.AUTO
            }
            preferencesManager.saveStatusBarColor(newMode)

            if (newMode != StatusBarColor.AUTO && !statusBarColorWarned) {
                Toast.makeText(requireContext(), R.string.warning_status_bar_oem, Toast.LENGTH_SHORT).show()
                statusBarColorWarned = true
            }

            // Specific event sent to not reload video
            WallpaperEventBus.emit(WallpaperEvent.StatusBarColorChanged)
        }



        // Parallax Toggle
        binding.switchParallax.preventDoubleInput()
        binding.switchParallax.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUi) return@setOnCheckedChangeListener

            preferencesManager.setParallaxEnabled(isChecked)

            // Animation
            TransitionManager.beginDelayedTransition(binding.root as ViewGroup)

            // Changing visibility will make TransitionManager animate
            binding.layoutParallaxStrength.visibility = if (isChecked) View.VISIBLE else View.GONE

            if (!isChecked) {
                WallpaperEventBus.emit(WallpaperEvent.ParallaxChanged)
            }
        }

        // Parallax Strength Slider
        binding.sliderParallaxStrength.addOnSliderTouchListener(object :
            com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {
                // Do nothing
            }

            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                preferencesManager.setParallaxStrength(slider.value)
                // No need to broadcast here. The Engine reads the preference live
                // inside onOffsetsChanged(), so the strength updates instantly on next swipe
            }
        })



        // Video Picker & Floating Preview Dismiss
        binding.buttonPickVideo.setSafeOnClickListener {
            openFilePicker()
        }
        binding.cardVideoPreview.setSafeOnClickListener {
            openFilePicker()
        }

        setupBrainsEasterEgg()
    }

    private fun setupBrainsEasterEgg() {
        binding.tvBrains.setSafeOnClickListener(debounceMs = 0L) { view ->
            val now = System.currentTimeMillis()
            if (now - lastBrainsTapTime > 1100L) {
                brainsTapCount = 0
                undeadActivationJob?.cancel()
                setUndeadState(false)
            }
            lastBrainsTapTime = now

            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)

            undeadActivationJob?.cancel()

            if (brainsTapCount == 0) {
                targetTapCount = (6..9).random()

                val loreMessage = when (targetTapCount) {
                    6 -> "Specimen six exhibiting autonomous motor reflexes. 🧬"
                    7 -> "Quarantine breach logged in sector seven. ☣︎"
                    8 -> "Administering eight milligrams of formaldehyde. 💉"
                    9 -> "Sub-level nine isolation protocol active. 🕸"
                    else -> "Cellular decay anomaly detected. 🦠"
                }

                Toast.makeText(requireContext(), loreMessage, Toast.LENGTH_SHORT).show()
            }

            brainsTapCount++

            if (brainsTapCount == targetTapCount) {
                undeadActivationJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(1000)
                    Toast.makeText(requireContext(), "you walk with the undead.", Toast.LENGTH_SHORT).show()
                    setUndeadState(true)
                    brainsTapCount = 0
                }
            } else if (brainsTapCount > targetTapCount) {
                if (brainsTapCount == targetTapCount + 1) {
                    Toast.makeText(requireContext(), "Containment restored.", Toast.LENGTH_SHORT).show()
                    setUndeadState(false)
                }
            }
        }
    }

    private fun setUndeadState(isActive: Boolean) {
        preferencesManager.setUndead(isActive)
        if (!isActive) {
            preferencesManager.saveLoggingEnabled(false)
            FileLogger.setLoggingEnabled(false)
        }
        if (isAdded) {
            updateActionBarTitle()
        }
    }


    /**
     * Opens the file picker for selecting a video.
     */
    private fun openFilePicker() {
        val act = activity ?: return
        if (!act.hasWindowFocus()) {
            FileLogger.d(tag, "openFilePicker deferred: window focus transition")
            return
        }

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            // We request persistable permissions to access the file across device reboots.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            pickMediaLauncher.launch(intent)
        } catch (activityNotFoundException: ActivityNotFoundException) {
            FileLogger.e(tag, "Failed to open file picker", activityNotFoundException)
            Toast.makeText(context, R.string.error_file_picker_not_available, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Handles the selected media URI from the file picker.
     * It copies the selected video to the app's private storage to ensure
     * persistent access and then updates the video source.
     *
     * @param uri The content URI of the selected media.
     */
    private fun handleSelectedMedia(uri: Uri) {
        if (BuildConfig.DEBUG) {
            FileLogger.d(tag, "Handling selected media URI: $uri")
        } else {
            FileLogger.d(tag, "Handling selected media URI")
        }

        // Try to take persistable permission (Nice to have, but NOT required for copying)
        val contentResolver = requireActivity().contentResolver
        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: SecurityException) {
            FileLogger.w(tag, "Failed to take persistable URI permission. Proceeding with copy anyway.", e)
        }

        // Ingestion in coroutine
        viewLifecycleOwner.lifecycleScope.launch {
            // Wrap the entire copy operation in our new Loading Overlay
            withLoadingOverlay(getString(R.string.importing_video)) {
                val copyResult = try {
                    withContext(Dispatchers.IO) {
                        videoFileManager.createFileFromContentUri(uri)
                    }
                } catch (_: CancellationException) {
                    null // Return null to skip the block
                }

                when (copyResult) {
                    is VideoFileManager.CopyResult.Success -> {
                        val copiedFile = copyResult.file
                        val originalName = copyResult.originalName

                        // Immediately save the VideoSettings with the displayName and current page
                        withContext(Dispatchers.IO) {
                            preferencesManager.updateVideoSettings(copiedFile.name) {
                                it.copy(
                                    displayName = originalName,
                                    expectedFileSize = copiedFile.length(),
                                    page = playlistController?.currentPage ?: 0
                                )
                            }
                        }

                        val savedFileUri = Uri.fromFile(copiedFile)
                        if (BuildConfig.DEBUG) {
                            FileLogger.d(tag, "File copied to: $savedFileUri")
                        } else {
                            FileLogger.d(tag, "File copied to local storage")
                        }

                        // Load the new file into the RecyclerView
                        playlistController?.loadRecentFiles()

                        // Update the current video (now that the file is in the adapter)
                        updateVideoSource(
                            savedFileUri,
                            true,
                            isManualTap = true
                        ) // Automatically set as active wallpaper

                        // Notifies the service of a change in the playlist
                        WallpaperEventBus.emit(WallpaperEvent.PlaylistReordered)
                    }

                    is VideoFileManager.CopyResult.SizeLimitExceeded -> {
                        Toast.makeText(context, getString(R.string.error_file_too_large), Toast.LENGTH_LONG).show()
                    }

                    is VideoFileManager.CopyResult.DimensionsExceeded -> {
                        Toast.makeText(context, getString(R.string.error_video_too_large), Toast.LENGTH_LONG).show()
                    }

                    is VideoFileManager.CopyResult.CorruptFile -> {
                        Toast.makeText(context, getString(R.string.error_cannot_play_video), Toast.LENGTH_LONG).show()
                    }

                    is VideoFileManager.CopyResult.Error -> {
                        if (BuildConfig.DEBUG) {
                            FileLogger.e(tag, "Failed to copy file from URI: $uri")
                        } else {
                            FileLogger.e(tag, "Failed to copy file from URI")
                        }
                        Toast.makeText(context, getString(R.string.error_copy_failed), Toast.LENGTH_LONG).show()
                    }

                    null -> {
                        // Operation was cancelled
                    }
                }
            }

        }

    }

    /**
     * Sets up the video preview.
     *
     * @param uri The URI of the video to be previewed.
     */
    @OptIn(UnstableApi::class)
    private fun setupVideoPreview(uri: Uri) {
        // Release any existing player first
        releasePreviewPlayer()

        // Define a 32MB Memory Cap (matching the service)
        val targetBufferBytes = 32 * 1024 * 1024

        // Configure the LoadControl
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
            .setBufferDurationsMs(
                15_000, // Min buffer 15
                30_000, // Max buffer 30
                2_500,  // Buffer to start playback
                5_000   // Buffer for rebuffer
            )
            .setTargetBufferBytes(targetBufferBytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        previewPlayer = ExoPlayer.Builder(requireContext())
            .setLoadControl(loadControl)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ALL
                volume = 0f // Muted

                val mediaItem = MediaItem.fromUri(uri)
                setMediaItem(mediaItem)

                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        if (BuildConfig.DEBUG) {
                            FileLogger.e(tag, "ExoPlayer error in preview: ${error.message}", error)
                        } else {
                            FileLogger.e(tag, "ExoPlayer error in preview", error)
                        }

                        // Check if the file vanished (happens during UUID migration)
                        var isFileNotFound = false
                        var currentCause: Throwable? = error
                        while (currentCause != null) {
                            if (currentCause is FileNotFoundException) {
                                isFileNotFound = true
                                break
                            }
                            currentCause = currentCause.cause
                        }

                        if (isFileNotFound) {
                            // File was migrated. Silently reload the new active URI.
                            val newUri = preferencesManager.getActiveVideoUri()
                            if (newUri != null) {
                                viewLifecycleOwner.lifecycleScope.launch {
                                    updateVideoSource(newUri.toUri(), false)
                                }
                            }
                            return
                        }

                        if (context != null) {
                            Toast.makeText(context, getString(R.string.error_cannot_play_video), Toast.LENGTH_SHORT)
                                .show()
                        }
                    }
                })

                prepare()
                playWhenReady = true
            }

        binding.videoPreview.player = previewPlayer

        // Fetch and apply the blurred thumbnail background
        uri.path?.let { path ->
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val file = File(path)
                    if (file.exists()) {
                        val bitmap = withContext(Dispatchers.IO) {
                            videoFileManager.getOrGenerateThumbnail(file)
                        }
                        if (bitmap != null && _binding != null) {
                            BlurHelper.applyBlurToImageView(binding.ivVideoBlurBg, bitmap)
                        }
                    }
                } catch (e: Exception) {
                    FileLogger.e(tag, "Error loading thumbnail for blurred background", e)
                }
            }
        }
    }

    private fun releasePreviewPlayer() {
        // Detach SurfaceView first so HWUI/BLASTBufferQueue does not hold locks during window stop
        _binding?.videoPreview?.player = null
        // Stop playback and clear video surface
        previewPlayer?.stop()
        previewPlayer?.clearVideoSurface()
        // Release player safely
        previewPlayer?.release()
        previewPlayer = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (_binding != null) {
            floatingPreviewController?.onConfigurationChanged()
            binding.root.post {
                if (_binding != null) {
                    ViewCompat.requestApplyInsets(binding.settingsScrollView)
                }
            }
        }
    }

    private fun setupLandscapeInsetsBalancing() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsScrollView) { view, windowInsets ->
            val rootInsets = ViewCompat.getRootWindowInsets(view) ?: windowInsets
            val cutout = rootInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val sysBars = rootInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            val leftInset = maxOf(cutout.left, sysBars.left)
            val rightInset = maxOf(cutout.right, sysBars.right)

            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val basePadding = (16 * resources.displayMetrics.density).toInt()

            if (isLandscape) {
                val diffLeft = maxOf(0, rightInset - leftInset)
                val diffRight = maxOf(0, leftInset - rightInset)
                binding.settingsContentContainer.setPadding(
                    basePadding + diffLeft,
                    basePadding,
                    basePadding + diffRight,
                    basePadding
                )
            } else {
                binding.settingsContentContainer.setPadding(
                    basePadding,
                    basePadding,
                    basePadding,
                    basePadding
                )
            }
            windowInsets
        }
        ViewCompat.requestApplyInsets(binding.settingsScrollView)
    }


    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        accordionController?.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        floatingPreviewController?.cleanup()
        floatingPreviewController = null
        playlistController?.cleanup()
        playlistController = null
        gestureControlsController = null
        accordionController = null
        undeadActivationJob?.cancel()
        undeadActivationJob = null
        // Ensure we don't leak the preview player
        releasePreviewPlayer()
        _binding = null
    }

    override fun onResume() {
        super.onResume()
        checkBatteryOptimization()
        // Snapshot Sync: Pull the latest active video from the background service when the UI opens.
        val activeUriString = preferencesManager.getActiveVideoUri()
        if (!activeUriString.isNullOrEmpty()) {
            val activeUri = activeUriString.toUri()

            // If the UI is out of sync with the background service (e.g. from a gesture)
            if (sharedViewModel.selectedVideoUri != activeUri) {
                sharedViewModel.selectedVideoUri = activeUri

                // Update the adapter highlight
                playlistController?.setActiveVideoUri(activeUriString)
            }
        }

        // Resume playback or initialize the player if it doesn't exist
        val targetUri = sharedViewModel.selectedVideoUri
            ?: preferencesManager.getActiveVideoUri()?.toUri()
        targetUri?.let { uri ->
            if (previewPlayer == null) {
                setupVideoPreview(uri)
            } else {
                previewPlayer?.playWhenReady = true
            }
        }

        updateActionBarTitle()
    }

    private fun updateActionBarTitle() {
        // Highlight "Undead" in the action bar title if we are undead
        if (preferencesManager.isUndead()) {
            val title = getString(R.string.first_fragment_label)
            val spannable = SpannableString(title)

            var index = title.indexOf("Undead", ignoreCase = true)
            var length = 6
            if (index == -1) {
                index = title.indexOf("亡灵", ignoreCase = true)
                length = 2
            }

            if (index != -1) {
                val greenColor = ContextCompat.getColor(requireContext(), R.color.light_green)
                spannable.setSpan(
                    ForegroundColorSpan(greenColor),
                    index,
                    index + length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                (activity as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.title = spannable
            }
        } else {
            (activity as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.title =
                getString(R.string.first_fragment_label)
        }
    }

    override fun onPause() {
        super.onPause()
        // Aggressively release resources when the settings screen is not active
        // This frees up the decoder for the actual wallpaper service
        releasePreviewPlayer()
    }

}