package org.maocide.undeadwallpaper.ui

import org.maocide.undeadwallpaper.databinding.FragmentSettingsBinding

import org.maocide.undeadwallpaper.R
import org.maocide.undeadwallpaper.BuildConfig

import org.maocide.undeadwallpaper.data.PreferencesManager
import org.maocide.undeadwallpaper.data.VideoFileManager
import org.maocide.undeadwallpaper.model.PlaybackMode
import org.maocide.undeadwallpaper.model.StartTime
import org.maocide.undeadwallpaper.model.StatusBarColor
import org.maocide.undeadwallpaper.model.KineticEffectType
import org.maocide.undeadwallpaper.event.WallpaperEvent
import org.maocide.undeadwallpaper.event.WallpaperEventBus
import org.maocide.undeadwallpaper.utils.FileLogger
import org.maocide.undeadwallpaper.utils.HapticHelper
import org.maocide.undeadwallpaper.utils.preventDoubleInput
import org.maocide.undeadwallpaper.utils.setSafeOnClickListener
import org.maocide.undeadwallpaper.ui.preview.FloatingPreviewController
import org.maocide.undeadwallpaper.ui.preview.PreviewFsm
import org.maocide.undeadwallpaper.ui.preview.VideoPreviewController
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

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


import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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

import androidx.media3.common.util.UnstableApi
import kotlin.time.Duration.Companion.milliseconds

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
    private var accordionController: AccordionController? = null
    private var videoPreviewController: VideoPreviewController? = null
    private var randomStartTimeWarned = false
    private var isUpdatingUi = false
    private var statusBarColorWarned = false

    private var undeadActivationJob: Job? = null
    private var lastUpdateVideoSourceTime = 0L
    private var floatingPreviewController: FloatingPreviewController? = null
    private val previewFsm = PreviewFsm()
    private var childFragmentLifecycleCallbacks: FragmentManager.FragmentLifecycleCallbacks? = null

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

        videoPreviewController = VideoPreviewController(
            binding = binding,
            preferencesManager = preferencesManager,
            videoFileManager = videoFileManager,
            coroutineScope = viewLifecycleOwner.lifecycleScope,
            previewFsm = previewFsm,
            getSelectedVideoUri = { sharedViewModel.selectedVideoUri },
            onVideoMigrated = { newUri ->
                viewLifecycleOwner.lifecycleScope.launch {
                    updateVideoSource(newUri, false)
                }
            }
        ).apply {
            setup()
        }

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
                val needsRecovery = videoPreviewController?.needsRecovery == true

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

        floatingPreviewController = FloatingPreviewController(
            binding = binding,
            preferencesManager = preferencesManager,
            onCardVisibilityChanged = { isVisible ->
                previewFsm.processEvent(PreviewFsm.PreviewEvent.CardVisibilityChanged(isVisible))
            }
        ).apply {
            setup()
        }
        setupChildFragmentLifecycleListener()
        accordionController = AccordionController(
            rootView = binding.root as ViewGroup,
            binding = binding,
            preferencesManager = preferencesManager
        ).apply {
            setup()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                WallpaperEventBus.events.collect { event ->
                    if (event is WallpaperEvent.VideoSettingsChanged) {
                        playlistController?.notifyDataSetChanged()
                        videoPreviewController?.refreshTransforms(forceRedraw = true)
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
            videoPreviewController?.loadVideo(uri)
        }

        // Save the preference and notify the service to reload the video from that value
        if (forceChange) {
            withContext(Dispatchers.IO) {
                preferencesManager.saveActiveVideoUri(uri.toString())
                preferencesManager.saveAnchorVideoUri(uri.toString())
            }
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
                        preferencesManager.saveAnchorVideoUri(uri.toString())
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

            // Undead Affects
            val currentEffect = preferencesManager.getKineticEffectType()
            when (currentEffect) {
                KineticEffectType.NONE -> binding.chipKineticNone.isChecked = true
                KineticEffectType.SEISMIC_JOLT -> binding.chipKineticJolt.isChecked = true
                KineticEffectType.VERTICAL_HOP -> binding.chipKineticHop.isChecked = true
                KineticEffectType.ELASTIC_BOUNCE -> binding.chipKineticBounce.isChecked = true
                else -> binding.chipKineticNone.isChecked = true
            }
            val hasActiveEffect = currentEffect != KineticEffectType.NONE
            binding.layoutKineticSubSettings.visibility = if (hasActiveEffect) View.VISIBLE else View.GONE
            binding.sliderKineticStrength.value = preferencesManager.getKineticIntensity()
            binding.switchKineticHaptics.isChecked = preferencesManager.isKineticHapticsEnabled()

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
                defaultUndeadAffects = hasActiveEffect,
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
            if (isUpdatingUi || checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            accordionController?.dismissNewBadge(AccordionType.GLOBAL_SETTINGS)

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
                preferencesManager.saveAnchorVideoUri(highlightedUri)
            } else {
                val currentSelectedUri =
                    sharedViewModel.selectedVideoUri?.toString() ?: preferencesManager.getActiveVideoUri()
                if (currentSelectedUri != null) {
                    preferencesManager.saveActiveVideoUri(currentSelectedUri)
                    preferencesManager.saveAnchorVideoUri(currentSelectedUri)
                }
            }

            notifySettingsChanged()
        }

        // StartTime preference
        binding.startTimeRestart.preventDoubleInput()
        binding.startTimeRandom.preventDoubleInput()
        binding.startTimeResume.preventDoubleInput()
        binding.startTimeGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isUpdatingUi || checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            accordionController?.dismissNewBadge(AccordionType.GLOBAL_SETTINGS)

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
        binding.statusBarColorGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isUpdatingUi || checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            accordionController?.dismissNewBadge(AccordionType.GLOBAL_SETTINGS)

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
            accordionController?.dismissNewBadge(AccordionType.PARALLAX)

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
        binding.sliderParallaxStrength.preventDoubleInput()
        binding.sliderParallaxStrength.addOnSliderTouchListener(object :
            com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {
                // Do nothing
            }

            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                accordionController?.dismissNewBadge(AccordionType.PARALLAX)
                preferencesManager.setParallaxStrength(slider.value)
                // No need to broadcast here. The Engine reads the preference live
                // inside onOffsetsChanged(), so the strength updates instantly on next swipe
            }
        })

        // Undead Affects Chips
        binding.chipKineticNone.preventDoubleInput()
        binding.chipKineticJolt.preventDoubleInput()
        binding.chipKineticHop.preventDoubleInput()
        binding.chipKineticBounce.preventDoubleInput()

        binding.chipGroupKineticEffect.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isUpdatingUi) return@setOnCheckedStateChangeListener
            accordionController?.dismissNewBadge(AccordionType.UNDEAD_AFFECTS)

            val selectedType = when (checkedIds.firstOrNull()) {
                R.id.chip_kinetic_jolt -> KineticEffectType.SEISMIC_JOLT
                R.id.chip_kinetic_hop -> KineticEffectType.VERTICAL_HOP
                R.id.chip_kinetic_bounce -> KineticEffectType.ELASTIC_BOUNCE
                else -> KineticEffectType.NONE
            }

            preferencesManager.setKineticEffectType(selectedType)

            TransitionManager.beginDelayedTransition(binding.root as ViewGroup)
            binding.layoutKineticSubSettings.visibility =
                if (selectedType != KineticEffectType.NONE) View.VISIBLE else View.GONE

            WallpaperEventBus.emit(WallpaperEvent.KineticSettingsChanged)
        }

        // Affect Strength Slider
        binding.sliderKineticStrength.preventDoubleInput()
        binding.sliderKineticStrength.addOnSliderTouchListener(object :
            com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {
                // Do nothing
            }

            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                accordionController?.dismissNewBadge(AccordionType.UNDEAD_AFFECTS)
                preferencesManager.setKineticIntensity(slider.value)
                WallpaperEventBus.emit(WallpaperEvent.KineticSettingsChanged)
            }
        })

        // Tactile Vibration Sync Switch
        binding.switchKineticHaptics.preventDoubleInput()
        binding.switchKineticHaptics.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            accordionController?.dismissNewBadge(AccordionType.UNDEAD_AFFECTS)
            preferencesManager.setKineticHapticsEnabled(isChecked)
            if (isChecked) {
                HapticHelper.performDualShortFeedback(requireContext())
            }
            WallpaperEventBus.emit(WallpaperEvent.KineticSettingsChanged)
        }


        // Video Picker & Floating Preview Dismiss
        binding.buttonPickVideo.setSafeOnClickListener {
            openFilePicker()
        }
        binding.cardVideoPreview.setSafeOnClickListener {
            openFilePicker()
        }

        setupBrainsEasterEgg()
    }

    private fun setupChildFragmentLifecycleListener() {
        val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
                if (f is BottomSheetDialogFragment) {
                    previewFsm.processEvent(PreviewFsm.PreviewEvent.SheetVisibilityChanged(isOccluded = true))
                }
            }

            override fun onFragmentStopped(fm: FragmentManager, f: Fragment) {
                val hasActiveSheet = fm.fragments.any {
                    it is BottomSheetDialogFragment && it.isAdded && !it.isRemoving && it != f
                }
                if (!hasActiveSheet) {
                    videoPreviewController?.refreshTransforms(forceRedraw = true)
                    previewFsm.processEvent(PreviewFsm.PreviewEvent.SheetVisibilityChanged(isOccluded = false))
                }
            }
        }
        childFragmentLifecycleCallbacks = callbacks
        childFragmentManager.registerFragmentLifecycleCallbacks(callbacks, false)
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
                    delay(1000.milliseconds)
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

                    is VideoFileManager.CopyResult.DimensionsExceeded,
                    is VideoFileManager.CopyResult.CorruptFile,
                    is VideoFileManager.CopyResult.StaticTissueRejected,
                    is VideoFileManager.CopyResult.Error -> {
                        if (copyResult is VideoFileManager.CopyResult.Error) {
                            if (BuildConfig.DEBUG) {
                                FileLogger.e(tag, "Failed to copy file from URI: $uri")
                            } else {
                                FileLogger.e(tag, "Failed to copy file from URI")
                            }
                        } else {
                            FileLogger.w(tag, "File ingestion rejected: ${copyResult.javaClass.simpleName}")
                        }
                        Toast.makeText(context, getString(R.string.error_cannot_play_video), Toast.LENGTH_LONG).show()
                    }

                    null -> {
                        // Operation was cancelled
                    }
                }
            }

        }

    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (_binding != null) {
            floatingPreviewController?.onConfigurationChanged()
            binding.root.post {
                if (_binding != null) {
                    videoPreviewController?.updateSurfaceDimensions()
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
        childFragmentLifecycleCallbacks?.let {
            childFragmentManager.unregisterFragmentLifecycleCallbacks(it)
            childFragmentLifecycleCallbacks = null
        }
        floatingPreviewController?.cleanup()
        floatingPreviewController = null
        videoPreviewController?.cleanup()
        videoPreviewController = null
        playlistController?.cleanup()
        playlistController = null
        accordionController = null
        undeadActivationJob?.cancel()
        undeadActivationJob = null
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
        videoPreviewController?.onResume(targetUri)

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
        // This frees up the decoder and GL context for the actual wallpaper service
        videoPreviewController?.onPause()
    }

}