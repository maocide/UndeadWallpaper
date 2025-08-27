package org.maocide.undeadwallpaper

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.arthenica.mobileffmpeg.Config
import com.arthenica.mobileffmpeg.FFmpeg
import java.io.File

class VideoClipWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_INPUT_URI = "KEY_INPUT_URI"
        const val KEY_OUTPUT_URI = "KEY_OUTPUT_URI"
        const val KEY_START_MS = "KEY_START_MS"
        const val KEY_END_MS = "KEY_END_MS"
        const val KEY_OUTPUT_PATH = "KEY_OUTPUT_PATH"
    }

    override suspend fun doWork(): Result {
        val inputUriString = inputData.getString(KEY_INPUT_URI) ?: return Result.failure()
        val startMs = inputData.getLong(KEY_START_MS, 0)
        val endMs = inputData.getLong(KEY_END_MS, -1)

        if (endMs == -1L) {
            return Result.failure()
        }

        val videoFileManager = VideoFileManager(applicationContext)
        val outputFile = videoFileManager.createTempVideoFile()

        val startTime = String.format("%d.%03d", startMs / 1000, startMs % 1000)
        val endTime = String.format("%d.%03d", endMs / 1000, endMs % 1000)

        // The path from the input URI needs to be resolved to a real file path
        val inputFile = File(Uri.parse(inputUriString).path)

        val command = "-y -i \"${inputFile.absolutePath}\" -ss $startTime -to $endTime -c copy \"${outputFile.absolutePath}\""

        Log.d("VideoClipWorker", "Executing FFmpeg command: $command")

        val rc = FFmpeg.execute(command)

        return if (rc == Config.RETURN_CODE_SUCCESS) {
            Log.d("VideoClipWorker", "FFmpeg command succeeded")
            val outputData = workDataOf(KEY_OUTPUT_PATH to outputFile.absolutePath)
            Result.success(outputData)
        } else {
            Log.e("VideoClipWorker", "FFmpeg command failed with rc=$rc")
            Config.printLastCommandOutput(Log.ERROR)
            Result.failure()
        }
    }
}
