package org.maocide.undeadwallpaper.model

import kotlinx.serialization.Serializable

// Stored in the app state now.
@Serializable
enum class StatusBarColor {
    AUTO,     // Automatically chosen
    LIGHT,    // Light forced
    DARK  // Dark forced
}
