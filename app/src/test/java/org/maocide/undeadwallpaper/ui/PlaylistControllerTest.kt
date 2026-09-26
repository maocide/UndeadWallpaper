package org.maocide.undeadwallpaper.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maocide.undeadwallpaper.model.VideoSettings
import org.maocide.undeadwallpaper.model.RecentFile
import java.io.File

class PlaylistControllerTest {

    @Test
    fun calculateReorderedPlaylistSettings_reordersCurrentPageWithoutAffectingOtherPages() {
        val existingSettings = listOf(
            VideoSettings(fileName = "video_a.mp4", page = 0),
            VideoSettings(fileName = "video_b.mp4", page = 0),
            VideoSettings(fileName = "video_c.mp4", page = 0),
            VideoSettings(fileName = "video_p1.mp4", page = 1)
        )

        // User dragged video_c to the top on page 0
        val newOrderPage0 = listOf("video_c.mp4", "video_a.mp4", "video_b.mp4")

        val result = PlaylistController.calculateReorderedPlaylistSettings(
            currentFileNames = newOrderPage0,
            allSettings = existingSettings,
            currentPage = 0
        )

        assertEquals(4, result.size)
        // Page 0 items should be in new order
        assertEquals("video_c.mp4", result[0].fileName)
        assertEquals("video_a.mp4", result[1].fileName)
        assertEquals("video_b.mp4", result[2].fileName)
        assertEquals(0, result[0].page)
        assertEquals(0, result[1].page)
        assertEquals(0, result[2].page)

        // Page 1 item should still exist untouched
        assertEquals("video_p1.mp4", result[3].fileName)
        assertEquals(1, result[3].page)
    }

    @Test
    fun calculateTargetMaxPage_returnsNextSlotIfHighestSlotHasItems() {
        val settings = listOf(
            VideoSettings(fileName = "vid1.mp4", page = 0),
            VideoSettings(fileName = "vid2.mp4", page = 1)
        )
        // Slot 0 and 1 have items -> dropdown should offer slot 2 as (New)
        val target = PlaylistController.calculateTargetMaxPage(settings)
        assertEquals(2, target)
    }

    @Test
    fun calculateTargetMaxPage_handlesEmptySettings() {
        val emptySettings = emptyList<VideoSettings>()
        val target = PlaylistController.calculateTargetMaxPage(emptySettings)
        assertEquals(0, target)
    }

    @Test
    fun filterFilesForPage_returnsOnlyFilesBelongingToCurrentPage() {
        val file1 = RecentFile(File("/tmp/vid1.mp4"), thumbnail = null)
        val file2 = RecentFile(File("/tmp/vid2.mp4"), thumbnail = null)
        val file3 = RecentFile(File("/tmp/vid3.mp4"), thumbnail = null)
        val allFiles = listOf(file1, file2, file3)

        val settings = listOf(
            VideoSettings(fileName = "vid1.mp4", page = 0),
            VideoSettings(fileName = "vid2.mp4", page = 1),
            VideoSettings(fileName = "vid3.mp4", page = 0)
        )

        val page0Files = PlaylistController.filterFilesForPage(allFiles, settings, currentPage = 0)
        assertEquals(2, page0Files.size)
        assertTrue(page0Files.any { it.file.name == "vid1.mp4" })
        assertTrue(page0Files.any { it.file.name == "vid3.mp4" })

        val page1Files = PlaylistController.filterFilesForPage(allFiles, settings, currentPage = 1)
        assertEquals(1, page1Files.size)
        assertEquals("vid2.mp4", page1Files.first().file.name)

        val page2Files = PlaylistController.filterFilesForPage(allFiles, settings, currentPage = 2)
        assertTrue(page2Files.isEmpty())
    }

    @Test
    fun calculateReorderedPlaylistSettings_preservesHigherPagesWhenCurrentPageIsEmptied() {
        val existingSettings = listOf(
            VideoSettings(fileName = "vid_p0.mp4", page = 0),
            VideoSettings(fileName = "vid_p3.mp4", page = 3),
            VideoSettings(fileName = "vid_p4.mp4", page = 4)
        )

        // vid_p3 on page 3 was deleted, leaving page 3 empty
        val result = PlaylistController.calculateReorderedPlaylistSettings(
            currentFileNames = emptyList(),
            allSettings = existingSettings,
            currentPage = 3
        )

        assertEquals(2, result.size)
        assertTrue(result.any { it.fileName == "vid_p0.mp4" && it.page == 0 })
        assertTrue(result.any { it.fileName == "vid_p4.mp4" && it.page == 4 })
    }
}
