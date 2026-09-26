package org.maocide.undeadwallpaper.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSettingsTest {

    @Test
    fun `hasSameVisualTransformsAs returns true for same aspect ratio and matching transforms`() {
        val abby = VideoSettings(
            fileName = "abby.mp4",
            width = 768,
            height = 1152,
            zoom = 1.0f,
            scalingMode = ScalingMode.FILL
        )
        val sybil = VideoSettings(
            fileName = "sybil.mp4",
            width = 1600,
            height = 2400,
            zoom = 1.0f,
            scalingMode = ScalingMode.FILL
        )

        assertTrue(abby.hasSameVisualTransformsAs(sybil))
    }

    @Test
    fun `hasSameVisualTransformsAs returns false when aspect ratios differ`() {
        val abby = VideoSettings(
            fileName = "abby.mp4",
            width = 768,
            height = 1152,
            zoom = 1.0f,
            scalingMode = ScalingMode.FILL
        )
        val tracer = VideoSettings(
            fileName = "tracer.mp4",
            width = 1332,
            height = 2400,
            zoom = 1.0f,
            scalingMode = ScalingMode.FILL
        )

        assertFalse(abby.hasSameVisualTransformsAs(tracer))
    }

    @Test
    fun `hasSameVisualTransformsAs returns false across landscape and portrait orientations`() {
        val portrait = VideoSettings(
            fileName = "portrait.mp4",
            width = 1080,
            height = 1920,
            zoom = 1.0f,
            scalingMode = ScalingMode.FILL
        )
        val landscape = VideoSettings(
            fileName = "landscape.mp4",
            width = 1920,
            height = 1080,
            zoom = 1.0f,
            scalingMode = ScalingMode.FILL
        )

        assertFalse(portrait.hasSameVisualTransformsAs(landscape))
    }

    @Test
    fun `hasSameVisualTransformsAs returns false when visual sliders differ`() {
        val base = VideoSettings(
            fileName = "base.mp4",
            width = 1080,
            height = 1920,
            zoom = 1.0f
        )
        val zoomed = base.copy(zoom = 1.4f)
        val rotated = base.copy(rotation = 90f)
        val pannedX = base.copy(positionX = 0.5f)
        val pannedY = base.copy(positionY = -0.2f)
        val flippedH = base.copy(flipHorizontal = true)
        val flippedV = base.copy(flipVertical = true)
        val dimmed = base.copy(brightness = 0.8f)
        val fitMode = base.copy(scalingMode = ScalingMode.FIT)

        assertFalse(base.hasSameVisualTransformsAs(zoomed))
        assertFalse(base.hasSameVisualTransformsAs(rotated))
        assertFalse(base.hasSameVisualTransformsAs(pannedX))
        assertFalse(base.hasSameVisualTransformsAs(pannedY))
        assertFalse(base.hasSameVisualTransformsAs(flippedH))
        assertFalse(base.hasSameVisualTransformsAs(flippedV))
        assertFalse(base.hasSameVisualTransformsAs(dimmed))
        assertFalse(base.hasSameVisualTransformsAs(fitMode))
    }
}
