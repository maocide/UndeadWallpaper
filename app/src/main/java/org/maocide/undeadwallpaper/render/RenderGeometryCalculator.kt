package org.maocide.undeadwallpaper.render

import android.graphics.Matrix
import android.opengl.Matrix as GlMatrix
import org.maocide.undeadwallpaper.model.RenderTransforms
import org.maocide.undeadwallpaper.model.ScalingMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Single Source of Truth for rendering geometry, scaling modes, and coordinate projections.
 *
 * Bridges OpenGL ES 2.0 orthographic NDC coordinates (+Y = Up, Center = 0,0) and
 * Android 2D View Canvas coordinates (+Y = Down, Top-Left = 0,0).
 *
 * Guarantees zero heap allocations during the active render loop by using caller-provided
 * scratch buffers for matrix multiplications.
 */
object RenderGeometryCalculator {

    /**
     * Calculates the width and height of the axis-aligned bounding box enclosing a rotated rectangle.
     */
    fun calculateRotatedBoundingBox(
        width: Float,
        height: Float,
        rotationDegrees: Float
    ): Pair<Float, Float> {
        if (width <= 0f || height <= 0f) return Pair(0f, 0f)
        val angleRad = Math.toRadians(rotationDegrees.toDouble())
        val sinVal = abs(sin(angleRad)).toFloat()
        val cosVal = abs(cos(angleRad)).toFloat()
        val currentWidthPx = (width * cosVal) + (height * sinVal)
        val currentHeightPx = (width * sinVal) + (height * cosVal)
        return Pair(currentWidthPx, currentHeightPx)
    }

    /**
     * Calculates scaling ratios required to map a rotated source box to a target viewport/screen.
     */
    fun calculateScaleRatios(
        sourceWidth: Float,
        sourceHeight: Float,
        targetWidth: Float,
        targetHeight: Float,
        rotationDegrees: Float
    ): Pair<Float, Float> {
        if (sourceWidth <= 0f || sourceHeight <= 0f || targetWidth <= 0f || targetHeight <= 0f) {
            return Pair(1f, 1f)
        }
        val (rotW, rotH) = calculateRotatedBoundingBox(sourceWidth, sourceHeight, rotationDegrees)
        if (rotW <= 0f || rotH <= 0f) return Pair(1f, 1f)
        val scaleRatioX = targetWidth / rotW
        val scaleRatioY = targetHeight / rotH
        return Pair(scaleRatioX, scaleRatioY)
    }

    /**
     * Calculates effective global scaling factors combining the [ScalingMode] and zoom multiplier.
     */
    fun calculateEffectiveScale(
        scalingMode: ScalingMode,
        scaleRatioX: Float,
        scaleRatioY: Float,
        zoom: Float
    ): Pair<Float, Float> {
        val (baseScaleX, baseScaleY) = when (scalingMode) {
            ScalingMode.STRETCH -> Pair(scaleRatioX, scaleRatioY)
            ScalingMode.FILL -> {
                val maxScale = max(scaleRatioX, scaleRatioY)
                Pair(maxScale, maxScale)
            }
            ScalingMode.FIT -> {
                val minScale = min(scaleRatioX, scaleRatioY)
                Pair(minScale, minScale)
            }
        }
        return Pair(baseScaleX * zoom, baseScaleY * zoom)
    }

    /**
     * Computes the parallax translation offset across any hidden width exceeding the screen bounds.
     */
    fun calculateParallaxTranslation(
        parallaxX: Float,
        currentWidthPx: Float,
        globalScaleX: Float,
        logicalScreenWidth: Float
    ): Float {
        val scaledVideoWidthPx = currentWidthPx * globalScaleX
        val hiddenWidthPx = max(0f, scaledVideoWidthPx - logicalScreenWidth)
        return parallaxX * hiddenWidthPx
    }

    /**
     * Computes canvas translation offsets matching GL NDC coordinate conventions.
     * Note: Android Canvas Y is positive downwards, while GL NDC Y is positive upwards.
     */
    fun calculateTranslation(
        positionX: Float,
        positionY: Float,
        targetWidth: Float,
        targetHeight: Float,
        hardwareCalibrationOffset: Float
    ): Pair<Float, Float> {
        val transX = positionX * (targetWidth / 2f) + hardwareCalibrationOffset
        val transY = -positionY * (targetHeight / 2f)
        return Pair(transX, transY)
    }

