package org.maocide.undeadwallpaper.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.max
import kotlin.math.sin

class BouncingDotView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dotCount = 3
    private val dotRadius = 8f
    private val dotSpacing = 16f
    private val bounceHeight = 15f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE // Or resolve from theme
        style = Paint.Style.FILL
    }

    private var animationPhase = 0f
    private var animator: ValueAnimator? = null

    init {
        // Resolve a primary color from the theme, or use white
        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorPrimary, typedValue, true)
        if (typedValue.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT && typedValue.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
            paint.color = typedValue.data
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimation()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private fun startAnimation() {
        if (animator != null) return

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                animationPhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = (dotCount * (dotRadius * 2) + (dotCount - 1) * dotSpacing).toInt() + paddingLeft + paddingRight
        val height = (dotRadius * 2 + bounceHeight).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val t = animationPhase * 2 * Math.PI
        val startX = paddingLeft + dotRadius
        val baseY = height - paddingBottom - dotRadius

        for (i in 0 until dotCount) {
            val offset = i * (Math.PI / 4)
            // Sine wave math matching the Flutter logic
            val bounce = max(0.0, sin(t - offset)) * -bounceHeight

            val cx = startX + i * (dotRadius * 2 + dotSpacing)
            val cy = baseY + bounce

            canvas.drawCircle(cx, cy.toFloat(), dotRadius, paint)
        }
    }
}
