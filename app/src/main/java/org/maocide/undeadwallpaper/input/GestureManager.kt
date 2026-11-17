package org.maocide.undeadwallpaper.input

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import org.maocide.undeadwallpaper.BuildConfig
import org.maocide.undeadwallpaper.utils.FileLogger
import kotlin.math.hypot

class WallpaperGestureManager(
    context: Context,
    private val isDisplayActive: () -> Boolean,
    private val onKineticTouch: (x: Float, y: Float) -> Unit
) {
    private val TAG = javaClass.simpleName
    private var lastTapUpTimeMs = 0L
    private var downTimeMs = 0L
    private var downX = 0f
    private var downY = 0f

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    fun onTouchEvent(event: MotionEvent?) {
        if (event == null) return

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Ignore uncalibrated or detached digitizer events
                val device = event.device
                if ((device != null && device.isVirtual) || !isDisplayActive()) {
                    resetTapState()
                    return
                }

                downTimeMs = SystemClock.uptimeMillis()
                downX = event.x
                downY = event.y
            }

            MotionEvent.ACTION_MOVE -> {
                if (downTimeMs > 0) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                        // User dragged finger beyond touch slop (e.g. swiping home screen)
                        resetTapState()
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                if (downTimeMs == 0L) return // Spurious UP without DOWN

                val device = event.device
                if ((device != null && device.isVirtual) || !isDisplayActive()) {
                    resetTapState()
                    return
                }

                val upTimeMs = SystemClock.uptimeMillis()
                val duration = upTimeMs - downTimeMs
                val tapX = downX
                val tapY = downY
                downTimeMs = 0L // Reset for next tap

                // 1. Digitizer Sampling Filter: Suppress sub-5ms controller transients and jitter
                if (duration <= 5L) {
                    if (BuildConfig.DEBUG) {
                        FileLogger.d(TAG, "Transient digitizer pulse (${duration}ms), ignoring.")
                    }
                    resetTapState()
                    return
                } else if (duration > 400L) {
                    // Long press or drag, not a tap
                    resetTapState()
                    return
                } else if (duration < 10L) {
                    // Glancing brush / jitter on high refresh screens, drop silently
                    if (BuildConfig.DEBUG) {
                        FileLogger.d(TAG, "Glancing brush detected (${duration}ms). Dropping tap.")
                    }
                    resetTapState()
                    return
                }

                // Check distance again on UP just in case
                val dx = event.x - tapX
                val dy = event.y - tapY
                if (hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                    resetTapState()
                    return
                }

                // 2. Cadence debounce: Ignore micro-bounces below physical threshold
                if (lastTapUpTimeMs > 0L) {
                    val gap = upTimeMs - lastTapUpTimeMs
                    if (gap < 80L) {
                        if (BuildConfig.DEBUG) {
                            FileLogger.d(TAG, "Rapid digitizer debounce (gap: ${gap}ms), ignoring bounce.")
                        }
                        resetTapState()
                        return
                    }
                }

                lastTapUpTimeMs = upTimeMs

                // Confirmed intentional physical tap! Fire kinetic somatic reflex
                onKineticTouch(tapX, tapY)
            }

            MotionEvent.ACTION_CANCEL -> {
                resetTapState()
            }
        }
    }

    fun destroy() {
        resetTapState()
    }

    private fun resetTapState() {
        lastTapUpTimeMs = 0L
        downTimeMs = 0L
    }
}