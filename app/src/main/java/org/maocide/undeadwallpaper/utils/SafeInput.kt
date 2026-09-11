package org.maocide.undeadwallpaper.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.os.SystemClock
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
