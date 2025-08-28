package org.maocide.undeadwallpaper

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Effects
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private data class VideoMetadata(val rotation: Int, val width: Int, val height: Int)

class VideoClipWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_INPUT_URI = "KEY_INPUT_URI"
        const val KEY_START_MS = "KEY_START_MS"
        const val KEY_END_MS = "KEY_END_MS"
        const val KEY_OUTPUT_PATH = "KEY_OUTPUT_PATH"
        private const val TAG = "VideoClipWorker"
    }

    override suspend fun doWork(): Result {
        val inputUriString = inputData.getString(KEY_INPUT_URI) ?: return Result.failure()
        val startMs = inputData.getLong(KEY_START_MS, 0)
        val endMs = inputData.getLong(KEY_END_MS, -1)

        if (endMs <= startMs) {
            Log.e(TAG, "Invalid trim times: startMs=$startMs, endMs=$endMs")
            return Result.failure()
        }

        val inputUri = Uri.parse(inputUriString)
        val videoFileManager = VideoFileManager(applicationContext)
        val outputFile = videoFileManager.createTempVideoFile()
        val outputPath = outputFile.absolutePath

        return try {
            // Transformer must be created and used on the main thread.
            val exportResult = withContext(Dispatchers.Main) {
                transformVideo(inputUri, outputPath, startMs, endMs)
            }
            Log.d(TAG, "Transformation completed. Output size: ${exportResult.fileSizeBytes} bytes")
            val outputData = workDataOf(KEY_OUTPUT_PATH to outputPath)
            Result.success(outputData)
        } catch (e: Exception) {
            Log.e(TAG, "Transformation failed", e)
            outputFile.delete() // Clean up the failed output file
            Result.failure()
        }
    }

    private suspend fun transformVideo(
        inputUri: Uri,
        outputPath: String,
        startMs: Long,
        endMs: Long
    ): ExportResult = suspendCancellableCoroutine { continuation ->
        val mediaItem = MediaItem.Builder()
            .setUri(inputUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
            )
            .build()

        val metadata = getVideoMetadata(appContext, inputUri)
        val editedMediaItem: EditedMediaItem

        if (metadata.rotation == 90 || metadata.rotation == 270) {
            Log.d(TAG, "Applying rotation (${metadata.rotation}°) and presentation effect.")
            val rotateEffect = ScaleAndRotateTransformation.Builder()
                .setRotationDegrees(metadata.rotation.toFloat())
                .build()
            // When rotating, the height becomes the new width. We scale to the original width.
            val presentationEffect = Presentation.createForHeight(metadata.width)

            val effects = Effects(listOf(), listOf(rotateEffect, presentationEffect))
            editedMediaItem = EditedMediaItem.Builder(mediaItem).setEffects(effects).build()
        } else {
            editedMediaItem = EditedMediaItem.Builder(mediaItem).build()
        }

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                if (continuation.isActive) {
                    continuation.resume(exportResult)
                }
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(exportException)
                }
            }
        }

        val transformer = Transformer.Builder(appContext)
            .setVideoMimeType(MimeTypes.VIDEO_H264) // Force re-encoding
            .addListener(listener)
            .build()

        transformer.start(editedMediaItem, outputPath)

        continuation.invokeOnCancellation {
            transformer.cancel()
        }
    }

    private fun getVideoMetadata(context: Context, uri: Uri): VideoMetadata {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            return VideoMetadata(rotation, width, height)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve metadata from URI: $uri", e)
            return VideoMetadata(0, 0, 0)
        } finally {
            retriever.release()
        }
    }
}
