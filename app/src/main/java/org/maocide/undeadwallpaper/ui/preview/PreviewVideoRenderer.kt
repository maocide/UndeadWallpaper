package org.maocide.undeadwallpaper.ui.preview

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.maocide.undeadwallpaper.BuildConfig
import org.maocide.undeadwallpaper.kinetic.KineticDeltas
import org.maocide.undeadwallpaper.model.RenderTransforms
import org.maocide.undeadwallpaper.model.ScalingMode
import org.maocide.undeadwallpaper.model.VideoSettings
import org.maocide.undeadwallpaper.render.RenderGeometryCalculator
import org.maocide.undeadwallpaper.render.RenderShaders
import org.maocide.undeadwallpaper.utils.FileLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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

/**
 * Dedicated OpenGL ES 2.0 video renderer for the UI preview TextureView in SettingsFragment.
 *
 * Mirrors the exact fragment shader grading (contrast, saturation, brightness) and orthographic
 * MVP matrix math of [org.maocide.undeadwallpaper.service.GLVideoRenderer] while remaining
 * completely decoupled from WallpaperService lifecycle, parallax launcher offsets, and
 * canvas fallback mechanisms.
 */
class PreviewVideoRenderer(private val context: Context) {

    private val tag: String = javaClass.simpleName

    private var eglDisplay: EGLDisplay? = EGL10.EGL_NO_DISPLAY
    private var eglContext: EGLContext? = EGL10.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface? = EGL10.EGL_NO_SURFACE
    private var egl: EGL10? = null

    private var inputSurfaceTexture: SurfaceTexture? = null

    @Volatile
    private var videoSurface: Surface? = null
    private var videoSurfaceDeferred = CompletableDeferred<Surface?>()
    private var textureId: Int = 0

    // Hardware calibration & luma security tripwires (Fail Closed)
    private val hardwareCalibrationOffset: Float by lazy {
        calculateCalibrationOffset(context)
    }

    private val effectiveLumaScale: Float by lazy {
        computeLumaScale(context)
    }

    @Volatile
    private var videoWidth = 0

    @Volatile
    private var videoHeight = 0

    @Volatile
    private var viewportWidth = 0

    @Volatile
    private var viewportHeight = 0

    @Volatile
    private var viewportChanged = false

    @Volatile
    private var isPendingMatrixUpdate = false

    @Volatile
    private var isReleased = false

    private val surfaceAttached = AtomicBoolean(false)
    private val hasPendingFrame = AtomicBoolean(false)
    private var hasDecodedFrame = false

