package org.maocide.undeadwallpaper.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.maocide.undeadwallpaper.model.RenderTransforms
import org.maocide.undeadwallpaper.model.ScalingMode

class RenderGeometryCalculatorTest {

    private val delta = 0.001f

    @Test
    fun testCalculateRotatedBoundingBox_orthogonalAngles() {
        val width = 1080f
        val height = 1920f

        // 0 degrees: rotW = width, rotH = height
        val (w0, h0) = RenderGeometryCalculator.calculateRotatedBoundingBox(width, height, 0f)
        assertEquals(width, w0, delta)
        assertEquals(height, h0, delta)

        // 90 degrees: rotW = height, rotH = width
        val (w90, h90) = RenderGeometryCalculator.calculateRotatedBoundingBox(width, height, 90f)
        assertEquals(height, w90, delta)
        assertEquals(width, h90, delta)

        // 180 degrees: rotW = width, rotH = height
        val (w180, h180) = RenderGeometryCalculator.calculateRotatedBoundingBox(width, height, 180f)
        assertEquals(width, w180, delta)
        assertEquals(height, h180, delta)

        // 270 degrees: rotW = height, rotH = width
        val (w270, h270) = RenderGeometryCalculator.calculateRotatedBoundingBox(width, height, 270f)
        assertEquals(height, w270, delta)
        assertEquals(width, h270, delta)
    }

    @Test
    fun testCalculateRotatedBoundingBox_invalidDimensions() {
        val (wZero, hZero) = RenderGeometryCalculator.calculateRotatedBoundingBox(0f, 0f, 45f)
        assertEquals(0f, wZero, delta)
        assertEquals(0f, hZero, delta)

        val (wNeg, hNeg) = RenderGeometryCalculator.calculateRotatedBoundingBox(-100f, 100f, 45f)
        assertEquals(0f, wNeg, delta)
        assertEquals(0f, hNeg, delta)
    }

    @Test
    fun testCalculateScaleRatios_standard() {
        val (scaleX, scaleY) = RenderGeometryCalculator.calculateScaleRatios(
            sourceWidth = 500f,
            sourceHeight = 1000f,
            targetWidth = 1000f,
            targetHeight = 2000f,
            rotationDegrees = 0f
        )
        assertEquals(2.0f, scaleX, delta)
        assertEquals(2.0f, scaleY, delta)
    }

    @Test
    fun testCalculateScaleRatios_with90DegreeRotation() {
        // 400x200 rotated 90 deg becomes 200x400
        val (scaleX, scaleY) = RenderGeometryCalculator.calculateScaleRatios(
            sourceWidth = 400f,
            sourceHeight = 200f,
            targetWidth = 200f,
            targetHeight = 400f,
            rotationDegrees = 90f
        )
        assertEquals(1.0f, scaleX, delta)
        assertEquals(1.0f, scaleY, delta)
    }

    @Test
    fun testCalculateEffectiveScale_scalingModes() {
        val scaleRatioX = 0.5f
        val scaleRatioY = 1.0f
        val zoom = 1.5f

        // FIT mode: min(0.5, 1.0) * 1.5 = 0.75
        val (fitX, fitY) = RenderGeometryCalculator.calculateEffectiveScale(ScalingMode.FIT, scaleRatioX, scaleRatioY, zoom)
        assertEquals(0.75f, fitX, delta)
        assertEquals(0.75f, fitY, delta)

        // FILL mode: max(0.5, 1.0) * 1.5 = 1.5
        val (fillX, fillY) = RenderGeometryCalculator.calculateEffectiveScale(ScalingMode.FILL, scaleRatioX, scaleRatioY, zoom)
        assertEquals(1.5f, fillX, delta)
        assertEquals(1.5f, fillY, delta)

        // STRETCH mode: (0.5 * 1.5, 1.0 * 1.5) = (0.75, 1.5)
        val (stretchX, stretchY) = RenderGeometryCalculator.calculateEffectiveScale(ScalingMode.STRETCH, scaleRatioX, scaleRatioY, zoom)
        assertEquals(0.75f, stretchX, delta)
        assertEquals(1.5f, stretchY, delta)
    }

    @Test
    fun testCalculateParallaxTranslation() {
        val screenWidth = 1080f
        val currentWidthPx = 1920f
        val globalScaleX = 1.0f

        // Hidden width = 1920 - 1080 = 840px
        // Parallax center (0.0): offset = 0
        val offsetCenter = RenderGeometryCalculator.calculateParallaxTranslation(0f, currentWidthPx, globalScaleX, screenWidth)
        assertEquals(0f, offsetCenter, delta)

        // Parallax scrolled to right edge (+0.5): offset = 0.5 * 840 = 420px
        val offsetRight = RenderGeometryCalculator.calculateParallaxTranslation(0.5f, currentWidthPx, globalScaleX, screenWidth)
        assertEquals(420f, offsetRight, delta)

        // When video is narrower than screen (hidden width = 0), offset is always 0
        val offsetNarrow = RenderGeometryCalculator.calculateParallaxTranslation(0.5f, 500f, 1.0f, screenWidth)
        assertEquals(0f, offsetNarrow, delta)
    }

    @Test
    fun testCalculateTranslation_canvasConventionAndSecurityTripwire() {
        val targetWidth = 1000f
        val targetHeight = 2000f

        // Normal calibrated state (hardwareCalibrationOffset = 0f)
        val (transX, transY) = RenderGeometryCalculator.calculateTranslation(
            positionX = 0.5f,
            positionY = 0.5f,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            hardwareCalibrationOffset = 0f
        )
        // positionX * (1000 / 2) = 250px
        assertEquals(250f, transX, delta)
        // -positionY * (2000 / 2) = -500px (Canvas Y inverted from GL)
        assertEquals(-500f, transY, delta)

        // SECURITY AUDIT: Tampered signature trigger (+20000f offset)
        // Must translate X exactly 20,000 pixels off-screen (Fail-Closed)
        val (transXTamper, transYTamper) = RenderGeometryCalculator.calculateTranslation(
            positionX = 0f,
            positionY = 0f,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            hardwareCalibrationOffset = 20000f
        )
        assertEquals(20000f, transXTamper, delta)
        assertEquals(0f, transYTamper, delta)
    }
}

