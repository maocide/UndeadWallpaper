package org.maocide.undeadwallpaper.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * Replaces standard click listeners with a debounced click listener that prevents double inputs
 * and validates touch events against window focus and system overlays.
 */
@SuppressLint("ClickableViewAccessibility")
fun View.setSafeOnClickListener(
    debounceMs: Long = 500L,
    minTapDurationMs: Long = 10L,
    action: (View) -> Unit
) {
    this.filterTouchesWhenObscured = true

    var downTime = 0L
    var wasPartiallyObscured = false
    var lastClickTime = 0L

    this.setOnTouchListener { v, event ->
        val flags = event.flags
        val activity = v.context as? Activity

        if ((flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0) {
            return@setOnTouchListener true
        }

        val isPartiallyObscured = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0
        } else {
            false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = SystemClock.uptimeMillis()
                wasPartiallyObscured = isPartiallyObscured
                false
            }

            MotionEvent.ACTION_UP -> {
                val tapDuration = SystemClock.uptimeMillis() - downTime
                val hasFocus = activity?.hasWindowFocus() ?: true
                val requiredDuration = if (isPartiallyObscured || wasPartiallyObscured) 20L else minTapDurationMs

                if (tapDuration < requiredDuration || !hasFocus) {
                    true // Consume event silently, blocking the click
                } else {
                    false
                }
            }

            else -> false
        }
    }

    this.setOnClickListener { v ->
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickTime >= debounceMs) {
            lastClickTime = now
            action(v)
        }
    }
}

/**
 * Applies touch debouncing and overlay validation to a View WITHOUT
 * overriding its click listeners.
 * Useful for Switches, Checkboxes, Sliders, and RadioButtons where you want to use
 * setOnCheckedChangeListener with debounced touch handling.
 */
fun View.preventDoubleInput(minTapDurationMs: Long = 10L) {
    this.filterTouchesWhenObscured = true

    var downTime = 0L
    var wasPartiallyObscured = false

    this.setOnTouchListener { v, event ->
        val flags = event.flags
        val activity = v.context as? Activity

        if ((flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0) {
            return@setOnTouchListener true
        }

        val isPartiallyObscured = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0
        } else {
            false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = SystemClock.uptimeMillis()
                wasPartiallyObscured = isPartiallyObscured
                false
            }

            MotionEvent.ACTION_UP -> {
                val tapDuration = SystemClock.uptimeMillis() - downTime
                val hasFocus = activity?.hasWindowFocus() ?: true
                val requiredDuration = if (isPartiallyObscured || wasPartiallyObscured) 20L else minTapDurationMs

                if (tapDuration < requiredDuration || !hasFocus) {
                    true // Consume event silently, blocking the interaction
                } else {
                    false
                }
            }

            else -> false
        }
    }
}

/**
 * Debouncer for hardware and virtual key events (e.g. Back, D-Pad Center, Enter, Space, Media buttons).
 * Protects against rapid auto-repeats.
 *
 * @param debounceMs Minimum time in milliseconds between successive key triggers of the same key code.
 * @param timeProvider Clock source providing monotonic milliseconds (defaults to SystemClock.elapsedRealtime).
 */
class SafeKeyDebouncer(
    private val debounceMs: Long = 300L,
    private val timeProvider: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private val lastDownTimes = mutableMapOf<Int, Long>()
    private val droppedDownKeys = mutableSetOf<Int>()

    /**
     * Determines whether a key event should be throttled or dropped based on action, keycode, and repeat count.
     * @return true if the event was consumed and should NOT be dispatched further, false to let it pass.
     */
    fun shouldDropKey(action: Int, keyCode: Int, repeatCount: Int = 0): Boolean {
        val isThrottledKey = when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_HEADSETHOOK -> true

            else -> false
        }

        if (!isThrottledKey) {
            return false
        }

        val now = timeProvider()

        when (action) {
            KeyEvent.ACTION_DOWN -> {
                // Drop synthetic auto-repeats caused by holding action keys down
                if (repeatCount > 0) {
                    droppedDownKeys.add(keyCode)
                    return true
                }

                val lastTime = lastDownTimes[keyCode] ?: 0L
                if (now - lastTime < debounceMs) {
                    // Flooding detected! Drop it
                    droppedDownKeys.add(keyCode)
                    return true
                }

                lastDownTimes[keyCode] = now
                droppedDownKeys.remove(keyCode)
                return false
            }

            KeyEvent.ACTION_UP -> {
                if (droppedDownKeys.remove(keyCode)) {
                    // Corresponding ACTION_DOWN was dropped, consume ACTION_UP to avoid firing on release
                    return true
                }
                return false
            }

            else -> return false
        }
    }

    /**
     * Convenience wrapper around [shouldDropKey] for [KeyEvent].
     */
    fun shouldDropKeyEvent(event: KeyEvent): Boolean =
        shouldDropKey(event.action, event.keyCode, event.repeatCount)

    /**
     * Resets internal debouncing timestamps and pending key state.
     */
    fun reset() {
        lastDownTimes.clear()
        droppedDownKeys.clear()
    }
}