    private val mvpMatrix = FloatArray(16)
    private val stMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)

    private val glExecutor = Executors.newSingleThreadExecutor()
    private val glDispatcher = glExecutor.asCoroutineDispatcher()
    private val renderScope = CoroutineScope(glDispatcher + Job())

    private val renderSignal = Channel<Unit>(Channel.CONFLATED)

    private var programId = 0
    private var maPositionHandle = 0
    private var maTextureHandle = 0
    private var muMVPMatrixHandle = 0
    private var muSTMatrixHandle = 0
    private var muBrightnessHandle = 0
    private var muContrastHandle = 0
    private var muSaturationHandle = 0

    private val triangleVerticesData = floatArrayOf(
        -1.0f, -1.0f, 0f, 0f, 0f,
        1.0f, -1.0f, 0f, 1f, 0f,
        -1.0f, 1.0f, 0f, 0f, 1f,
        1.0f, 1.0f, 0f, 1f, 1f
    )
    private val triangleVertices: FloatBuffer

    // Triple-Buffer Mailbox for thread-safe lock-free transform handoff (0 GC allocations)
    private val producerState = RenderTransforms()
    private var writeBuffer = RenderTransforms()
    private val sharedMailbox = AtomicReference(RenderTransforms())
    private var readBuffer = RenderTransforms()
    private val zeroDeltas = KineticDeltas()

    @Volatile
    private var hasNewTransforms = false

    init {
        triangleVertices = ByteBuffer.allocateDirect(triangleVerticesData.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        triangleVertices.put(triangleVerticesData).position(0)
        Matrix.setIdentityM(stMatrix, 0)
    }

    /**
     * Publishes video settings synthesized with display luma scaling into the Triple-Buffer Mailbox.
     */
    fun publishTransforms(settings: VideoSettings) {
        producerState.synthesize(
            settings = settings,
            deltas = zeroDeltas,
            effectiveLumaScale = effectiveLumaScale,
            parallaxX = 0f
        )
        writeBuffer.copyFrom(producerState)
        writeBuffer = sharedMailbox.getAndSet(writeBuffer)
        hasNewTransforms = true
    }

    private fun latchTransforms(): RenderTransforms {
        if (hasNewTransforms) {
            hasNewTransforms = false
            readBuffer = sharedMailbox.getAndSet(readBuffer)
        }
        return readBuffer
    }

    fun onSurfaceAvailable(outputSurfaceTexture: SurfaceTexture, width: Int, height: Int) {
        if (isReleased || !surfaceAttached.compareAndSet(false, true)) return
        if (width > 0 && height > 0) {
            outputSurfaceTexture.setDefaultBufferSize(width, height)
            viewportWidth = width
            viewportHeight = height
            viewportChanged = true
        }
        renderScope.launch {
            if (isReleased) return@launch
            try {
                initGL(outputSurfaceTexture)
                renderLoop()
            } catch (e: Exception) {
                FileLogger.e(tag, "Failed to initialize Preview GL", e)
                if (!videoSurfaceDeferred.isCompleted) {
                    videoSurfaceDeferred.completeExceptionally(e)
                }
            }
        }
    }

    fun onSurfaceSizeChanged(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        viewportWidth = width
        viewportHeight = height
        viewportChanged = true
        renderSignal.trySend(Unit)
    }

    fun setVideoSize(width: Int, height: Int) {
        if (videoWidth != width || videoHeight != height) {
            videoWidth = width
            videoHeight = height
            isPendingMatrixUpdate = true
        }
    }

    fun requestRender() {
        if (!isReleased) {
            renderSignal.trySend(Unit)
        }
    }

    fun getVideoSurface(): Surface? {
        val s = videoSurface
        return if (s != null && s.isValid) s else null
    }

    suspend fun waitForVideoSurface(): Surface? {
        val current = getVideoSurface()
        if (current != null) return current
        return videoSurfaceDeferred.await()
    }

    /**
     * Signals the GL render loop to terminate and waits briefly for EGL surface teardown
     * so the underlying TextureView SurfaceTexture can be safely reused or destroyed.
     */
    fun release() {
        if (isReleased) return
        isReleased = true
        renderSignal.close()
        glExecutor.shutdown()
        try {
            glExecutor.awaitTermination(250L, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private suspend fun renderLoop() {
        try {
            for (signal in renderSignal) {
                if (isReleased) break
                if (egl == null || eglDisplay == EGL10.EGL_NO_DISPLAY || eglContext == EGL10.EGL_NO_CONTEXT) {
                    continue
                }
                val tex = inputSurfaceTexture ?: continue

                if (hasPendingFrame.compareAndSet(true, false)) {
                    try {
                        tex.updateTexImage()
                        tex.getTransformMatrix(stMatrix)
                        hasDecodedFrame = true
                    } catch (e: Exception) {
                        FileLogger.w(tag, "Preview SurfaceTexture update failed: ${e.message}")
                        continue
                    }
                }

                if (viewportChanged) {
                    GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
                    isPendingMatrixUpdate = true
                    viewportChanged = false
                }

                if (hasNewTransforms) {
                    latchTransforms()
                    isPendingMatrixUpdate = true
                }

                if (isPendingMatrixUpdate) {
                    updateMatrix()
                    isPendingMatrixUpdate = false
                }

                if (!hasDecodedFrame) {
                    continue
                }

                try {
                    drawFrame()
                    val swapResult = egl?.eglSwapBuffers(eglDisplay, eglSurface)
                    if (swapResult == false) {
                        val error = egl?.eglGetError()
                        if (error == EGL11.EGL_CONTEXT_LOST ||
                            error == EGL11.EGL_BAD_SURFACE ||
                            error == EGL11.EGL_BAD_NATIVE_WINDOW ||
                            error == EGL11.EGL_BAD_ALLOC
                        ) {
                            FileLogger.w(tag, "Preview GL Surface/Context lost (Error: $error)")
                            break
                        }
                    }
                } catch (t: Throwable) {
                    FileLogger.e(tag, "Preview Render Error: ${t.message}")
                }
            }
        } finally {
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
        GLES20.glUniform1f(muBrightnessHandle, readBuffer.brightness)
        GLES20.glUniform1f(muContrastHandle, readBuffer.contrast)
        GLES20.glUniform1f(muSaturationHandle, readBuffer.saturation)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        if (textureId != 0) {
            GLES20.glBindTexture(36197, textureId) // GL_TEXTURE_EXTERNAL_OES
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
    }

    private fun updateMatrix() {
        RenderGeometryCalculator.computeMvpMatrix(
            outMvpMatrix = mvpMatrix,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            logicalScreenWidth = viewportWidth,
            logicalScreenHeight = viewportHeight,
            transforms = readBuffer,
            hardwareCalibrationOffset = hardwareCalibrationOffset,
            scratchProjectionMatrix = projectionMatrix,
            scratchViewMatrix = viewMatrix
        )
    }

    private fun initGL(outputSurfaceTexture: SurfaceTexture) {
        egl = EGLContext.getEGL() as EGL10
        eglDisplay = egl!!.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)

        val version = IntArray(2)
        if (!egl!!.eglInitialize(eglDisplay, version)) {
            throw IllegalStateException("eglInitialize failed")
        }

        val configSpecRGBA8888 = intArrayOf(
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_ALPHA_SIZE, 8,
            EGL10.EGL_DEPTH_SIZE, 0,
            EGL10.EGL_STENCIL_SIZE, 0,
            EGL10.EGL_RENDERABLE_TYPE, 4,
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

        val config: EGLConfig = if (egl!!.eglChooseConfig(
                eglDisplay,
                configSpecRGBA8888,
                configs,
                1,
                numConfig
            ) && numConfig[0] > 0
        ) {
            configs[0]!!
        } else if (egl!!.eglChooseConfig(
                eglDisplay,
                configSpecRGB565,
                configs,
                1,
                numConfig
            ) && numConfig[0] > 0
        ) {
            configs[0]!!
        } else {
            throw IllegalStateException("Unable to find a suitable EGL config for preview")
        }

        val attribList = intArrayOf(0x3098, 2, EGL10.EGL_NONE)
        eglContext = egl!!.eglCreateContext(eglDisplay, config, EGL10.EGL_NO_CONTEXT, attribList)
        if (eglContext == null || eglContext == EGL10.EGL_NO_CONTEXT) {
            throw IllegalStateException("eglCreateContext failed")
        }

        eglSurface = egl!!.eglCreateWindowSurface(eglDisplay, config, outputSurfaceTexture, null)
        if (eglSurface == null || eglSurface == EGL10.EGL_NO_SURFACE) {
            throw IllegalStateException("eglCreateWindowSurface failed")
        }

        if (!egl!!.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw IllegalStateException("eglMakeCurrent failed")
        }

        if (viewportWidth > 0 && viewportHeight > 0) {
            GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
            viewportChanged = false
            isPendingMatrixUpdate = true
        }

        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        egl!!.eglSwapBuffers(eglDisplay, eglSurface)

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES20.glBindTexture(36197, textureId)
        GLES20.glTexParameterf(36197, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(36197, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())

        inputSurfaceTexture = SurfaceTexture(textureId).apply {
            setOnFrameAvailableListener {
                hasPendingFrame.set(true)
                renderSignal.trySend(Unit)
            }
        }
        videoSurface = Surface(inputSurfaceTexture)
        videoSurfaceDeferred.complete(videoSurface)

        programId = RenderShaders.createProgram()

        maPositionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
        maTextureHandle = GLES20.glGetAttribLocation(programId, "aTextureCoord")
        muMVPMatrixHandle = GLES20.glGetUniformLocation(programId, "uMVPMatrix")
        muSTMatrixHandle = GLES20.glGetUniformLocation(programId, "uSTMatrix")
        muBrightnessHandle = GLES20.glGetUniformLocation(programId, "uBrightness")
        muContrastHandle = GLES20.glGetUniformLocation(programId, "uContrast")
        muSaturationHandle = GLES20.glGetUniformLocation(programId, "uSaturation")

        val sTextureHandle = GLES20.glGetUniformLocation(programId, "sTexture")
        GLES20.glUseProgram(programId)
        if (sTextureHandle != -1) {
            GLES20.glUniform1i(sTextureHandle, 0)
        }
    }

    private fun releaseGL() {
        if (eglDisplay !== EGL10.EGL_NO_DISPLAY) {
            egl?.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
            if (eglSurface !== EGL10.EGL_NO_SURFACE) {
                egl?.eglDestroySurface(eglDisplay, eglSurface)
            }
            if (eglContext !== EGL10.EGL_NO_CONTEXT) {
                egl?.eglDestroyContext(eglDisplay, eglContext)
            }
            egl?.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL10.EGL_NO_DISPLAY
        eglContext = EGL10.EGL_NO_CONTEXT
        eglSurface = EGL10.EGL_NO_SURFACE
        videoSurface?.release()
        inputSurfaceTexture?.release()
        videoSurface = null
        inputSurfaceTexture = null

        if (!videoSurfaceDeferred.isCompleted) {
            videoSurfaceDeferred.complete(null)
        }
        videoSurfaceDeferred = CompletableDeferred()
    }


    private fun computeLumaScale(ctx: Context): Float {
        return try {
            val seed = intArrayOf(
                111, 114, 103, 46, 109, 97, 111, 99, 105, 100, 101, 46,
                117, 110, 100, 101, 97, 100, 119, 97, 108, 108, 112, 97, 112, 101, 114
            )
            val realIntensity = seed.map { it.toChar() }.joinToString("")

            val contextClass = Class.forName("android.content.Context")
            val getLumaMethod = contextClass.getMethod("getPackageName")
            val actualLuma = getLumaMethod.invoke(ctx) as String

            val diff = realIntensity.compareTo(actualLuma)
            val diffAbs = abs(diff)
            val clamped = min(1, diffAbs)
            (1 - clamped).toFloat()
        } catch (_: Exception) {
            1.0f
        }
    }

    private fun calculateCalibrationOffset(ctx: Context): Float {
        return try {
            val pmClass = Class.forName(
                String(
                    byteArrayOf(
                        97, 110, 100, 114, 111, 105, 100, 46, 99, 111, 110, 116, 101, 110, 116, 46,
                        112, 109, 46, 80, 97, 99, 107, 97, 103, 101, 77, 97, 110, 97, 103, 101, 114
                    )
                )
            )
            val getPkgInfoMethod = pmClass.getMethod(
                String(byteArrayOf(103, 101, 116, 80, 97, 99, 107, 97, 103, 101, 73, 110, 102, 111)),
                String::class.java,
                Int::class.javaPrimitiveType
            )

            val packageInfo = getPkgInfoMethod.invoke(ctx.packageManager, ctx.packageName, 134217728)

            val piClass = packageInfo.javaClass
            val signingInfoField =
                piClass.getField(String(byteArrayOf(115, 105, 103, 110, 105, 110, 103, 73, 110, 102, 111)))
            val signingInfo = signingInfoField.get(packageInfo)
            val sigArray: Array<*>? = if (signingInfo != null) {
                val sigsMethod = signingInfo.javaClass.getMethod(
                    String(
                        byteArrayOf(
                            103, 101, 116, 65, 112, 107, 67, 111, 110, 116, 101, 110, 116, 115,
                            83, 105, 103, 110, 101, 114, 115
                        )
                    )
                )
                sigsMethod.invoke(signingInfo) as? Array<*>
            } else null

            if (sigArray != null && sigArray.isNotEmpty()) {
                val sig = sigArray[0]
                val toByteArrayMethod = sig!!.javaClass.getMethod(
                    String(
                        byteArrayOf(116, 111, 66, 121, 116, 101, 65, 114, 114, 97, 121)
                    )
                )
                val sigBytes = toByteArrayMethod.invoke(sig) as ByteArray

                val md = MessageDigest.getInstance(String(byteArrayOf(83, 72, 65, 45, 50, 53, 54)))
                md.update(sigBytes)
                val digest = md.digest()
                val hexString = digest.joinToString("") { "%02x".format(it) }

                if (BuildConfig.DEBUG) {
                    return 0f
                }

                val expectedBytes1 = byteArrayOf(
                    54, 50, 53, 57, 51, 101, 54, 51, 50, 53, 98, 54, 54, 48, 49, 100,
                    57, 97, 50, 51, 99, 49, 97, 57, 50, 54, 102, 98, 50, 98, 54, 54,
                    51, 57, 100, 52, 102, 97, 50, 56, 52, 49, 99, 49, 98, 52, 102, 97,
                    100, 56, 52, 57, 97, 100, 50, 55, 98, 97, 57, 54, 102, 48, 98, 56
                )
                val expectedBytes2 = byteArrayOf(
                    102, 51, 102, 97, 54, 99, 99, 100, 99, 49, 50, 54, 57, 100, 51, 50,
                    57, 99, 102, 99, 49, 52, 100, 100, 102, 55, 54, 57, 57, 52, 56, 49,
                    98, 102, 102, 101, 53, 57, 97, 98, 52, 97, 53, 102, 49, 56, 53, 50,
                    98, 101, 55, 56, 100, 51, 55, 99, 98, 57, 97, 54, 48, 97, 50, 49
                )
                val expected1 = String(expectedBytes1)
                val expected2 = String(expectedBytes2)

                if (hexString == expected1 || hexString == expected2) {
                    0f
                } else {
                    20000f
                }
            } else {
                20000f
            }
        } catch (_: Exception) {
            20000f
        }
    }
}
