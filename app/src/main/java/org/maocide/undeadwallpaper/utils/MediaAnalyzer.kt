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

    sealed class VideoValidationResult {
        object Valid : VideoValidationResult()
        object DimensionsExceeded : VideoValidationResult()
        object NotAVideo : VideoValidationResult()
    }

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
     * Determines whether the given byte array begins with unambiguous magic bytes
     * characteristic of static image formats (JPEG, PNG, GIF, BMP, TIFF, WebP).
     *
     * @param bytes Initial bytes of the file (recommended at least 16 bytes).
     * @return True if identified as a static image format, false otherwise.
     */
    fun isStaticImageHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 3) return false

        // JPEG: FF D8 FF
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return true
        }

        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() && bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte()
        ) {
            return true
        }

        // GIF: GIF87a or GIF89a
        if (bytes.size >= 6 &&
            bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() &&
            bytes[3] == 0x38.toByte() && (bytes[4] == 0x37.toByte() || bytes[4] == 0x39.toByte()) &&
            bytes[5] == 0x61.toByte()
        ) {
            return true
        }

        // BMP: 42 4D ('BM') with 4 zero bytes in reserved field at offset 6..9
        if (bytes.size >= 10 &&
            bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte() &&
            bytes[6] == 0.toByte() && bytes[7] == 0.toByte() &&
            bytes[8] == 0.toByte() && bytes[9] == 0.toByte()
        ) {
            return true
        }

        // TIFF: 49 49 2A 00 (Little-endian) or 4D 4D 00 2A (Big-endian)
        if (bytes.size >= 4) {
            if (bytes[0] == 0x49.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x2A.toByte() && bytes[3] == 0x00.toByte()) return true
            if (bytes[0] == 0x4D.toByte() && bytes[1] == 0x4D.toByte() && bytes[2] == 0x00.toByte() && bytes[3] == 0x2A.toByte()) return true
        }

        // WebP: RIFF....WEBP (bytes 0..3 = 'RIFF', bytes 8..11 = 'WEBP')
        if (bytes.size >= 12 &&
            bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() && bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
        ) {
            return true
        }

        return false
    }

    /**
     * Validates that the media URI corresponds to a playable video stream and conforms to pixel limits.
     * Rejects static images, non-video audio, and containers missing visual tracks.
     *
     * @param maxPixels The maximum allowed pixel count (default 12M pixels, ~4K UHD).
     * @return VideoValidationResult indicating Valid, DimensionsExceeded, or NotAVideo.
     */
    suspend fun validateVideoMedia(
        context: Context,
        uri: Uri,
        maxPixels: Int = 12_000_000
    ): VideoValidationResult {
        val result = withTimeoutOrNull(5000L) {
            withContext(getSafeDispatcher()) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)

                    val hasVideoStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                    val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)

                    val width = widthStr?.toIntOrNull() ?: 0
                    val height = heightStr?.toIntOrNull() ?: 0
                    val durationMs = durationStr?.toLongOrNull() ?: 0L
                    val hasVideo = hasVideoStr == "yes" || (width > 0 && height > 0)

                    if (!hasVideo || width <= 0 || height <= 0) {
                        // Fallback: Check with MediaExtractor in case MediaMetadataRetriever failed on a quirky video container
                        var extractorHasVideo = false
                        var extractorWidth = 0
                        var extractorHeight = 0
                        val extractor = MediaExtractor()
                        try {
                            extractor.setDataSource(context, uri, null)
                            for (i in 0 until extractor.trackCount) {
                                val format = extractor.getTrackFormat(i)
                                val mime = format.getString(MediaFormat.KEY_MIME)
                                if (mime?.startsWith("video/") == true) {
                                    extractorHasVideo = true
                                    if (format.containsKey(MediaFormat.KEY_WIDTH)) {
                                        extractorWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                                    }
                                    if (format.containsKey(MediaFormat.KEY_HEIGHT)) {
                                        extractorHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                                    }
                                    break
                                }
                            }
                        } catch (_: Exception) {
                            // Extractor failed as well
                        } finally {
                            try {
                                extractor.release()
                            } catch (_: Exception) {}
                        }

                        val finalW = if (width > 0) width else extractorWidth
                        val finalH = if (height > 0) height else extractorHeight
                        if (!extractorHasVideo || finalW <= 0 || finalH <= 0) {
                            FileLogger.w(TAG, "Media validation failed: No valid video track found.")
                            return@withContext VideoValidationResult.NotAVideo
                        }
                    }

                    val pixelCount = width * height
                    FileLogger.i(
                        TAG,
                        "Video Analysis: ${width}x${height} ($pixelCount pixels, duration: ${durationMs}ms). Max allowed: $maxPixels"
                    )

                    if (pixelCount >= maxPixels) {
                        VideoValidationResult.DimensionsExceeded
                    } else {
                        VideoValidationResult.Valid
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) {
                        FileLogger.e(TAG, "MediaMetadataRetriever failed for $uri, attempting MediaExtractor fallback", e)
                    }

                    // Fallback to MediaExtractor if retriever threw
                    var extractorHasVideo = false
                    var extractorPixelCount = 0
                    val extractor = MediaExtractor()
                    try {
                        extractor.setDataSource(context, uri, null)
                        for (i in 0 until extractor.trackCount) {
                            val format = extractor.getTrackFormat(i)
                            val mime = format.getString(MediaFormat.KEY_MIME)
                            if (mime?.startsWith("video/") == true) {
                                extractorHasVideo = true
                                val w = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 0
                                val h = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
                                extractorPixelCount = w * h
                                break
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        try {
                            extractor.release()
                        } catch (_: Exception) {}
                    }

                    if (extractorHasVideo) {
                        if (extractorPixelCount >= maxPixels) {
                            VideoValidationResult.DimensionsExceeded
                        } else {
                            VideoValidationResult.Valid
                        }
                    } else {
                        FileLogger.w(TAG, "Media analysis failed to find video track: ${e.message}")
                        VideoValidationResult.NotAVideo
                    }
                } finally {
                    try {
                        retriever.release()
                    } catch (_: Exception) {}
                }
            }
        }

        if (result == null) {
            FileLogger.e(
                TAG,
                "validateVideoMedia timed out! Deadlocked native thread detected. Resetting dispatcher."
            )
            resetDispatcher()
        }

        return result ?: VideoValidationResult.NotAVideo // strictly fail on timeout
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
    ): Boolean = validateVideoMedia(context, uri, maxPixels) is VideoValidationResult.Valid

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
