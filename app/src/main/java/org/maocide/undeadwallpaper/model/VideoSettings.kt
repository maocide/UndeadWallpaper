package org.maocide.undeadwallpaper.model

import kotlinx.serialization.Serializable

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
    val volume: Float = 0.0f // Replaces boolean `audioEnabled` with a float [0.0 - 1.0]
)
