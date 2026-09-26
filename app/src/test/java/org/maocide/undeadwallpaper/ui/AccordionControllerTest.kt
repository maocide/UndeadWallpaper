package org.maocide.undeadwallpaper.ui

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
            AccordionController.KEY_ACCORDION_TOUCH,
            AccordionController.KEY_ACCORDION_PARALLAX
        )
        assertEquals(3, keys.size)
    }

    @Test
    fun `accordion types cover all three sections`() {
        val types = AccordionType.values()
        assertEquals(3, types.size)
        assertTrue(types.contains(AccordionType.GLOBAL_SETTINGS))
        assertTrue(types.contains(AccordionType.TOUCH_CONTROLS))
        assertTrue(types.contains(AccordionType.PARALLAX))
    }
}
