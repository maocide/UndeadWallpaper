package org.maocide.undeadwallpaper.service

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import org.maocide.undeadwallpaper.utils.FileLogger

class PlaybackWatchdog(
    private val onStallDetected: () -> Unit
) {
    private val TAG: String = javaClass.simpleName

    private var player: Player? = null
    private var renderer: GLVideoRenderer? = null

    private var lastPosition: Long = 0
    private var lastRenderTimestamp: Long = 0
    private var stallCount: Int = 0

    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            checkPlaybackStall()
            // Re-run continuously while visible
            watchdogHandler.postDelayed(this, 2000) // Check every 2 seconds
        }
    }

    fun start(player: Player, renderer: GLVideoRenderer?) {
        stop() // Ensure we don't double-post

        this.player = player
        this.renderer = renderer

        watchdogHandler.post(watchdogRunnable)
    }

    fun stop() {
        watchdogHandler.removeCallbacks(watchdogRunnable)
        stallCount = 0

        // Clear references
        this.player = null
        this.renderer = null
    }

    private fun checkPlaybackStall() {
        val currentPlayer = player ?: return
        val currentRenderer = renderer ?: return

        // We only care if we SHOULD be playing
        if (currentPlayer.isPlaying && currentPlayer.playbackState == Player.STATE_READY) {
            val currentPos = currentPlayer.currentPosition
            val currentRenderTime = currentRenderer.getSurfaceDrawTimestamp()

            val isPlayerStuck = (currentPos == lastPosition)
            val isScreenFrozen = (currentRenderTime == lastRenderTimestamp)

            // If EITHER is true, the player is stuck with no error.
            if ((isPlayerStuck || isScreenFrozen) && currentPlayer.duration > 2000) {
                stallCount++
                FileLogger.w(TAG, "Watchdog: Stall detected! PlayerStuck=$isPlayerStuck, ScreenFrozen=$isScreenFrozen ($stallCount/2)")

                if (stallCount >= 2) { // Stalled for ~4 seconds
                    FileLogger.e(TAG, "Watchdog: STALL CONFIRMED. Triggering callback.")
                    stallCount = 0
                    onStallDetected()
                }
            } else {
                // It moved! Reset counters.
                stallCount = 0
                lastPosition = currentPos
                lastRenderTimestamp = currentRenderTime
            }
        } else {
            // If not playing or not ready, reset the watchdog counters to avoid false positives.
            stallCount = 0
            lastPosition = currentPlayer.currentPosition
            lastRenderTimestamp = currentRenderer.getSurfaceDrawTimestamp()
        }
    }
}
