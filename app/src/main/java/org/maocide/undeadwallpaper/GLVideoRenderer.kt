package org.maocide.undeadwallpaper

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.maocide.undeadwallpaper.model.ScalingMode
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
        void main() {
          vec4 color = texture2D(sTexture, vTextureCoord);
          gl_FragColor = vec4(color.rgb + (uBrightness - 1.0) * 0.5, color.a);
        }
    """

    private var programId = 0
    private var maPositionHandle = 0
    private var maTextureHandle = 0
    private var muMVPMatrixHandle = 0
    private var muSTMatrixHandle = 0
    private var muBrightnessHandle = 0

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

    init {
        triangleVertices = ByteBuffer.allocateDirect(triangleVerticesData.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        triangleVertices.put(triangleVerticesData).position(0)
        Matrix.setIdentityM(stMatrix, 0)
    }

    fun setScalingMode(mode: ScalingMode) {
        if (currentScalingMode != mode) {
            Log.i(tag, "Scaling Mode changed to: $mode")
            currentScalingMode = mode
            updateMatrix()
        }
    }

    fun setTransforms(x: Float, y: Float, zoom: Float, rotation: Float) {
        userTranslateX = x
        userTranslateY = y
        userZoom = zoom
        userRotation = rotation
        updateMatrix()
    }

    fun setBrightness(brightness: Float) {
        userBrightness = brightness
    }

    fun onSurfaceCreated(holder: SurfaceHolder) {
        renderScope.launch {
            initGL(holder)
            renderLoop()
        }
    }

    fun release() {
        Log.i(tag, "Renderer Release Signal Received.")
        renderSignal.close()
        glExecutor.shutdown()
        // We do NOT call releaseGL here directly, we let the loop finish and clean up itself
        // or we risk thread collision.
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        // 1. Store Viewport (The Source of Truth for Orientation)
        viewportWidth = width
        viewportHeight = height

        // 2. Get Physical Metrics
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        val metrics = android.util.DisplayMetrics()
        display.getRealMetrics(metrics)

        // 3. ORIENTATION CORRECTION 🛡️
        // Sometimes 'metrics' reports Portrait dimensions even if the Surface is Landscape
        // (common on Tablets or locked Launchers).
        // We trust the Viewport's shape. If they disagree, we SWAP the metrics.

        val isViewportLandscape = width > height
        val isMetricsLandscape = metrics.widthPixels > metrics.heightPixels

        if (isViewportLandscape != isMetricsLandscape) {
            // Mismatch detected! Swap dimensions to match Viewport.
            screenWidth = metrics.heightPixels
            screenHeight = metrics.widthPixels
            Log.w(tag, "Orientation Mismatch! Swapped metrics to: ${screenWidth}x${screenHeight}")
        } else {
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }

        // 4. Signal Render Thread
        viewportChanged = true
        renderSignal.trySend(Unit)

        Log.i(tag, "Surface Changed: Viewport=${width}x${height}, LogicalScreen=${screenWidth}x${screenHeight}")
    }

    fun setVideoSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        updateMatrix()
    }

    suspend fun waitForVideoSurface(): Surface? {
        var limit = 80
        while (videoSurface == null && limit > 0) {
            kotlinx.coroutines.delay(100)
            limit--
        }
        return videoSurface
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
                    Log.w(tag, "Attempting to recover GL Context...")
                    // Might need to call initGL logic here or just
                    // continue and hope the surface is valid.
                    // Usually, just skipping the frame can be safe...
                    needsReinit = false
                }

                try {
                    // Update texture MUST happen on the thread with the EGL Context
                    surfaceTexture?.updateTexImage()
                    surfaceTexture?.getTransformMatrix(stMatrix)
                } catch (e: Exception) {
                    // This often happens when the video player is stopped/released
                    // but a frame signal was already in the pipe. Safe to ignore.
                    Log.w(tag, "SurfaceTexture update failed (Context lost?): ${e.message}")
                    continue
                }

                // CHECK FOR VIEWPORT UPDATES
                if (viewportChanged) {
                    GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
                    updateMatrix()
                    viewportChanged = false
                }

                // The Draw Call
                try {
                    drawFrame()
                    val swapResult = egl?.eglSwapBuffers(eglDisplay, eglSurface)

                    // Check if Swap failed (Context Lost)
                    if (swapResult == false) {
                        val error = egl?.eglGetError()
                        if (error == EGL11.EGL_CONTEXT_LOST) {
                            Log.e(tag, "GL Context Lost! triggering re-init.")
                            needsReinit = true
                            // You could trigger a full releaseGL() -> initGL() here if you want to be fancy
                        } else {
                            Log.w(tag, "eglSwapBuffers failed: $error")
                        }
                    }
                } catch (t: Throwable) {
                    // CATCH EVERYTHING here.
                    // Prevents a render error from crashing the whole service
                    Log.e(tag, "Critical Render Error: ${t.message}")
                }
            }
        } finally {
            Log.i(tag, "Render loop finished. Cleaning up GL on renderer thread.")
            releaseGL()
        }
    }

    private fun drawFrame() {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_COLOR_BUFFER_BIT)
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

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        if (textureId != 0) {
            GLES20.glBindTexture(36197, textureId) // GL_TEXTURE_EXTERNAL_OES
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
    }

    // The "Pixel Perfect" Math
    // Since we now run this on the correct thread, glViewport actually works,
    // so the logic will finally align correctly.
    private fun updateMatrix() {
        if (screenWidth == 0 || screenHeight == 0 || viewportWidth == 0 || viewportHeight == 0) {
            Matrix.setIdentityM(mvpMatrix, 0)
            return
        }

        // 1. SETUP PIXEL SPACE (Ortho)
        val left = -viewportWidth / 2f
        val right = viewportWidth / 2f
        val bottom = -viewportHeight / 2f
        val top = viewportHeight / 2f
        Matrix.orthoM(projectionMatrix, 0, left, right, bottom, top, -1f, 1f)

        // 2. CALCULATE GEOMETRY (Rotated Bounding Box)
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

        // 3. DETERMINE SCALING FACTORS
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

        // Apply User Zoom
        globalScaleX *= userZoom
        globalScaleY *= userZoom

        // 4. BUILD MODEL MATRIX
        Matrix.setIdentityM(viewMatrix, 0)

        // Translate
        val transX = userTranslateX * (screenWidth / 2f)
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

        // 5. COMBINE
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
    }

    private fun initGL(holder: SurfaceHolder) {
        egl = EGLContext.getEGL() as EGL10
        eglDisplay = egl!!.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)

        val version = IntArray(2)
        egl!!.eglInitialize(eglDisplay, version)

        val configSpec = intArrayOf(
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_ALPHA_SIZE, 8,
            EGL10.EGL_RENDERABLE_TYPE, 4,
            EGL10.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfig = IntArray(1)
        egl!!.eglChooseConfig(eglDisplay, configSpec, configs, 1, numConfig)
        val config = configs[0]

        val attribList = intArrayOf(0x3098, 2, EGL10.EGL_NONE)
        eglContext = egl!!.eglCreateContext(eglDisplay, config, EGL10.EGL_NO_CONTEXT, attribList)
        eglSurface = egl!!.eglCreateWindowSurface(eglDisplay, config, holder, null)

        egl!!.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

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

        // Check compile status
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(fragmentShader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        var failed = false

        if (compileStatus[0] == 0) {
            // Retrieve the error message
            val errorMsg = GLES20.glGetShaderInfoLog(fragmentShader)
            failed = true
            Log.e(tag,"Fragment Shader compile error: $errorMsg")
        }

        GLES20.glGetShaderiv(vertexShader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            // Retrieve the error message
            val errorMsg = GLES20.glGetShaderInfoLog(fragmentShader)
            failed = true
            Log.e(tag,"Vertex Shader compile error: $errorMsg")
        }
        if(!failed)
            Log.i(tag, "GL Initialized!")
        else
            Log.e(tag, "GL Error! ^^^")

    }

    private fun releaseGL() {
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
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }
}
