package org.maocide.undeadwallpaper.ui.preview

import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultAllocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maocide.undeadwallpaper.BuildConfig
import org.maocide.undeadwallpaper.R
import org.maocide.undeadwallpaper.data.PreferencesManager
import org.maocide.undeadwallpaper.data.VideoFileManager
import org.maocide.undeadwallpaper.databinding.FragmentSettingsBinding
import org.maocide.undeadwallpaper.utils.BlurHelper
import org.maocide.undeadwallpaper.utils.DisplayGeometryHelper
import org.maocide.undeadwallpaper.utils.FileLogger
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * Controller governing the video preview surface, OpenGL ES [PreviewVideoRenderer],
 * and Media3 [ExoPlayer] lifecycle inside SettingsFragment.
 *
 * Strictly decoupled from [FloatingPreviewController] (which manages 2D card container
 * scroll physics and floating animations).
 */
@OptIn(UnstableApi::class)
class VideoPreviewController(
    private val binding: FragmentSettingsBinding,
    private val preferencesManager: PreferencesManager,
    private val videoFileManager: VideoFileManager,
    private val coroutineScope: CoroutineScope,
    private val previewFsm: PreviewFsm,
    private val getSelectedVideoUri: () -> Uri?,
    private val onVideoMigrated: (Uri) -> Unit
) {
    private val tag = "VideoPreviewController"

    private var previewPlayer: ExoPlayer? = null
    private var previewGlRenderer: PreviewVideoRenderer? = null
    private var previewSurfaceAttachmentJob: Job? = null
    private var blurLoadJob: Job? = null
    private var isHostResumed: Boolean = false

    private val layoutChangeListener =
        View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val width = right - left
            val height = bottom - top
            if (width > 0 && height > 0 && (width != oldRight - oldLeft || height != oldBottom - oldTop)) {
                binding.previewFrameContainer.post {
                    updateSurfaceDimensions()
                }
            }
        }

    val needsRecovery: Boolean
        get() = previewPlayer == null || previewPlayer?.playerError != null

    fun setup() {
        binding.videoPreviewContainer.addOnLayoutChangeListener(layoutChangeListener)
        binding.videoPreviewContainer.doOnLayout {
            updateSurfaceDimensions()
        }

        binding.videoPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                if (!isHostResumed) return
                ensureGlRenderer().onSurfaceAvailable(surface, width, height)
                attachPlayerToGlSurface()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                previewGlRenderer?.onSurfaceSizeChanged(width, height)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                previewPlayer?.clearVideoSurface()
                releaseGlRenderer()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                // No-op
            }
        }
    }

    fun updateSurfaceDimensions() {
        val containerWidth = binding.videoPreviewContainer.width
        val containerHeight = binding.videoPreviewContainer.height
        if (containerWidth <= 0 || containerHeight <= 0) return

        val resources = binding.root.resources
        val insetPx = resources.getDimensionPixelSize(R.dimen.preview_phone_frame_inset)
        val metrics = resources.displayMetrics
        val screenRatio = DisplayGeometryHelper.getEffectiveAspectRatio(metrics, preferencesManager)

        val (targetWidth, targetHeight) = calculateFrameDimensions(
            containerWidth = containerWidth,
            containerHeight = containerHeight,
            insetPx = insetPx,
            screenRatio = screenRatio
        )

        val lp = binding.previewFrameContainer.layoutParams
        if (lp.width != targetWidth || lp.height != targetHeight) {
            binding.previewFrameContainer.updateLayoutParams<FrameLayout.LayoutParams> {
                width = targetWidth
                height = targetHeight
                gravity = Gravity.CENTER
            }
        }
    }

    fun loadVideo(uri: Uri) {
        val renderer = ensureGlRenderer()
        val surface = renderer.getVideoSurface()

        var player = previewPlayer
        if (player?.playerError != null) {
            releasePlayer()
            player = null
        }

        if (player == null) {
            val context = binding.root.context
            val loadControl = DefaultLoadControl.Builder()
                .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
                .setBufferDurationsMs(
                    PREVIEW_MIN_BUFFER_MS,
                    PREVIEW_MAX_BUFFER_MS,
                    PREVIEW_BUFFER_FOR_PLAYBACK_MS,
                    PREVIEW_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                )
                .setTargetBufferBytes(PREVIEW_BUFFER_BYTES)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            player = ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .build()
                .apply {
                    repeatMode = Player.REPEAT_MODE_ALL
                    volume = 0f

                    addListener(object : Player.Listener {
                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            if (videoSize.width > 0 && videoSize.height > 0) {
                                previewGlRenderer?.setVideoSize(videoSize.width, videoSize.height)
                                previewGlRenderer?.requestRender()
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            if (BuildConfig.DEBUG) {
                                FileLogger.e(tag, "ExoPlayer error in preview: ${error.message}", error)
                            }

                            var isFileNotFound = false
                            var currentCause: Throwable? = error
                            while (currentCause != null) {
                                if (currentCause is FileNotFoundException) {
                                    isFileNotFound = true
                                    break
                                }
                                currentCause = currentCause.cause
                            }

                            if (isFileNotFound) {
                                val newUri = preferencesManager.getActiveVideoUri()
                                if (newUri != null) {
                                    onVideoMigrated(newUri.toUri())
                                }
                                return
                            }

                            Toast.makeText(
                                context,
                                context.getString(R.string.error_cannot_play_video),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    })
                }
            previewPlayer = player
            previewFsm.processEvent(PreviewFsm.PreviewEvent.PlayerAttached(player))
        }

        if (surface != null && surface.isValid) {
            player.setVideoSurface(surface)
        } else {
            attachPlayerToGlSurface()
        }

        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()

        refreshTransforms(uri = uri, forceRedraw = false)

        blurLoadJob?.cancel()
        uri.path?.let { path ->
            blurLoadJob = coroutineScope.launch {
                try {
                    val file = File(path)
                    if (file.exists()) {
                        val bitmap = withContext(Dispatchers.IO) {
                            videoFileManager.getOrLoadThumbnailCached(file)
                                ?: videoFileManager.getOrGenerateThumbnail(file)
                        }
                        if (bitmap != null) {
                            BlurHelper.applyBlurToImageView(binding.ivVideoBlurBg, bitmap)
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        throw e // Rethrow to let the coroutine framework handle the cancellation
                    }
                    FileLogger.e(tag, "Error loading thumbnail for blurred background", e)
                }
            }
        }
    }

    fun refreshTransforms(uri: Uri? = null, forceRedraw: Boolean = false) {
        val renderer = previewGlRenderer ?: return
        val targetUri = uri
            ?: getSelectedVideoUri()
            ?: preferencesManager.getActiveVideoUri()?.toUri()
            ?: return
        val fileName = targetUri.lastPathSegment ?: return
        val settings = preferencesManager.getVideoSettings(fileName)

        val w = settings.width ?: 0
        val h = settings.height ?: 0
        if (w > 0 && h > 0) {
            renderer.setVideoSize(w, h)
        }
        renderer.publishTransforms(settings)
        if (forceRedraw) {
            renderer.requestRender()
        }
    }

    fun onResume(targetUri: Uri?) {
        isHostResumed = true
        previewFsm.processEvent(PreviewFsm.PreviewEvent.HostResumed(true))
        updateSurfaceDimensions()
        if (targetUri != null && previewPlayer == null) {
            loadVideo(targetUri)
        }
    }

    fun onPause() {
        isHostResumed = false
        previewFsm.processEvent(PreviewFsm.PreviewEvent.HostResumed(false))
        releasePlayer()
        releaseGlRenderer()
    }

    fun cleanup() {
        isHostResumed = false
        blurLoadJob?.cancel()
        blurLoadJob = null
        binding.videoPreviewContainer.removeOnLayoutChangeListener(layoutChangeListener)
        binding.videoPreview.surfaceTextureListener = null
        releasePlayer()
        releaseGlRenderer()
    }

    private fun ensureGlRenderer(): PreviewVideoRenderer {
        val existing = previewGlRenderer
        val w = binding.videoPreview.width.takeIf { it > 0 } ?: binding.previewFrameContainer.width
        val h = binding.videoPreview.height.takeIf { it > 0 } ?: binding.previewFrameContainer.height

        if (existing != null) {
            if (binding.videoPreview.isAvailable) {
                val texture = binding.videoPreview.surfaceTexture
                if (texture != null) {
                    existing.onSurfaceAvailable(texture, w, h)
                }
            }
            return existing
        }

        val renderer = PreviewVideoRenderer(binding.root.context.applicationContext)
        previewGlRenderer = renderer

        if (binding.videoPreview.isAvailable) {
            val texture = binding.videoPreview.surfaceTexture
            if (texture != null) {
                renderer.onSurfaceAvailable(texture, w, h)
            }
        }
        return renderer
    }

    private fun attachPlayerToGlSurface() {
        val player = previewPlayer ?: return
        val renderer = previewGlRenderer ?: return

        previewSurfaceAttachmentJob?.cancel()
        previewSurfaceAttachmentJob = coroutineScope.launch {
            val surface = withTimeoutOrNull(3000L.milliseconds) {
                renderer.waitForVideoSurface()
            }
            if (surface != null && surface.isValid && previewPlayer === player && previewGlRenderer === renderer) {
                player.setVideoSurface(surface)
                refreshTransforms(forceRedraw = false)
            }
        }
    }

    private fun releasePlayer() {
        previewSurfaceAttachmentJob?.cancel()
        previewSurfaceAttachmentJob = null
        previewFsm.processEvent(PreviewFsm.PreviewEvent.PlayerReleased)
        previewPlayer?.stop()
        previewPlayer?.clearVideoSurface()
        previewPlayer?.release()
        previewPlayer = null
    }

    private fun releaseGlRenderer() {
        previewSurfaceAttachmentJob?.cancel()
        previewSurfaceAttachmentJob = null
        previewGlRenderer?.release()
        previewGlRenderer = null
    }

    companion object {
        private const val PREVIEW_MIN_BUFFER_MS = 500
        private const val PREVIEW_MAX_BUFFER_MS = 1_500
        private const val PREVIEW_BUFFER_FOR_PLAYBACK_MS = 50
        private const val PREVIEW_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 150
        private const val PREVIEW_BUFFER_BYTES = 2 * 1024 * 1024

        /**
         * Pure calculation of phone frame dimensions inside the blurred preview container.
         * Enforces proportional aspect scaling of [screenRatio] with [insetPx] boundary clearance.
         */
        fun calculateFrameDimensions(
            containerWidth: Int,
            containerHeight: Int,
            insetPx: Int,
            screenRatio: Float
        ): Pair<Int, Int> {
            if (containerWidth <= 0 || containerHeight <= 0) return Pair(0, 0)
            val maxAvailableWidth = (containerWidth - insetPx * 2).coerceAtLeast(1)
            val maxAvailableHeight = (containerHeight - insetPx * 2).coerceAtLeast(1)
            val availableRatio = maxAvailableWidth.toFloat() / maxAvailableHeight.toFloat()

            val targetWidth: Int
            val targetHeight: Int
            if (screenRatio <= availableRatio) {
                targetHeight = maxAvailableHeight
                targetWidth = (maxAvailableHeight * screenRatio).roundToInt().coerceAtLeast(1)
            } else {
                targetWidth = maxAvailableWidth
                targetHeight = (maxAvailableWidth / screenRatio).roundToInt().coerceAtLeast(1)
            }
            return Pair(targetWidth, targetHeight)
        }
    }
}

