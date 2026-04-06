package org.maocide.undeadwallpaper.model

import android.graphics.Bitmap
import java.io.File
import java.util.Locale

/**
 * Data class representing a recent file.
 *
 * @param file The file object.
 * @param thumbnail The thumbnail of the file.
 * @param durationMs The duration of the video in milliseconds.
 * @param width The width of the video.
 * @param height The height of the video.
 * @param sizeBytes The size of the file in bytes.
 * @param fps The frame rate of the video.
 */
data class RecentFile(
    val file: File,
    val thumbnail: Bitmap?,
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0,
    val fps: Int = 0
) {
    fun getFormattedMetadata(): String {
        val totalSeconds = durationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val timeString = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

        val sizeMB = sizeBytes / (1024 * 1024)

        return if (width > 0 && height > 0 && durationMs > 0) {
            val fpsString = if (fps > 0) " • ${fps}fps" else ""
            "$timeString • ${width}x${height}$fpsString • $sizeMB MB"
        } else {
            "MP4 Video • $sizeMB MB"
        }
    }
}
