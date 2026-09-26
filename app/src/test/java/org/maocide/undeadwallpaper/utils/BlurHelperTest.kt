package org.maocide.undeadwallpaper.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class BlurHelperTest {

    @Test
    fun `portrait downscale maintains aspect ratio and clamps width to maxDimension`() {
        val (w, h) = BlurHelper.calculateDownscaledDimensions(1080, 1920, 320)
        assertEquals(320, w)
        assertEquals(568, h)
    }

    @Test
    fun `landscape downscale maintains aspect ratio and clamps width to maxDimension`() {
        val (w, h) = BlurHelper.calculateDownscaledDimensions(1920, 1080, 320)
        assertEquals(320, w)
        assertEquals(180, h)
    }

    @Test
    fun `square downscale reduces to maxDimension square`() {
        val (w, h) = BlurHelper.calculateDownscaledDimensions(1000, 1000, 320)
        assertEquals(320, w)
        assertEquals(320, h)
    }

    @Test
    fun `smaller dimensions than maxDimension remain unchanged without upscaling`() {
        val (w, h) = BlurHelper.calculateDownscaledDimensions(200, 400, 320)
        assertEquals(200, w)
        assertEquals(400, h)
    }

    @Test
    fun `dimension matching maxDimension remains unchanged`() {
        val (w, h) = BlurHelper.calculateDownscaledDimensions(320, 480, 320)
        assertEquals(320, w)
        assertEquals(480, h)
    }

    @Test
    fun `extreme aspect ratio coercing height to at least 1`() {
        val (w, h) = BlurHelper.calculateDownscaledDimensions(1000, 1, 320)
        assertEquals(320, w)
        assertEquals(1, h)
    }

    @Test
    fun `invalid or zero dimensions return zero pair`() {
        val zero = BlurHelper.calculateDownscaledDimensions(0, 0, 320)
        assertEquals(0, zero.first)
        assertEquals(0, zero.second)

        val negative = BlurHelper.calculateDownscaledDimensions(-100, 500, 320)
        assertEquals(0, negative.first)
        assertEquals(0, negative.second)
    }

    @Test
    fun `custom maxDimension is respected`() {
        val (w, h) = BlurHelper.calculateDownscaledDimensions(1920, 1080, 480)
        assertEquals(480, w)
        assertEquals(270, h)
    }
}
