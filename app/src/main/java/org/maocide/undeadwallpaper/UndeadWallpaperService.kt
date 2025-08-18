package org.maocide.undeadwallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
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


    // <<< ABBY'S ADDITION: Our secret passphrase >>>
    companion object {
        const val ACTION_VIDEO_URI_CHANGED = "org.maocide.undeadwallpaper.VIDEO_URI_CHANGED"
    }

    override fun onCreateEngine(): Engine {
        return MyWallpaperEngine()
    }

    private inner class MyWallpaperEngine : Engine() {

        private var mediaPlayer: ExoPlayer? = null
        private var surfaceHolder: SurfaceHolder? = null
        private var playheadTime: Long = 0L
        private val TAG: String = javaClass.simpleName

        // <<< ABBY'S FIX: The radio receiver that listens for our signal >>>
        private val videoChangeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_VIDEO_URI_CHANGED) {
                    Log.i(TAG, "Broadcast received! Re-initializing player with new video.")
                    // Re-initialize the player to load the new URI from SharedPreferences
                    initializePlayer()
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            Log.i(TAG, "Engine onCreate")
            // <<< ABBY'S FIX: Turn on the radio and start listening >>>
            val intentFilter = IntentFilter(ACTION_VIDEO_URI_CHANGED)
            // Using `registerReceiver` with the `RECEIVER_NOT_EXPORTED` flag is the
            // modern, secure way for Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(videoChangeReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag") // Flag not needed for older APIs
                registerReceiver(videoChangeReceiver, intentFilter)
            }
        }

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
            // <<< ABBY'S FIX: Turn off the radio to prevent leaks. CRUCIAL! >>>
            unregisterReceiver(videoChangeReceiver)
            Log.i(TAG, "Engine onDestroy")
            // This is the final cleanup. Make sure everything is released.
            releasePlayer()
        }

        @Deprecated("Deprecated in Java") // This is needed for older Android versions
        override fun onCommand(
            action: String?,
            x: Int,
            y: Int,
            z: Int,
            extras: Bundle?,
            resultRequested: Boolean
        ): Bundle? {
            super.onCommand(action, x, y, z, extras, resultRequested)
            Log.d(TAG, "onCommand received: $action")

            if (action == "android.wallpaper.reapply" || action == ACTION_VIDEO_URI_CHANGED) {
                Log.i(TAG, "Video URI changed command received! Re-initializing player.")
                // The most robust way to handle the change is a full reset.
                // This ensures all old data is cleared and the new URI is loaded.
                initializePlayer()
            }
            return null
        }
    }
}