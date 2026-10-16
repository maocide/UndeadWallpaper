package org.maocide.undeadwallpaper.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import org.maocide.undeadwallpaper.R
import org.maocide.undeadwallpaper.utils.FileLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders the static CRT fallback frame directly onto the OpenGL ES surface.
 * Used during Type B Thanatosis when hardware video decoders (MediaCodec) are starved or exhausted,
 * rendering the quarantine warning with ZERO MediaCodec allocations.
 */
class ThanatosisOverlay(private val context: Context) {
    private val tag: String = javaClass.simpleName

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

    fun initGL(baseProgramId: Int = 0) {
        if (textureId != 0) return

        try {
            val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.thanatosis_static_frame)
                ?: throw IllegalStateException("Could not decode thanatosis_static_frame")

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
            bitmap.recycle()
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
                FileLogger.e(tag, "ThanatosisOverlay Program link error: $errorMsg")
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
                GLES20.glUniform1i(textureUniformHandle, 1) // Strictly texture unit 1
            }

            if (baseProgramId != 0) {
                GLES20.glUseProgram(baseProgramId)
            }

            FileLogger.i(tag, "ThanatosisOverlay initialized successfully on TEXTURE1 (TextureId: $textureId)")
        } catch (e: Exception) {
            FileLogger.e(tag, "Failed to initialize ThanatosisOverlay GL", e)
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

        // Center-crop geometry math:
        // Asset native aspect ratio: 600x480 = 1.25
        val imageAspect = 600f / 480f
        val screenAspect = viewportWidth.toFloat() / viewportHeight.toFloat()

        val drawWidth: Float
        val drawHeight: Float
        if (screenAspect > imageAspect) {
            drawWidth = viewportWidth.toFloat()
            drawHeight = drawWidth / imageAspect
        } else {
            drawHeight = viewportHeight.toFloat()
            drawWidth = drawHeight * imageAspect
        }

        val left = (viewportWidth - drawWidth) / 2f
        val top = (viewportHeight - drawHeight) / 2f
        val right = left + drawWidth
        val bottom = top + drawHeight

        verticesData[0] = left;  verticesData[1] = top;    verticesData[2] = 0f; verticesData[3] = 0f; verticesData[4] = 0f
        verticesData[5] = right; verticesData[6] = top;    verticesData[7] = 0f; verticesData[8] = 1f; verticesData[9] = 0f
        verticesData[10] = left;  verticesData[11] = bottom; verticesData[12] = 0f; verticesData[13] = 0f; verticesData[14] = 1f
        verticesData[15] = right; verticesData[16] = bottom; verticesData[17] = 0f; verticesData[18] = 1f; verticesData[19] = 1f

        vertices.position(0)
        vertices.put(verticesData).position(0)

        Matrix.orthoM(orthoMatrix, 0, 0f, viewportWidth.toFloat(), viewportHeight.toFloat(), 0f, -1f, 1f)

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

        // Always restore TEXTURE0 and the main video program
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        if (baseProgramId != 0) {
            GLES20.glUseProgram(baseProgramId)
        }
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
            FileLogger.e(tag, "ThanatosisOverlay Shader compile error ($type): $errorMsg")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}
