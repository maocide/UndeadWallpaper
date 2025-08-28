package org.maocide.undeadwallpaper

import android.content.Context
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
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
     * Creates a file in the app's specific storage from a content URI.
     * It uses the original file name to avoid duplicates.
     *
     * @param fileUri The URI of the file to be copied.
     * @return The newly created File object, or null if the operation fails.
     */
    fun createFileFromContentUri(fileUri: Uri): File? {
        var originalFileName = ""
        context.contentResolver.query(fileUri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            originalFileName = cursor.getString(nameIndex)
        }

        if (originalFileName.isBlank()) {
            return null
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
    fun loadRecentFiles(): List<RecentFile> {
        val videosDir = getAppSpecificAlbumStorageDir(context, "videos")
        return videosDir.listFiles()?.map { file ->
            val thumbnail = createVideoThumbnail(file.path)
            RecentFile(file, thumbnail)
        } ?: emptyList()
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
