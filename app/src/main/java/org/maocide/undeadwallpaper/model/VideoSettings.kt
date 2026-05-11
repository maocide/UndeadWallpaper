package org.maocide.undeadwallpaper.model

import android.content.Context
import org.maocide.undeadwallpaper.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.math.pow

@Serializable
data class VideoSettings(
    val fileName: String,
    val scalingMode: ScalingMode = ScalingMode.FILL,
    val positionX: Float = 0.0f,
    val positionY: Float = 0.0f,
    val zoom: Float = 1.0f,
    val rotation: Float = 0.0f,
    val brightness: Float = 1.0f,
    val speed: Float = 1.0f,
    val volume: Float = 0.0f, // Replaces boolean `audioEnabled` with a float [0.0 - 1.0]
    val primaryColor: Int? = null,
    val secondaryColor: Int? = null,
    val tertiaryColor: Int? = null,
    val colorHints: Int? = null
) {
    fun getBreadcrumbText(context: Context): String? {
        val defaultSettings = VideoSettings(fileName)
        val changedLabels = mutableListOf<String>()

        if (scalingMode != defaultSettings.scalingMode) {
            val scalingLabel = when (scalingMode) {
                ScalingMode.FIT -> context.getString(R.string.scaling_mode_fit)
                ScalingMode.STRETCH -> context.getString(R.string.scaling_mode_stretch)
                else -> context.getString(R.string.scaling_mode_fill)
            }
            changedLabels.add(scalingLabel)
        }
        if (zoom != defaultSettings.zoom) {
            changedLabels.add(context.getString(R.string.breadcrumb_zoom))
        }
        if (positionX != defaultSettings.positionX || positionY != defaultSettings.positionY) {
            changedLabels.add(context.getString(R.string.breadcrumb_pan))
        }
        if (rotation != defaultSettings.rotation) {
            changedLabels.add(context.getString(R.string.breadcrumb_rotation))
        }
        if (brightness != defaultSettings.brightness) {
            changedLabels.add(context.getString(R.string.breadcrumb_brightness))
        }
        if (speed != defaultSettings.speed) {
            changedLabels.add(context.getString(R.string.breadcrumb_speed))
        }
        if (volume != defaultSettings.volume) {
            changedLabels.add(context.getString(R.string.breadcrumb_volume))
        }

        if (changedLabels.isEmpty()) {
            return null
        }

        val displayLabels = if (changedLabels.size > 3) {
            val top3 = changedLabels.take(3)
            top3 + context.getString(R.string.breadcrumb_more, changedLabels.size - 3)
        } else {
            changedLabels
        }

        return displayLabels.joinToString(" • ")
    }

    /**
     * Checks if this video shares the exact same OpenGL visual transforms as another video.
     * This is used to determine if ExoPlayer can gapless-transition between them without
     * a visual snap from the renderer matrix updating.
     */
    fun hasSameVisualTransformsAs(other: VideoSettings): Boolean {
        return this.scalingMode == other.scalingMode &&
                this.positionX == other.positionX &&
                this.positionY == other.positionY &&
                this.zoom == other.zoom &&
                this.rotation == other.rotation &&
                this.brightness == other.brightness
    }

    /**
     * Applies a quadratic curve to the linear volume slider value.
     * This mimics the logarithmic perception of human hearing.
     */
    fun getPerceivedVolume(): Float {
        return volume.toDouble().pow(2.0).toFloat()
    }
}
