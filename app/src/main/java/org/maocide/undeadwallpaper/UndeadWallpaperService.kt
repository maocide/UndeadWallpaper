package org.maocide.undeadwallpaper

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer

class UndeadWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine {
        return MyWallpaperEngine()
    }

    private inner class MyWallpaperEngine : Engine() {

        private var mediaPlayer: ExoPlayer? = null
        private var surfaceHolder: SurfaceHolder? = null
        private var playheadTime: Long = 0L
        private val TAG: String = javaClass.simpleName

        @OptIn(UnstableApi::class)
        private fun initializePlayer() {
            if (mediaPlayer != null) {
                releasePlayer()
            }

            val holder = surfaceHolder
            if (holder == null) {
                Log.w(TAG, "Cannot initialize player: surface is not ready.")
                return
            }

            Log.i(TAG, "Initializing ExoPlayer...")

            // <<< NEW: Custom LoadControl for resource-constrained environments like a wallpaper >>>
            // This is the key to fixing the crash. We are telling ExoPlayer to use smaller buffers.
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    15 * 1000, // min buffer
                    30 * 1000, // max buffer
                    500,       // buffer for playback after rebuffer
                    100        // buffer for playback after initial start
                )
                .setTargetBufferBytes(DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES / 4) // Use a fraction of the default
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            val player = ExoPlayer.Builder(baseContext)
                // <<< APPLY THE CUSTOM LOAD CONTROL >>>
                .setLoadControl(loadControl)
                .build().apply {
                    val mediaItem = MediaItem.fromUri(getMediaUri())
                    setMediaItem(mediaItem)
                    // Using REPEAT_MODE_ONE is generally seamless in modern ExoPlayer.
                    // If you still see a pause, your custom loop is a good fallback.
                    repeatMode = Player.REPEAT_MODE_ONE
                    videoScalingMode = VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING

                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            // Your custom looping logic can still live here if needed
                            if (state == Player.STATE_ENDED) {
                                Log.d(TAG, "STATE_ENDED received, seeking to start.")
                                // A simpler way to loop without re-creating the player
                                this@apply.seekTo(0)
                                this@apply.play()
                            }
                        }
                    })

                    volume = 0f
                    seekTo(playheadTime)
                    setVideoSurface(holder.surface)
                    prepare()
                    play()
                }

            mediaPlayer = player
        }

        private fun releasePlayer() {
            mediaPlayer?.let { player ->
                Log.i(TAG, "Releasing ExoPlayer...")
                playheadTime = player.currentPosition
                // This clears all listeners and resources. Crucial for preventing leaks.
                player.clearMediaItems()
                player.release()
            }
            mediaPlayer = null
        }

        private fun getMediaUri() : Uri {
            val sharedPrefs = getSharedPreferences("DEFAULT", MODE_PRIVATE)
            getString(R.string.video_uri)
            val uriString = sharedPrefs.getString(getString(R.string.video_uri), "").toString()

            Log.i(TAG, "URI: $uriString")

            return uriString.toUri()
        }

        // --- Lifecycle Methods ---
        // (These are mostly the same as the previous good version, but now they call the more robust initializePlayer)

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            Log.i(TAG, "onSurfaceCreated")
            this.surfaceHolder = holder
            initializePlayer()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            Log.i(TAG, "onSurfaceChanged: New dimensions ${width}x${height}")
            this.surfaceHolder = holder
            initializePlayer()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            Log.i(TAG, "onSurfaceDestroyed")
            releasePlayer()
            this.surfaceHolder = null
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            Log.i(TAG, "onVisibilityChanged: visible = $visible")
            if (visible) {
                // If the player is null (it was released), re-initialize it.
                if (mediaPlayer == null) {
                    initializePlayer()
                } else {
                    // If it exists, just resume playback.
                    mediaPlayer?.play()
                }
            } else {
                // When wallpaper is not visible, just pause. Releasing can be too aggressive
                // if the user is just checking notifications. We release fully onSurfaceDestroyed.
                mediaPlayer?.pause()
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            Log.i(TAG, "Engine onDestroy")
            // This is the final cleanup. Make sure everything is released.
            releasePlayer()
        }
    }
}