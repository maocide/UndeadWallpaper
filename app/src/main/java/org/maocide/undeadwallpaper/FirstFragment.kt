package org.maocide.undeadwallpaper

import android.Manifest
import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build // Import needed for API level checks
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import org.maocide.undeadwallpaper.databinding.FragmentFirstBinding
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import org.maocide.undeadwallpaper.UndeadWallpaperService

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!
    private val tag: String = javaClass.simpleName

    // --- Modern Result Launchers ---
    // Launcher for getting the video content
    private val pickMediaLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleSelectedMedia(uri)
            }
        }
    }

    // Launcher for requesting the permission
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission was just granted. Now we can safely open the file picker.
            Log.d(tag, "Permission granted by user.")
            openFilePicker()
        } else {
            // Permission denied. Inform the user.
            Log.d(tag, "Permission denied by user.")
            // Optionally show a Snackbar or Toast explaining why the permission is needed.
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonPick.setOnClickListener {
            // This is now the single point of entry.
            // It checks for permission and acts accordingly.
            checkPermissionAndOpenFilePicker()
        }
    }

    private fun checkPermissionAndOpenFilePicker() {
        // Determine the correct permission based on Android version
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        when {
            // Case 1: Permission is already granted
            ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED -> {
                Log.d(tag, "Permission already granted. Opening file picker.")
                openFilePicker()
            }
            // Case 2: Should show a rationale (optional but good practice)
            shouldShowRequestPermissionRationale(permission) -> {
                // Here you would show a dialog explaining why you need the permission
                // before launching the request. For simplicity, we'll just request.
                Log.d(tag, "Showing permission rationale is recommended. Requesting permission.")
                requestPermissionLauncher.launch(permission)
            }
            // Case 3: Permission has not been granted yet, request it
            else -> {
                Log.d(tag, "Permission not granted. Requesting permission.")
                requestPermissionLauncher.launch(permission)
            }
        }
    }

    private fun openFilePicker() {
        // Using ACTION_OPEN_DOCUMENT is more modern and robust
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*" // Specify you only want videos
            // Request persistent access
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        pickMediaLauncher.launch(intent)
    }

    private fun handleSelectedMedia(uri: Uri) {
        Log.d(tag, "Handling selected media URI: $uri")

        // Take persistable URI permission to access the file across reboots
        val contentResolver = requireActivity().contentResolver
        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        contentResolver.takePersistableUriPermission(uri, takeFlags)

        // Now, copy the file to app-specific storage
        val copiedFile = createFileFromContentUri(uri)
        val savedFileUri = Uri.fromFile(copiedFile)

        Log.d(tag, "File copied to: $savedFileUri")

        // Save the URI of the *copied file* to SharedPreferences
        val sharedPrefs = requireContext().getSharedPreferences("DEFAULT", MODE_PRIVATE)
        with(sharedPrefs.edit()) {
            putString(getString(R.string.video_uri), savedFileUri.toString())
            apply()
        }
    }

    // --- Your File Helper Functions (largely unchanged) ---

    private fun createFileFromContentUri(fileUri: Uri): File {
        var fileName = ""
        requireActivity().contentResolver.query(fileUri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            fileName = cursor.getString(nameIndex)
        }

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