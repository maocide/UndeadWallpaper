package org.maocide.undeadwallpaper

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
            val exportResult = transformVideo(inputUri, outputPath, startMs, endMs)
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
        val mediaItem = MediaItem.fromUri(inputUri)
        val clippedMediaItem = mediaItem.buildUpon()
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
            ).build()

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
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setListener(listener)
            .build()

        transformer.start(clippedMediaItem, outputPath)

        continuation.invokeOnCancellation {
            transformer.cancel()
        }
    }
}
