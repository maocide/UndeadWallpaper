package org.maocide.undeadwallpaper.utils

import android.app.WallpaperColors
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import org.maocide.undeadwallpaper.BuildConfig

data class VideoMetadata(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Int
)

data class WallpaperColorsData(
    val primaryColor: Int,
    val secondaryColor: Int?,
    val tertiaryColor: Int?,
    val colorHints: Int
)

object MediaAnalyzer {
    private const val TAG = "MediaAnalyzer"

    // Dedicated isolated thread to prevent deadlocked decoders from exhausting the IO pool
    private var executorService = Executors.newSingleThreadExecutor()
    private var isolatedDispatcher = executorService.asCoroutineDispatcher()

    @Synchronized
    private fun getSafeDispatcher() = isolatedDispatcher

    @Synchronized
    private fun resetDispatcher() {
        try {
            executorService.shutdownNow()
        } catch (_: Exception) {
            // Ignore shutdown errors
        }
        FileLogger.w(TAG, "Spinning up new isolated thread executor.")
        executorService = Executors.newSingleThreadExecutor()
        isolatedDispatcher = executorService.asCoroutineDispatcher()
    }

    /**
     * Checks if the video dimensions fall under the specified pixel cap limit.
     * This protects the hardware decoder from extremely large resolutions (like 5K/8K).
     *
     * @param maxPixels The maximum allowed pixel count (default 12M pixels).
     * @return True if valid, False if it strictly exceeds the cap.
     */
    suspend fun validateVideoDimensions(
        context: Context,
        uri: Uri,
        maxPixels: Int = 12_000_000
    ): Boolean {
        val result = withTimeoutOrNull(5000L) {
            withContext(getSafeDispatcher()) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)

                    val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)

                    val width = widthStr?.toIntOrNull() ?: 0
                    val height = heightStr?.toIntOrNull() ?: 0
                    val pixelCount = width * height

                    FileLogger.i(
                        TAG,
                        "Video Analysis: ${width}x${height} ($pixelCount pixels). Max allowed: $maxPixels"
                    )

                    pixelCount < maxPixels
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) {
                        FileLogger.e(TAG, "Failed to analyze video dimensions for $uri", e)
                    } else {
                        FileLogger.e(TAG, "Failed to analyze video dimensions", e)
                    }
                    // Allow processing to continue if analysis fails (fallback safe)
                    true
                } finally {
                    try {
                        retriever.release()
                    } catch (e: Exception) {
                        // Ignore release exceptions
                    }
                }
            }
        }

        if (result == null) {
            FileLogger.e(
                TAG,
                "validateVideoDimensions timed out! Deadlocked native thread detected. Resetting dispatcher."
            )
            resetDispatcher()
        }

        return result ?: false // strictly fail on timeout
    }

    /**
     * Retrieves the duration of a video from its URI.
     * @return The duration in milliseconds, or 0L if it cannot be determined.
     */
    suspend fun getVideoDuration(context: Context, uri: Uri): Long {
        val result = withTimeoutOrNull(5000L) {
            withContext(getSafeDispatcher()) {
                var durationMs = 0L
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val durationString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    durationMs = durationString?.toLongOrNull() ?: 0L
                } catch (e: Exception) {
                    FileLogger.e(TAG, "Failed to get video duration", e)
                } finally {
                    try {
                        retriever.release()
                    } catch (e: Exception) {}
                }

                if (durationMs == 0L) {
                    val extractor = MediaExtractor()
                    try {
                        extractor.setDataSource(context, uri, null)
                        for (i in 0 until extractor.trackCount) {
                            val format = extractor.getTrackFormat(i)
                            val mime = format.getString(MediaFormat.KEY_MIME)
                            if (mime?.startsWith("video/") == true) {
                                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                                    durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000L
                                }
                                break
                            }
                        }
                    } catch (e: Exception) {
                        FileLogger.e(TAG, "Failed to get video duration via Extractor", e)
                    } finally {
                        try {
                            extractor.release()
                        } catch (e: Exception) {
                            // Ignore release exceptions
                        }
                    }
                }
                durationMs
            }
        }

        if (result == null) {
            FileLogger.e(TAG, "getVideoDuration timed out! Deadlocked native thread detected. Resetting dispatcher.")
            resetDispatcher()
        }

        return result ?: 0L
    }

    /**
     * Extracts duration, width, height, and FPS from a physical file path.
     */
    suspend fun extractVideoMetadata(filePath: String): VideoMetadata {
        val result = withTimeoutOrNull(5000L) {
            withContext(getSafeDispatcher()) {
                var durationMs = 0L
                var width = 0
                var height = 0
                var fps = 0

                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(filePath)
                    durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                    width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        ?.toIntOrNull() ?: 0
                    height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        ?.toIntOrNull() ?: 0
                } catch (e: Exception) {
                    FileLogger.e(TAG, "Failed to extract basic metadata via Retriever for $filePath", e)
                } finally {
                    retriever.release()
                }

                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(filePath)
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME)

                        if (mime?.startsWith("video/") == true) {
                            if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                                fps = format.getInteger(MediaFormat.KEY_FRAME_RATE)
                            }
                            if (durationMs == 0L && format.containsKey(MediaFormat.KEY_DURATION)) {
                                durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000L
                            }
                            break
                        }
                    }
                } catch (e: Exception) {
                    FileLogger.e(TAG, "Failed to extract FPS via Extractor for $filePath", e)
                } finally {
                    try {
                        extractor.release()
                    } catch (e: Exception) {
                        // Ignore release exceptions
                    }
                }

                VideoMetadata(durationMs, width, height, fps)
            }
        }

        if (result == null) {
            FileLogger.e(
                TAG,
                "extractVideoMetadata timed out! Deadlocked native thread detected. Resetting dispatcher."
            )
            resetDispatcher()
        }

        return result ?: VideoMetadata(0L, 0, 0, 0)
    }

    /**
     * Extracts Material You WallpaperColors from a full-sized thumbnail.
     * This method safely downscales the thumbnail first for performance.
     */
    fun extractWallpaperColors(thumbnail: Bitmap): WallpaperColorsData? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null

        return try {
            // Downscale for performance
            val scaledThumb = Bitmap.createScaledBitmap(thumbnail, 112, 112, true)
            val colors = WallpaperColors.fromBitmap(scaledThumb)

            WallpaperColorsData(
                primaryColor = colors.primaryColor.toArgb(),
                secondaryColor = colors.secondaryColor?.toArgb(),
                tertiaryColor = colors.tertiaryColor?.toArgb(),
                colorHints = colors.colorHints
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to extract wallpaper colors from thumbnail", e)
            null
        }
    }
}
