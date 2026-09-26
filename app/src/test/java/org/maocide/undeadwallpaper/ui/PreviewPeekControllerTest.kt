package org.maocide.undeadwallpaper.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maocide.undeadwallpaper.model.ScalingMode
import org.maocide.undeadwallpaper.ui.PreviewPeekController.Companion.calculateDimensions
import org.maocide.undeadwallpaper.ui.PreviewPeekController.Companion.calculateEffectiveScale
import org.maocide.undeadwallpaper.ui.PreviewPeekController.Companion.calculateScaleRatios
import org.maocide.undeadwallpaper.ui.PreviewPeekController.Companion.calculateTranslation

class PreviewPeekControllerTest {

    private val delta = 0.001f

    @Test
    fun testCalculateDimensions_portraitStandardPhone() {
        // Pixel 7 Portrait: 1080x2400, density 2.625
        val (widthPx, heightPx) = calculateDimensions(1080, 2400, 2.625f)

        // Target height = 260dp * 2.625 = 683px (within 55% of 2400 = 1320px)
        assertEquals(683, heightPx)

        // Target width = 683 * (1080 / 2400) = 307.35 -> 307px
        assertEquals(307, widthPx)

        // Verify ratio matches screen ratio
        val screenRatio = 1080f / 2400f
        val calculatedRatio = widthPx.toFloat() / heightPx.toFloat()
        assertEquals(screenRatio, calculatedRatio, 0.01f)
    }

    @Test
    fun testCalculateDimensions_landscapeStandardPhone() {
        // Landscape: 2400x1080, density 2.625
        val (widthPx, heightPx) = calculateDimensions(2400, 1080, 2.625f)

        // Target height = 160dp * 2.625 = 420px (within 55% of 1080 = 594px)
        assertEquals(420, heightPx)

        // Target width = 420 * (2400 / 1080) = 933.33 -> 933px
        assertEquals(933, widthPx)

        val screenRatio = 2400f / 1080f
        val calculatedRatio = widthPx.toFloat() / heightPx.toFloat()
        assertEquals(screenRatio, calculatedRatio, 0.01f)
    }

    @Test
    fun testCalculateDimensions_clampsToMaxSheetHeight() {
        // Ultra-compact or split screen window: 300px height, density 2.0
        // Desired height would be 260 * 2 = 520px, but 55% of 300px is 165px
        val (widthPx, heightPx) = calculateDimensions(200, 300, 2.0f)

        assertEquals(165, heightPx)
        assertEquals(110, widthPx) // 165 * (200 / 300) = 110px
    }

    @Test
    fun testCalculateDimensions_handlesZeroOrNegativeValues() {
        assertEquals(Pair(0, 0), calculateDimensions(0, 0, 2.0f))
        assertEquals(Pair(0, 0), calculateDimensions(-100, 500, 2.0f))
        assertEquals(Pair(0, 0), calculateDimensions(1080, 2400, 0f))
    }

    @Test
    fun testCalculateScaleRatios_squareBitmapNoRotation() {
        val (scaleX, scaleY) = calculateScaleRatios(
            bmpW = 512f,
            bmpH = 512f,
            viewW = 256f,
            viewH = 512f,
            rotationDeg = 0f
        )
        assertEquals(0.5f, scaleX, delta)
        assertEquals(1.0f, scaleY, delta)
    }

    @Test
    fun testCalculateScaleRatios_with90DegreeRotation() {
        // Rotated 90 degrees swaps dimensions of bounding box
        val (scaleX, scaleY) = calculateScaleRatios(
            bmpW = 400f,
            bmpH = 200f,
            viewW = 200f,
            viewH = 400f,
            rotationDeg = 90f
        )
        // Rotated bounding box: rotW = 200, rotH = 400
        // scaleRatioX = 200 / 200 = 1.0, scaleRatioY = 400 / 400 = 1.0
        assertEquals(1.0f, scaleX, delta)
        assertEquals(1.0f, scaleY, delta)
    }

    @Test
    fun testCalculateEffectiveScale_scalingModes() {
        val scaleRatioX = 0.5f
        val scaleRatioY = 1.0f
        val zoom = 1.2f

        // FIT mode: min(0.5, 1.0) * 1.2 = 0.6
        val (fitX, fitY) = calculateEffectiveScale(ScalingMode.FIT, scaleRatioX, scaleRatioY, zoom)
        assertEquals(0.6f, fitX, delta)
        assertEquals(0.6f, fitY, delta)

        // FILL mode: max(0.5, 1.0) * 1.2 = 1.2
        val (fillX, fillY) = calculateEffectiveScale(ScalingMode.FILL, scaleRatioX, scaleRatioY, zoom)
        assertEquals(1.2f, fillX, delta)
        assertEquals(1.2f, fillY, delta)

        // STRETCH mode: 0.5 * 1.2 = 0.6, 1.0 * 1.2 = 1.2
        val (stretchX, stretchY) = calculateEffectiveScale(ScalingMode.STRETCH, scaleRatioX, scaleRatioY, zoom)
        assertEquals(0.6f, stretchX, delta)
        assertEquals(1.2f, stretchY, delta)
    }

    @Test
    fun testCalculateTranslation_canvasConvention() {
        val viewW = 200f
        val viewH = 400f

        // Center position
        val (transCenterX, transCenterY) = calculateTranslation(0f, 0f, viewW, viewH)
        assertEquals(0f, transCenterX, delta)
        assertEquals(0f, transCenterY, delta)

        // Offset position: X right (+0.5), Y up (+0.5 in GL -> down in Canvas = negative)
        val (transX, transY) = calculateTranslation(0.5f, 0.5f, viewW, viewH)
        assertEquals(50f, transX, delta)   // 0.5 * (200 / 2)
        assertEquals(-100f, transY, delta) // -0.5 * (400 / 2)
    }
}
