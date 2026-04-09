package org.maocide.undeadwallpaper.ui

import android.content.Context
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView

/**
 * A NestedScrollView that intercepts touch events at the bottom of the screen
 * within the system gesture zone. This prevents child views (like Sliders)
 * from instantly responding to ACTION_DOWN when the user is trying to swipe home.
 */
class GestureSafeScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val insets = ViewCompat.getRootWindowInsets(this)
            val gestureBottomInset = insets?.getInsets(WindowInsetsCompat.Type.systemGestures())?.bottom ?: 0

            if (gestureBottomInset > 0) {
                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val realMetrics = DisplayMetrics()
                windowManager.defaultDisplay.getRealMetrics(realMetrics)
                val realScreenHeight = realMetrics.heightPixels

                // If the touch is within the gesture inset area at the bottom,
                // intercept it so child views do not receive the ACTION_DOWN.
                if (ev.rawY >= (realScreenHeight - gestureBottomInset)) {
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }
}
