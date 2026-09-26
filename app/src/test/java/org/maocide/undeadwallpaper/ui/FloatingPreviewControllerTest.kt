package org.maocide.undeadwallpaper.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.maocide.undeadwallpaper.ui.FloatingPreviewController.Companion.calculateButtonPickAlpha
import org.maocide.undeadwallpaper.ui.FloatingPreviewController.Companion.calculateDismissBadgeAlpha
import org.maocide.undeadwallpaper.ui.FloatingPreviewController.Companion.calculateElevationZ
import org.maocide.undeadwallpaper.ui.FloatingPreviewController.Companion.calculateGlideX
import org.maocide.undeadwallpaper.ui.FloatingPreviewController.Companion.calculateProgress
import org.maocide.undeadwallpaper.ui.FloatingPreviewController.Companion.calculateScale

class FloatingPreviewControllerTest {

    private val delta = 0.001f

    @Test
    fun testCalculateProgress_clampsCorrectly() {
        // scrollY = 0 (Resting at top)
        assertEquals(0f, calculateProgress(0, 180f), delta)
        
        // scrollY = 90 (Halfway collapsed)
        assertEquals(0.5f, calculateProgress(90, 180f), delta)
        
        // scrollY = 180 (Fully collapsed)
        assertEquals(1f, calculateProgress(180, 180f), delta)
        
        // scrollY = 300 (Scrolled way past collapse point)
        assertEquals(1f, calculateProgress(300, 180f), delta)
        
        // Negative scroll (Overscroll top)
        assertEquals(0f, calculateProgress(-50, 180f), delta)
    }

    @Test
    fun testCalculateScale_scalesLinearlyToMin60Percent() {
        assertEquals(1.0f, calculateScale(0.0f), delta) // Progress 0% -> Scale 100%
        assertEquals(0.8f, calculateScale(0.5f), delta) // Progress 50% -> Scale 80%
        assertEquals(0.6f, calculateScale(1.0f), delta) // Progress 100% -> Scale 60%
    }

    @Test
    fun testCalculateGlideX_glidesProportionally() {
        val glideDistancePx = 36f // e.g. 12dp * 3.0 density
        assertEquals(0f, calculateGlideX(0.0f, glideDistancePx), delta)
        assertEquals(18f, calculateGlideX(0.5f, glideDistancePx), delta)
        assertEquals(36f, calculateGlideX(1.0f, glideDistancePx), delta)
    }

    @Test
    fun testCalculateElevationZ_scalesWithDensity() {
        val density = 2.5f
        // Max elevation is 24dp * density
        val maxElevation = 24f * density
        
        assertEquals(0f, calculateElevationZ(0.0f, density), delta)
        assertEquals(maxElevation / 2f, calculateElevationZ(0.5f, density), delta)
        assertEquals(maxElevation, calculateElevationZ(1.0f, density), delta)
    }

    @Test
    fun testCalculateButtonPickAlpha_fadesOutQuickly() {
        // (1f - progress * 2.5f).coerceIn(0f, 1f)
        assertEquals(1.0f, calculateButtonPickAlpha(0.0f), delta)
        assertEquals(0.75f, calculateButtonPickAlpha(0.1f), delta) // 1 - 0.25
        
        // Faded out completely by progress 0.4
        assertEquals(0.0f, calculateButtonPickAlpha(0.4f), delta)
        
        // Remains 0 after progress 0.4
        assertEquals(0.0f, calculateButtonPickAlpha(1.0f), delta)
    }

    @Test
    fun testCalculateDismissBadgeAlpha_fadesInBetween30And70Percent() {
        // ((progress - 0.3f) / 0.4f).coerceIn(0f, 1f)
        
        // Before 30% progress, alpha is 0
        assertEquals(0.0f, calculateDismissBadgeAlpha(0.0f), delta)
        assertEquals(0.0f, calculateDismissBadgeAlpha(0.2f), delta)
        
        // At exactly 30% progress, alpha starts at 0
        assertEquals(0.0f, calculateDismissBadgeAlpha(0.3f), delta)
        
        // At 50% progress (halfway between 30% and 70%), alpha is 50%
        assertEquals(0.5f, calculateDismissBadgeAlpha(0.5f), delta)
        
        // At exactly 70% progress, alpha reaches 100%
        assertEquals(1.0f, calculateDismissBadgeAlpha(0.7f), delta)
        
        // After 70% progress, alpha remains 100%
        assertEquals(1.0f, calculateDismissBadgeAlpha(1.0f), delta)
    }
}
