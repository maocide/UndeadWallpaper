package org.maocide.undeadwallpaper.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import androidx.core.content.ContextCompat
import org.maocide.undeadwallpaper.R
import org.maocide.undeadwallpaper.utils.FileLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.random.Random

/**
 * Encapsulates the 2D branding watermark overlay rendered strictly in Preview mode.
 * Self-contained GL pipeline using GL_TEXTURE1 to eliminate texture unit contention
 * with the external video sampler on GL_TEXTURE0.
 */
class PreviewBadgeOverlay(private val context: Context) {
    private val tag: String = javaClass.simpleName

    @Volatile
    var slotTop: Boolean = true
        private set

    @Volatile
    var jitterX: Float = 0f
        private set

    @Volatile
    var jitterY: Float = 0f
        private set

    private var programId = 0
    private var positionHandle = 0
    private var textureCoordHandle = 0
    private var mvpMatrixHandle = 0
    private var textureUniformHandle = 0
    private var textureId = 0

    private val orthoMatrix = FloatArray(16)
    private val verticesData = FloatArray(20)
    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(20 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec2 aTextureCoord;
        uniform mat4 uMVPMatrix;
        varying vec2 vTextureCoord;
        void main() {
          gl_Position = uMVPMatrix * aPosition;
          vTextureCoord = aTextureCoord;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform sampler2D uTexture;
        void main() {
          gl_FragColor = texture2D(uTexture, vTextureCoord);
        }
    """.trimIndent()

    fun randomizePlacement() {
        slotTop = Random.nextBoolean()
        jitterX = Random.nextInt(-15, 16).toFloat()
        jitterY = Random.nextInt(-15, 16).toFloat()
        FileLogger.i(
            tag,
            "Badge Preview Mode Active. SlotTop: $slotTop, Jitter: ($jitterX, $jitterY)"
        )
    }

    fun initGL(baseProgramId: Int = 0) {
        if (textureId != 0) return

        try {
            val bitmap = getOrCreateBadgeBitmap(context)

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
            val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

            programId = GLES20.glCreateProgram()
            GLES20.glAttachShader(programId, vertexShader)
            GLES20.glAttachShader(programId, fragmentShader)
            GLES20.glLinkProgram(programId)

            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val errorMsg = GLES20.glGetProgramInfoLog(programId)
                FileLogger.e(tag, "Badge Program link error: $errorMsg")
                GLES20.glDeleteProgram(programId)
                programId = 0
                return
            }

            positionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
            textureCoordHandle = GLES20.glGetAttribLocation(programId, "aTextureCoord")
            mvpMatrixHandle = GLES20.glGetUniformLocation(programId, "uMVPMatrix")
            textureUniformHandle = GLES20.glGetUniformLocation(programId, "uTexture")

            GLES20.glUseProgram(programId)
            if (textureUniformHandle != -1) {
                GLES20.glUniform1i(textureUniformHandle, 1) // Strictly unit 1
            }

            if (baseProgramId != 0) {
                GLES20.glUseProgram(baseProgramId)
            }

            FileLogger.i(
                tag,
                "Badge GL initialized successfully on TEXTURE1. TextureId: $textureId, SlotTop: $slotTop"
            )
        } catch (e: Exception) {
            FileLogger.e(tag, "Failed to initialize Badge GL", e)
            textureId = 0
            programId = 0
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            if (baseProgramId != 0) {
                GLES20.glUseProgram(baseProgramId)
            }
        }
    }

    fun draw(viewportWidth: Int, viewportHeight: Int, baseProgramId: Int) {
        if (textureId == 0) {
            initGL(baseProgramId)
        }
        if (textureId == 0 || programId == 0 || viewportWidth == 0 || viewportHeight == 0) return

        val density = context.resources.displayMetrics.density
        val bounds = calculateBadgeBounds(viewportWidth, viewportHeight, density, slotTop, jitterX, jitterY)

        verticesData[0] = bounds.left;  verticesData[1] = bounds.top;    verticesData[2] = 0f; verticesData[3] = 0f; verticesData[4] = 0f
        verticesData[5] = bounds.right; verticesData[6] = bounds.top;    verticesData[7] = 0f; verticesData[8] = 1f; verticesData[9] = 0f
        verticesData[10] = bounds.left;  verticesData[11] = bounds.bottom; verticesData[12] = 0f; verticesData[13] = 0f; verticesData[14] = 1f
        verticesData[15] = bounds.right; verticesData[16] = bounds.bottom; verticesData[17] = 0f; verticesData[18] = 1f; verticesData[19] = 1f

        vertices.position(0)
        vertices.put(verticesData).position(0)

        Matrix.orthoM(orthoMatrix, 0, 0f, viewportWidth.toFloat(), viewportHeight.toFloat(), 0f, -1f, 1f)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUseProgram(programId)

        vertices.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 20, vertices)
        GLES20.glEnableVertexAttribArray(positionHandle)

        vertices.position(3)
        GLES20.glVertexAttribPointer(textureCoordHandle, 2, GLES20.GL_FLOAT, false, 20, vertices)
        GLES20.glEnableVertexAttribArray(textureCoordHandle)

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, orthoMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(textureUniformHandle, 1)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(textureCoordHandle)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glDisable(GLES20.GL_BLEND)

        // Always restore TEXTURE0 and the main video program
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUseProgram(baseProgramId)
    }

    fun release() {
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        if (programId != 0) {
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val errorMsg = GLES20.glGetShaderInfoLog(shader)
            FileLogger.e(tag, "Badge Shader compile error ($type): $errorMsg")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    data class BadgeBounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val width: Float,
        val height: Float
    )

    companion object {
        @Volatile
        private var cachedBadgeBitmap: Bitmap? = null

        fun calculateBadgeBounds(
            viewportWidth: Int,
            viewportHeight: Int,
            density: Float,
            slotTop: Boolean,
            jitterX: Float = 0f,
            jitterY: Float = 0f
        ): BadgeBounds {
            val badgeWidthPx = (340f * density).coerceAtMost(viewportWidth * 0.90f)
            val badgeHeightPx = (68f * density).coerceAtMost(viewportHeight * 0.15f)

            val left = ((viewportWidth - badgeWidthPx) / 2f) + jitterX
            val right = left + badgeWidthPx

            val top = if (slotTop) {
                // Upper Safe Zone: clear system status bar + LivePicker toolbar (~115dp)
                val baseTop = (viewportHeight * 0.135f).coerceAtLeast(115f * density)
                baseTop + jitterY
            } else {
                // Lower-Third Safe Zone: rest cleanly above the LivePicker bottom sheet
                val baseTop = viewportHeight * 0.705f
                baseTop + jitterY
            }
            val bottom = top + badgeHeightPx

            return BadgeBounds(left, top, right, bottom, badgeWidthPx, badgeHeightPx)
        }

        fun getOrCreateBadgeBitmap(context: Context): Bitmap {
            val existing = cachedBadgeBitmap
            if (existing != null && !existing.isRecycled) {
                return existing
            }
            synchronized(this) {
                val current = cachedBadgeBitmap
                if (current != null && !current.isRecycled) {
                    return current
                }
                val created = createBadgeBitmap(context.applicationContext)
                cachedBadgeBitmap = created
                return created
            }
        }

        private fun createBadgeBitmap(context: Context): Bitmap {
            val density = context.resources.displayMetrics.density
            val widthPx = (340f * density).toInt().coerceAtLeast(1)
            val heightPx = (68f * density).toInt().coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val pillRadius = heightPx / 2f
            val strokeWidth = 2f * density

            // Obsidian pill background (88% alpha)
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0E1012")
                alpha = 224
                style = Paint.Style.FILL
            }
            val pillRect = RectF(
                strokeWidth / 2f,
                strokeWidth / 2f,
                widthPx - (strokeWidth / 2f),
                heightPx - (strokeWidth / 2f)
            )
            canvas.drawRoundRect(pillRect, pillRadius, pillRadius, bgPaint)

            // Undead Lime / Icon accent border (#3DDC84 matching app icon background)
            val accentColor = Color.parseColor("#3DDC84")
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accentColor
                style = Paint.Style.STROKE
                this.strokeWidth = strokeWidth
            }
            canvas.drawRoundRect(pillRect, pillRadius, pillRadius, borderPaint)

            // Round Zombillie Avatar
            val pad = heightPx * 0.12f
            val iconSize = heightPx - (2f * pad)
            val iconLeft = pad
            val iconTop = pad
            val iconRight = iconLeft + iconSize
            val iconBottom = iconTop + iconSize
            val iconRadius = iconSize / 2f
            val iconCenterX = iconLeft + iconRadius
            val iconCenterY = iconTop + iconRadius

            val iconDrawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher_round)
                ?: ContextCompat.getDrawable(context, R.mipmap.ic_launcher)

            if (iconDrawable != null) {
                val clipPath = Path().apply {
                    addCircle(iconCenterX, iconCenterY, iconRadius, Path.Direction.CW)
                }
                canvas.save()
                canvas.clipPath(clipPath)
                iconDrawable.setBounds(iconLeft.toInt(), iconTop.toInt(), iconRight.toInt(), iconBottom.toInt())
                iconDrawable.draw(canvas)
                canvas.restore()

                // Avatar ring
                val avatarRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accentColor
                    style = Paint.Style.STROKE
                    this.strokeWidth = 1.5f * density
                }
                canvas.drawCircle(iconCenterX, iconCenterY, iconRadius, avatarRingPaint)
            }

            // Typography
            val textLeft = iconRight + (12f * density)

            // Title: UNDEAD WALLPAPER
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#F5F5F5")
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textSize = heightPx * 0.28f
            }
            val titleBaseline = heightPx * 0.44f
            canvas.drawText("UNDEAD WALLPAPER", textLeft, titleBaseline, titlePaint)

            // Subtitle: L̶I̶V̶E̶ GL U̶N̶D̶E̶A̶D̶ ENGINE
            val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accentColor
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                textSize = heightPx * 0.20f
            }
            val subBaseline = heightPx * 0.76f
            canvas.drawText(
                "L\u0336I\u0336V\u0336E\u0336 GL U\u0336N\u0336D\u0336E\u0336A\u0336D\u0336 ENGINE",
                textLeft,
                subBaseline,
                subPaint
            )

            return bitmap
        }
    }
}
