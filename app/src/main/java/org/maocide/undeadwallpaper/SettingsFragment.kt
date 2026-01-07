package org.maocide.undeadwallpaper

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.MediaController
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maocide.undeadwallpaper.databinding.FragmentSettingsBinding
import org.maocide.undeadwallpaper.model.PlaybackMode
import org.maocide.undeadwallpaper.model.ScalingMode
import androidx.core.view.isVisible
import com.google.android.material.slider.Slider
import org.maocide.undeadwallpaper.model.StatusBarColor
import kotlin.math.roundToInt

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 * This fragment allows the user to select a video and set it as a live wallpaper.
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val tag: String = javaClass.simpleName
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var videoFileManager: VideoFileManager
    private lateinit var recentFilesAdapter: RecentFilesAdapter
    private val recentFiles = mutableListOf<RecentFile>()
    private var currentVideoDurationMs: Long = 0L
    private var previewMediaPlayer: MediaPlayer? = null

    private var isUpdatingUi = false

    // Initialize the shared ViewModel
    private val sharedViewModel: SettingsViewModel by activityViewModels()

    companion object { // key for bundle in restoring instance state
        private const val KEY_ADVANCED_EXPANDED = "key_advanced_expanded"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        // Check if the binding is initialized and the view exists
        if (_binding != null) {
            val isVisible = binding.advancedOptionsContainer.isVisible
            // Save the state of the accordion
            outState.putBoolean(KEY_ADVANCED_EXPANDED, isVisible)
        }
    }

    /**
     * Launcher for picking media from the file system.
     */
    private val pickMediaLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleSelectedMedia(uri)
            }
        }
    }

    /**
     * Retrieves the duration of a video from its URI.
     * @param uri The URI of the video.
     * @return The duration in milliseconds, or 0L if it cannot be determined.
     */
    private fun getVideoDuration(uri: Uri): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val durationString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            durationString?.toLong() ?: 0L
        } catch (e: Exception) {
            Log.e(tag, "Failed to get video duration for URI: $uri", e)
            0L
        }
    }

    /**
     * Launcher for requesting permissions.
     */
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d(tag, "Permission granted by user.")
            openFilePicker()
        } else {
            Log.d(tag, "Permission denied by user.")
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
        savedInstanceState?.let { restoreState(it) }
        setupRecyclerView()


        // DISABLE VIEW STATE SAVING FOR SLIDERS, it might overwrite syncUiState
        binding.positionXSlider.isSaveEnabled = false
        binding.positionYSlider.isSaveEnabled = false
        binding.zoomSlider.isSaveEnabled = false
        binding.rotationSlider.isSaveEnabled = false
        binding.brightnessSlider.isSaveEnabled = false

        // ASYNC TASKS (Data Loading)
        lifecycleScope.launch {
            // This might take time on first run (copying file)
            ensureDefaultVideoExists()

            // load the data/settings
            syncUiState()
            setupListeners()
            loadRecentFiles()
        }

    }

    /**
     * Restores the state of the UI after a configuration change (e.g., screen rotation).
     * Specifically, this handles the expanded/collapsed state of the "Advanced Options" accordion.
     *
     * @param savedInstanceState The bundle containing the saved state, typically from `onSaveInstanceState`.
     */
    private fun restoreState(savedInstanceState: Bundle) {
        // RESTORE ACCORDION STATE
        val isExpanded = savedInstanceState.getBoolean(KEY_ADVANCED_EXPANDED, false)

        if (isExpanded) {
            // 1. Show the container
            binding.advancedOptionsContainer.visibility = View.VISIBLE


            binding.imageArrow.rotation = 180f
        } else {
            binding.advancedOptionsContainer.visibility = View.GONE
            binding.imageArrow.rotation = 0f
        }
    }

    /**
     * Centralized function to handle setting or changing the video source.
     * This function ensures that whenever a new video is selected, the UI and
     * preferences are reset and updated correctly.
     *
     * @param uri The URI of the new video file.
     * @param sendBroadcast Weather to send a broadcast to the service to cause a video reload.
     */
    private fun updateVideoSource(uri: Uri, forceChange: Boolean) {
        // Clear any previous trimming data
        preferencesManager.removeClippingTimes()

        // Store the new video URI as a shared value
        sharedViewModel.selectedVideoUri = uri

        // Get duration and update UI
        currentVideoDurationMs = getVideoDuration(uri)
        if (currentVideoDurationMs == 0L) {
            // Handle case where duration is invalid or video is corrupt
            Toast.makeText(context, "Could not read video duration.", Toast.LENGTH_LONG).show()
        }

        // Update the video preview
        setupVideoPreview(uri)

        // Save the preference and notify the service to reload the video from that value
        if(forceChange) {
            preferencesManager.saveVideoUri(uri.toString())
            val intent = Intent(UndeadWallpaperService.ACTION_VIDEO_URI_CHANGED)
            context?.sendBroadcast(intent)
        }

        // Refresh the recent files list
        loadRecentFiles()

    }


    /**
     * Sets up the RecyclerView for displaying recent files.
     */
    private fun setupRecyclerView() {
        recentFilesAdapter = RecentFilesAdapter(
            recentFiles,
            onItemClick = { recentFile ->
                val fileUri = Uri.fromFile(recentFile.file)
                updateVideoSource(fileUri, false)
            }
        )
        binding.recyclerViewRecentFiles.layoutManager = LinearLayoutManager(context)
        binding.recyclerViewRecentFiles.adapter = recentFilesAdapter
    }

    /**
     * Loads the list of recent files and updates the RecyclerView.
     */
    private fun loadRecentFiles() {
        viewLifecycleOwner.lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                val rawFiles = videoFileManager.loadRecentFiles()
                rawFiles.sortedWith(compareBy({ it.file.lastModified() }, { it.file.name })).reversed()
            }
            recentFiles.clear()
            recentFiles.addAll(files)
            recentFilesAdapter.notifyDataSetChanged()
        }
    }

    /**
     * Checks if we need to copy the default video.
     * specific to Dispatchers.IO to keep UI smooth.
     */
    private suspend fun ensureDefaultVideoExists() = withContext(Dispatchers.IO) {
        if (preferencesManager.getVideoUri() == null) {
            val defaultFile = videoFileManager.createDefaultFileFromResource(
                R.raw.zombillie_default,
                getString(R.string.default_video_filename)
            )

            if (defaultFile != null) {
                val defaultUri = Uri.fromFile(defaultFile)
                // Switch back to Main thread to update Prefs safely
                withContext(Dispatchers.Main) {
                    preferencesManager.saveVideoUri(defaultUri.toString())
                    // Update your variable/preview here if needed
                    updateVideoSource(defaultUri, false)
                }
            }
        }
    }

    /**
     * Update all Buttons/Switches to match Preferences.
     * Calling this BEFORE listeners prevents accidental triggers.
     * Also called by reset button listener after resetting values
     */
    private fun syncUiState() {

        // SET to avoid overriding
        isUpdatingUi = true

        try {

            // Audio
            binding.switchAudio.isChecked = preferencesManager.isAudioEnabled()

            // Playback Mode
            when (preferencesManager.getPlaybackMode()) {
                PlaybackMode.LOOP -> binding.playbackModeGroup.check(binding.playbackModeLoop.id)
                PlaybackMode.ONE_SHOT -> binding.playbackModeGroup.check(binding.playbackModeOneshot.id)
            }

            // Scaling Mode
            when (preferencesManager.getScalingMode()) {
                ScalingMode.FIT -> binding.scalingModeGroup.check(binding.scalingModeFit.id)
                ScalingMode.FILL -> binding.scalingModeGroup.check(binding.scalingModeFill.id)
                ScalingMode.STRETCH -> binding.scalingModeGroup.check(binding.scalingModeStretch.id)
            }

            // StatusBar Color
            when (preferencesManager.getStatusBarColor()) {
                StatusBarColor.AUTO -> binding.statusBarColorGroup.check(binding.statusBarAuto.id)
                StatusBarColor.DARK -> binding.statusBarColorGroup.check(binding.statusBarDark.id)
                StatusBarColor.LIGHT -> binding.statusBarColorGroup.check(binding.statusBarLight.id)
            }

            // Load sliders for advanced (using safe loading)
            binding.positionXSlider.setValueSafe(preferencesManager.getPositionX())
            binding.positionYSlider.setValueSafe(preferencesManager.getPositionY())
            binding.zoomSlider.setValueSafe(preferencesManager.getZoom())
            binding.rotationSlider.setValueSafe(preferencesManager.getRotation())
            binding.brightnessSlider.setValueSafe(preferencesManager.getBrightness())

            // Load Video Preview and set the video as selected
            preferencesManager.getVideoUri()?.let { uriString ->
                //setupVideoPreview(uriString.toUri())
                updateVideoSource(uriString.toUri(), false)
            }
        } finally {
            isUpdatingUi = false
        }

    }

    private fun resetPreferencesToDefaults() {
        preferencesManager.apply {
            setScalingMode(ScalingMode.FILL)
            savePositionX(0.0f)
            savePositionY(0.0f)
            saveZoom(1.0f)
            saveRotation(0f)
            saveBrightness(1.0f)
        }
    }


    /**
     * Sets up all the listeners for controls.
     */
    private fun setupListeners() {
        // Helper to broadcast changes
        fun notifySettingsChanged() {
            val intent = Intent(UndeadWallpaperService.ACTION_PLAYBACK_MODE_CHANGED).apply {
                setPackage(requireContext().packageName)
            }
            requireContext().applicationContext.sendBroadcast(intent)
        }

        // Helper to setup slider safe listeners, to avoid sending too many
        fun setupSafeSlider(slider: Slider, saveAction: (Float) -> Unit) {
            slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {
                    // Do nothing when touch starts
                }

                override fun onStopTrackingTouch(slider: Slider) {
                    // If is updating skip this event to not override
                    if (isUpdatingUi) return

                    // Only save and broadcast when the user lifts their finger
                    saveAction(slider.value)
                    notifySettingsChanged()
                }
            })
        }

        // Playback Mode
        binding.playbackModeGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            // If nothing is selected, skip
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener

            val checkedId = checkedIds[0] // Get the single selected ID

            val newMode = when (checkedId) {
                binding.playbackModeOneshot.id -> PlaybackMode.ONE_SHOT
                else -> PlaybackMode.LOOP
            }

            preferencesManager.setPlaybackMode(newMode)
            notifySettingsChanged()
        }

        // StatusBar Color
        binding.statusBarColorGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener

            val checkedId = checkedIds[0] // Get the single selected ID

            val newMode = when (checkedId) {
                binding.statusBarDark.id -> StatusBarColor.DARK
                binding.statusBarLight.id -> StatusBarColor.LIGHT
                else -> StatusBarColor.AUTO
            }
            preferencesManager.saveStatusBarColor(newMode)

            // Specific intent sent to not reload video
            val intent = Intent(UndeadWallpaperService.ACTION_STATUS_BAR_COLOR_CHANGED).apply {
                setPackage(requireContext().packageName)
            }
            requireContext().applicationContext.sendBroadcast(intent)
        }

        // Audio Switch
        binding.switchAudio.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.saveAudioEnabled(isChecked)
            // Update local preview immediately
            previewMediaPlayer?.setVolume(if (isChecked) 1f else 0f, if (isChecked) 1f else 0f)
            notifySettingsChanged()
        }

        // Accordion Logic and animation
        binding.layoutHeader.setOnClickListener {
            val advancedOptionsContainer = binding.advancedOptionsContainer
            val isVisible = advancedOptionsContainer.isVisible

            TransitionManager.beginDelayedTransition(binding.accordionCard as ViewGroup, AutoTransition())
            advancedOptionsContainer.visibility = if (isVisible) View.GONE else View.VISIBLE

            val rotation = if (!isVisible) 180f else 0f
            binding.imageArrow.animate().rotation(rotation).setDuration(200).start()
        }

        // Scaling Mode
        binding.scalingModeGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener

            val checkedId = checkedIds[0] // Get the single selected ID

            val newMode = when (checkedId) {
                binding.scalingModeFit.id -> ScalingMode.FIT
                binding.scalingModeStretch.id -> ScalingMode.STRETCH
                else -> ScalingMode.FILL
            }
            preferencesManager.setScalingMode(newMode)
            notifySettingsChanged()
        }

        // Video Picker
        binding.buttonPickVideo.setOnClickListener {
            checkPermissionAndOpenFilePicker()
        }

        // Advanced Settings
        // Brightness
        setupSafeSlider(binding.brightnessSlider) { value ->
            preferencesManager.saveBrightness(value)
        }

        // Horizontal Position (X)
        setupSafeSlider(binding.positionXSlider) { value ->
            preferencesManager.savePositionX(value)
        }

        // Vertical Position (Y)
        setupSafeSlider(binding.positionYSlider) { value ->
            preferencesManager.savePositionY(value)
        }

        // Zoom
        setupSafeSlider(binding.zoomSlider) { value ->
            preferencesManager.saveZoom(value)
        }

        // Rotation
        setupSafeSlider(binding.rotationSlider) { value ->
            preferencesManager.saveRotation(value)
        }

        // Reset Values in UI
        binding.buttonResetAdvanced.setOnClickListener {
            // Reset and save values
            resetPreferencesToDefaults()

            // Reload UI (Warning!! might refire listeners of controls! Should be ok...)
            syncUiState()

            // Notify wallpaper service
            notifySettingsChanged()
        }
    }

    /**
     * Checks for storage permissions and opens the file picker.
     * This function is carefully designed to handle the different permission models
     * across various Android versions.
     */
    private fun checkPermissionAndOpenFilePicker() {
        // We define the permission we need based on the Android version.
        // Build.VERSION.SDK_INT is the API level of the device's OS.
        val permission =
            // For Android 13 (TIRAMISU, API 33) and higher, we need READ_MEDIA_VIDEO.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_VIDEO
            }
            // For all older versions (Android 12L and below), we use the classic storage permission.
            else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

        // Now, we check if we already have the permission.
        when {
            // If the permission is already granted, we can proceed directly.
            ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED -> {
                Log.d(tag, "Permission '$permission' already granted. Opening picker.")
                openFilePicker()
            }
            // If we want to show a popup explaining why we need the permission.
            // For now, we'll just request it directly.
            shouldShowRequestPermissionRationale(permission) -> {
                Log.d(tag, "Showing rationale for permission request.")
                requestPermissionLauncher.launch(permission)
            }
            // If we don't have the permission, we launch the request.
            else -> {
                Log.d(tag, "Requesting permission: $permission")
                requestPermissionLauncher.launch(permission)
            }
        }
    }


    /**
     * Opens the file picker for selecting a video.
     */
    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            // We request persistable permissions to access the file across device reboots.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        pickMediaLauncher.launch(intent)
    }

    /**
     * Handles the selected media URI from the file picker.
     * It copies the selected video to the app's private storage to ensure
     * persistent access and then updates the video source.
     *
     * @param uri The content URI of the selected media.
     */
    private fun handleSelectedMedia(uri: Uri) {
        Log.d(tag, "Handling selected media URI: $uri")

        // Ensure we have persistable permission for this URI
        val contentResolver = requireActivity().contentResolver
        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: SecurityException) {
            Log.e(tag, "Failed to take persistable URI permission for $uri", e)
            Toast.makeText(context, getString(R.string.error_permission_failed), Toast.LENGTH_LONG).show()
            return
        }

        try {
            // Warn for very large videos, might exceed video card VRAM
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0

            // Safety Check: 4K Video (roughly 3840x2160 = 8.2 million pixels)
            if (width * height > 4000 * 2000) {
                Toast.makeText(context, getString(R.string.warning_4k_video), Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to get video dimensions for $uri", e)
        }


        // Copy the file to internal storage in a background thread
        viewLifecycleOwner.lifecycleScope.launch {
            val copiedFile = withContext(Dispatchers.IO) {
                videoFileManager.createFileFromContentUri(uri)
            }
            if (copiedFile != null) {
                val savedFileUri = Uri.fromFile(copiedFile)
                Log.d(tag, "File copied to: $savedFileUri")
                updateVideoSource(savedFileUri, false) // Centralized update logic
            } else {
                Log.e(tag, "Failed to copy file from URI: $uri")
                Toast.makeText(context, getString(R.string.error_copy_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Sets up the video preview.
     *
     * @param uri The URI of the video to be previewed.
     */
    private fun setupVideoPreview(uri: Uri) {
        binding.videoPreview.setVideoURI(uri)
        val mediaController = MediaController(requireContext())
        mediaController.setAnchorView(binding.videoPreview)
        binding.videoPreview.setMediaController(mediaController)
        binding.videoPreview.setOnPreparedListener {
            it.isLooping = true
            previewMediaPlayer = it
            binding.videoPreview.start()
            if(binding.switchAudio.isChecked) it.setVolume(1f, 1f)
            else it.setVolume(0f, 0f)
        }
        binding.videoPreview.setOnErrorListener { _, _, _ ->
            Toast.makeText(context, getString(R.string.error_cannot_play_video), Toast.LENGTH_SHORT).show()
            true
        }
    }

    /**
     * Converts milliseconds to a HH:MM:SS.mmm formatted string.
     * @param millis The time in milliseconds.
     * @return A string in HH:MM:SS.mmm format, or an empty string if input is negative.
     */
    private fun formatMillisecondsToHHMMSSmmm(millis: Long): String {
        if (millis < 0) return "" // Return empty for invalid or unset times
        val hours = millis / (1000 * 60 * 60)
        val minutes = (millis % (1000 * 60 * 60)) / (1000 * 60)
        val seconds = (millis % (1000 * 60)) / 1000
        val milliseconds = millis % 1000
        return String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, milliseconds)
    }

    /**
     * Safely sets the value of a Material Slider, preventing crashes from out-of-range/step values.
     *
     * This extension function ensures that any value assigned to the slider is first clamped
     * to be within the slider's `valueFrom` and `valueTo` range. If the slider has a `stepSize`
     * defined, the function will also snap the clamped value to the nearest valid step.
     *
     * This is useful for programmatically setting slider values that might come from external
     * sources (like saved preferences) without causing an `IllegalArgumentException`.
     *
     * @param newValue The desired new value for the slider.
     */
    fun com.google.android.material.slider.Slider.setValueSafe(newValue: Float) {
        // 1. Clamp: Ensure value is strictly between valueFrom and valueTo
        val clampedValue = newValue.coerceIn(valueFrom, valueTo)

        // 2. Snap: If a stepSize is defined, ensure the value fits the step
        val finalValue = if (stepSize > 0) {
            // Calculate how many "steps" we are from the start
            val steps = ((clampedValue - valueFrom) / stepSize).roundToInt()
            // Reconstruct the value based on exact steps
            valueFrom + (steps * stepSize)
        } else {
            clampedValue
        }

        // 3. Apply: Only now is it safe to set the value
        this.value = finalValue
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Ensure we don't leak the preview player
        binding.videoPreview.stopPlayback()
        _binding = null
    }

    override fun onPause() {
        super.onPause()
        // Aggressively release resources when the settings screen is not active
        // This frees up the decoder for the actual wallpaper service
        binding.videoPreview.suspend()
    }

}