package org.maocide.undeadwallpaper.model

import kotlinx.serialization.Serializable

/**
 * Encapsulates dynamic buffering thresholds passed to ExoPlayer's DefaultLoadControl.
 */
@Serializable
data class BufferConfig(
    val targetBufferBytes: Int,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int
) {
    companion object {
        /**
         * Conservative baseline configuration matching the legacy static engine parameters.
         */
        val DEFAULT = BufferConfig(
            targetBufferBytes = 32 * 1024 * 1024, // 32MB
            minBufferMs = 15_000,
            maxBufferMs = 30_000,
            bufferForPlaybackMs = 2_500,
            bufferForPlaybackAfterRebufferMs = 5_000
        )
    }
}
