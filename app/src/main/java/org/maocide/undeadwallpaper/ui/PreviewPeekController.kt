package org.maocide.undeadwallpaper.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import androidx.core.view.updateLayoutParams
import org.maocide.undeadwallpaper.model.ScalingMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Controller managing the Press-and-Hold Lightbox Peek Preview in VideoSettingsSheet.
 *
 * Intercepts gestures on the mini preview frame to display an enlarged, crisp mockup
 * of the phone screen centered over a dimmed scrim backdrop, dismissing seamlessly
 * when the finger is lifted.
 */
class PreviewPeekController(
    private val overlayContainer: ViewGroup,
    private val peekCard: View,
    private val peekThumbnail: ImageView,
    private val previewContainer: View,
    private val sourceThumbnail: ImageView? = null,
    private val parallaxBadge: View? = null,
    var isParallaxEnabled: Boolean = false
) {
    private val decelerateInterpolator = DecelerateInterpolator()
    private val accelerateInterpolator = AccelerateInterpolator()

    var isShowing: Boolean = false
        private set

    var currentTargetWidthPx: Int = 0
        private set

    var currentTargetHeightPx: Int = 0
        private set

    private var lastBitmap: Bitmap? = null
    private var lastParams: TransformParams? = null

    data class TransformParams(
        val flipHorizontal: Boolean,
        val flipVertical: Boolean,
        val rotation: Float,
        val scalingMode: ScalingMode,
        val zoom: Float,
        val positionX: Float,
        val positionY: Float,
        val brightness: Float
    )

    @SuppressLint("ClickableViewAccessibility")
    fun setup() {
        overlayContainer.visibility = View.GONE
        overlayContainer.alpha = 0f
        parallaxBadge?.visibility = if (isParallaxEnabled) View.VISIBLE else View.GONE

        previewContainer.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    showPeek()
                    true
                }

                MotionEvent.ACTION_UP -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    v.performClick()
                    hidePeek()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    hidePeek()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }

                else -> false
            }
        }

        // Accessibility click support (e.g. TalkBack / keyboard navigation)
        previewContainer.setOnClickListener {
            if (isShowing) {
                hidePeek()
            } else {
                showPeek()
            }
        }

        // Tap dimmed backdrop to dismiss
        overlayContainer.setOnClickListener {
            hidePeek()
        }
    }

    fun updateDimensions(metrics: DisplayMetrics) {
        val (targetW, targetH) = calculateDimensions(
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            density = metrics.density
        )

        currentTargetWidthPx = targetW
        currentTargetHeightPx = targetH

        peekCard.updateLayoutParams<ViewGroup.LayoutParams> {
            width = targetW
            height = targetH
        }

        // Re-apply transform if parameters already exist
        val bmp = lastBitmap
        val params = lastParams
        if (bmp != null && params != null && targetW > 0 && targetH > 0) {
            applyTransformToView(peekThumbnail, bmp, params, targetW.toFloat(), targetH.toFloat())
        }
    }

    fun updateTransform(
        bmp: Bitmap,
        flipHorizontal: Boolean,
        flipVertical: Boolean,
        rotation: Float,
        scalingMode: ScalingMode,
        zoom: Float,
        positionX: Float,
        positionY: Float,
        brightness: Float
    ) {
        val params = TransformParams(
            flipHorizontal = flipHorizontal,
            flipVertical = flipVertical,
            rotation = rotation,
            scalingMode = scalingMode,
            zoom = zoom,
            positionX = positionX,
            positionY = positionY,
            brightness = brightness
        )
        lastBitmap = bmp
        lastParams = params

        // Synchronize source thumbnail (mini preview in sheet) if present
        sourceThumbnail?.let { src ->
            val srcW = src.width.toFloat()
            val srcH = src.height.toFloat()
            if (srcW > 0f && srcH > 0f) {
                applyTransformToView(src, bmp, params, srcW, srcH)
            }
        }

        // Synchronize lightbox peek thumbnail
        peekThumbnail.setImageBitmap(bmp)

        val viewW = if (peekThumbnail.width > 0) peekThumbnail.width.toFloat() else currentTargetWidthPx.toFloat()
        val viewH = if (peekThumbnail.height > 0) peekThumbnail.height.toFloat() else currentTargetHeightPx.toFloat()

        if (viewW > 0f && viewH > 0f) {
            applyTransformToView(peekThumbnail, bmp, params, viewW, viewH)
        }
    }

    private fun applyTransformToView(
        targetView: ImageView,
        bmp: Bitmap,
        params: TransformParams,
        viewW: Float,
        viewH: Float
    ) {
        val matrix = computeTransformMatrix(
            bmpW = bmp.width.toFloat(),
            bmpH = bmp.height.toFloat(),
            viewW = viewW,
            viewH = viewH,
            flipHorizontal = params.flipHorizontal,
            flipVertical = params.flipVertical,
            rotation = params.rotation,
            scalingMode = params.scalingMode,
            zoom = params.zoom,
            positionX = params.positionX,
            positionY = params.positionY
        )
        targetView.imageMatrix = matrix
        applyBrightnessFilter(targetView, params.brightness)
    }

    fun showPeek() {
        // Fallback: sync bitmap from source if not already populated
        if (peekThumbnail.drawable == null && sourceThumbnail?.drawable is BitmapDrawable) {
            val srcBmp = (sourceThumbnail.drawable as BitmapDrawable).bitmap
            if (srcBmp != null) {
                lastBitmap = srcBmp
                peekThumbnail.setImageBitmap(srcBmp)
            }
        }

        // Recompute matrix with current measured dimensions if available
        val bmp = lastBitmap
        val params = lastParams
        val viewW = if (peekThumbnail.width > 0) peekThumbnail.width.toFloat() else currentTargetWidthPx.toFloat()
        val viewH = if (peekThumbnail.height > 0) peekThumbnail.height.toFloat() else currentTargetHeightPx.toFloat()
        if (bmp != null && params != null && viewW > 0f && viewH > 0f) {
            applyTransformToView(peekThumbnail, bmp, params, viewW, viewH)
        }

        isShowing = true
        overlayContainer.animate().cancel()
        peekCard.animate().cancel()

        parallaxBadge?.visibility = if (isParallaxEnabled) View.VISIBLE else View.GONE
        overlayContainer.visibility = View.VISIBLE
        overlayContainer.alpha = 0f
        peekCard.scaleX = 0.92f
        peekCard.scaleY = 0.92f

        overlayContainer.animate()
            .alpha(1f)
            .setDuration(ENTER_ANIMATION_DURATION_MS)
            .setInterpolator(decelerateInterpolator)
            .setListener(null)
            .start()

        peekCard.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(ENTER_ANIMATION_DURATION_MS)
            .setInterpolator(decelerateInterpolator)
            .start()
    }

    fun hidePeek() {
        if (!isShowing && overlayContainer.visibility != View.VISIBLE) return
        isShowing = false

        overlayContainer.animate().cancel()
        peekCard.animate().cancel()

        overlayContainer.animate()
            .alpha(0f)
            .setDuration(EXIT_ANIMATION_DURATION_MS)
            .setInterpolator(accelerateInterpolator)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!isShowing) {
                        overlayContainer.visibility = View.GONE
                    }
                }
            })
            .start()

        peekCard.animate()
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(EXIT_ANIMATION_DURATION_MS)
            .setInterpolator(accelerateInterpolator)
            .start()
    }

    fun cleanup() {
        overlayContainer.animate().cancel()
        peekCard.animate().cancel()
        previewContainer.setOnTouchListener(null)
        previewContainer.setOnClickListener(null)
        overlayContainer.setOnClickListener(null)
        isShowing = false
    }

    companion object {
        const val PORTRAIT_HEIGHT_DP = 260
        const val LANDSCAPE_HEIGHT_DP = 160
        const val ENTER_ANIMATION_DURATION_MS = 120L
        const val EXIT_ANIMATION_DURATION_MS = 100L

        /**
         * Pure helper to compute peek dimensions maintaining the device's native aspect ratio.
         */
        fun calculateDimensions(
            screenWidth: Int,
            screenHeight: Int,
            density: Float
        ): Pair<Int, Int> {
            if (screenWidth <= 0 || screenHeight <= 0 || density <= 0f) return Pair(0, 0)
            val isLandscape = screenWidth > screenHeight
            val desiredHeightDp = if (isLandscape) LANDSCAPE_HEIGHT_DP else PORTRAIT_HEIGHT_DP
            val desiredHeightPx = (desiredHeightDp * density).roundToInt()
            val maxHeightPx = (screenHeight * 0.55f).roundToInt()
            val targetHeightPx = min(desiredHeightPx, maxHeightPx)
            val screenRatio = screenWidth.toFloat() / screenHeight.toFloat()
            val targetWidthPx = (targetHeightPx * screenRatio).roundToInt()
            return Pair(targetWidthPx, targetHeightPx)
        }

        /**
         * Calculates rotated bounding box and scaling ratios.
         */
        fun calculateScaleRatios(
            bmpW: Float,
            bmpH: Float,
            viewW: Float,
            viewH: Float,
            rotationDeg: Float
        ): Pair<Float, Float> {
            if (bmpW <= 0f || bmpH <= 0f || viewW <= 0f || viewH <= 0f) return Pair(1f, 1f)
            val angleRad = Math.toRadians(rotationDeg.toDouble())
            val sinVal = abs(sin(angleRad)).toFloat()
            val cosVal = abs(cos(angleRad)).toFloat()
            val rotW = (bmpW * cosVal) + (bmpH * sinVal)
            val rotH = (bmpW * sinVal) + (bmpH * cosVal)
            val scaleRatioX = viewW / rotW
            val scaleRatioY = viewH / rotH
            return Pair(scaleRatioX, scaleRatioY)
        }

        /**
         * Calculates global scale factors for scaling modes and zoom.
         */
        fun calculateEffectiveScale(
            scalingMode: ScalingMode,
            scaleRatioX: Float,
            scaleRatioY: Float,
            zoom: Float
        ): Pair<Float, Float> {
            var globalScaleX: Float
            var globalScaleY: Float

            when (scalingMode) {
                ScalingMode.STRETCH -> {
                    globalScaleX = scaleRatioX
                    globalScaleY = scaleRatioY
                }

                ScalingMode.FILL -> {
                    val maxScale = max(scaleRatioX, scaleRatioY)
                    globalScaleX = maxScale
                    globalScaleY = maxScale
                }

                ScalingMode.FIT -> {
                    val minScale = min(scaleRatioX, scaleRatioY)
                    globalScaleX = minScale
                    globalScaleY = minScale
                }
            }

            globalScaleX *= zoom
            globalScaleY *= zoom
            return Pair(globalScaleX, globalScaleY)
        }

        /**
         * Calculates canvas translation offsets matching GL NDC coordinate conventions.
         */
        fun calculateTranslation(
            positionX: Float,
            positionY: Float,
            viewW: Float,
            viewH: Float
        ): Pair<Float, Float> {
            val transX = positionX * (viewW / 2f)
            val transY = -positionY * (viewH / 2f)
            return Pair(transX, transY)
        }

        // ============================================================================
        // FORENSIC GEOMETRY CONTRACT:
        // 2D Canvas equivalent of GLVideoRenderer.updateMatrix().
        // Translates OpenGL NDC center-origin math into Android View pixel matrix space.
        // If modifying projection or aspect math in GLVideoRenderer, keep both in sync!
        // ============================================================================
        /**
         * Pure 2D canvas transformation matrix equivalent of GLVideoRenderer.updateMatrix().
         */
        fun computeTransformMatrix(
            bmpW: Float,
            bmpH: Float,
            viewW: Float,
            viewH: Float,
            flipHorizontal: Boolean,
            flipVertical: Boolean,
            rotation: Float,
            scalingMode: ScalingMode,
            zoom: Float,
            positionX: Float,
            positionY: Float
        ): Matrix {
            val matrix = Matrix()
            if (bmpW <= 0f || bmpH <= 0f || viewW <= 0f || viewH <= 0f) return matrix

            // Center bitmap pivot at (0, 0)
            matrix.postTranslate(-bmpW / 2f, -bmpH / 2f)

            // Apply Flips
            val flipScaleX = if (flipHorizontal) -1f else 1f
            val flipScaleY = if (flipVertical) -1f else 1f
            matrix.postScale(flipScaleX, flipScaleY)

            // Apply Rotation
            matrix.postRotate(rotation)

            // Calculate Scales
            val (scaleRatioX, scaleRatioY) = calculateScaleRatios(bmpW, bmpH, viewW, viewH, rotation)
            val (globalScaleX, globalScaleY) = calculateEffectiveScale(scalingMode, scaleRatioX, scaleRatioY, zoom)
            matrix.postScale(globalScaleX, globalScaleY)

            // Translation (GL Y is Up, Canvas Y is Down -> negate positionY)
            val (transX, transY) = calculateTranslation(positionX, positionY, viewW, viewH)
            matrix.postTranslate(transX, transY)

            // Translate to View Center
            matrix.postTranslate(viewW / 2f, viewH / 2f)

            return matrix
        }

        /**
         * Brightness filter matching GL Fragment shader: color.rgb + (uBrightness - 1.0) * 0.5
         */
        fun applyBrightnessFilter(imageView: ImageView, brightness: Float) {
            if (abs(brightness - 1.0f) > 0.001f) {
                val offset = (brightness - 1.0f) * 0.5f * 255f
                val cm = ColorMatrix(
                    floatArrayOf(
                        1f, 0f, 0f, 0f, offset,
                        0f, 1f, 0f, 0f, offset,
                        0f, 0f, 1f, 0f, offset,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                imageView.colorFilter = ColorMatrixColorFilter(cm)
            } else {
                imageView.colorFilter = null
            }
        }
    }
}
