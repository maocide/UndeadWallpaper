package org.maocide.undeadwallpaper.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maocide.undeadwallpaper.service.PreviewBadgeOverlay.Companion.calculateBadgeBounds

class PreviewBadgeOverlayTest {

    private val delta = 0.001f

    @Test
    fun testCalculateBadgeBounds_slotTop_standardPortrait() {
        val viewportWidth = 1080
        val viewportHeight = 2400
        val density = 2.625f

        val bounds = calculateBadgeBounds(
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            density = density,
            slotTop = true,
            jitterX = 0f,
            jitterY = 0f
        )

        // Expected dimensions: 340dp * 2.625 = 892.5px (within 90% of 1080 = 972)
        assertEquals(892.5f, bounds.width, delta)
        // 68dp * 2.625 = 178.5px (within 15% of 2400 = 360)
        assertEquals(178.5f, bounds.height, delta)

        // Horizontally centered: (1080 - 892.5) / 2 = 93.75px
        assertEquals(93.75f, bounds.left, delta)
        assertEquals(93.75f + 892.5f, bounds.right, delta)

        // Upper safe zone: max(2400 * 0.135 = 324, 115 * 2.625 = 301.875) = 324px
        assertEquals(324f, bounds.top, delta)
        assertEquals(324f + 178.5f, bounds.bottom, delta)
    }

    @Test
    fun testCalculateBadgeBounds_slotBottom_standardPortrait() {
        val viewportWidth = 1080
        val viewportHeight = 2400
        val density = 2.625f

        val bounds = calculateBadgeBounds(
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            density = density,
            slotTop = false,
            jitterX = 0f,
            jitterY = 0f
        )

        // Lower-third safe zone: 2400 * 0.705 = 1692px
        assertEquals(1692f, bounds.top, delta)
        assertEquals(1692f + 178.5f, bounds.bottom, delta)
    }

    @Test
    fun testCalculateBadgeBounds_clampsToMaxViewportDimensions() {
        // Very small viewport (e.g. 200x200) where 340dp exceeds width
        val viewportWidth = 200
        val viewportHeight = 300
        val density = 2.0f

        val bounds = calculateBadgeBounds(
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            density = density,
            slotTop = true
        )

        // Clamped to 90% of viewportWidth: 200 * 0.90 = 180px
        assertEquals(180f, bounds.width, delta)
        // Clamped to 15% of viewportHeight: 300 * 0.15 = 45px
        assertEquals(45f, bounds.height, delta)
        assertTrue(bounds.right <= viewportWidth)
    }

    @Test
    fun testCalculateBadgeBounds_appliesJitter() {
        val viewportWidth = 1080
        val viewportHeight = 2400
        val density = 2.625f
        val jitterX = 12f
        val jitterY = -8f

        val baseBounds = calculateBadgeBounds(
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            density = density,
            slotTop = true,
            jitterX = 0f,
            jitterY = 0f
        )

        val jitteredBounds = calculateBadgeBounds(
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            density = density,
            slotTop = true,
            jitterX = jitterX,
            jitterY = jitterY
        )

        assertEquals(baseBounds.left + jitterX, jitteredBounds.left, delta)
        assertEquals(baseBounds.top + jitterY, jitteredBounds.top, delta)
    }
}
