package org.maocide.undeadwallpaper.input

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import org.maocide.undeadwallpaper.data.PreferencesManager
import org.maocide.undeadwallpaper.model.GestureType
import org.maocide.undeadwallpaper.model.WallpaperAction
import org.maocide.undeadwallpaper.utils.FileLogger

class WallpaperGestureManager(
    private val prefs: PreferencesManager,
    private val onActionTriggered: (WallpaperAction) -> Unit
) {
    private val TAG = javaClass.simpleName
    private var tapCount = 0
    private var lastTapTimeMs = 0L
    private val TAP_TIMEOUT_MS = 300L
    private val handler = Handler(Looper.getMainLooper())

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
        if (event?.action == MotionEvent.ACTION_DOWN) {
            val currentTapTimeMs = System.currentTimeMillis()

            if (currentTapTimeMs - lastTapTimeMs > TAP_TIMEOUT_MS) {
                // First tap, or too much time passed
                tapCount = 1
            } else {
                tapCount++
            }

            lastTapTimeMs = currentTapTimeMs

            // Cancel any pending resolution because a new tap arrived!
            handler.removeCallbacks(tapResolutionRunnable)

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
    }

    fun destroy() {
        handler.removeCallbacks(tapResolutionRunnable)
        resetTapState()
    }

    private fun resetTapState() {
        tapCount = 0
        lastTapTimeMs = 0L
    }
}