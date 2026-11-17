package org.maocide.undeadwallpaper.ui.preview

import org.junit.Assert.assertEquals
import org.junit.Test
import org.maocide.undeadwallpaper.ui.preview.VideoPreviewController.Companion.calculateFrameDimensions

class VideoPreviewControllerTest {

    private val delta = 0.01f

    @Test
    fun testCalculateFrameDimensions_invalidDimensionsReturnZero() {
        assertEquals(Pair(0, 0), calculateFrameDimensions(0, 500, 20, 0.45f))
        assertEquals(Pair(0, 0), calculateFrameDimensions(500, 0, 20, 0.45f))
        assertEquals(Pair(0, 0), calculateFrameDimensions(-10, -10, 20, 0.45f))
    }

    @Test
    fun testCalculateFrameDimensions_portraitPhoneInside16by9Container() {
        // Container: 1000px wide, 562px tall (16:9 ratio ≈ 1.779)
        // Inset: 20px on each side (maxAvailableHeight = 562 - 40 = 522px)
        // Screen ratio: 1080 / 2400 = 0.45f (Pixel portrait)
        val containerWidth = 1000
        val containerHeight = 562
        val insetPx = 20
        val screenRatio = 1080f / 2400f // 0.45f

        val (targetWidth, targetHeight) = calculateFrameDimensions(
            containerWidth = containerWidth,
            containerHeight = containerHeight,
            insetPx = insetPx,
            screenRatio = screenRatio
        )

        // Height should take the full available height
        assertEquals(522, targetHeight)

        // Width should be targetHeight * screenRatio = 522 * 0.45 = 234.9 -> 235
        assertEquals(235, targetWidth)

        // Aspect ratio must match screenRatio
        val calculatedRatio = targetWidth.toFloat() / targetHeight.toFloat()
        assertEquals(screenRatio, calculatedRatio, delta)
    }

    @Test
    fun testCalculateFrameDimensions_landscapePhoneInside16by9Container() {
        // Container: 1000px wide, 562px tall (available 960 x 522, availableRatio = 1.839)
        // Ultra-wide landscape screen ratio: 2400 / 1080 ≈ 2.222f
        // screenRatio > availableRatio -> width constrained
        val containerWidth = 1000
        val containerHeight = 562
        val insetPx = 20
        val screenRatio = 2400f / 1080f // 2.222f

        val (targetWidth, targetHeight) = calculateFrameDimensions(
            containerWidth = containerWidth,
            containerHeight = containerHeight,
            insetPx = insetPx,
            screenRatio = screenRatio
        )

        // Width should take the full available width = 1000 - 40 = 960
        assertEquals(960, targetWidth)

        // Height should be targetWidth / screenRatio = 960 / 2.222 = 432
        assertEquals(432, targetHeight)

        val calculatedRatio = targetWidth.toFloat() / targetHeight.toFloat()
        assertEquals(screenRatio, calculatedRatio, delta)
    }

    @Test
    fun testCalculateFrameDimensions_excessiveInsetClampsToCoerceAtLeastOne() {
        val (targetWidth, targetHeight) = calculateFrameDimensions(
            containerWidth = 100,
            containerHeight = 100,
            insetPx = 60, // 2 * 60 = 120 > 100
            screenRatio = 0.5f
        )
        assertEquals(1, targetWidth)
        assertEquals(1, targetHeight)
    }
}

