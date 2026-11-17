package org.maocide.undeadwallpaper.ui

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccordionControllerTest {

    @Test
    fun `resolveExpandedState returns defaultValue when savedValue is null`() {
        assertTrue(
            AccordionController.resolveExpandedState(
                savedValue = null,
                defaultValue = true
            )
        )
        assertFalse(
            AccordionController.resolveExpandedState(
                savedValue = null,
                defaultValue = false
            )
        )
    }

    @Test
    fun `resolveExpandedState returns savedValue when present, ignoring default`() {
        assertTrue(
            AccordionController.resolveExpandedState(
                savedValue = true,
                defaultValue = false
            )
        )
        assertFalse(
            AccordionController.resolveExpandedState(
                savedValue = false,
                defaultValue = true
            )
        )
    }

    @Test
    fun `bundle keys are unique and distinct`() {
        val keys = setOf(
            AccordionController.KEY_ACCORDION_GLOBAL,
            AccordionController.KEY_ACCORDION_UNDEAD_AFFECTS,
            AccordionController.KEY_ACCORDION_PARALLAX
        )
        assertEquals(3, keys.size)
    }

    @Test
    fun `accordion types cover all three sections and have distinct seenEpochKeys`() {
        val types = AccordionType.entries
        assertEquals(3, types.size)
        assertTrue(types.contains(AccordionType.GLOBAL_SETTINGS))
        assertTrue(types.contains(AccordionType.UNDEAD_AFFECTS))
        assertTrue(types.contains(AccordionType.PARALLAX))

        val epochKeys = types.map { it.seenEpochKey }.toSet()
        assertEquals(3, epochKeys.size)
    }

    @Test
    fun `shouldShowNewBadge evaluates seenEpoch against featureEpoch`() {
        assertFalse(AccordionController.shouldShowNewBadge(seenEpoch = 0, featureEpoch = 0))
        assertTrue(AccordionController.shouldShowNewBadge(seenEpoch = 0, featureEpoch = 1))
        assertFalse(AccordionController.shouldShowNewBadge(seenEpoch = 1, featureEpoch = 1))
        assertTrue(AccordionController.shouldShowNewBadge(seenEpoch = 1, featureEpoch = 2))
    }

    @Test
    fun `computeRandomStampRotation stays within tilted bounds and never flat`() {
        val rng = Random(42)
        var sawPositive = false
        var sawNegative = false
        repeat(100) {
            val angle = AccordionController.computeRandomStampRotation(rng)
            val magnitude = abs(angle)
            assertTrue(
                "Expected magnitude in 4.5..10.5, got $magnitude",
                magnitude in AccordionController.STAMP_MIN_ANGLE_DEG..AccordionController.STAMP_MAX_ANGLE_DEG
            )
            if (angle > 0f) sawPositive = true
            if (angle < 0f) sawNegative = true
        }
        assertTrue(sawPositive && sawNegative)
    }
}
