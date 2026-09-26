package org.maocide.undeadwallpaper.utils

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SafeKeyDebouncerTest {

    private var currentTime = 1000L
    private lateinit var debouncer: SafeKeyDebouncer

    @Before
    fun setUp() {
        currentTime = 1000L
        debouncer = SafeKeyDebouncer(debounceMs = 300L) { currentTime }
    }

    @Test
    fun `first action key press passes through`() {
        // ACTION_DOWN
        val dropDown = debouncer.shouldDropKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)
        assertFalse("First ACTION_DOWN should not be dropped", dropDown)

        currentTime += 50L
        // ACTION_UP
        val dropUp = debouncer.shouldDropKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0)
        assertFalse("First ACTION_UP should not be dropped", dropUp)
    }

    @Test
    fun `rapid action key press within debounce threshold is dropped`() {
        // First click at t=1000
        debouncer.shouldDropKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0)
        debouncer.shouldDropKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0)

        // Rapid spam at t=1100 (100ms later < 300ms debounce)
        currentTime = 1100L
        val dropDown = debouncer.shouldDropKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0)
        assertTrue("Rapid ACTION_DOWN must be dropped", dropDown)

        currentTime = 1150L
        val dropUp = debouncer.shouldDropKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0)
        assertTrue("Subsequent ACTION_UP for dropped key must also be dropped", dropUp)
    }

    @Test
    fun `action key press after debounce threshold passes through`() {
        // First click at t=1000
        debouncer.shouldDropKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0)
        debouncer.shouldDropKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0)

        // Allowed press at t=1350 (350ms later >= 300ms debounce)
        currentTime = 1350L
        val dropDown = debouncer.shouldDropKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0)
        assertFalse("ACTION_DOWN after debounce window should pass", dropDown)

        currentTime = 1400L
        val dropUp = debouncer.shouldDropKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0)
        assertFalse("ACTION_UP after debounce window should pass", dropUp)
    }

    @Test
    fun `auto-repeated action keys are dropped`() {
        // Held key creates repeatCount > 0
        val dropDown = debouncer.shouldDropKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, repeatCount = 1)
        assertTrue("Auto-repeated key event (repeatCount > 0) must be dropped", dropDown)

        val dropUp = debouncer.shouldDropKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0)
        assertTrue("ACTION_UP corresponding to dropped repeat must be dropped", dropUp)
    }

    @Test
    fun `non-action keys are never throttled`() {
        // Normal typing or volume keys should pass unthrottled
        val dropA = debouncer.shouldDropKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0)
        assertFalse("Standard letter keys should never be dropped", dropA)

        val dropVol = debouncer.shouldDropKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, 0)
        assertFalse("Volume keys should not be throttled by key debouncer", dropVol)
    }

    @Test
    fun `reset clears previous timestamps`() {
        debouncer.shouldDropKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)
        debouncer.shouldDropKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0)

        // Reset debouncer (e.g. on activity pause/resume)
        debouncer.reset()

        // Immediate next press at t=1010
        currentTime = 1010L
        val dropDown = debouncer.shouldDropKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)
        assertFalse("After reset, immediate key press should pass", dropDown)
    }
}
