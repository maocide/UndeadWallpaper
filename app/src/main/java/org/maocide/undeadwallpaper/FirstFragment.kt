package org.maocide.undeadwallpaper

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
        }

        // Load and apply audio setting
        binding.switchAudio.isChecked = preferencesManager.isAudioEnabled()

        // Load and apply scaling mode
        val defaultScaleMode = R.id.radio_scale_crop
        val selectedScaleMode = preferencesManager.getScalingMode(defaultScaleMode)
        binding.radioGroupScaling.check(selectedScaleMode)
    }

    /**
     * Sets up listeners for preference changes.
     */
    private fun setupPreferenceListeners() {
        // Listener for audio switch
        binding.switchAudio.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.saveAudioEnabled(isChecked)
        }

        // Listener for scaling mode radio group
        binding.radioGroupScaling.setOnCheckedChangeListener { _, checkedId ->
            preferencesManager.saveScalingMode(checkedId)
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}