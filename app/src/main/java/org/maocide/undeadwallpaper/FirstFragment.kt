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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.widget.Toast
import kotlinx.coroutines.withContext
import org.maocide.undeadwallpaper.databinding.FragmentFirstBinding
import java.io.File

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

        setupRecyclerView()
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
                preferencesManager.saveVideoUri(fileUri.toString())
                setupVideoPreview(fileUri)
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
     * Loads and applies user preferences.
     */
    private fun loadAndApplyPreferences() {
        // Load and apply the video URI for preview
        val videoUriString = preferencesManager.getVideoUri()
        if (videoUriString != null) {
            val videoUri = videoUriString.toUri()
            setupVideoPreview(videoUri)
            currentVideoDurationMs = getVideoDuration(videoUri)
        }

        // Load and apply audio setting
        binding.switchAudio.isChecked = preferencesManager.isAudioEnabled()

        // Load and apply clipping times
        val (startMs, endMs) = preferencesManager.getClippingTimes()
        binding.etStartTime.setText(formatMillisecondsToHHMMSSmmm(startMs))
        if (endMs != -1L) {
            binding.etEndTime.setText(formatMillisecondsToHHMMSSmmm(endMs))
        } else {
            binding.etEndTime.setText("") // Clear if not set
        }

        // The scaling mode has been removed from the user settings.
        // The wallpaper service now uses a smart scaling logic by default.
    }

    /**
     * Sets up listeners for preference changes.
     */
    private fun setupPreferenceListeners() {
        // Listener for audio switch
        binding.switchAudio.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.saveAudioEnabled(isChecked)
        }

        binding.buttonSaveTimes.setOnClickListener {
            val startTimeString = binding.etStartTime.text.toString()
            val endTimeString = binding.etEndTime.text.toString()

            val startMs = parseHHMMSSmmmToMilliseconds(startTimeString)
            val endMs = if (endTimeString.isNotBlank()) {
                parseHHMMSSmmmToMilliseconds(endTimeString)
            } else {
                -1L
            }

            // --- Validation ---
            if (startMs == -1L) {
                Toast.makeText(context, "Invalid start time format. Use HH:MM:SS.mmm", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (endTimeString.isNotBlank() && endMs == -1L) {
                Toast.makeText(context, "Invalid end time format. Use HH:MM:SS.mmm", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (endMs != -1L && endMs <= startMs) {
                Toast.makeText(context, "End time must be after start time.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (currentVideoDurationMs > 0) {
                if (startMs >= currentVideoDurationMs) {
                    Toast.makeText(context, "Start time must be less than video duration (${formatMillisecondsToHHMMSSmmm(currentVideoDurationMs)}).", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (endMs != -1L && endMs > currentVideoDurationMs) {
                    Toast.makeText(context, "End time cannot be greater than video duration (${formatMillisecondsToHHMMSSmmm(currentVideoDurationMs)}).", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            }

            preferencesManager.saveClippingTimes(startMs, endMs)

            val intent = Intent(UndeadWallpaperService.ACTION_VIDEO_URI_CHANGED)
            context?.sendBroadcast(intent)

            Toast.makeText(context, "Clipping times applied.", Toast.LENGTH_SHORT).show()
        }
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
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        pickMediaLauncher.launch(intent)
    }

    /**
     * Handles the selected media URI.
     *
     * @param uri The URI of the selected media.
     */
    private fun handleSelectedMedia(uri: Uri) {
        Log.d(tag, "Handling selected media URI: $uri")

        val contentResolver = requireActivity().contentResolver
        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        contentResolver.takePersistableUriPermission(uri, takeFlags)

        viewLifecycleOwner.lifecycleScope.launch {
            val copiedFile = withContext(Dispatchers.IO) {
                videoFileManager.createFileFromContentUri(uri)
            }
            if (copiedFile != null) {
                val savedFileUri = Uri.fromFile(copiedFile)
                Log.d(tag, "File copied to: $savedFileUri")
                currentVideoDurationMs = getVideoDuration(savedFileUri)
                preferencesManager.saveVideoUri(savedFileUri.toString())
                setupVideoPreview(savedFileUri)
                loadRecentFiles()
            } else {
                Log.e(tag, "Failed to copy file from URI: $uri")
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
     * Parses a HH:MM:SS.mmm formatted string into milliseconds.
     * @param timeString The time string in HH:MM:SS.mmm format.
     * @return The time in milliseconds, or -1 if the format is invalid.
     */
    private fun parseHHMMSSmmmToMilliseconds(timeString: String): Long {
        if (timeString.isBlank()) return -1
        return try {
            val mainParts = timeString.split('.')
            val hmsParts = mainParts[0].split(':')

            if (hmsParts.size != 3) return -1

            val hours = hmsParts[0].toLong()
            val minutes = hmsParts[1].toLong()
            val seconds = hmsParts[2].toLong()

            val millis = if (mainParts.size == 2 && mainParts[1].isNotBlank()) {
                val mmmString = mainParts[1].padEnd(3, '0').substring(0, 3)
                if (mmmString.length != 3) return -1
                mmmString.toLong()
            } else {
                0L
            }

            if (hours < 0 || minutes < 0 || minutes > 59 || seconds < 0 || seconds > 59 || millis < 0 || millis > 999) return -1

            (hours * 3600 + minutes * 60 + seconds) * 1000 + millis
        } catch (e: Exception) { // Catch broader exceptions
            -1
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}