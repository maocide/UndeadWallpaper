package org.maocide.undeadwallpaper.service

import org.maocide.undeadwallpaper.model.ScalingMode
import org.maocide.undeadwallpaper.utils.FileLogger

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGLExt.EGL_RECORDABLE_ANDROID
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log

import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGL11
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class GLVideoRenderer(private val context: Context) {

    private val tag: String = javaClass.simpleName

    // GL Context stuff
    private var eglDisplay: EGLDisplay? = EGL10.EGL_NO_DISPLAY
    private var eglContext: EGLContext? = EGL10.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface? = EGL10.EGL_NO_SURFACE
    private var egl: EGL10? = null

    // Surface stuff
    private var surfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private var videoSurfaceDeferred = CompletableDeferred<Surface?>()
    private var textureId: Int = 0

    private var videoWidth = 0
    private var videoHeight = 0
    private val mvpMatrix = FloatArray(16)
    private val stMatrix = FloatArray(16)

    private val glExecutor = Executors.newSingleThreadExecutor()
    private val glDispatcher = glExecutor.asCoroutineDispatcher()
    private val renderScope = CoroutineScope(glDispatcher + Job())

    // Trigger signal
    private val renderSignal = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var viewportWidth = 0
    @Volatile private var viewportHeight = 0
    @Volatile private var screenWidth = 0
    @Volatile private var screenHeight = 0
    @Volatile private var viewportChanged = false


    // Add Projection/View Matrices for Ortho Math
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)

    // Parallax offset variables.
    @Volatile private var parallaxTranslateX = 0.0f;

    // --- Per-screen bridge layer (static image / frozen frame) ---
    // The video is drawn over a static 2D layer; videoAlpha crossfades between them.
    @Volatile private var videoAlpha = 1.0f
    @Volatile private var staticEnabled = false
    @Volatile private var staticTexScaleX = 1.0f
    @Volatile private var staticTexScaleY = 1.0f
    @Volatile private var staticFlipV = 0.0f // 1.0 for uploaded bitmaps, 0.0 for captured frames

    // GL-thread command flags (set from any thread, consumed in renderLoop)
    @Volatile private var pendingCapture = false
    @Volatile private var pendingStaticBitmap: Bitmap? = null
    @Volatile private var hasPendingBitmap = false

    private var staticTextureId: Int = 0
    private var captureFboId: Int = 0


    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec4 aTextureCoord;
        uniform mat4 uMVPMatrix;
        uniform mat4 uSTMatrix;
        varying vec2 vTextureCoord;
        void main() {
          gl_Position = uMVPMatrix * aPosition;
          vTextureCoord = (uSTMatrix * aTextureCoord).xy;
        }
    """

    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform samplerExternalOES sTexture;
        uniform float uBrightness;
        uniform float uVideoAlpha;
        void main() {
          vec4 color = texture2D(sTexture, vTextureCoord);
          gl_FragColor = vec4(color.rgb + (uBrightness - 1.0) * 0.5, color.a * uVideoAlpha);
        }
    """

    // Static layer (the "bridge" image shown between pages). Full-screen quad with
    // center-crop via uTexScale and an optional vertical flip for uploaded bitmaps.
    private val staticVertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec4 aTextureCoord;
        uniform vec2 uTexScale;
        uniform float uFlipV;
        varying vec2 vTextureCoord;
        void main() {
          gl_Position = aPosition;
          vec2 tc = aTextureCoord.xy;
          tc.y = mix(tc.y, 1.0 - tc.y, uFlipV);
          vTextureCoord = (tc - 0.5) * uTexScale + 0.5;
        }
    """

    private val staticFragmentShaderCode = """
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform sampler2D sTexture;
        void main() {
          gl_FragColor = texture2D(sTexture, vTextureCoord);
        }
    """

    private var programId = 0
    private var maPositionHandle = 0
    private var maTextureHandle = 0
    private var muMVPMatrixHandle = 0
    private var muSTMatrixHandle = 0
    private var muBrightnessHandle = 0
    private var muVideoAlphaHandle = 0

    private var staticProgramId = 0
    private var staticPositionHandle = 0
    private var staticTexHandle = 0
    private var staticTexScaleHandle = 0
    private var staticFlipVHandle = 0
    private var staticSamplerHandle = 0

    private val triangleVerticesData = floatArrayOf(
        -1.0f, -1.0f, 0f, 0f, 0f,
        1.0f, -1.0f, 0f, 1f, 0f,
        -1.0f, 1.0f, 0f, 0f, 1f,
        1.0f, 1.0f, 0f, 1f, 1f
    )
    private var triangleVertices: FloatBuffer

    // User Transform Variables
    private var currentScalingMode: ScalingMode = ScalingMode.FILL
    private var userTranslateX = 0f
    private var userTranslateY = 0f
    private var userZoom = 1.0f
    private var userRotation = 0f
    private var userBrightness = 1.0f
    @Volatile private var surfaceDrawTimestamp: Long = 0L
    @Volatile private var isPendingMatrixUpdate = false

    init {
        triangleVertices = ByteBuffer.allocateDirect(triangleVerticesData.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        triangleVertices.put(triangleVerticesData).position(0)
        Matrix.setIdentityM(stMatrix, 0)
    }

    fun getSurfaceDrawTimestamp(): Long {
        return surfaceDrawTimestamp
    }

    fun setScalingMode(mode: ScalingMode) {
        if (currentScalingMode != mode) {
            FileLogger.i(tag, "Scaling Mode changed to: $mode")
            currentScalingMode = mode
            isPendingMatrixUpdate = true
        }
    }

    fun setTransforms(x: Float, y: Float, zoom: Float, rotation: Float) {
        userTranslateX = x
        userTranslateY = y
        userZoom = zoom
        userRotation = rotation
        isPendingMatrixUpdate = true
    }

    fun setBrightness(brightness: Float) {
        userBrightness = brightness
    }

    fun onSurfaceCreated(holder: SurfaceHolder) {
        renderScope.launch {
            try {
                initGL(holder)
                renderLoop()
            } catch (e: Exception) {
                FileLogger.e(tag, "Failed to initialize GL", e)
                videoSurfaceDeferred.completeExceptionally(e)
            }
        }
    }

    fun release() {
        FileLogger.i(tag, "Renderer Release Signal Received.")
        renderSignal.close()
        glExecutor.shutdown()
        // We do NOT call releaseGL here directly, we let the loop finish and clean up itself
        // or we risk thread collision.
    }

    /**
     * Forces the GL thread to render a frame immediately.
     * This is useful when visual settings (like zoom or position) are changed
     * while the video player is paused, preventing the screen from appearing stuck
     * on old settings until the video resumes playback.
     */
    fun requestRender() {
        renderSignal.trySend(Unit)
    }

    fun setParallaxOffset(xOffsetFromCenter: Float) {
        // xOffsetFromCenter should be a value like -0.2 to 0.2
        if(parallaxTranslateX != xOffsetFromCenter) {
            parallaxTranslateX = xOffsetFromCenter
            isPendingMatrixUpdate = true
            requestRender() // Force a draw even if paused!
        }
    }

    /**
     * Sets the opacity of the video layer (0.0 = fully hidden, only the static
     * bridge layer shows; 1.0 = video fully opaque). Used to crossfade between
     * the per-screen video and the bridge image.
     */
    fun setVideoAlpha(alpha: Float) {
        val clamped = alpha.coerceIn(0f, 1f)
        if (videoAlpha != clamped) {
            videoAlpha = clamped
            requestRender() // Force a draw even if the player is paused
        }
    }

    /**
     * Uploads [bitmap] as the static bridge layer (BridgeMode SHARED_IMAGE /
     * PER_PAGE_IMAGE). Passing null clears the static layer. The actual GL upload
     * happens on the render thread.
     */
    fun setStaticBitmap(bitmap: Bitmap?) {
        if (bitmap == null) {
            clearStatic()
            return
        }
        pendingStaticBitmap = bitmap
        hasPendingBitmap = true
        requestRender()
    }

    /**
     * Captures the currently displayed video frame into the static bridge layer
     * (BridgeMode FROZEN_FRAME). The capture happens on the render thread.
     */
    fun captureCurrentFrameToStatic() {
        pendingCapture = true
        requestRender()
    }

    /** Disables the static bridge layer (the video layer draws on its own). */
    fun clearStatic() {
        staticEnabled = false
        requestRender()
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        // Store Viewport (The Source of Truth for Orientation)
        viewportWidth = width
        viewportHeight = height

        // Get Physical Metrics
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        val metrics = android.util.DisplayMetrics()
        display.getRealMetrics(metrics)

        // ORIENTATION CORRECTION
        // Sometimes 'metrics' reports Portrait dimensions even if the Surface is Landscape
        // (common on Tablets or locked Launchers).
        // Trust the Viewport's shape. If they disagree, swap the metrics.

        val isViewportLandscape = width > height
        val isMetricsLandscape = metrics.widthPixels > metrics.heightPixels

        if (isViewportLandscape != isMetricsLandscape) {
            // Mismatch detected! Swap dimensions to match Viewport.
            screenWidth = metrics.heightPixels
            screenHeight = metrics.widthPixels
            FileLogger.w(tag, "Orientation Mismatch! Swapped metrics to: ${screenWidth}x${screenHeight}")
        } else {
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }

        // Signal Render Thread
        viewportChanged = true
        renderSignal.trySend(Unit)

        FileLogger.i(tag, "Surface Changed: Viewport=${width}x${height}, LogicalScreen=${screenWidth}x${screenHeight}")
    }

    fun setVideoSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        isPendingMatrixUpdate = true
    }

    suspend fun waitForVideoSurface(): Surface? {
        return videoSurfaceDeferred.await()
    }

    private suspend fun renderLoop() {
        // Tracks the need to reset render state
        var needsReinit = false

        try {
            for (signal in renderSignal) {
                // Null Checks
                if (egl == null || eglDisplay == EGL10.EGL_NO_DISPLAY || eglContext == EGL10.EGL_NO_CONTEXT) {
                    continue
                }

                if (surfaceTexture == null) continue

                // Re-initialization Check... Recover from error
                if (needsReinit) {
                    FileLogger.w(tag, "Attempting to recover GL Context...")
                    /* Might need to call initGL logic here or just
                    continue and hope the surface is valid.
                    usually, just skipping the frame can be safe... */
                    needsReinit = false
                }

                try {
                    // Update texture MUST happen on the thread with the EGL Context
                    surfaceTexture?.updateTexImage()
                    surfaceTexture?.getTransformMatrix(stMatrix)
                } catch (e: Exception) {
                    // This often happens when the video player is stopped/released
                    // but a frame signal was already in the pipe. Safe to ignore.
                    FileLogger.w(tag, "SurfaceTexture update failed (Context lost?): ${e.message}")
                    continue
                }

                // CHECK FOR VIEWPORT UPDATES
                if (viewportChanged) {
                    GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
                    isPendingMatrixUpdate = true
                    viewportChanged = false
                }

                if (isPendingMatrixUpdate) {
                    updateMatrix()
                    isPendingMatrixUpdate = false
                }

                // Process bridge-layer commands on the GL thread.
                if (hasPendingBitmap) {
                    uploadStaticBitmap(pendingStaticBitmap)
                    pendingStaticBitmap = null
                    hasPendingBitmap = false
                }
                if (pendingCapture) {
                    captureFrameToStatic()
                    pendingCapture = false
                }

                // The Draw Call
                try {
                    drawFrame()
                    val swapResult = egl?.eglSwapBuffers(eglDisplay, eglSurface)

                    // Check if Swap failed (Context Lost)
                    if (swapResult == false) {
                        val error = egl?.eglGetError()
                        if (error == EGL11.EGL_CONTEXT_LOST) {
                            FileLogger.e(tag, "GL Context Lost! triggering re-init.")
                            needsReinit = true
                            // Possibly releaseGL() -> initGL() here to make a full restart
                        } else {
                            FileLogger.w(tag, "eglSwapBuffers failed: $error")
                        }
                    } else {
                        // Update surface timestamp for a successful draw call
                        surfaceDrawTimestamp = System.currentTimeMillis()
                    }
                } catch (t: Throwable) {
                    // CATCH EVERYTHING here.
                    // Prevents a render error from crashing the whole service
                    FileLogger.e(tag, "Critical Render Error: ${t.message}")
                }
            }
        } finally {
            FileLogger.i(tag, "Render loop finished. Cleaning up GL on renderer thread.")
            releaseGL()
        }
    }

    private fun drawFrame() {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_COLOR_BUFFER_BIT)

        // Alpha blending lets the video layer crossfade over the static bridge layer.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val alpha = videoAlpha

        // Draw the static bridge layer underneath only when the video is not fully opaque.
        if (staticEnabled && staticTextureId != 0 && alpha < 1.0f) {
            drawStaticLayer()
        }

        drawVideoLayer(alpha)
    }

    private fun drawVideoLayer(alpha: Float) {
        GLES20.glUseProgram(programId)

        triangleVertices.position(0)
        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false, 20, triangleVertices)
        GLES20.glEnableVertexAttribArray(maPositionHandle)

        triangleVertices.position(3)
        GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false, 20, triangleVertices)
        GLES20.glEnableVertexAttribArray(maTextureHandle)

        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, stMatrix, 0)
        GLES20.glUniform1f(muBrightnessHandle, userBrightness)
        GLES20.glUniform1f(muVideoAlphaHandle, alpha)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        if (textureId != 0) {
            GLES20.glBindTexture(36197, textureId) // GL_TEXTURE_EXTERNAL_OES
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
    }

    private fun drawStaticLayer() {
        GLES20.glUseProgram(staticProgramId)

        triangleVertices.position(0)
        GLES20.glVertexAttribPointer(staticPositionHandle, 3, GLES20.GL_FLOAT, false, 20, triangleVertices)
        GLES20.glEnableVertexAttribArray(staticPositionHandle)

        triangleVertices.position(3)
        GLES20.glVertexAttribPointer(staticTexHandle, 2, GLES20.GL_FLOAT, false, 20, triangleVertices)
        GLES20.glEnableVertexAttribArray(staticTexHandle)

        GLES20.glUniform2f(staticTexScaleHandle, staticTexScaleX, staticTexScaleY)
        GLES20.glUniform1f(staticFlipVHandle, staticFlipV)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, staticTextureId)
        GLES20.glUniform1i(staticSamplerHandle, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Ensures the static 2D texture exists and is sized [width] x [height] (data may be null). */
    private fun ensureStaticTexture(width: Int, height: Int) {
        if (staticTextureId == 0) {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            staticTextureId = t[0]
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, staticTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
    }

    private fun uploadStaticBitmap(bitmap: Bitmap?) {
        if (bitmap == null) return
        try {
            if (staticTextureId == 0) {
                val t = IntArray(1)
                GLES20.glGenTextures(1, t, 0)
                staticTextureId = t[0]
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, staticTextureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

            // Center-crop the image to the screen (viewport) aspect ratio.
            if (viewportWidth > 0 && viewportHeight > 0 && bitmap.height > 0) {
                val imgAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
                val screenAspect = viewportWidth.toFloat() / viewportHeight.toFloat()
                if (imgAspect > screenAspect) {
                    staticTexScaleX = screenAspect / imgAspect
                    staticTexScaleY = 1.0f
                } else {
                    staticTexScaleX = 1.0f
                    staticTexScaleY = imgAspect / screenAspect
                }
            } else {
                staticTexScaleX = 1.0f
                staticTexScaleY = 1.0f
            }
            staticFlipV = 1.0f // uploaded bitmaps have top-left origin
            staticEnabled = true
        } catch (e: Exception) {
            FileLogger.e(tag, "Failed to upload static bitmap: ${e.message}")
        }
    }

    private fun captureFrameToStatic() {
        if (viewportWidth == 0 || viewportHeight == 0 || textureId == 0) return
        try {
            ensureStaticTexture(viewportWidth, viewportHeight)

            if (captureFboId == 0) {
                val f = IntArray(1)
                GLES20.glGenFramebuffers(1, f, 0)
                captureFboId = f[0]
            }

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, captureFboId)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, staticTextureId, 0
            )
            GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // Render the live video frame opaque into the FBO-backed texture.
            drawVideoLayer(1.0f)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, viewportWidth, viewportHeight)

            // A captured frame already matches screen orientation and aspect.
            staticTexScaleX = 1.0f
            staticTexScaleY = 1.0f
            staticFlipV = 0.0f
            staticEnabled = true
        } catch (e: Exception) {
            FileLogger.e(tag, "Failed to capture frame: ${e.message}")
        }
    }

    // The Matrix calculation
    private fun updateMatrix() {
        if (screenWidth == 0 || screenHeight == 0 || viewportWidth == 0 || viewportHeight == 0) {
            Matrix.setIdentityM(mvpMatrix, 0)
            return
        }

        // SETUP PIXEL SPACE (Ortho matrix)
        val left = -viewportWidth / 2f
        val right = viewportWidth / 2f
        val bottom = -viewportHeight / 2f
        val top = viewportHeight / 2f
        Matrix.orthoM(projectionMatrix, 0, left, right, bottom, top, -1f, 1f)

        // CALCULATE GEOMETRY (Rotated Bounding Box)
        val rotation = userRotation * -1f
        val angleRad = Math.toRadians(rotation.toDouble())
        val sinVal = abs(sin(angleRad)).toFloat()
        val cosVal = abs(cos(angleRad)).toFloat()

        // Size of the Rotated Video Box (in Pixels)
        val currentWidthPx = (videoWidth * cosVal) + (videoHeight * sinVal)
        val currentHeightPx = (videoWidth * sinVal) + (videoHeight * cosVal)

        // Ratios to match the LOGICAL SCREEN (Now guaranteed to match Viewport orientation)
        val scaleRatioX = screenWidth.toFloat() / currentWidthPx
        val scaleRatioY = screenHeight.toFloat() / currentHeightPx

        // DETERMINE SCALING FACTORS
        var globalScaleX = 1.0f
        var globalScaleY = 1.0f

        when (currentScalingMode) {
            ScalingMode.STRETCH -> {
                // Stretch: Force fit the rotated box to the screen
                globalScaleX = scaleRatioX
                globalScaleY = scaleRatioY
            }
            ScalingMode.FILL -> {
                // Fill: Zoom to cover (Max)
                val maxScale = max(scaleRatioX, scaleRatioY)
                globalScaleX = maxScale
                globalScaleY = maxScale
            }
            ScalingMode.FIT -> {
                // Fit: Zoom to fit inside (Min)
                val minScale = min(scaleRatioX, scaleRatioY)
                globalScaleX = minScale
                globalScaleY = minScale
            }
        }

        // Apply Zoom
        globalScaleX *= userZoom
        globalScaleY *= userZoom

        // BUILD MODEL MATRIX
        Matrix.setIdentityM(viewMatrix, 0)

        // Translate
        val transX = (userTranslateX + parallaxTranslateX) * (screenWidth / 2f)
        val transY = userTranslateY * (screenHeight / 2f)
        Matrix.translateM(viewMatrix, 0, transX, transY, 0f)

        // Scale Global (Fit/Stretch)
        Matrix.scaleM(viewMatrix, 0, globalScaleX, globalScaleY, 1f)

        // Rotate
        Matrix.rotateM(viewMatrix, 0, rotation, 0f, 0f, 1f)

        // Scale Base (Video Size)
        val baseScaleX = videoWidth / 2f
        val baseScaleY = videoHeight / 2f
        Matrix.scaleM(viewMatrix, 0, baseScaleX, baseScaleY, 1f)

        // COMBINE
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
    }

    private fun initGL(holder: SurfaceHolder) {
        egl = EGLContext.getEGL() as EGL10
        eglDisplay = egl!!.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)

        val version = IntArray(2)
        if (!egl!!.eglInitialize(eglDisplay, version)) {
            throw IllegalStateException("eglInitialize failed")
        }

        val configSpecRGBA8888Recordable = intArrayOf(
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_ALPHA_SIZE, 8,
            EGL10.EGL_DEPTH_SIZE, 0,
            EGL10.EGL_STENCIL_SIZE, 0,
            EGL10.EGL_RENDERABLE_TYPE, 4,
            EGL_RECORDABLE_ANDROID, 1,
            EGL10.EGL_NONE
        )

        val configSpecRGB565Recordable = intArrayOf(
            EGL10.EGL_RED_SIZE, 5,
            EGL10.EGL_GREEN_SIZE, 6,
            EGL10.EGL_BLUE_SIZE, 5,
            EGL10.EGL_ALPHA_SIZE, 0,
            EGL10.EGL_DEPTH_SIZE, 0,
            EGL10.EGL_STENCIL_SIZE, 0,
            EGL10.EGL_RENDERABLE_TYPE, 4,
            EGL_RECORDABLE_ANDROID, 1,
            EGL10.EGL_NONE
        )

        val configSpecRGB565 = intArrayOf(
            EGL10.EGL_RED_SIZE, 5,
            EGL10.EGL_GREEN_SIZE, 6,
            EGL10.EGL_BLUE_SIZE, 5,
            EGL10.EGL_ALPHA_SIZE, 0,
            EGL10.EGL_DEPTH_SIZE, 0,
            EGL10.EGL_STENCIL_SIZE, 0,
            EGL10.EGL_RENDERABLE_TYPE, 4,
            EGL10.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfig = IntArray(1)

        var config: EGLConfig? = null

        if (egl!!.eglChooseConfig(eglDisplay, configSpecRGBA8888Recordable, configs, 1, numConfig) && numConfig[0] > 0) {
            config = configs[0]
            FileLogger.i(tag, "Using EGL_RGBA_8888_RECORDABLE config")
        } else if (egl!!.eglChooseConfig(eglDisplay, configSpecRGB565Recordable, configs, 1, numConfig) && numConfig[0] > 0) {
            config = configs[0]
            FileLogger.i(tag, "Using EGL_RGB_565_RECORDABLE config")
        } else if (egl!!.eglChooseConfig(eglDisplay, configSpecRGB565, configs, 1, numConfig) && numConfig[0] > 0) {
            config = configs[0]
            FileLogger.i(tag, "Using EGL_RGB_565 fallback config")
        } else {
            throw IllegalStateException("Unable to find a suitable EGL config")
        }

        val attribList = intArrayOf(0x3098, 2, EGL10.EGL_NONE)
        eglContext = egl!!.eglCreateContext(eglDisplay, config, EGL10.EGL_NO_CONTEXT, attribList)
        if (eglContext == null || eglContext == EGL10.EGL_NO_CONTEXT) {
            throw IllegalStateException("eglCreateContext failed")
        }

        eglSurface = egl!!.eglCreateWindowSurface(eglDisplay, config, holder, null)
        if (eglSurface == null || eglSurface == EGL10.EGL_NO_SURFACE) {
            throw IllegalStateException("eglCreateWindowSurface failed")
        }

        if (!egl!!.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw IllegalStateException("eglMakeCurrent failed")
        }

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES20.glBindTexture(36197, textureId)
        GLES20.glTexParameterf(36197, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(36197, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())

        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture!!.setOnFrameAvailableListener {
            renderSignal.trySend(Unit)
        }
        videoSurface = Surface(surfaceTexture)
        videoSurfaceDeferred.complete(videoSurface)

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vertexShader)
        GLES20.glAttachShader(programId, fragmentShader)
        GLES20.glLinkProgram(programId)

        maPositionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
        maTextureHandle = GLES20.glGetAttribLocation(programId, "aTextureCoord")
        muMVPMatrixHandle = GLES20.glGetUniformLocation(programId, "uMVPMatrix")
        muSTMatrixHandle = GLES20.glGetUniformLocation(programId, "uSTMatrix")
        muBrightnessHandle = GLES20.glGetUniformLocation(programId, "uBrightness")
        muVideoAlphaHandle = GLES20.glGetUniformLocation(programId, "uVideoAlpha")

        // Build the static (bridge image) program.
        val staticVertexShader = loadShader(GLES20.GL_VERTEX_SHADER, staticVertexShaderCode)
        val staticFragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, staticFragmentShaderCode)
        staticProgramId = GLES20.glCreateProgram()
        GLES20.glAttachShader(staticProgramId, staticVertexShader)
        GLES20.glAttachShader(staticProgramId, staticFragmentShader)
        GLES20.glLinkProgram(staticProgramId)
        staticPositionHandle = GLES20.glGetAttribLocation(staticProgramId, "aPosition")
        staticTexHandle = GLES20.glGetAttribLocation(staticProgramId, "aTextureCoord")
        staticTexScaleHandle = GLES20.glGetUniformLocation(staticProgramId, "uTexScale")
        staticFlipVHandle = GLES20.glGetUniformLocation(staticProgramId, "uFlipV")
        staticSamplerHandle = GLES20.glGetUniformLocation(staticProgramId, "sTexture")

        // Check compile status
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(fragmentShader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)

        if (compileStatus[0] == 0) {
            // Retrieve the error message
            val errorMsg = GLES20.glGetShaderInfoLog(fragmentShader)
            FileLogger.e(tag,"Fragment Shader compile error: $errorMsg")
            throw IllegalStateException("Fragment Shader compile error: $errorMsg")
        }

        GLES20.glGetShaderiv(vertexShader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            // Retrieve the error message
            val errorMsg = GLES20.glGetShaderInfoLog(vertexShader)
            FileLogger.e(tag,"Vertex Shader compile error: $errorMsg")
            throw IllegalStateException("Vertex Shader compile error: $errorMsg")
        }
        FileLogger.i(tag, "GL Initialized!")

    }

    private fun releaseGL() {
        // Release bridge-layer resources (must happen while the context is current).
        if (staticTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(staticTextureId), 0)
            staticTextureId = 0
        }
        if (captureFboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(captureFboId), 0)
            captureFboId = 0
        }
        staticEnabled = false
        pendingCapture = false
        hasPendingBitmap = false
        pendingStaticBitmap = null

        if (eglDisplay !== EGL10.EGL_NO_DISPLAY) {
            egl!!.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
            egl!!.eglDestroySurface(eglDisplay, eglSurface)
            egl!!.eglDestroyContext(eglDisplay, eglContext)
            egl!!.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL10.EGL_NO_DISPLAY
        eglContext = EGL10.EGL_NO_CONTEXT
        eglSurface = EGL10.EGL_NO_SURFACE
        videoSurface?.release()
        surfaceTexture?.release() // Release texture explicitly
        videoSurface = null
        surfaceTexture = null

        if (!videoSurfaceDeferred.isCompleted) {
            videoSurfaceDeferred.complete(null)
        }
        videoSurfaceDeferred = CompletableDeferred()
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }
}
