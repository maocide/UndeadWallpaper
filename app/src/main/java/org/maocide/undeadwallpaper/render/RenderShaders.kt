package org.maocide.undeadwallpaper.render

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.opengl.GLES20
import org.maocide.undeadwallpaper.utils.FileLogger
import kotlin.math.abs

/**
 * Single Source of Truth for OpenGL ES 2.0 shaders and 2D canvas color grading.
 *
 * Unifies the Rec. 709 saturation weighting, mid-gray contrast curve, and brightness
 * formulas across both hardware GL fragment shaders and Android View ColorMatrix filters.
 */
object RenderShaders {

    private const val TAG = "RenderShaders"

    const val VERTEX_SHADER = """
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

    const val FRAGMENT_SHADER_OES = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform samplerExternalOES sTexture;
        uniform float uBrightness;
        uniform float uContrast;
        uniform float uSaturation;
        const vec3 lumaWeights = vec3(0.2126, 0.7152, 0.0722);
        void main() {
          vec4 color = texture2D(sTexture, vTextureCoord);
          vec3 rgb = color.rgb;
          float luma = dot(rgb, lumaWeights);
          rgb = mix(vec3(luma), rgb, uSaturation);
          rgb = (rgb - 0.5) * uContrast + 0.5;
          rgb += (uBrightness - 1.0) * 0.5;
          gl_FragColor = vec4(rgb, color.a);
        }
    """

    /**
     * Compiles a single shader stage (vertex or fragment) and verifies its compilation status.
     * Throws [IllegalStateException] on failure (Fail Fast and Loud).
     */
    fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) {
            throw IllegalStateException("glCreateShader failed for type $type")
        }
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val errorMsg = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            val typeStr = if (type == GLES20.GL_VERTEX_SHADER) "Vertex" else "Fragment"
            FileLogger.e(TAG, "$typeStr Shader compile error: $errorMsg")
            throw IllegalStateException("$typeStr Shader compile error: $errorMsg")
        }
        return shader
    }

    /**
     * Compiles and links the vertex and fragment shaders into a GL program.
     * Throws [IllegalStateException] if linking fails (Fail Fast and Loud).
     */
    fun createProgram(
        vertexSource: String = VERTEX_SHADER,
        fragmentSource: String = FRAGMENT_SHADER_OES
    ): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)

        val programId = GLES20.glCreateProgram()
        if (programId == 0) {
            throw IllegalStateException("glCreateProgram failed")
        }
        GLES20.glAttachShader(programId, vertexShader)
        GLES20.glAttachShader(programId, fragmentShader)
        GLES20.glLinkProgram(programId)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val errorMsg = GLES20.glGetProgramInfoLog(programId)
            GLES20.glDeleteProgram(programId)
            FileLogger.e(TAG, "GL Program link error: $errorMsg")
            throw IllegalStateException("GL Program link error: $errorMsg")
        }

        // Shaders are linked into program; flag them for deletion once program is detached
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        return programId
    }

    /**
     * 2D Android [ColorMatrixColorFilter] analytical equivalent of the GL fragment shader.
     *
     * Mathematically mirrors:
     * 1. Rec. 709 saturation luma weights (0.2126, 0.7152, 0.0722).
     * 2. Mid-gray contrast expansion: (rgb - 0.5) * contrast + 0.5.
     * 3. Brightness level shift: (brightness - 1.0) * 0.5.
     *
     * Net RGB offset in 8-bit space: 0.5 * (brightness - contrast) * 255.0.
     *
     * Returns null if brightness, contrast, and saturation are all at identity (1.0),
     * completely bypassing heap allocations in standard rendering.
     */
    fun createColorFilter(
        brightness: Float,
        contrast: Float,
        saturation: Float
    ): ColorMatrixColorFilter? {
        val hasBrightness = abs(brightness - 1.0f) > 0.001f
        val hasContrast = abs(contrast - 1.0f) > 0.001f
        val hasSaturation = abs(saturation - 1.0f) > 0.001f

        if (!hasBrightness && !hasContrast && !hasSaturation) {
            return null
        }

        val satMatrix = ColorMatrix()
        if (hasSaturation) {
            satMatrix.setSaturation(saturation)
        }

        return if (hasBrightness || hasContrast) {
            val scale = contrast
            val offset = 0.5f * (brightness - contrast) * 255.0f
            val cbMatrix = ColorMatrix(
                floatArrayOf(
                    scale, 0f, 0f, 0f, offset,
                    0f, scale, 0f, 0f, offset,
                    0f, 0f, scale, 0f, offset,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            if (hasSaturation) {
                cbMatrix.preConcat(satMatrix)
            }
            ColorMatrixColorFilter(cbMatrix)
        } else {
            ColorMatrixColorFilter(satMatrix)
        }
    }
}

