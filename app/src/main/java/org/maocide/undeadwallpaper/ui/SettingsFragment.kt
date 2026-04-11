package org.maocide.undeadwallpaper.ui

import org.maocide.undeadwallpaper.databinding.FragmentSettingsBinding

import org.maocide.undeadwallpaper.R
import org.maocide.undeadwallpaper.BuildConfig

import org.maocide.undeadwallpaper.data.PreferencesManager
import org.maocide.undeadwallpaper.data.VideoFileManager
import org.maocide.undeadwallpaper.model.PlaybackMode
import org.maocide.undeadwallpaper.model.RecentFile
import org.maocide.undeadwallpaper.model.StartTime
import org.maocide.undeadwallpaper.model.StatusBarColor
import org.maocide.undeadwallpaper.service.UndeadWallpaperService
import org.maocide.undeadwallpaper.utils.FileLogger

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings


import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.MediaController
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
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
    private var previewPlayer: ExoPlayer? = null

    private var speedValueWarned = false
    private var randomStartTimeWarned = false
    private var isUpdatingUi = false

    // Initialize the shared ViewModel
    private val sharedViewModel: SettingsViewModel by activityViewModels()

    private val videoSettingsChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == UndeadWallpaperService.ACTION_VIDEO_SETTINGS_CHANGED) {
                if (::recentFilesAdapter.isInitialized) {
                    recentFilesAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    companion object { // key for bundle in restoring instance state
        private const val KEY_ADVANCED_EXPANDED = "key_advanced_expanded"
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
    private suspend fun getVideoDuration(uri: Uri): Long = withContext(Dispatchers.IO) {
        return@withContext try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val durationString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            durationString?.toLong() ?: 0L
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                FileLogger.e(tag, "Failed to get video duration for URI: $uri", e)
            } else {
                FileLogger.e(tag, "Failed to get video duration", e)
            }
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
            FileLogger.d(tag, "Permission granted by user.")
            openFilePicker()
        } else {
            FileLogger.d(tag, "Permission denied by user.")
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
        setupRecyclerView()
        setupBatteryWarningCard()

        // ASYNC TASKS (Data Loading)
        lifecycleScope.launch {
            // This might take time on first run (copying file)
            ensureDefaultVideoExists()

            // load the data/settings
            syncUiState()
            setupListeners()
        }

    }

    private suspend fun setPreviewVideo(uri: Uri) {
        // Clear any previous trimming data
        preferencesManager.removeClippingTimes() // Now actually removed, might re implement

        // Update ViewModel (Holds the state for the FAB)
        sharedViewModel.selectedVideoUri = uri

        // Get duration and update UI
        currentVideoDurationMs = getVideoDuration(uri)
        if (currentVideoDurationMs == 0L) {
            Toast.makeText(context, R.string.error_could_not_read_video_duration, Toast.LENGTH_LONG).show()
        }

        // Update the video preview player
        setupVideoPreview(uri)

        // Refresh recent files (since we likely just added one)
        loadRecentFiles()
    }

    /**
     * Centralized function to handle setting or changing the video source.
     * This function ensures that whenever a new video is selected, the UI and
     * preferences are reset and updated correctly.
     *
     * @param uri The URI of the new video file.
     * @param sendBroadcast Weather to send a broadcast to the service to cause a video reload.
     */
    private suspend fun updateVideoSource(uri: Uri, forceChange: Boolean) {
        // Clear any previous trimming data
        preferencesManager.removeClippingTimes()

        // Store the new video URI as a shared value
        sharedViewModel.selectedVideoUri = uri

        // Update the active video highlight in the adapter
        if (::recentFilesAdapter.isInitialized) {
            recentFilesAdapter.currentVideoUriString = uri.toString()
            recentFilesAdapter.notifyDataSetChanged()
        }

        // Get duration and update UI
        currentVideoDurationMs = getVideoDuration(uri)
        if (currentVideoDurationMs == 0L) {
            // Handle case where duration is invalid or video is corrupt
            Toast.makeText(context, R.string.error_could_not_read_video_duration, Toast.LENGTH_LONG).show()
        }

        // Update the video preview
        setupVideoPreview(uri)

        // Save the preference and notify the service to reload the video from that value
        if(forceChange) {
            preferencesManager.saveActiveVideoUri(uri.toString())
            val intent = Intent(UndeadWallpaperService.ACTION_VIDEO_URI_CHANGED).apply {
                setPackage(context?.packageName)
            }
            context?.sendBroadcast(intent)
        }

    }


    /**
     * Sets up the RecyclerView for displaying recent files.
     */
    private fun setupRecyclerView() {
        val currentUri = sharedViewModel.selectedVideoUri?.toString() ?: preferencesManager.getActiveVideoUri()
        recentFilesAdapter = RecentFilesAdapter(
            recentFiles,
            currentVideoUriString = currentUri,
            preferencesManager = preferencesManager,
            onItemClick = { recentFile ->
                val fileUri = Uri.fromFile(recentFile.file)
                viewLifecycleOwner.lifecycleScope.launch {
                    updateVideoSource(fileUri, true)
                }
            },
            onSettingsClick = { recentFile ->
                val bottomSheet = VideoSettingsSheet.newInstance(recentFile.file.name, recentFile.getFormattedMetadata())
                bottomSheet.show(childFragmentManager, "VideoSettingsBottomSheet")
            }
        )
        binding.recyclerViewRecentFiles.layoutManager = LinearLayoutManager(context)
        binding.recyclerViewRecentFiles.adapter = recentFilesAdapter

        val itemTouchHelperCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, // Drag directions
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT // Swipe directions
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                recentFilesAdapter.onItemMove(fromPos, toPos)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                val item = recentFilesAdapter.getItems()[position]

                if (recentFilesAdapter.itemCount <= 1) {
                    Toast.makeText(context, getString(R.string.error_cannot_remove_last_video), Toast.LENGTH_SHORT).show()
                    recentFilesAdapter.notifyItemChanged(position)
                    return
                }

                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.remove_file_title))
                    .setMessage(getString(R.string.remove_file_message, item.file.name))
                    .setPositiveButton(getString(R.string.remove_action)) { _, _ ->
                        val deletedUriString = Uri.fromFile(item.file).toString()
                        val uiSelectedUriString = sharedViewModel.selectedVideoUri?.toString() ?: preferencesManager.getActiveVideoUri()
                        val backgroundActiveUriString = preferencesManager.getActiveVideoUri()

                        // Remove from adapter
                        recentFilesAdapter.onItemDismiss(position)

                        // Delete physical file
                        if (item.file.exists()) {
                            item.file.delete()
                        }

                        // Save new list order
                        saveCurrentPlaylistOrder()

                        // Edge case: User deleted the currently UI-highlighted video, OR the video actively playing in the background
                        if (deletedUriString == uiSelectedUriString || deletedUriString == backgroundActiveUriString) {
                            val nextItem = recentFilesAdapter.getItems().firstOrNull()
                            if (nextItem != null) {
                                val newUri = Uri.fromFile(nextItem.file)
                                viewLifecycleOwner.lifecycleScope.launch {
                                    updateVideoSource(newUri, true)
                                }
                            } else {
                                // Fallback if list is entirely empty (shouldn't happen due to 1 video at least enforced)
                                viewLifecycleOwner.lifecycleScope.launch {
                                    ensureDefaultVideoExists()
                                    val defaultUri = preferencesManager.getActiveVideoUri()
                                    if (defaultUri != null) {
                                        updateVideoSource(defaultUri.toUri(), true)
                                    }
                                }
                            }
                        } else {
                            // If we deleted an inactive video, we MUST still notify the service that the playlist changed.
                            // Otherwise, the background service's chunk loader might try to buffer the physically deleted file!
                            val intent = Intent(UndeadWallpaperService.ACTION_PLAYLIST_REORDERED).apply {
                                setPackage(requireContext().packageName)
                            }
                            requireContext().applicationContext.sendBroadcast(intent)
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                        recentFilesAdapter.notifyItemChanged(position)
                        dialog.dismiss()
                    }
                    .setOnCancelListener {
                        recentFilesAdapter.notifyItemChanged(position)
                    }
                    .show()
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // Called when drag or swipe is completed (dropped)
                saveCurrentPlaylistOrder()

                // Send intent to the service to notify a change
                val intent = Intent(UndeadWallpaperService.ACTION_PLAYLIST_REORDERED).apply {
                    setPackage(requireContext().packageName)
                }
                requireContext().applicationContext.sendBroadcast(intent)
            }

            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return 0
                return super.getSwipeDirs(recyclerView, viewHolder)
            }
        }

        ItemTouchHelper(itemTouchHelperCallback).attachToRecyclerView(binding.recyclerViewRecentFiles)
    }

    /**
     * Saves the current order of files from the adapter to SharedPreferences.
     */
    private fun saveCurrentPlaylistOrder() {
        val currentFileNames = recentFilesAdapter.getItems().map { it.file.name }
        val currentSettings = preferencesManager.getPlaylistSettings()

        // Reorder the settings to match the new file name order
        val newSettingsList = mutableListOf<org.maocide.undeadwallpaper.model.VideoSettings>()
        for (fileName in currentFileNames) {
            val setting = currentSettings.find { it.fileName == fileName }
            if (setting != null) {
                newSettingsList.add(setting)
            }
        }
        preferencesManager.savePlaylistSettings(newSettingsList)
    }

    /**
     * Loads the list of recent files and updates the RecyclerView.
     */
    private suspend fun loadRecentFiles() {
        viewLifecycleOwner.lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                videoFileManager.loadRecentFiles()
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
        if (preferencesManager.getActiveVideoUri() == null) {
            val defaultFile = videoFileManager.createDefaultFileFromResource(
                R.raw.zombillie_default,
                getString(R.string.default_video_filename)
            )

            if (defaultFile != null) {
                val defaultUri = Uri.fromFile(defaultFile)
                // Switch back to Main thread to update Prefs safely
                withContext(Dispatchers.Main) {
                    preferencesManager.saveActiveVideoUri(defaultUri.toString())
                }
            }
        }
    }

    /**
     * Update all Buttons/Switches to match Preferences.
     * Calling this BEFORE listeners prevents accidental triggers.
     * Also called by reset button listener after resetting values
     */
    private suspend fun syncUiState() {

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
                //setupVideoPreview(uriString.toUri())
                updateVideoSource(savedUri.toUri(), false)
            }

            // Regardless of having a selected video or not, we need to load the recent files
            // into the RecyclerView adapter ONCE during UI initialization.
            loadRecentFiles()
        } finally {
            isUpdatingUi = false
        }

    }


    /**
     * Sets up all the listeners for controls.
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

    private fun setupBatteryWarningCard() {
        binding.btnFixBattery.setOnClickListener {
            // Expand the instructions and hide the fix button
            binding.btnFixBattery.visibility = View.GONE
            binding.layoutBatteryInstructions.visibility = View.VISIBLE
        }

        binding.btnGoToSettings.setOnClickListener {
            try {
                // Drop them directly into Undead Wallpaper's specific App Info page
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", requireContext().packageName, null)
                }
                startActivity(intent)
            } catch (e: Exception) {
                FileLogger.e(tag, "Failed to open app info settings", e)
                Toast.makeText(requireContext(), "Unable to open settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupListeners() {
        // Helper to broadcast changes
        fun notifySettingsChanged() {
            val intent = Intent(UndeadWallpaperService.ACTION_PLAYBACK_MODE_CHANGED).apply {
                setPackage(requireContext().packageName)
            }
            requireContext().applicationContext.sendBroadcast(intent)
        }

        // Playback Mode
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
            if (::recentFilesAdapter.isInitialized && recentFilesAdapter.currentVideoUriString?.isNotEmpty() == true) {
                val highlightedUri = recentFilesAdapter.currentVideoUriString!!
                sharedViewModel.selectedVideoUri = Uri.parse(highlightedUri)
                preferencesManager.saveActiveVideoUri(highlightedUri)
            } else {
                val currentSelectedUri = sharedViewModel.selectedVideoUri?.toString() ?: preferencesManager.getActiveVideoUri()
                preferencesManager.saveActiveVideoUri(currentSelectedUri.toString())
            }

            notifySettingsChanged()
        }

        // StartTime preference
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

            // Specific intent sent to apply
            val intent = Intent(UndeadWallpaperService.ACTION_PLAYBACK_MODE_CHANGED).apply {
                setPackage(requireContext().packageName)
            }
            requireContext().applicationContext.sendBroadcast(intent)
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

        // Video Picker
        binding.buttonPickVideo.setOnClickListener {
            checkPermissionAndOpenFilePicker()
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
                if (BuildConfig.DEBUG) {
                    FileLogger.d(tag, "Permission '$permission' already granted. Opening picker.")
                } else {
                    FileLogger.d(tag, "Permission already granted. Opening picker.")
                }
                openFilePicker()
            }
            // If we want to show a popup explaining why we need the permission.
            // For now, we'll just request it directly.
            shouldShowRequestPermissionRationale(permission) -> {
                FileLogger.d(tag, "Showing rationale for permission request.")
                requestPermissionLauncher.launch(permission)
            }
            // If we don't have the permission, we launch the request.
            else -> {
                if (BuildConfig.DEBUG) {
                    FileLogger.d(tag, "Requesting permission: $permission")
                } else {
                    FileLogger.d(tag, "Requesting permission")
                }
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

        // Metadata check in coroutine
        viewLifecycleOwner.lifecycleScope.launch {
            // Check if the video is valid to be played
            val isValid = withContext(Dispatchers.IO) {

                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)

                    // Extract dimensions
                    val widthStr =
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val heightStr =
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    val rotationStr =
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)

                    val width = widthStr?.toIntOrNull() ?: 0
                    val height = heightStr?.toIntOrNull() ?: 0

                    // Calculate total pixels
                    val pixelCount = width * height

                    // Hard cap at 12 Million to allow DCI 4K but block 5K/8K.
                    val maxPixels = 12_000_000

                    FileLogger.i(
                        tag,
                        "Video Analysis: ${width}x${height} ($pixelCount pixels). Max allowed: $maxPixels"
                    )

                    pixelCount < maxPixels // If valid
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) {
                        FileLogger.e(tag, "Failed to analyze video dimensions for $uri", e)
                    } else {
                        FileLogger.e(tag, "Failed to analyze video dimensions", e)
                    }
                    true // We let the check pass anyway on error.
                } finally {
                    retriever.release()
                }
            }

            if (!isValid) {
                Toast.makeText(
                    context,
                    "Video too large! Max supported resolution is 4K (UHD).",
                    Toast.LENGTH_LONG
                ).show()

                // Stop!
                return@launch
            }

            // Copy the file to internal storage in a background thread
            val copiedFile = withContext(Dispatchers.IO) {
                videoFileManager.createFileFromContentUri(uri)
            }
            if (copiedFile != null) {
                val savedFileUri = Uri.fromFile(copiedFile)
                if (BuildConfig.DEBUG) {
                    FileLogger.d(tag, "File copied to: $savedFileUri")
                } else {
                    FileLogger.d(tag, "File copied to local storage")
                }

                // Load the new file into the RecyclerView
                loadRecentFiles()

                // Update the current video (now that the file is in the adapter)
                updateVideoSource(savedFileUri, true) // Automatically set as active wallpaper

                // Notifies the service of a change in the playlist
                val intent = Intent(UndeadWallpaperService.ACTION_PLAYLIST_REORDERED).apply {
                    setPackage(requireContext().packageName)
                }
                requireContext().applicationContext.sendBroadcast(intent)
            } else {
                if (BuildConfig.DEBUG) {
                    FileLogger.e(tag, "Failed to copy file from URI: $uri")
                } else {
                    FileLogger.e(tag, "Failed to copy file from URI")
                }
                Toast.makeText(context, getString(R.string.error_copy_failed), Toast.LENGTH_LONG).show()
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
                        if (context != null) {
                            Toast.makeText(context, getString(R.string.error_cannot_play_video), Toast.LENGTH_SHORT).show()
                        }
                    }
                })

                prepare()
                playWhenReady = true
            }

        binding.videoPreview.player = previewPlayer
    }

    private fun releasePreviewPlayer() {
        previewPlayer?.release()
        previewPlayer = null
        binding.videoPreview.player = null
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
        // Clamp: Ensure value is strictly between valueFrom and valueTo
        val clampedValue = newValue.coerceIn(valueFrom, valueTo)

        // Snap: If a stepSize is defined, ensure the value fits the step
        val finalValue = if (stepSize > 0) {
            // Calculate how many "steps" we are from the start
            val steps = ((clampedValue - valueFrom) / stepSize).roundToInt()
            // Reconstruct the value based on exact steps
            valueFrom + (steps * stepSize)
        } else {
            clampedValue
        }

        // Apply: Only now is it safe to set the value
        this.value = finalValue
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Ensure we don't leak the preview player
        releasePreviewPlayer()
        _binding = null
    }

    override fun onResume() {
        super.onResume()
        checkBatteryOptimization()
        ContextCompat.registerReceiver(
            requireContext(),
            videoSettingsChangedReceiver,
            IntentFilter(UndeadWallpaperService.ACTION_VIDEO_SETTINGS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Resume playback if we have a selected video uri
        sharedViewModel.selectedVideoUri?.let { uri ->
            if (previewPlayer == null) {
                setupVideoPreview(uri)
            } else {
                previewPlayer?.playWhenReady = true
            }
        } ?: run {
            preferencesManager.getActiveVideoUri()?.let { uriString ->
                if (previewPlayer == null) {
                    setupVideoPreview(uriString.toUri())
                } else {
                    previewPlayer?.playWhenReady = true
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Aggressively release resources when the settings screen is not active
        // This frees up the decoder for the actual wallpaper service
        releasePreviewPlayer()
        try {
            requireContext().unregisterReceiver(videoSettingsChangedReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }
    }

}