package org.maocide.undeadwallpaper.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maocide.undeadwallpaper.model.WallpaperAction

class GestureControlsControllerTest {

    private val noneId = 101
    private val pauseId = 102
    private val skipId = 103

    @Test
    fun `chipIdToAction correctly maps chip IDs to WallpaperAction`() {
        assertEquals(
            WallpaperAction.PLAY_PAUSE,
            GestureControlsController.chipIdToAction(pauseId, pauseId, skipId)
        )
        assertEquals(
            WallpaperAction.SKIP_NEXT,
            GestureControlsController.chipIdToAction(skipId, pauseId, skipId)
        )
        assertEquals(
            WallpaperAction.NONE,
            GestureControlsController.chipIdToAction(noneId, pauseId, skipId)
        )
        assertEquals(
            WallpaperAction.NONE,
            GestureControlsController.chipIdToAction(9999, pauseId, skipId)
        )
    }

    @Test
    fun `actionToChipId correctly maps WallpaperAction to chip IDs`() {
        assertEquals(
            noneId,
            GestureControlsController.actionToChipId(WallpaperAction.NONE, noneId, pauseId, skipId)
        )
        assertEquals(
            pauseId,
            GestureControlsController.actionToChipId(WallpaperAction.PLAY_PAUSE, noneId, pauseId, skipId)
        )
        assertEquals(
            skipId,
            GestureControlsController.actionToChipId(WallpaperAction.SKIP_NEXT, noneId, pauseId, skipId)
        )
    }

    @Test
    fun `isAnyGestureActive returns true only when at least one gesture is active`() {
        assertFalse(
            "Both NONE should be false",
            GestureControlsController.isAnyGestureActive(WallpaperAction.NONE, WallpaperAction.NONE)
        )
        assertTrue(
            "Double tap active should be true",
            GestureControlsController.isAnyGestureActive(WallpaperAction.PLAY_PAUSE, WallpaperAction.NONE)
        )
        assertTrue(
            "Triple tap active should be true",
            GestureControlsController.isAnyGestureActive(WallpaperAction.NONE, WallpaperAction.SKIP_NEXT)
        )
        assertTrue(
            "Both active should be true",
            GestureControlsController.isAnyGestureActive(WallpaperAction.PLAY_PAUSE, WallpaperAction.SKIP_NEXT)
        )
    }
}
