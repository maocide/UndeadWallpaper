package org.maocide.undeadwallpaper

import android.content.Context
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
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
            Log.e(tag, "Failed to copy default resource: $fileName", e)
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
        } catch (e: Exception) {
            Log.w(tag, "Could not query file name, generating fallback.", e)
        }

        // If name query failed or returned blank, generate a timestamp name
        if (originalFileName.isBlank()) {
            originalFileName = "imported_video_${System.currentTimeMillis()}.mp4"
        }

        val outputDir = getAppSpecificAlbumStorageDir(context, "videos")
        val outputFile = File(outputDir, originalFileName)

        try {
            context.contentResolver.openInputStream(fileUri)?.use { iStream ->
                copyStreamToFile(iStream, outputFile)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error copying file from URI: $fileUri", e)
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
     * Loads the list of recent video files from the app's storage.
     *
     * @return A list of [RecentFile] objects.
     */
    suspend fun loadRecentFiles(): List<RecentFile> = withContext(Dispatchers.IO) {
        val videosDir = getAppSpecificAlbumStorageDir(context, "videos")
        val files = videosDir.listFiles() ?: return@withContext emptyList()
        val semaphore = Semaphore(4) // Limit to 4 concurrent thumbnail generations

        files.map { file ->
            async {
                semaphore.withPermit {
                    try {
                        val thumbnail = createVideoThumbnail(file.path)
                        RecentFile(file, thumbnail)
                    } catch (e: Exception) {
                        Log.e(tag, "Error loading thumbnail for ${file.name}", e)
                        RecentFile(file, null)
                    }
                }
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
