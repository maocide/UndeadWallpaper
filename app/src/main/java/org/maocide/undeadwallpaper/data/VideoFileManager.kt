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
    fun createDefaultFileFromResource(resourceId: Int): File? {
        val outputDir = getAppSpecificAlbumStorageDir(context, "videos")
        val outputFile = File(outputDir, "video_${java.util.UUID.randomUUID()}.mp4")

        return try {
            context.resources.openRawResource(resourceId).use { inputStream ->
                copyStreamToFile(inputStream, outputFile)
            }
            outputFile
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(tag, "Failed to copy default resource", e)
            } else {
                Log.e(tag, "Failed to copy default resource", e)
            }
            null
        }
    }


    /**
     * Creates a file in the app's specific storage from a content URI.
     * It uses a random UUID for the physical filename, and returns the original display name.
     *
     * @param fileUri The URI of the file to be copied.
     * @return A Pair containing the new File and its original display name, or null if it fails.
     */
    fun createFileFromContentUri(fileUri: Uri): Pair<File, String>? {
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

        // Use UUID for the fallback display name
        if (originalFileName.isBlank()) {
            originalFileName = "imported_video_${java.util.UUID.randomUUID()}.mp4"
        }

        val outputDir = getAppSpecificAlbumStorageDir(context, "videos")
        val outputFile = File(outputDir, "video_${java.util.UUID.randomUUID()}.mp4")

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

        return Pair(outputFile, originalFileName)
    }


    /**
     * Copies a file to the app's specific storage.
     * It uses the original file name to avoid duplicates.
     *
     * @param file The file to be copied.
     * @return The newly created File object.
     */
    fun copyRecentFile(file: File): Pair<File, String> {
        val outputDir = getAppSpecificAlbumStorageDir(context, "videos")
        val newFile = File(outputDir, "video_${java.util.UUID.randomUUID()}.mp4")
        copyStreamToFile(file.inputStream(), newFile)
        return Pair(newFile, file.name)
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
        var physicalFiles = videosDir.listFiles() ?: return@withContext emptyList()
        val preferencesManager = PreferencesManager(context)

        // Get the persisted list of settings
        var persistedSettings = preferencesManager.getPlaylistSettings().toMutableList()
        var settingsChanged = false

        // CHECK & MIGRATION
        val updatedSettings = mutableListOf<org.maocide.undeadwallpaper.model.VideoSettings>()
        for (setting in persistedSettings) {
            val file = physicalFiles.find { it.name == setting.fileName }
            if (file != null) {
                if (setting.expectedFileSize == null) {
                    settingsChanged = true
                    
                    // Verify if it is the legacy default file, check size
                    val defaultFileName = context.getString(org.maocide.undeadwallpaper.R.string.default_video_filename)
                    if (setting.fileName == defaultFileName) {
                        val EXPECTED_DEFAULT_SIZE = 1808797L
                        if (file.length() != EXPECTED_DEFAULT_SIZE) {
                            android.util.Log.e(tag, "File is corrupted, deleting video ${file.name}")
                            file.delete()
                            continue
                        }
                    }

                    // Rename ALL legacy files to UUID
                    val originalName = setting.fileName
                    val newFileName = "video_${java.util.UUID.randomUUID()}.mp4"
                    val newFile = java.io.File(videosDir, newFileName)
                    if (file.renameTo(newFile)) {
                        updatedSettings.add(setting.copy(fileName = newFileName, expectedFileSize = newFile.length(), displayName = originalName))
                        
                        // Keep the active video in sync
                        val activeUri = preferencesManager.getActiveVideoUri()
                        if (activeUri != null && android.net.Uri.parse(activeUri).lastPathSegment == originalName) {
                            preferencesManager.saveActiveVideoUri(android.net.Uri.fromFile(newFile).toString())
                        }
                    } else {
                        updatedSettings.add(setting.copy(expectedFileSize = file.length(), displayName = originalName))
                    }
                } else if (setting.expectedFileSize != file.length()) {
                    // Inconsistency found
                    android.util.Log.e(tag, "File is corrupted, deleting video ${file.name}")
                    file.delete()
                    settingsChanged = true
                    // Do not add to updatedSettings so it gets removed from the playlist
                } else {
                    updatedSettings.add(setting)
                }
            } else {
                updatedSettings.add(setting) // File missing physically, will be filtered out below
            }
        }

        if (settingsChanged) {
            persistedSettings = updatedSettings
            physicalFiles = videosDir.listFiles() ?: emptyArray() // Refresh physical files
        }

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
        if (sortedNewFiles.isNotEmpty()) {
            persistedFileNames.addAll(0, sortedNewFileNames)
            // Add corresponding default VideoSettings, locking in the file size
            val newSettings = sortedNewFiles.map { file ->
                org.maocide.undeadwallpaper.model.VideoSettings(fileName = file.name, expectedFileSize = file.length())
            }
            persistedSettings.addAll(0, newSettings)
            settingsChanged = true
        }

        // Save the synchronized list back to SharedPreferences if it was changed
        if (settingsChanged || persistedSettings.size != physicalFiles.size) {
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
                            val currentSettings = preferencesManager.getVideoSettings(fileName)
                            val thumbnail = getOrGenerateThumbnail(file)

                            // Extract WallpaperColors if needed
                            if (thumbnail != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
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

                            var durationMs = currentSettings.durationMs
                            var width = currentSettings.width
                            var height = currentSettings.height
                            var fps = currentSettings.fps

                            // If metadata is missing, extract and save it
                            if (durationMs == null || width == null || height == null || fps == null) {
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

                                    synchronized(preferencesManager) {
                                        preferencesManager.updateVideoSettings(fileName) { settings ->
                                            settings.copy(
                                                durationMs = durationMs,
                                                width = width,
                                                height = height,
                                                fps = fps
                                            )
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(tag, "Error extracting metadata for ${file.name}", e)
                                }
                            }

                            RecentFile(
                                file = file,
                                thumbnail = thumbnail,
                                durationMs = durationMs ?: 0L,
                                width = width ?: 0,
                                height = height ?: 0,
                                sizeBytes = file.length(),
                                fps = fps ?: 0
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
     * Gets a cached thumbnail from disk, or generates and caches one if missing.
     */
    fun getOrGenerateThumbnail(videoFile: File): Bitmap? {
        val thumbnailsDir = getAppSpecificAlbumStorageDir(context, "thumbnails")
        val thumbnailFile = File(thumbnailsDir, "${videoFile.nameWithoutExtension}.jpg")
        
        if (thumbnailFile.exists()) {
            return android.graphics.BitmapFactory.decodeFile(thumbnailFile.absolutePath)
        }
        
        val bitmap = createVideoThumbnail(videoFile.absolutePath)
        if (bitmap != null) {
            try {
                java.io.FileOutputStream(thumbnailFile).use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to cache thumbnail for ${videoFile.name}", e)
            }
        }
        return bitmap
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
