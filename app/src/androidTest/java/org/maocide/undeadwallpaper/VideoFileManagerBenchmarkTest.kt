package org.maocide.undeadwallpaper

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.util.Log
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class VideoFileManagerBenchmarkTest {

    private val TAG = "VideoFileManagerBenchmark"

    @Test
    fun benchmarkLoadRecentFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val videoFileManager = VideoFileManager(context)

        // 1. Setup: Clean up existing videos
        val videosDir = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "videos")
        if (videosDir.exists()) {
            videosDir.listFiles()?.forEach { it.delete() }
        } else {
            videosDir.mkdirs()
        }

        // 2. Setup: Create multiple video files (20 copies)
        val copyCount = 20
        for (i in 0 until copyCount) {
            val fileName = "benchmark_video_$i.mp4"
            videoFileManager.createDefaultFileFromResource(org.maocide.undeadwallpaper.R.raw.zombillie_default, fileName)
        }

        // 3. Measure
        val startTime = System.nanoTime()
        val recentFiles = kotlinx.coroutines.runBlocking {
             videoFileManager.loadRecentFiles()
        }
        val endTime = System.nanoTime()

        // 4. Report
        val durationMs = (endTime - startTime) / 1_000_000
        Log.i(TAG, "Benchmark: loadRecentFiles took $durationMs ms for $copyCount files")
        println("Benchmark: loadRecentFiles took $durationMs ms for $copyCount files")

        // 5. Verify
        assertEquals(copyCount, recentFiles.size)
    }
}
