package org.maocide.undeadwallpaper.data

import org.maocide.undeadwallpaper.R
import org.maocide.undeadwallpaper.BuildConfig

import org.maocide.undeadwallpaper.model.RecentFile

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.app.WallpaperColors
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Manages video files for the application.
 * This class encapsulates the logic for storing, retrieving, and managing video files.
 *
 * @param context The application context.
 */
class VideoFileManager(private val context: Context) {

    private val tag: String = javaClass.simpleName


    /**
     * Copies a raw resource to the app's video storage if it doesn't exist.
     * Used for the default Zombillie asset.
     *
     * @param resourceId The R.raw ID of the asset.
     * @param fileName The desired filename (e.g., "zombillie_default.mp4").
     * @return The File object of the created or existing video.
     */
    fun createDefaultFileFromResource(resourceId: Int, fileName: String): File? {
        val outputDir = getAppSpecificAlbumStorageDir(context, "videos")
        val outputFile = File(outputDir, fileName)

        if (outputFile.exists()) {
            return outputFile
        }

        return try {
            context.resources.openRawResource(resourceId).use { inputStream ->
                copyStreamToFile(inputStream, outputFile)
            }
            outputFile
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(tag, "Failed to copy default resource: $fileName", e)
            } else {
                Log.e(tag, "Failed to copy default resource", e)
            }
            null
        }
    }


    /**
     * Creates a file in the app's specific storage from a content URI.
     * It uses the original file name to avoid duplicates or generates a timestamp name.
     *
     * @param fileUri The URI of the file to be copied.
     * @return The newly created File object, or null if the operation fails.
     */
    fun createFileFromContentUri(fileUri: Uri): File? {
        var originalFileName = ""

        // Try to query the display name
        try {
            context.contentResolver.query(fileUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        originalFileName = cursor.getString(nameIndex)
                    }
                }
            }
            originalFileName = java.io.File(originalFileName).name
        } catch (e: Exception) {
            Log.w(tag, "Could not query file name, generating fallback.", e)
        }

        // Use UUID for the fallback
        if (originalFileName.isBlank()) {
            originalFileName = "imported_video_${java.util.UUID.randomUUID()}.mp4"
        }

        val outputDir = getAppSpecificAlbumStorageDir(context, "videos")
        val outputFile = File(outputDir, originalFileName)

        // If the final path doesn't start with output folder, someone is tampering
        if (!outputFile.canonicalPath.startsWith(outputDir.canonicalPath)) {
            Log.e(tag, "Security Warning: Path traversal attempt detected!")
            return null
        }

        try {
            context.contentResolver.openInputStream(fileUri)?.use { iStream ->
                copyStreamToFile(iStream, outputFile)
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(tag, "Error copying file from URI: $fileUri", e)
            } else {
                Log.e(tag, "Error copying file from URI", e)
            }
            return null
        }

        return outputFile
    }


    /**
     * Copies a file to the app's specific storage.
     * It uses the original file name to avoid duplicates.
     *
     * @param file The file to be copied.
     * @return The newly created File object.
     */
    fun copyRecentFile(file: File): File {
        val outputDir = getAppSpecificAlbumStorageDir(context, "videos")
        val newFile = File(outputDir, file.name)
        copyStreamToFile(file.inputStream(), newFile)
        return newFile
    }

    /**
     * Creates a temporary video file in the app's specific storage.
     * The file will have a unique name.
     *
     * @return The newly created File object.
     */
    fun createTempVideoFile(): File {
        val outputDir = getAppSpecificAlbumStorageDir(context, "videos")
        return File.createTempFile("clip_", ".mp4", outputDir)
    }

    /**
     * Loads the list of recent video files from the app's storage,
     * synchronizing it with the persisted order.
     *
     * @return A list of [RecentFile] objects in the correct order.
     */
    suspend fun loadRecentFiles(): List<RecentFile> = withContext(Dispatchers.IO) {
        val videosDir = getAppSpecificAlbumStorageDir(context, "videos")
        val physicalFiles = videosDir.listFiles() ?: return@withContext emptyList()
        val preferencesManager = PreferencesManager(context)

        // Get the persisted list of settings
        val persistedSettings = preferencesManager.getPlaylistSettings().toMutableList()
        val persistedFileNames = persistedSettings.map { it.fileName }.toMutableList()

        // Identify physical files that are NOT in the persisted list (e.g., newly imported)
        val physicalFileNames = physicalFiles.map { it.name }.toSet()
        val newPhysicalFiles = physicalFiles.filter { it.name !in persistedFileNames }

        // Sort new files by modification date (newest first)
        val sortedNewFiles = newPhysicalFiles.sortedByDescending { it.lastModified() }
        val sortedNewFileNames = sortedNewFiles.map { it.name }

        // Identify files in the persisted list that no longer exist physically
        persistedFileNames.retainAll(physicalFileNames)
        persistedSettings.retainAll { it.fileName in physicalFileNames }

        // Prepend new files to the beginning of the persisted list (to maintain "recent" behavior)
        if (sortedNewFileNames.isNotEmpty()) {
            persistedFileNames.addAll(0, sortedNewFileNames)
            // Add corresponding default VideoSettings
            val newSettings = sortedNewFileNames.map { org.maocide.undeadwallpaper.model.VideoSettings(fileName = it) }
            persistedSettings.addAll(0, newSettings)
        }

        // Save the synchronized list back to SharedPreferences if it was changed
        if (sortedNewFileNames.isNotEmpty() || persistedSettings.size != physicalFiles.size) {
            preferencesManager.savePlaylistSettings(persistedSettings)
        }

        // Create a lookup map for physical files to maintain O(1) access
        val physicalFileMap = physicalFiles.associateBy { it.name }

        // Generate thumbnails based on the synchronized order
        val semaphore = Semaphore(4) // Limit to 4 concurrent thumbnail generations

        persistedFileNames.mapNotNull { fileName ->
            val file = physicalFileMap[fileName]
            if (file != null) {
                async {
                    semaphore.withPermit {
                        try {
                            val thumbnail = createVideoThumbnail(file.path)

                            // Extract WallpaperColors if needed
                            if (thumbnail != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                                val currentSettings = preferencesManager.getVideoSettings(fileName)
                                if (currentSettings.primaryColor == null) {
                                    // Downscale for performance
                                    val scaledThumb = Bitmap.createScaledBitmap(thumbnail, 112, 112, true)
                                    val colors = WallpaperColors.fromBitmap(scaledThumb)

                                    preferencesManager.updateVideoSettings(fileName) { settings ->
                                        settings.copy(
                                            primaryColor = colors.primaryColor.toArgb(),
                                            secondaryColor = colors.secondaryColor?.toArgb(),
                                            tertiaryColor = colors.tertiaryColor?.toArgb(),
                                            colorHints = colors.colorHints
                                        )
                                    }
                                }
                            }

                            var durationMs = 0L
                            var width = 0
                            var height = 0
                            var fps = 0

                            try {
                                val retriever = MediaMetadataRetriever()
                                try {
                                    retriever.setDataSource(file.path)
                                    durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                    width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                                    height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                                } finally {
                                    retriever.release()
                                }

                                val extractor = MediaExtractor()
                                try {
                                    extractor.setDataSource(file.path)
                                    for (i in 0 until extractor.trackCount) {
                                        val format = extractor.getTrackFormat(i)
                                        val mime = format.getString(MediaFormat.KEY_MIME)

                                        if (mime?.startsWith("video/") == true) {
                                            if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                                                fps = format.getInteger(MediaFormat.KEY_FRAME_RATE)
                                            }
                                            break // Found the video track, stop looking
                                        }
                                    }
                                } finally {
                                    extractor.release()
                                }
                            } catch (e: Exception) {
                                Log.e(tag, "Error extracting metadata for ${file.name}", e)
                            }

                            RecentFile(
                                file = file,
                                thumbnail = thumbnail,
                                durationMs = durationMs,
                                width = width,
                                height = height,
                                sizeBytes = file.length(),
                                fps = fps
                            )
                        } catch (e: Exception) {
                            Log.e(tag, "Error loading thumbnail for ${file.name}", e)
                            RecentFile(file, null, sizeBytes = file.length())
                        }
                    }
                }
            } else {
                null
            }
        }.awaitAll()
    }

    /**
     * Creates a thumbnail for a video file.
     *
     * @param filePath The path of the video file.
     * @return A [Bitmap] thumbnail, or null if creation fails.
     */
    fun createVideoThumbnail(filePath: String): Bitmap? {
        return ThumbnailUtils.createVideoThumbnail(
            filePath,
            MediaStore.Images.Thumbnails.MINI_KIND
        )
    }

    /**
     * Gets the app-specific album storage directory.
     *
     * @param context The application context.
     * @param albumName The name of the album.
     * @return The directory file.
     */
    private fun getAppSpecificAlbumStorageDir(context: Context, albumName: String): File {
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), albumName)
        if (!file.mkdirs()) {
            Log.e(tag, "Directory not created or already exists")
        }
        return file
    }

    /**
     * Copies an input stream to a file.
     *
     * @param inputStream The input stream to copy from.
     * @param outputFile The file to copy to.
     */
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
}
