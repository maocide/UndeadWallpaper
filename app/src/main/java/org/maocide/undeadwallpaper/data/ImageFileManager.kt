package org.maocide.undeadwallpaper.data

import org.maocide.undeadwallpaper.BuildConfig

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Manages the bridge images used by the per-screen wallpaper feature.
 * Images are copied into the app's private pictures directory and decoded
 * down-sampled to (at most) screen resolution to keep GPU memory bounded.
 *
 * @param context The application context.
 */
class ImageFileManager(private val context: Context) {

    private val tag: String = javaClass.simpleName

    /**
     * Copies a picked image (content URI) into the app's images directory.
     * @return the stored [File], or null if the copy fails.
     */
    fun createImageFromContentUri(fileUri: Uri): File? {
        var originalFileName = ""

        try {
            context.contentResolver.query(fileUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        originalFileName = cursor.getString(nameIndex)
                    }
                }
            }
            originalFileName = File(originalFileName).name
        } catch (e: Exception) {
            Log.w(tag, "Could not query image name, generating fallback.", e)
        }

        if (originalFileName.isBlank()) {
            originalFileName = "bridge_${java.util.UUID.randomUUID()}.png"
        }

        // Prefix with a UUID so re-picking the same file never collides with an
        // image still referenced by another slot.
        val storedName = "${java.util.UUID.randomUUID()}_$originalFileName"

        val outputDir = getImagesDir()
        val outputFile = File(outputDir, storedName)

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
                Log.e(tag, "Error copying image from URI: $fileUri", e)
            } else {
                Log.e(tag, "Error copying image from URI", e)
            }
            return null
        }

        return outputFile
    }

    fun getImageFile(fileName: String): File = File(getImagesDir(), fileName)

    /**
     * Loads a stored bridge image as a Bitmap, down-sampled so neither dimension
     * greatly exceeds [reqWidth] x [reqHeight] (typically the screen size).
     */
    fun loadBitmap(fileName: String?, reqWidth: Int, reqHeight: Int): Bitmap? {
        if (fileName.isNullOrBlank()) return null
        val file = getImageFile(fileName)
        if (!file.exists()) return null

        return try {
            // First pass: bounds only.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds, reqWidth, reqHeight)
            }
            BitmapFactory.decodeFile(file.path, options)
        } catch (e: Exception) {
            Log.e(tag, "Failed to decode bridge image", e)
            null
        }
    }

    /** Deletes a stored image if it is no longer referenced. Best-effort. */
    fun deleteImage(fileName: String?) {
        if (fileName.isNullOrBlank()) return
        try {
            val file = getImageFile(fileName)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            Log.w(tag, "Failed to delete image $fileName", e)
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (reqWidth <= 0 || reqHeight <= 0) return 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun getImagesDir(): File {
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "images")
        if (!file.exists() && !file.mkdirs()) {
            Log.e(tag, "Images directory not created")
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
}
