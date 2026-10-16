package org.maocide.undeadwallpaper.service

import org.maocide.undeadwallpaper.model.BufferConfig
import org.maocide.undeadwallpaper.model.PlaybackMode
import org.maocide.undeadwallpaper.model.VideoSettings
import org.maocide.undeadwallpaper.utils.FileLogger

/**
 * Computes optimal, hardware-aware ExoPlayer buffer dimensions based on the active playback mode,
 * video duration, and media bitrates.
 *
 * For single-clip modes (LOOP, ONE_SHOT), buffer parameters are tailored exclusively to the active video.
 * For queue modes (LOOP_ALL, SHUFFLE), buffer parameters accommodate the peak bitrate in the active playlist.
 */
object BufferPolicyCalculator {
    private const val TAG = "BufferPolicyCalculator"

    // Guardrail constraints
    private const val MIN_BUFFER_BYTES = 8 * 1024 * 1024 // 8 MB floor
    private const val MAX_BUFFER_BYTES = 32 * 1024 * 1024 // 32 MB ceiling
    private const val DEFAULT_ESTIMATED_BITRATE_BPS = 10_000_000L // 10 Mbps baseline

    fun compute(
        playbackMode: PlaybackMode,
        activeVideo: VideoSettings?,
        playlist: List<VideoSettings>
    ): BufferConfig {
        // Mode 1: Single-video targets (LOOP, ONE_SHOT)
        if (playbackMode == PlaybackMode.LOOP || playbackMode == PlaybackMode.ONE_SHOT) {
            val target = activeVideo
            if (target == null) {
                FileLogger.i(TAG, "[$playbackMode] Active video is null. Using default buffer config.")
                return BufferConfig.DEFAULT
            }
            return computeForSingleVideo(playbackMode, target)
        }

        // Mode 2: Multi-video / playlist targets (LOOP_ALL, SHUFFLE)
        val activePage = activeVideo?.page ?: 0
        val pageVideos = playlist.filter { it.page == activePage }
        val candidateVideos = if (pageVideos.isNotEmpty()) pageVideos else playlist

        if (candidateVideos.isEmpty()) {
            FileLogger.i(TAG, "[$playbackMode] Playlist is empty. Using default buffer config.")
            return BufferConfig.DEFAULT
        }

        return computeForPlaylist(playbackMode, candidateVideos)
    }

    private fun computeForSingleVideo(mode: PlaybackMode, video: VideoSettings): BufferConfig {
        val durationMs = video.durationMs
        val bitrateBps = estimateBitrateBps(video)
        val isShortLoop = durationMs != null && durationMs in 1..10_000L

        val (minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferAfterRebufferMs) = if (isShortLoop) {
            // Agile short-loop profile (sub-second playback startup)
            Profile(
                minBufferMs = 4_000,
                maxBufferMs = 8_000,
                bufferForPlaybackMs = 500,
                bufferAfterRebufferMs = 1_000
            )
        } else {
            // Standard video profile
            Profile(
                minBufferMs = 8_000,
                maxBufferMs = 15_000,
                bufferForPlaybackMs = 1_000,
                bufferAfterRebufferMs = 2_000
            )
        }

        val targetBytes = calculateTargetBytes(bitrateBps, maxBufferMs)
        val config = BufferConfig(
            targetBufferBytes = targetBytes,
            minBufferMs = minBufferMs,
            maxBufferMs = maxBufferMs,
            bufferForPlaybackMs = bufferForPlaybackMs,
            bufferForPlaybackAfterRebufferMs = bufferAfterRebufferMs
        )

        val bitrateMbps = String.format(java.util.Locale.US, "%.1f", bitrateBps / 1_000_000.0)
        val durationSec = durationMs?.let { String.format(java.util.Locale.US, "%.1fs", it / 1000.0) } ?: "unknown"
        val bufferMb = targetBytes / (1024 * 1024)

        FileLogger.i(
            TAG,
            "[$mode] '${video.fileName}' ($durationSec, ${bitrateMbps} Mbps) -> Buffer: ${bufferMb}MB (Min: ${minBufferMs}ms, Max: ${maxBufferMs}ms, Start: ${bufferForPlaybackMs}ms)"
        )

        return config
    }

