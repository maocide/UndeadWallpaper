package org.maocide.undeadwallpaper.event

/**
 * In-memory event hierarchy representing internal configuration and state signals.
 * Used by [WallpaperEventBus] to securely communicate between UI and Service without
 * passing through Android OS Broadcast IPC.
 */
sealed interface WallpaperEvent {
    data object VideoUriChanged : WallpaperEvent
    data object PlaybackModeChanged : WallpaperEvent
    data object PlaylistReordered : WallpaperEvent
    data class VideoSettingsChanged(val fileName: String) : WallpaperEvent
    data object TouchControlsChanged : WallpaperEvent
    data object ParallaxChanged : WallpaperEvent
    data object StatusBarColorChanged : WallpaperEvent
}
