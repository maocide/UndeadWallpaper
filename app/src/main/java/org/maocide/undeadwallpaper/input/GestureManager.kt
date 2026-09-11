package org.maocide.undeadwallpaper.input

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import org.maocide.undeadwallpaper.data.PreferencesManager
import org.maocide.undeadwallpaper.model.GestureType
import org.maocide.undeadwallpaper.model.WallpaperAction
import org.maocide.undeadwallpaper.utils.FileLogger
import kotlin.math.hypot

class WallpaperGestureManager(
    context: Context,
    private val prefs: PreferencesManager,
    private val onActionTriggered: (WallpaperAction) -> Unit
) {
    private val TAG = javaClass.simpleName
    private var tapCount = 0
    private var lastTapUpTimeMs = 0L
    private var downTimeMs = 0L
    private var downX = 0f
    private var downY = 0f

    private val TAP_TIMEOUT_MS = 300L
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val tapResolutionRunnable = Runnable {
        if (tapCount == 2) {
            FileLogger.i(TAG, "Confirmed DOUBLE TAP")
            // Get and trigger action
            val action = prefs.getActionForGesture(GestureType.DOUBLE_TAP)
            onActionTriggered(action)
        }
        resetTapState()
    }

    fun onTouchEvent(event: MotionEvent?) {
        if (event == null) return

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTimeMs = SystemClock.uptimeMillis()
                downX = event.x
                downY = event.y

                // Cancel any pending resolution because a new tap arrived!
                handler.removeCallbacks(tapResolutionRunnable)
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

                val upTimeMs = SystemClock.uptimeMillis()
                val duration = upTimeMs - downTimeMs
                downTimeMs = 0L // Reset for next tap

                // 1. Digitizer Sampling Filter: Suppress sub-5ms controller transients and jitter
                if (duration <= 5L) {
                    FileLogger.d(TAG, "Transient digitizer pulse (${duration}ms), ignoring.")
                    resetTapState()
                    return
                } else if (duration > 400L) {
                    // Long press or drag, not a tap
                    resetTapState()
                    return
                } else if (duration < 10L) {
                    // Glancing brush / jitter on high refresh screens, drop silently
                    FileLogger.d(TAG, "Glancing brush detected (${duration}ms). Dropping tap.")
                    resetTapState()
                    return
                }

                // Check distance again on UP just in case
                val dx = event.x - downX
                val dy = event.y - downY
                if (hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                    resetTapState()
                    return
                }

                // 2. Multi-tap cadence debounce: Ignore micro-bounces below physical threshold
                if (tapCount > 0) {
                    val gap = upTimeMs - lastTapUpTimeMs
                    if (gap < 80L) {
                        FileLogger.d(TAG, "Rapid digitizer debounce (gap: ${gap}ms), ignoring bounce.")
                        resetTapState()
                        return
                    } else if (gap > TAP_TIMEOUT_MS) {
                        // Too much time passed since last tap, start over
                        tapCount = 0
                    }
                }

                tapCount++
                lastTapUpTimeMs = upTimeMs

                if (tapCount == 3) {
                    // TRIPLE TAP DETECTED! Execute immediately.
                    FileLogger.i(TAG, "Confirmed TRIPLE TAP")

                    // Reset immediately so a 4th rapid tap doesn't trigger anything weird
                    resetTapState()

                    // Get and trigger action
                    val action = prefs.getActionForGesture(GestureType.TRIPLE_TAP)
                    onActionTriggered(action)
                } else {
                    // Tap 1 or 2. Wait to see if another tap comes.
                    handler.postDelayed(tapResolutionRunnable, TAP_TIMEOUT_MS)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                resetTapState()
            }
        }
    }

    fun destroy() {
        handler.removeCallbacks(tapResolutionRunnable)
        resetTapState()
    }

    private fun resetTapState() {
        tapCount = 0
        lastTapUpTimeMs = 0L
        downTimeMs = 0L
    }
}