    private fun computeForPlaylist(mode: PlaybackMode, videos: List<VideoSettings>): BufferConfig {
        // Worst-case bitrate in the active playlist
        val maxBitrateBps = videos.maxOfOrNull { estimateBitrateBps(it) } ?: DEFAULT_ESTIMATED_BITRATE_BPS

        // Check if all known videos in the playlist are short loops
        val allShortLoops = videos.all { it.durationMs != null && it.durationMs in 1..10_000L }

        val (minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferAfterRebufferMs) = if (allShortLoops) {
            Profile(
                minBufferMs = 4_000,
                maxBufferMs = 8_000,
                bufferForPlaybackMs = 500,
                bufferAfterRebufferMs = 1_000
            )
        } else {
            Profile(
                minBufferMs = 8_000,
                maxBufferMs = 15_000,
                bufferForPlaybackMs = 1_000,
                bufferAfterRebufferMs = 2_000
            )
        }

        val targetBytes = calculateTargetBytes(maxBitrateBps, maxBufferMs)
        val config = BufferConfig(
            targetBufferBytes = targetBytes,
            minBufferMs = minBufferMs,
            maxBufferMs = maxBufferMs,
            bufferForPlaybackMs = bufferForPlaybackMs,
            bufferForPlaybackAfterRebufferMs = bufferAfterRebufferMs
        )

        val peakMbps = String.format(java.util.Locale.US, "%.1f", maxBitrateBps / 1_000_000.0)
        val bufferMb = targetBytes / (1024 * 1024)

        FileLogger.i(
            TAG,
            "[$mode] Items: ${videos.size}, Peak: ${peakMbps} Mbps -> Buffer: ${bufferMb}MB (Min: ${minBufferMs}ms, Max: ${maxBufferMs}ms, Start: ${bufferForPlaybackMs}ms)"
        )

        return config
    }

    fun estimateBitrateBps(video: VideoSettings): Long {
        val fileSize = video.expectedFileSize
        val durationMs = video.durationMs

        if (fileSize != null && fileSize > 0 && durationMs != null && durationMs > 0) {
            val bps = (fileSize * 8L * 1000L) / durationMs
            if (bps > 0) return bps
        }

        // Heuristic fallback based on resolution and fps
        val w = video.width ?: 0
        val h = video.height ?: 0
        val fps = video.fps ?: 30
        val pixels = w * h

        return when {
            pixels >= 3840 * 2160 -> if (fps >= 59) 45_000_000L else 30_000_000L // 4K (covers 59.94 / 60 fps)
            pixels >= 1920 * 1080 -> if (fps >= 59) 18_000_000L else 12_000_000L // 1080p
            pixels >= 1280 * 720  -> if (fps >= 59) 8_000_000L  else 5_000_000L  // 720p
            else -> DEFAULT_ESTIMATED_BITRATE_BPS
        }
    }

    private fun calculateTargetBytes(bitrateBps: Long, maxBufferMs: Int): Int {
        // Required bytes for maxBufferMs: (bitrateBps * maxBufferMs) / (8 * 1000)
        // Add a 20% safety margin for VBR spikes
        val neededBytes = ((bitrateBps * maxBufferMs.toLong() * 12L) / (10L * 8L * 1000L))
        return neededBytes.coerceIn(MIN_BUFFER_BYTES.toLong(), MAX_BUFFER_BYTES.toLong()).toInt()
    }

    private data class Profile(
        val minBufferMs: Int,
        val maxBufferMs: Int,
        val bufferForPlaybackMs: Int,
        val bufferAfterRebufferMs: Int
    )
}
