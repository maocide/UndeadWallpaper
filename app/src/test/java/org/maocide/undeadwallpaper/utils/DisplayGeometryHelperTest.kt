package org.maocide.undeadwallpaper.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayGeometryHelperTest {

    private val delta = 0.0001f

    @Test
    fun testEffectiveAspectRatio_fallbackWhenNoRawDimensions_portrait() {
        val ratio = DisplayGeometryHelper.getEffectiveAspectRatio(
            metricsWidth = 1080,
            metricsHeight = 2400,
            rawLongSide = 0,
            rawShortSide = 0
        )
        assertEquals(1080f / 2400f, ratio, delta)
    }

    @Test
    fun testEffectiveAspectRatio_fallbackWhenNoRawDimensions_landscape() {
        val ratio = DisplayGeometryHelper.getEffectiveAspectRatio(
            metricsWidth = 2400,
            metricsHeight = 1080,
            rawLongSide = 0,
            rawShortSide = 0
        )
        assertEquals(2400f / 1080f, ratio, delta)
    }

    @Test
    fun testEffectiveAspectRatio_reconcilesKindleFireNavbarInsets_portrait() {
        // Amazon Kindle Fire 800x1280 physical screen
        // Window manager insets bottom bar by 72px -> metrics = 800x1208
        // Raw service surface recorded ground-truth = 1280 long, 800 short
        val ratio = DisplayGeometryHelper.getEffectiveAspectRatio(
            metricsWidth = 800,
            metricsHeight = 1208,
            rawLongSide = 1280,
            rawShortSide = 800
        )

        // Must match ground-truth physical aspect ratio (800 / 1280 = 0.625)
        assertEquals(800f / 1280f, ratio, delta)
    }

    @Test
    fun testEffectiveAspectRatio_reconcilesKindleFireNavbarInsets_landscape() {
        // Amazon Kindle Fire rotated to landscape: physical 1280x800
        // Window manager insets bar -> metrics = 1208x800
        val ratio = DisplayGeometryHelper.getEffectiveAspectRatio(
            metricsWidth = 1208,
            metricsHeight = 800,
            rawLongSide = 1280,
            rawShortSide = 800
        )

        // Must match ground-truth physical aspect ratio (1280 / 800 = 1.6)
        assertEquals(1280f / 800f, ratio, delta)
    }

    @Test
    fun testEffectiveAspectRatio_rotationInvariant_serviceStartedPortrait_sheetOpenedLandscape() {
        // Service initialized when device was portrait (1080x2400)
        // rawLongSide = 2400, rawShortSide = 1080
        // User rotates phone to landscape and opens settings sheet: metrics = 2400x1080
        val ratio = DisplayGeometryHelper.getEffectiveAspectRatio(
            metricsWidth = 2400,
            metricsHeight = 1080,
            rawLongSide = 2400,
            rawShortSide = 1080
        )

        assertEquals(2400f / 1080f, ratio, delta)
    }

    @Test
    fun testEffectiveAspectRatio_rotationInvariant_serviceStartedLandscape_sheetOpenedPortrait() {
        // Service initialized in landscape (2400x1080)
        // rawLongSide = 2400, rawShortSide = 1080
        // User rotates phone to portrait and opens settings sheet: metrics = 1080x2400
        val ratio = DisplayGeometryHelper.getEffectiveAspectRatio(
            metricsWidth = 1080,
            metricsHeight = 2400,
            rawLongSide = 2400,
            rawShortSide = 1080
        )

        assertEquals(1080f / 2400f, ratio, delta)
    }

    @Test
    fun testEffectiveScreenDimensions_portraitAndLandscape() {
        val (portraitW, portraitH) = DisplayGeometryHelper.getEffectiveScreenDimensions(
            metricsWidth = 800,
            metricsHeight = 1208,
            rawLongSide = 1280,
            rawShortSide = 800
        )
        assertEquals(800, portraitW)
        assertEquals(1280, portraitH)

        val (landscapeW, landscapeH) = DisplayGeometryHelper.getEffectiveScreenDimensions(
            metricsWidth = 1208,
            metricsHeight = 800,
            rawLongSide = 1280,
            rawShortSide = 800
        )
        assertEquals(1280, landscapeW)
        assertEquals(800, landscapeH)
    }

    @Test
    fun testHandlesZeroOrNegativeInputsSafely() {
        val ratioZero = DisplayGeometryHelper.getEffectiveAspectRatio(0, 0, 0, 0)
        assertEquals(1f, ratioZero, delta)

        val ratioNegative = DisplayGeometryHelper.getEffectiveAspectRatio(-100, 200, 0, 0)
        assertEquals(1f, ratioNegative, delta)

        val (dimW, dimH) = DisplayGeometryHelper.getEffectiveScreenDimensions(0, 0, 0, 0)
        assertEquals(0, dimW)
        assertEquals(0, dimH)
    }
}
