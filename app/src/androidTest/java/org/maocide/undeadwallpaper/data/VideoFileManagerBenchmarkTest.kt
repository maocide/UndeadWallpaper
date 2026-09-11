package org.maocide.undeadwallpaper.data

import org.maocide.undeadwallpaper.R

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
    fun benchmarkLoadRecentFiles() = kotlinx.coroutines.runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val videoFileManager = org.maocide.undeadwallpaper.data.VideoFileManager(context, "TEST_PREFS_BENCHMARK", "test_videos_benchmark")

        // 1. Setup: Clean up existing videos
        val videosDir = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "test_videos_benchmark")
        if (videosDir.exists()) {
            videosDir.listFiles()?.forEach { it.delete() }
        } else {
            videosDir.mkdirs()
        }

        // 2. Setup: Create multiple video files (20 copies)
        val copyCount = 20
        val prefs = org.maocide.undeadwallpaper.data.PreferencesManager(context, "TEST_PREFS_BENCHMARK")
        val settingsList = mutableListOf<org.maocide.undeadwallpaper.model.VideoSettings>()
        
        for (i in 0 until copyCount) {
            val file = videoFileManager.createDefaultFileFromResource(org.maocide.undeadwallpaper.R.raw.zombillie_default)
            if (file != null) {
                settingsList.add(org.maocide.undeadwallpaper.model.VideoSettings(fileName = file.name, expectedFileSize = file.length()))
            }
        }
        prefs.savePlaylistSettings(settingsList)

        // 3. Measure
        val startTime = System.nanoTime()
        val recentFiles = videoFileManager.loadRecentFiles()
        val endTime = System.nanoTime()

        // 4. Report
        val durationMs = (endTime - startTime) / 1_000_000
        android.util.Log.i(TAG, "Benchmark: loadRecentFiles took $durationMs ms for $copyCount files")
        println("Benchmark: loadRecentFiles took $durationMs ms for $copyCount files")

        // 5. Verify
        assertEquals(copyCount, recentFiles.size)
    }
}
