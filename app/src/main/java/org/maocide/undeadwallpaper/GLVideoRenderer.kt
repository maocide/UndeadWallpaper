package org.maocide.undeadwallpaper

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface

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

    // We use a single-threaded executor. This ensures every coroutine launch
    // in this scope runs on the EXACT same OS thread.
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
        void main() {
          gl_FragColor = texture2D(sTexture, vTextureCoord);
        }
    """

    private var programId = 0
    private var maPositionHandle = 0
    private var maTextureHandle = 0
    private var muMVPMatrixHandle = 0
    private var muSTMatrixHandle = 0

    private val triangleVerticesData = floatArrayOf(
        -1.0f, -1.0f, 0f, 0f, 0f,
        1.0f, -1.0f, 0f, 1f, 0f,
        -1.0f, 1.0f, 0f, 0f, 1f,
        1.0f, 1.0f, 0f, 1f, 1f
    )
    private var triangleVertices: FloatBuffer

    init {
        triangleVertices = ByteBuffer.allocateDirect(triangleVerticesData.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        triangleVertices.put(triangleVerticesData).position(0)
        Matrix.setIdentityM(stMatrix, 0)
    }

    /**
     * Start the GL Thread.
     */
    fun onSurfaceCreated(holder: SurfaceHolder) {
        // We launch on the glDispatcher to ensure initGL happens on the right thread
        renderScope.launch {
            initGL(holder)
            renderLoop()
        }
    }

    /**
     * Stop the GL Thread and clean up.
     */
    // We will let release() handle everything.
    fun onSurfaceDestroyed() {
        // We can leave this empty or use it to signal, but release() is safer.
    }

    /**
     * Call this to cleanly shutdown the executor when the service is truly done.
     */
    fun release() {
        Log.i(tag, "Renderer Release Signal Received.")
        // 1. Close the channel. This breaks the 'for' loop in renderLoop.
        renderSignal.close()

        // 2. Shut down the executor.
        // We wait a tiny bit? No, shutdown is fine.
        // But the finally block inside renderLoop might need a moment.
        // Actually, shutdown() allows the current task to finish. shutdownNow() interrupts.
        // Let's use shutdown() to let the finally block run gracefully.
        glExecutor.shutdown()
        Log.i(tag, "GLRenderer released and Executor shutdown")
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
        var limit = 80 // Increased wait time slightly
        while (videoSurface == null && limit > 0) {
            kotlinx.coroutines.delay(100)
            limit--
        }
        return videoSurface
    }

    private suspend fun renderLoop() {
        // SAFE CHANNEL LOOP:
        // This loop automatically breaks when the channel is closed!
        // No more exceptions.
        try {
            for (signal in renderSignal) {
                if (egl == null || eglDisplay == EGL10.EGL_NO_DISPLAY) continue

                try {
                    surfaceTexture?.updateTexImage()
                    surfaceTexture?.getTransformMatrix(stMatrix)
                } catch (e: Exception) {
                    Log.e(tag, "Error updating texture: ${e.message}")
                    continue
                }

                // Draw
                drawFrame()
                egl?.eglSwapBuffers(eglDisplay, eglSurface)
            }
        } finally {
            // THIS WILL RUN WHEN THE CHANNEL CLOSES OR SCOPE IS CANCELLED
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
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(36197, textureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun updateMatrix() {
        if (screenWidth == 0 || screenHeight == 0 || videoWidth == 0 || videoHeight == 0) {
            Matrix.setIdentityM(mvpMatrix, 0)
            return
        }

        val screenRatio = screenWidth.toFloat() / screenHeight.toFloat()
        val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()

        Matrix.setIdentityM(mvpMatrix, 0)

        // HARDCODED "FILL" (Center Crop)
        // Logic: Always ensure the video covers the screen dimension
        if (videoRatio > screenRatio) {
            // Video is wider -> Scale width to match height, then crop width
            // Correct logic for scaling to FILL:
            val scaleX = videoRatio / screenRatio
            Matrix.scaleM(mvpMatrix, 0, scaleX, 1f, 1f)
        } else {
            // Video is taller -> Scale height to match width, then crop height
            val scaleY = screenRatio / videoRatio
            Matrix.scaleM(mvpMatrix, 0, 1f, scaleY, 1f)
        }
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
            // Signal dispatch
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

        Log.i(tag, "GL Initialized!")
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
        surfaceTexture?.release()
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
