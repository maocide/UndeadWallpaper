package org.maocide.undeadwallpaper

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
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
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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

    // Scaling Logic
    private var screenWidth = 0
    private var screenHeight = 0
    private var videoWidth = 0
    private var videoHeight = 0
    private val mvpMatrix = FloatArray(16)
    private val stMatrix = FloatArray(16)

    private val glExecutor = Executors.newSingleThreadExecutor()
    private val glDispatcher = glExecutor.asCoroutineDispatcher()
    private val renderScope = CoroutineScope(glDispatcher + Job())

    // Trigger signal
    private val renderSignal = Channel<Unit>(Channel.CONFLATED)

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

    fun onSurfaceDestroyed() {
        // Handled by release()
    }

    fun release() {
        Log.i(tag, "Renderer Release Signal Received.")
        renderSignal.close()
        glExecutor.shutdown()
        // We do NOT call releaseGL here directly, we let the loop finish and clean up itself
        // or we risk thread collision.
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
        updateMatrix()
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
        try {
            for (signal in renderSignal) {
                // SECURITY GUARD 🛡️: Prevent 0x502 Invalid Operation
                if (egl == null || eglDisplay == EGL10.EGL_NO_DISPLAY || eglContext == EGL10.EGL_NO_CONTEXT) {
                    continue
                }

                // If the surface texture is released, don't try to update it
                if (surfaceTexture == null) continue

                try {
                    // Update texture MUST happen on the thread with the EGL Context
                    surfaceTexture?.updateTexImage()
                    surfaceTexture?.getTransformMatrix(stMatrix)
                } catch (e: Exception) {
                    Log.w(tag, "SurfaceTexture update failed (Context lost?): ${e.message}")
                    continue
                }

                drawFrame()
                egl?.eglSwapBuffers(eglDisplay, eglSurface)
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

    private fun updateMatrix() {
        // If we have zero-dimensions, don't do math.
        if (screenWidth == 0 || screenHeight == 0 || videoWidth == 0 || videoHeight == 0) {
            Matrix.setIdentityM(mvpMatrix, 0)
            return
        }

        // Calculate Ratios
        val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()
        val screenRatio = screenWidth.toFloat() / screenHeight.toFloat()

        Matrix.setIdentityM(mvpMatrix, 0)

        // Pan (Translation)
        // We apply this first so "moving right" moves the image right on the screen
        Matrix.translateM(mvpMatrix, 0, userTranslateX, userTranslateY, 0f)

        // Zoom
        // Simple scaling around the center
        Matrix.scaleM(mvpMatrix, 0, userZoom, userZoom, 1f)

        if (currentScalingMode == ScalingMode.STRETCH) {
            // STRETCH means "Map video corners to screen corners."
            // Since our base geometry acts as -1 to 1, and the screen is -1 to 1,
            Log.d(tag, "Matrix Update: Mode=$currentScalingMode, VideoRatio=$videoRatio, ScreenRatio=$screenRatio")
            return
        }

        // Aspect Ratio Correction (The "De-Squush" Phase)
        // Give the video its correct physical width relative to height (1.0)
        Matrix.scaleM(mvpMatrix, 0, videoRatio, 1f, 1f)

        // Normalize against the screen's aspect ratio
        // This ensures a square object actually looks square on the device screen
        Matrix.scaleM(mvpMatrix, 0, 1f/screenRatio, 1f, 1f)

        // Automatic Scaling (Fit/Fill)
        // We calculate how much we need to scale X and Y to match the screen boundaries.
        // Our "Object" width is videoRatio. Screen width is screenRatio.
        val scaleX = screenRatio / videoRatio
        val scaleY = 1.0f // Vertical is already normalized to 1.0 vs 1.0

        var autoScale = 1.0f

        if (currentScalingMode == ScalingMode.FILL) {
            // FILL: maximizing scale so the image COVERS the screen (Crop)
            autoScale = max(scaleX, scaleY)
        } else {
            // FIT: minimizing scale so the image FITS INSIDE the screen (Letterbox)
            autoScale = min(scaleX, scaleY)
        }

        // Apply the auto-fit factor
        Matrix.scaleM(mvpMatrix, 0, autoScale, autoScale, 1f)

        Log.d(tag, "Matrix Update: Mode=$currentScalingMode, VideoRatio=$videoRatio, ScreenRatio=$screenRatio, FinalScale=$autoScale")
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
