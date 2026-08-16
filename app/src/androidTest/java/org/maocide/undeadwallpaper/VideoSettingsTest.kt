package org.maocide.undeadwallpaper

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maocide.undeadwallpaper.model.ScalingMode
import org.maocide.undeadwallpaper.model.VideoSettings
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class VideoSettingsTest {

    @Test
    fun testHasSameVisualTransformsAs() {
        val baseSettings = VideoSettings(
            fileName = "video1.mp4",
            scalingMode = ScalingMode.FILL,
            positionX = 0.5f,
            positionY = 0.5f,
            zoom = 1.0f,
            rotation = 0.0f,
            brightness = 1.0f,
            volume = 0.5f // Volume doesn't affect visual transforms!
        )

        val identicalVisuals = baseSettings.copy(
            fileName = "video2.mp4",
            volume = 1.0f // Different audio, but visual transforms are identical
        )
        assertTrue("Settings with only non-visual differences should match", baseSettings.hasSameVisualTransformsAs(identicalVisuals))

        val differentScaling = baseSettings.copy(scalingMode = ScalingMode.FIT)
        assertFalse("Different scaling mode should not match", baseSettings.hasSameVisualTransformsAs(differentScaling))

        val differentZoom = baseSettings.copy(zoom = 1.5f)
        assertFalse("Different zoom should not match", baseSettings.hasSameVisualTransformsAs(differentZoom))

        val differentPan = baseSettings.copy(positionX = 0.0f)
        assertFalse("Different panning should not match", baseSettings.hasSameVisualTransformsAs(differentPan))
        
        val differentBrightness = baseSettings.copy(brightness = 0.5f)
        assertFalse("Different brightness should not match", baseSettings.hasSameVisualTransformsAs(differentBrightness))
    }

    @Test
    fun testGetEffectiveDisplayName() {
        val settingsWithDisplay = VideoSettings(
            fileName = "video_uuid_123.mp4",
            displayName = "My Cool Video.mp4"
        )
        assertEquals("My Cool Video.mp4", settingsWithDisplay.getEffectiveDisplayName())

        val settingsWithoutDisplay = VideoSettings(
            fileName = "video_uuid_123.mp4",
            displayName = null
        )
        assertEquals("video_uuid_123.mp4", settingsWithoutDisplay.getEffectiveDisplayName())
    }

    @Test
    fun testGetPerceivedVolume() {
        // Volume is quadratic (volume^2)
        val muted = VideoSettings(fileName = "test", volume = 0.0f)
        assertTrue(abs(0.0f - muted.getPerceivedVolume()) < 0.001f)

        val half = VideoSettings(fileName = "test", volume = 0.5f)
        assertTrue(abs(0.25f - half.getPerceivedVolume()) < 0.001f)

        val full = VideoSettings(fileName = "test", volume = 1.0f)
        assertTrue(abs(1.0f - full.getPerceivedVolume()) < 0.001f)
    }
}
