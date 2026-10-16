package org.maocide.undeadwallpaper.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maocide.undeadwallpaper.model.BufferConfig
import org.maocide.undeadwallpaper.model.PlaybackMode
import org.maocide.undeadwallpaper.model.VideoSettings

class BufferPolicyCalculatorTest {

    private val mb = 1024 * 1024

    @Test
    fun compute_nullActiveVideo_returnsDefault() {
        val config = BufferPolicyCalculator.compute(
            playbackMode = PlaybackMode.LOOP,
            activeVideo = null,
            playlist = emptyList()
        )
        assertEquals(BufferConfig.DEFAULT, config)
    }

    @Test
    fun compute_emptyPlaylistInLoopAll_returnsDefault() {
        val config = BufferPolicyCalculator.compute(
            playbackMode = PlaybackMode.LOOP_ALL,
            activeVideo = null,
            playlist = emptyList()
        )
        assertEquals(BufferConfig.DEFAULT, config)
    }

    @Test
    fun compute_loop_shortVideoLoop() {
        // 4 seconds, 5 MB file size (~10 Mbps)
        val shortVideo = VideoSettings(
            fileName = "short_loop.mp4",
            durationMs = 4_000L,
            expectedFileSize = 5_000_000L
        )

        val config = BufferPolicyCalculator.compute(
            playbackMode = PlaybackMode.LOOP,
            activeVideo = shortVideo,
            playlist = listOf(shortVideo)
        )

        // Short-loop profile verification
        assertEquals(4_000, config.minBufferMs)
        assertEquals(8_000, config.maxBufferMs)
        assertEquals(500, config.bufferForPlaybackMs)
        assertEquals(1_000, config.bufferForPlaybackAfterRebufferMs)

        // Target bytes: 10 Mbps * 8s * 1.2 = 12 MB
        assertTrue("Target bytes should be within guardrails", config.targetBufferBytes in (8 * mb)..(32 * mb))
        assertEquals(12_000_000, config.targetBufferBytes)
    }

    @Test
    fun compute_oneShot_shortVideoUsesSameTarget() {
        val shortVideo = VideoSettings(
            fileName = "one_shot.mp4",
            durationMs = 3_000L,
            expectedFileSize = 3_000_000L
        )

        val config = BufferPolicyCalculator.compute(
            playbackMode = PlaybackMode.ONE_SHOT,
            activeVideo = shortVideo,
            playlist = emptyList()
        )

        assertEquals(500, config.bufferForPlaybackMs)
        assertEquals(4_000, config.minBufferMs)
        assertEquals(8_000, config.maxBufferMs)
    }

    @Test
    fun compute_loop_longVideoUsesStandardProfile() {
        // 60 seconds, 30 MB file size (~4 Mbps)
        val longVideo = VideoSettings(
            fileName = "ambient_long.mp4",
            durationMs = 60_000L,
            expectedFileSize = 30_000_000L
        )

        val config = BufferPolicyCalculator.compute(
            playbackMode = PlaybackMode.LOOP,
            activeVideo = longVideo,
            playlist = listOf(longVideo)
        )

        // Standard profile verification
        assertEquals(8_000, config.minBufferMs)
        assertEquals(15_000, config.maxBufferMs)
        assertEquals(1_000, config.bufferForPlaybackMs)
        assertEquals(2_000, config.bufferForPlaybackAfterRebufferMs)

        // Clamped to 8MB floor since 4 Mbps * 15s * 1.2 = 9 MB
        assertTrue("Target bytes must respect 8MB floor", config.targetBufferBytes >= 8 * mb)
    }

    @Test
    fun compute_loopAll_allShortLoops() {
        val video1 = VideoSettings("loop1.mp4", durationMs = 4_000L, expectedFileSize = 5_000_000L, page = 0)
        val video2 = VideoSettings("loop2.mp4", durationMs = 6_000L, expectedFileSize = 8_000_000L, page = 0)

        val config = BufferPolicyCalculator.compute(
            playbackMode = PlaybackMode.LOOP_ALL,
            activeVideo = video1,
            playlist = listOf(video1, video2)
        )

        // When all videos are short loops, agile profile is selected
        assertEquals(4_000, config.minBufferMs)
        assertEquals(8_000, config.maxBufferMs)
        assertEquals(500, config.bufferForPlaybackMs)
    }

    @Test
    fun compute_loopAll_mixedDurationUsesStandardProfile() {
        val video1 = VideoSettings("loop1.mp4", durationMs = 4_000L, expectedFileSize = 5_000_000L, page = 0)
        val video2 = VideoSettings("long2.mp4", durationMs = 30_000L, expectedFileSize = 40_000_000L, page = 0)

        val config = BufferPolicyCalculator.compute(
            playbackMode = PlaybackMode.LOOP_ALL,
            activeVideo = video1,
            playlist = listOf(video1, video2)
        )

        // Mixed durations fallback to standard profile for safety
        assertEquals(8_000, config.minBufferMs)
        assertEquals(15_000, config.maxBufferMs)
        assertEquals(1_000, config.bufferForPlaybackMs)
    }

    @Test
    fun compute_ultraHeavy4KVideo_clampsTo32MBCap() {
        // High-bitrate 4K 60fps (~80 Mbps)
        val heavy4K = VideoSettings(
            fileName = "heavy_4k.mp4",
            durationMs = 10_000L,
            expectedFileSize = 100_000_000L // 100 MB in 10s = 80 Mbps
        )

        val config = BufferPolicyCalculator.compute(
            playbackMode = PlaybackMode.LOOP,
            activeVideo = heavy4K,
            playlist = listOf(heavy4K)
        )

        // Must be capped at exactly 32MB
        assertEquals(32 * mb, config.targetBufferBytes)
    }

    @Test
    fun compute_tinyLowResVideo_clampsTo8MBFloor() {
        // Tiny 240p clip (100 KB in 5s = 160 kbps)
        val tiny = VideoSettings(
            fileName = "tiny.mp4",
            durationMs = 5_000L,
            expectedFileSize = 100_000L
        )

        val config = BufferPolicyCalculator.compute(
            playbackMode = PlaybackMode.LOOP,
            activeVideo = tiny,
            playlist = listOf(tiny)
        )

        // Must be elevated to 8MB floor to prevent underflow
        assertEquals(8 * mb, config.targetBufferBytes)
    }

    @Test
    fun estimateBitrateBps_heuristicFallback() {
        val unknown4K = VideoSettings("4k.mp4", width = 3840, height = 2160, fps = 60)
        val bitrate = BufferPolicyCalculator.estimateBitrateBps(unknown4K)
        assertEquals(45_000_000L, bitrate)

        val unknown1080p = VideoSettings("1080p.mp4", width = 1080, height = 1920, fps = 30)
        val bitrate1080p = BufferPolicyCalculator.estimateBitrateBps(unknown1080p)
        assertEquals(12_000_000L, bitrate1080p)

        // 59fps (e.g. 59.94 truncated to integer) must trigger high-framerate 18Mbps tier
        val truncatedNTSC = VideoSettings("ntsc_60.mp4", width = 1080, height = 1920, fps = 59)
        val bitrate59 = BufferPolicyCalculator.estimateBitrateBps(truncatedNTSC)
        assertEquals(18_000_000L, bitrate59)
    }
}
