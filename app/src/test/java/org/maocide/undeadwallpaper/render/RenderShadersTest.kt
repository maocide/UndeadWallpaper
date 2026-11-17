package org.maocide.undeadwallpaper.render

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RenderShadersTest {

    @Test
    fun testCreateColorFilter_identityReturnsNull() {
        // Identity values (1.0, 1.0, 1.0) must bypass allocations and return null
        val filter = RenderShaders.createColorFilter(
            brightness = 1.0f,
            contrast = 1.0f,
            saturation = 1.0f
        )
        assertNull(filter)
    }

    @Test
    fun testCreateColorFilter_withinToleranceReturnsNull() {
        // Values within 0.001f of 1.0f must also return null
        val filter = RenderShaders.createColorFilter(
            brightness = 1.0005f,
            contrast = 0.9995f,
            saturation = 1.0f
        )
        assertNull(filter)
    }
}

