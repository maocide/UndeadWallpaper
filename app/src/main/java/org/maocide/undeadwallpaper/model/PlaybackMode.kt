package org.maocide.undeadwallpaper.model

import kotlinx.serialization.Serializable

// Stored in AppState now, not just SharedPreferences.
@Serializable
enum class PlaybackMode {
    LOOP,
    ONE_SHOT,
    LOOP_ALL,
    SHUFFLE
}
