package org.maocide.undeadwallpaper

import android.Manifest
import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.MediaController
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import org.maocide.undeadwallpaper.databinding.FragmentFirstBinding
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.maocide.undeadwallpaper.UndeadWallpaperService

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!
    private val tag: String = javaClass.simpleName
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var recentFilesAdapter: RecentFilesAdapter
    private val recentFiles = mutableListOf<RecentFile>()

    private val pickMediaLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleSelectedMedia(uri)
            }
        }
    }

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
        sharedPrefs = requireContext().getSharedPreferences("DEFAULT", MODE_PRIVATE)
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

    private fun setupRecyclerView() {
        recentFilesAdapter = RecentFilesAdapter(
            recentFiles,
            onItemClick = { recentFile ->
                val fileUri = Uri.fromFile(recentFile.file)
                sharedPrefs.edit().putString(getString(R.string.video_uri), fileUri.toString()).apply()
                setupVideoPreview(fileUri)
            },
            onDeleteClick = { recentFile ->
                recentFile.file.delete()
                loadRecentFiles()
            }
        )
        binding.recyclerViewRecentFiles.layoutManager = LinearLayoutManager(context)
        binding.recyclerViewRecentFiles.adapter = recentFilesAdapter
    }

    private fun loadRecentFiles() {
        recentFiles.clear()
        val videosDir = getAppSpecificAlbumStorageDir(requireContext(), "videos")
        val files = videosDir.listFiles()
        if (files != null) {
            for (file in files) {
                val thumbnail = createVideoThumbnail(file.path)
                recentFiles.add(RecentFile(file, thumbnail))
            }
        }
        recentFilesAdapter.notifyDataSetChanged()
    }

    private fun createVideoThumbnail(filePath: String): Bitmap? {
        return ThumbnailUtils.createVideoThumbnail(
            filePath,
            MediaStore.Images.Thumbnails.MINI_KIND
        )
    }

    private fun loadAndApplyPreferences() {
        // Load and apply the video URI for preview
        val videoUriString = sharedPrefs.getString(getString(R.string.video_uri), null)
        if (videoUriString != null) {
            val videoUri = videoUriString.toUri()
            setupVideoPreview(videoUri)
        }

        // Load and apply audio setting
        val isAudioEnabled = sharedPrefs.getBoolean(getString(R.string.video_audio_enabled), false)
        binding.switchAudio.isChecked = isAudioEnabled

        // Load and apply scaling mode
        val defaultScaleMode = R.id.radio_scale_crop
        val selectedScaleMode = sharedPrefs.getInt(getString(R.string.video_scaling_mode), defaultScaleMode)
        binding.radioGroupScaling.check(selectedScaleMode)

    }

    private fun setupPreferenceListeners() {
        val editor = sharedPrefs.edit()

        // Listener for audio switch
        binding.switchAudio.setOnCheckedChangeListener { _, isChecked ->
            editor.putBoolean(getString(R.string.video_audio_enabled), isChecked).apply()
        }

        // Listener for scaling mode radio group
        binding.radioGroupScaling.setOnCheckedChangeListener { _, checkedId ->
            editor.putInt(getString(R.string.video_scaling_mode), checkedId).apply()
        }
    }

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

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        pickMediaLauncher.launch(intent)
    }

    private fun handleSelectedMedia(uri: Uri) {
        Log.d(tag, "Handling selected media URI: $uri")

        val contentResolver = requireActivity().contentResolver
        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        contentResolver.takePersistableUriPermission(uri, takeFlags)

        val copiedFile = createFileFromContentUri(uri)
        val savedFileUri = Uri.fromFile(copiedFile)

        Log.d(tag, "File copied to: $savedFileUri")

        sharedPrefs.edit().putString(getString(R.string.video_uri), savedFileUri.toString()).apply()

        // Set up the video preview
        setupVideoPreview(savedFileUri)
        loadRecentFiles()
    }

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

    private fun createFileFromContentUri(fileUri: Uri): File {
        var originalFileName = ""
        requireActivity().contentResolver.query(fileUri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            originalFileName = cursor.getString(nameIndex)
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val extension = originalFileName.substringAfterLast('.', "")
        val fileName = if (extension.isNotEmpty()) "VIDEO_${timeStamp}.$extension" else "VIDEO_$timeStamp"


        val iStream: InputStream = requireActivity().contentResolver.openInputStream(fileUri)!!
        val outputDir = getAppSpecificAlbumStorageDir(requireContext(), "videos")
        val outputFile = File(outputDir, fileName)
        copyStreamToFile(iStream, outputFile)
        iStream.close()
        return outputFile
    }

    private fun getAppSpecificAlbumStorageDir(context: Context, albumName: String): File {
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), albumName)
        if (!file.mkdirs()) {
            Log.e(tag, "Directory not created or already exists")
        }
        return file
    }

    private fun copyStreamToFile(inputStream: InputStream, outputFile: File) {
        inputStream.use { input ->
            FileOutputStream(outputFile).use { output ->
                val buffer = ByteArray(4 * 1024)
                var byteCount: Int
                while (input.read(buffer).also { byteCount = it } != -1) {
                    output.write(buffer, 0, byteCount)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}