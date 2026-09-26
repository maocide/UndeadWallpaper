package org.maocide.undeadwallpaper.event

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * High-performance, in-memory event bus backed by Kotlin Coroutines.
 */
object WallpaperEventBus {
    private val _events = MutableSharedFlow<WallpaperEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Shared flow of [WallpaperEvent] signals to collect within lifecycle or service scopes.
     */
    val events = _events.asSharedFlow()

    /**
     * Emits a [WallpaperEvent] synchronously into the shared flow buffer.
     */
    fun emit(event: WallpaperEvent) {
        _events.tryEmit(event)
    }
}
