package org.maocide.undeadwallpaper.model

import kotlinx.serialization.Serializable

// Stored with per-item settings now.
@Serializable
enum class ScalingMode {
    FIT,     // Letterbox (Black bars)
    FILL,    // Zoom/Crop (No black bars)
    STRETCH  // Deform (Distort to fit)
}
