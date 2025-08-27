package org.maocide.undeadwallpaper

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
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
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.android.material.slider.RangeSlider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maocide.undeadwallpaper.databinding.FragmentFirstBinding

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 * This fragment allows the user to select a video and set it as a live wallpaper.
 */
class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!
    private val tag: String = javaClass.simpleName

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var videoFileManager: VideoFileManager
    private lateinit var recentFilesAdapter: RecentFilesAdapter
    private val recentFiles = mutableListOf<RecentFile>()
    private var currentVideoDurationMs: Long = 0L

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
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        preferencesManager = PreferencesManager(requireContext())
        videoFileManager = VideoFileManager(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadAndApplyPreferences()
        setupPreferenceListeners()

        binding.buttonPickVideo.setOnClickListener {
            checkPermissionAndOpenFilePicker()
        }

        binding.buttonApplyTrim.setOnClickListener {
            applyVideoTrim()
        }

        setupRecyclerView()
        loadRecentFiles()
    }

    /**
     * Centralized function to handle setting or changing the video source.
     * This function ensures that whenever a new video is selected, the UI and
     * preferences are reset and updated correctly.
     *
     * @param uri The URI of the new video file.
     */
    private fun updateVideoSource(uri: Uri) {
        // 1. Clear any previous trimming data
        preferencesManager.removeClippingTimes()

        // 2. Save the new video URI
        preferencesManager.saveVideoUri(uri.toString())

        // 3. Get duration and update UI
        currentVideoDurationMs = getVideoDuration(uri)
        if (currentVideoDurationMs > 0) {
            binding.trimSlider.valueFrom = 0f
            binding.trimSlider.valueTo = currentVideoDurationMs.toFloat()
            binding.trimSlider.values = listOf(0f, currentVideoDurationMs.toFloat())
            binding.tvStartTime.text = formatMillisecondsToHHMMSSmmm(0)
            binding.tvEndTime.text = formatMillisecondsToHHMMSSmmm(currentVideoDurationMs)
        } else {
            // Handle case where duration is invalid or video is corrupt
            binding.trimSlider.isEnabled = false
            Toast.makeText(context, "Could not read video duration. Trimming disabled.", Toast.LENGTH_LONG).show()
        }

        // 4. Update the video preview
        setupVideoPreview(uri)

        // 5. Notify the service to reload the video
        val intent = Intent(UndeadWallpaperService.ACTION_VIDEO_URI_CHANGED)
        context?.sendBroadcast(intent)

        // 6. Refresh the recent files list
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
                updateVideoSource(fileUri)
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
                videoFileManager.loadRecentFiles()
            }
            recentFiles.clear()
            recentFiles.addAll(files)
            recentFilesAdapter.notifyDataSetChanged()
        }
    }

    /**
     * Loads and applies user preferences on startup.
     * This function is "paranoid" and validates saved clipping times against the
     * video's actual duration to prevent crashes or weird behavior from stale data.
     */
    private fun loadAndApplyPreferences() {
        // Load audio setting first
        binding.switchAudio.isChecked = preferencesManager.isAudioEnabled()

        // Load video URI
        val videoUriString = preferencesManager.getVideoUri()
        if (videoUriString == null) {
            // No video set, disable the slider
            binding.trimSlider.isEnabled = false
            return
        }

        val videoUri = videoUriString.toUri()
        currentVideoDurationMs = getVideoDuration(videoUri)

        if (currentVideoDurationMs <= 0) {
            // Invalid video or duration, disable slider
            binding.trimSlider.isEnabled = false
            setupVideoPreview(videoUri) // Still try to show preview
            return
        }

        // Video is valid, enable and configure slider
        binding.trimSlider.isEnabled = true
        binding.trimSlider.valueFrom = 0f
        binding.trimSlider.valueTo = currentVideoDurationMs.toFloat()

        // Load saved clipping times
        var (startMs, endMs) = preferencesManager.getClippingTimes()

        // If endMs is default, set it to the full duration for the UI
        if (endMs == -1L || endMs > currentVideoDurationMs) {
            endMs = currentVideoDurationMs
        }

        // **Sanity Check:** Validate saved times against the video's duration.
        if (startMs < 0 || startMs >= endMs || startMs > currentVideoDurationMs) {
            // If times are invalid (e.g., from a different video), reset to full range.
            startMs = 0L
            endMs = currentVideoDurationMs
        }

        // Apply the validated values to the slider and text views
        binding.trimSlider.values = listOf(startMs.toFloat(), endMs.toFloat())
        binding.tvStartTime.text = formatMillisecondsToHHMMSSmmm(startMs)
        binding.tvEndTime.text = formatMillisecondsToHHMMSSmmm(endMs)

        // Finally, set up the video preview
        setupVideoPreview(videoUri)
    }


    /**
     * Sets up listeners for preference changes.
     */
    private fun setupPreferenceListeners() {
        // Listener for audio switch
        binding.switchAudio.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.saveAudioEnabled(isChecked)
            // Notify service immediately if audio preference changes
            val intent = Intent(UndeadWallpaperService.ACTION_VIDEO_URI_CHANGED)
            context?.sendBroadcast(intent)
        }

        // Listener for trim slider value changes (updates text views in real-time)
        binding.trimSlider.addOnChangeListener { slider, _, _ ->
            val values = slider.values
            val startMs = values[0].toLong()
            val endMs = values[1].toLong()
            binding.tvStartTime.text = formatMillisecondsToHHMMSSmmm(startMs)
            binding.tvEndTime.text = formatMillisecondsToHHMMSSmmm(endMs)
        }

        // Listener for when the user lifts their finger from the slider
        binding.trimSlider.addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: RangeSlider) {
                // No-op
            }

            override fun onStopTrackingTouch(slider: RangeSlider) {
                // No-op. The user will now press the "Apply" button.
            }
        })
    }

    private fun applyVideoTrim() {
        val videoUriString = preferencesManager.getVideoUri()
        if (videoUriString == null) {
            Toast.makeText(context, "No video selected.", Toast.LENGTH_SHORT).show()
            return
        }

        val values = binding.trimSlider.values
        val startMs = values[0].toLong()
        val endMs = values[1].toLong()

        setUiEnabled(false)

        val workRequest = OneTimeWorkRequestBuilder<VideoClipWorker>()
            .setInputData(workDataOf(
                VideoClipWorker.KEY_INPUT_URI to videoUriString,
                VideoClipWorker.KEY_START_MS to startMs,
                VideoClipWorker.KEY_END_MS to endMs
            ))
            .build()

        val workManager = WorkManager.getInstance(requireContext())
        workManager.enqueue(workRequest)

        workManager.getWorkInfoByIdLiveData(workRequest.id)
            .observe(viewLifecycleOwner) { workInfo ->
                if (workInfo != null) {
                    when (workInfo.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            setUiEnabled(true)
                            val outputPath = workInfo.outputData.getString(VideoClipWorker.KEY_OUTPUT_PATH)
                            if (outputPath != null) {
                                val outputFileUri = Uri.fromFile(File(outputPath))
                                updateVideoSource(outputFileUri)
                                Toast.makeText(context, "Video clip saved and applied.", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "Error: Clipped video path not found.", Toast.LENGTH_LONG).show()
                            }
                        }
                        WorkInfo.State.FAILED -> {
                            setUiEnabled(true)
                            Toast.makeText(context, "Failed to clip video.", Toast.LENGTH_LONG).show()
                        }
                        WorkInfo.State.RUNNING -> {
                            // The UI is already in a "processing" state.
                        }
                        else -> {
                            // Other states (ENQUEUED, CANCELLED, BLOCKED) can be handled here if needed.
                        }
                    }
                }
            }
    }

    private fun setUiEnabled(isEnabled: Boolean) {
        binding.buttonPickVideo.isEnabled = isEnabled
        binding.buttonApplyTrim.isEnabled = isEnabled
        binding.switchAudio.isEnabled = isEnabled
        binding.trimSlider.isEnabled = isEnabled
        binding.recyclerViewRecentFiles.isEnabled = isEnabled
        binding.progressBar.isVisible = !isEnabled
    }


    /**
     * Checks for storage permissions and opens the file picker.
     */
    private fun checkPermissionAndOpenFilePicker() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        when {
            ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED -> {
                openFilePicker()
            }
            shouldShowRequestPermissionRationale(permission) -> {
                // We can show a rationale here if needed in the future
                requestPermissionLauncher.launch(permission)
            }
            else -> {
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
            Toast.makeText(context, "Failed to get permission for the selected file.", Toast.LENGTH_LONG).show()
            return
        }


        // Copy the file to internal storage in a background thread
        viewLifecycleOwner.lifecycleScope.launch {
            val copiedFile = withContext(Dispatchers.IO) {
                videoFileManager.createFileFromContentUri(uri)
            }
            if (copiedFile != null) {
                val savedFileUri = Uri.fromFile(copiedFile)
                Log.d(tag, "File copied to: $savedFileUri")
                updateVideoSource(savedFileUri) // Centralized update logic
            } else {
                Log.e(tag, "Failed to copy file from URI: $uri")
                Toast.makeText(context, "Failed to copy video file.", Toast.LENGTH_LONG).show()
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
            binding.videoPreview.start()
        }
        binding.videoPreview.setOnErrorListener { _, _, _ ->
            Toast.makeText(context, "Cannot play this video file.", Toast.LENGTH_SHORT).show()
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}