    /**
     * Computes the combined 4x4 Orthographic Model-View-Projection (MVP) matrix for OpenGL ES 2.0.
     *
     * Zero-Compromise Contract: [hardwareCalibrationOffset] is mandatory.
     * Zero-GC Contract: [scratchProjectionMatrix] and [scratchViewMatrix] must be 16-element FloatArrays
     * allocated once by the caller and reused on every frame.
     */
    fun computeMvpMatrix(
        outMvpMatrix: FloatArray,
        videoWidth: Int,
        videoHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        logicalScreenWidth: Int,
        logicalScreenHeight: Int,
        transforms: RenderTransforms,
        hardwareCalibrationOffset: Float,
        scratchProjectionMatrix: FloatArray,
        scratchViewMatrix: FloatArray
    ) {
        if (logicalScreenWidth == 0 || logicalScreenHeight == 0 ||
            viewportWidth == 0 || viewportHeight == 0 ||
            videoWidth == 0 || videoHeight == 0
        ) {
            GlMatrix.setIdentityM(outMvpMatrix, 0)
            return
        }

        // Setup Orthographic Projection Matrix in Pixel Space
        val left = -viewportWidth / 2f
        val right = viewportWidth / 2f
        val bottom = -viewportHeight / 2f
        val top = viewportHeight / 2f
        GlMatrix.orthoM(scratchProjectionMatrix, 0, left, right, bottom, top, -1f, 1f)

        // Calculate Rotated Bounding Box
        val rotation = transforms.rotation * -1f
        val (currentWidthPx, currentHeightPx) = calculateRotatedBoundingBox(
            videoWidth.toFloat(),
            videoHeight.toFloat(),
            transforms.rotation
        )

        // Ratios to match logical screen
        val scaleRatioX = logicalScreenWidth.toFloat() / currentWidthPx
        val scaleRatioY = logicalScreenHeight.toFloat() / currentHeightPx

        val (globalScaleX, globalScaleY) = calculateEffectiveScale(
            transforms.scalingMode,
            scaleRatioX,
            scaleRatioY,
            transforms.zoom
        )

        // Build View / Model Matrix
        GlMatrix.setIdentityM(scratchViewMatrix, 0)

        // Translation: User Translation + Parallax Translation + Hardware Calibration Offset
        val userTransPx = transforms.translateX * (logicalScreenWidth / 2f)
        val parallaxTransPx = calculateParallaxTranslation(
            transforms.parallaxX,
            currentWidthPx,
            globalScaleX,
            logicalScreenWidth.toFloat()
        )
        val transX = userTransPx + parallaxTransPx + hardwareCalibrationOffset
        val transY = transforms.translateY * (logicalScreenHeight / 2f)
        GlMatrix.translateM(scratchViewMatrix, 0, transX, transY, 0f)

        // Global Scaling (Fit/Fill/Stretch + Zoom)
        GlMatrix.scaleM(scratchViewMatrix, 0, globalScaleX, globalScaleY, 1f)

        // Rotation
        GlMatrix.rotateM(scratchViewMatrix, 0, rotation, 0f, 0f, 1f)

        // Base Scale (Video Size) & Flips
        val flipScaleX = if (transforms.flipHorizontal) -1f else 1f
        val flipScaleY = if (transforms.flipVertical) -1f else 1f
        val baseScaleX = (videoWidth / 2f) * flipScaleX
        val baseScaleY = (videoHeight / 2f) * flipScaleY
        GlMatrix.scaleM(scratchViewMatrix, 0, baseScaleX, baseScaleY, 1f)

        // Combine MVP = Projection * View
        GlMatrix.multiplyMM(outMvpMatrix, 0, scratchProjectionMatrix, 0, scratchViewMatrix, 0)
    }

    /**
     * Pure 2D Android [Matrix] equivalent of [computeMvpMatrix].
     *
     * Translates OpenGL NDC center-origin math into Android View pixel matrix space.
     * Zero-Compromise Contract: [hardwareCalibrationOffset] is mandatory.
     */
    fun computeCanvasMatrix(
        bmpWidth: Float,
        bmpHeight: Float,
        viewWidth: Float,
        viewHeight: Float,
        transforms: RenderTransforms,
        hardwareCalibrationOffset: Float,
        outMatrix: Matrix = Matrix()
    ): Matrix {
        outMatrix.reset()
        if (bmpWidth <= 0f || bmpHeight <= 0f || viewWidth <= 0f || viewHeight <= 0f) {
            return outMatrix
        }

        // Center bitmap pivot at (0, 0)
        outMatrix.postTranslate(-bmpWidth / 2f, -bmpHeight / 2f)

        // Apply Flips
        val flipScaleX = if (transforms.flipHorizontal) -1f else 1f
        val flipScaleY = if (transforms.flipVertical) -1f else 1f
        outMatrix.postScale(flipScaleX, flipScaleY)

        // Apply Rotation
        outMatrix.postRotate(transforms.rotation)

        // Calculate Scale Ratios and Global Scale
        val (scaleRatioX, scaleRatioY) = calculateScaleRatios(
            bmpWidth,
            bmpHeight,
            viewWidth,
            viewHeight,
            transforms.rotation
        )
        val (globalScaleX, globalScaleY) = calculateEffectiveScale(
            transforms.scalingMode,
            scaleRatioX,
            scaleRatioY,
            transforms.zoom
        )
        outMatrix.postScale(globalScaleX, globalScaleY)

        // Translation (GL Y is Up, Canvas Y is Down -> negate translateY)
        val (transX, transY) = calculateTranslation(
            transforms.translateX,
            transforms.translateY,
            viewWidth,
            viewHeight,
            hardwareCalibrationOffset
        )
        outMatrix.postTranslate(transX, transY)

        // Translate to View Center
        outMatrix.postTranslate(viewWidth / 2f, viewHeight / 2f)

        return outMatrix
    }
}

