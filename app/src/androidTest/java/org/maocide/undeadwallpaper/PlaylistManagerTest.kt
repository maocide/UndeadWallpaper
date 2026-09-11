package org.maocide.undeadwallpaper

import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.maocide.undeadwallpaper.data.PlaylistManager
import org.maocide.undeadwallpaper.data.PreferencesManager
import org.maocide.undeadwallpaper.model.PlaybackMode
import org.maocide.undeadwallpaper.model.ScalingMode
import org.maocide.undeadwallpaper.model.VideoSettings
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlaylistManagerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var prefsManager: PreferencesManager
    private lateinit var playlistManager: PlaylistManager
    private lateinit var videosDir: File

    @Before
    fun setup() {
        prefsManager = PreferencesManager(context, "TEST_PREFS")
        
        // Clear prefs before each test
        prefsManager.savePlaylistSettings(emptyList())
        prefsManager.saveActivePage(0)

        // Create dummy video files
        videosDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "test_videos")
        if (!videosDir.exists()) {
            videosDir.mkdirs()
        }
        
        // Ensure directory is clean
        videosDir.listFiles()?.forEach { it.delete() }
        playlistManager = PlaylistManager(context, prefsManager, "test_videos")
    }

    @After
    fun teardown() {
        prefsManager.savePlaylistSettings(emptyList())
        prefsManager.saveActivePage(0)
        videosDir.listFiles()?.forEach { it.delete() }
    }

    private fun createDummyVideo(fileName: String, settings: VideoSettings) {
        val file = File(videosDir, fileName)
        file.createNewFile()
        
        // Save settings via PreferencesManager
        val currentList = prefsManager.getPlaylistSettings().toMutableList()
        currentList.add(settings)
        prefsManager.savePlaylistSettings(currentList)
    }

    @Test
    fun testGaplessChunkingLinear() = runBlocking {
        // v1, v2 have SAME visual settings
        createDummyVideo("v1.mp4", VideoSettings("v1.mp4", scalingMode = ScalingMode.FILL))
        createDummyVideo("v2.mp4", VideoSettings("v2.mp4", scalingMode = ScalingMode.FILL, volume = 1.0f)) // audio differs, visuals same
        
        // v3 has DIFFERENT visual settings
        createDummyVideo("v3.mp4", VideoSettings("v3.mp4", scalingMode = ScalingMode.FIT))

        val playlistUris = playlistManager.getPlaylistUris()
        assertEquals(3, playlistUris.size)

        // Chunk starting at v1 should include v1 and v2, but break at v3
        val chunkV1 = playlistManager.getGaplessChunkUris(playlistUris[0], PlaybackMode.LOOP_ALL, playlistUris)
        assertEquals(2, chunkV1.size)
        assertEquals(playlistUris[0], chunkV1[0]) // v1
        assertEquals(playlistUris[1], chunkV1[1]) // v2

        // Chunk starting at v3 should only contain v3 (next one wraps to v1, which differs from v3)
        val chunkV3 = playlistManager.getGaplessChunkUris(playlistUris[2], PlaybackMode.LOOP_ALL, playlistUris)
        assertEquals(1, chunkV3.size)
        assertEquals(playlistUris[2], chunkV3[0])
    }

    @Test
    fun testGaplessChunkingWrapAround() = runBlocking {
        // All videos have same visual settings
        createDummyVideo("v1.mp4", VideoSettings("v1.mp4", scalingMode = ScalingMode.FILL))
        createDummyVideo("v2.mp4", VideoSettings("v2.mp4", scalingMode = ScalingMode.FILL))
        createDummyVideo("v3.mp4", VideoSettings("v3.mp4", scalingMode = ScalingMode.FILL))

        val playlistUris = playlistManager.getPlaylistUris()

        // Chunk starting at v2 should wrap around and include all videos: v2 -> v3 -> v1
        val chunkV2 = playlistManager.getGaplessChunkUris(playlistUris[1], PlaybackMode.LOOP_ALL, playlistUris)
        
        assertEquals(3, chunkV2.size)
        assertEquals(playlistUris[1], chunkV2[0]) // v2
        assertEquals(playlistUris[2], chunkV2[1]) // v3
        assertEquals(playlistUris[0], chunkV2[2]) // v1
    }

    @Test
    fun testShuffleChunkingNeverWraps() = runBlocking {
        // All videos have same settings, but SHUFFLE chunking should NEVER wrap around the sequence boundary
        createDummyVideo("v1.mp4", VideoSettings("v1.mp4"))
        createDummyVideo("v2.mp4", VideoSettings("v2.mp4"))
        createDummyVideo("v3.mp4", VideoSettings("v3.mp4"))
        createDummyVideo("v4.mp4", VideoSettings("v4.mp4"))

        val playlistUris = playlistManager.getPlaylistUris()

        // Force a known shuffle order so we know where the end of the sequence is.
        // Let's pretend the shuffle sequence ended up putting our current video near the end.
        // To properly test this without mocking, we'll just test all of them.
        // At least one of them MUST be the last in the shuffle sequence and return a chunk size of 1.
        var foundBoundary = false
        for (uri in playlistUris) {
            val chunk = playlistManager.getGaplessChunkUris(uri, PlaybackMode.SHUFFLE, playlistUris)
            if (chunk.size < playlistUris.size) {
                foundBoundary = true
            }
        }
        
        // If it wrapped around, every video would return a chunk of size 4.
        // Because SHUFFLE forces a break at the sequence end, someone must return < 4.
        assertEquals("Shuffle chunk must break at sequence boundary", true, foundBoundary)
    }

    @Test
    fun testSequenceAdvancement() = runBlocking {
        createDummyVideo("v1.mp4", VideoSettings("v1.mp4"))
        createDummyVideo("v2.mp4", VideoSettings("v2.mp4"))

        val playlistUris = playlistManager.getPlaylistUris()

        // Test Linear Loop
        val nextV1Linear = playlistManager.getNextUri(playlistUris[0], PlaybackMode.LOOP_ALL)
        assertEquals(playlistUris[1], nextV1Linear)
        
        val nextV2Linear = playlistManager.getNextUri(playlistUris[1], PlaybackMode.LOOP_ALL)
        assertEquals(playlistUris[0], nextV2Linear) // Wrap around to start
        
        // Test Shuffle regeneration at boundary
        // For a 2-item list, calling next enough times MUST eventually touch both
        val nextV1Shuffle = playlistManager.getNextUri(playlistUris[0], PlaybackMode.SHUFFLE)
        val nextNextShuffle = nextV1Shuffle?.let { playlistManager.getNextUri(it, PlaybackMode.SHUFFLE) }
        
        assertEquals(true, nextV1Shuffle != null && nextNextShuffle != null)
    }
    @Test
    fun testCollapseEmptyPages_noEmptyPages() {
        val settings = mutableListOf(
            VideoSettings("video1.mp4", page = 0),
            VideoSettings("video2.mp4", page = 1),
            VideoSettings("video3.mp4", page = 2)
        )

        val collapsed = playlistManager.collapseEmptyPages(settings)
        org.junit.Assert.assertFalse(collapsed)
        assertEquals(0, settings[0].page)
        assertEquals(1, settings[1].page)
        assertEquals(2, settings[2].page)
    }

    @Test
    fun testCollapseEmptyPages_withEmptyPages() {
        // Page 1 is empty
        val settings = mutableListOf(
            VideoSettings("video1.mp4", page = 0),
            VideoSettings("video2.mp4", page = 2),
            VideoSettings("video3.mp4", page = 3)
        )

        val collapsed = playlistManager.collapseEmptyPages(settings)
        org.junit.Assert.assertTrue(collapsed)
        
        // Items should be shifted down
        assertEquals(0, settings[0].page)
        assertEquals(1, settings[1].page)
        assertEquals(2, settings[2].page)
    }

    @Test
    fun testCollapseEmptyPages_updatesActivePage() {
        val settings = mutableListOf(
            VideoSettings("video1.mp4", page = 0),
            VideoSettings("video2.mp4", page = 2)
        )
        
        // User was on page 2 (which will become page 1 after collapse)
        prefsManager.saveActivePage(2)

        val collapsed = playlistManager.collapseEmptyPages(settings)
        org.junit.Assert.assertTrue(collapsed)
        
        assertEquals(0, settings[0].page)
        assertEquals(1, settings[1].page)
        
        // Active page should be clamped to the new max page (1)
        assertEquals(1, prefsManager.getActivePage())
    }
}
