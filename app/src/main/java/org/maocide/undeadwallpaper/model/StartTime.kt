package org.maocide.undeadwallpaper.model

import kotlinx.serialization.Serializable

// Stored in the app state now.
@Serializable
enum class StartTime {
    RESUME,
    RESTART,
    RANDOM
}
