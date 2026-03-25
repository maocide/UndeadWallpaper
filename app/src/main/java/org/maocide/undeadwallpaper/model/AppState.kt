package org.maocide.undeadwallpaper.model

import kotlinx.serialization.Serializable

// Central app state.
@Serializable
data class AppState(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val activeItemId: String? = null,
    val global: GlobalPlaybackState = GlobalPlaybackState(),
    val playlist: List<PlaylistItemState> = emptyList()
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

@Serializable
// Settings that still apply app-wide.
data class GlobalPlaybackState(
    val audioEnabled: Boolean = false,
    val playbackMode: PlaybackMode = PlaybackMode.LOOP,
    val startTime: StartTime = StartTime.RESUME,
    val statusBarColor: StatusBarColor = StatusBarColor.AUTO
)

@Serializable
// One playlist item, with its own state.
data class PlaylistItemState(
    val id: String,
    val fileName: String,
    val enabled: Boolean = true,
    val loopCount: Int = 1,
    val settings: ItemPlaybackSettings = ItemPlaybackSettings()
)

@Serializable
// Per-wallpaper playback and transform settings.
data class ItemPlaybackSettings(
    val scalingMode: ScalingMode = ScalingMode.FILL,
    val positionX: Float = 0f,
    val positionY: Float = 0f,
    val zoom: Float = 1f,
    val rotation: Float = 0f,
    val brightness: Float = 1f,
    val speed: Float = 1f
)
