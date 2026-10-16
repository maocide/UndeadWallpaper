package org.maocide.undeadwallpaper.ui.preview

import android.content.res.Configuration
import android.view.View
import androidx.core.widget.NestedScrollView
import org.maocide.undeadwallpaper.R
import org.maocide.undeadwallpaper.data.PreferencesManager
import org.maocide.undeadwallpaper.databinding.FragmentSettingsBinding
import org.maocide.undeadwallpaper.utils.HapticHelper
import org.maocide.undeadwallpaper.utils.setSafeOnClickListener

class FloatingPreviewController(
    private val binding: FragmentSettingsBinding,
    private val preferencesManager: PreferencesManager,
    private val onCardVisibilityChanged: (isVisible: Boolean) -> Unit = {}
) {
    var isDismissed: Boolean = !preferencesManager.isFloatingPreviewEnabled()
        private set

    fun setup() {
        binding.buttonDismissFloating.setSafeOnClickListener {
            if (isDismissed) {
                resurrect()
            } else {
                dismiss()
            }
        }
        applyCurrentState()
    }

    fun onConfigurationChanged() {
        applyCurrentState()
    }

    private fun applyCurrentState() {
        val resources = binding.root.resources
        val density = resources.displayMetrics.density
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        if (isLandscape) {
            resetToLandscapeState()
        } else if (isDismissed) {
            applyDismissedHeroState()
        }

        setupScrollListener(density)

        val cardHeight = binding.cardVideoPreview.height.takeIf { it > 0 } ?: (COLLAPSE_DISTANCE_DP * density).toInt()
        val isVisible = if (isLandscape || isDismissed) {
            binding.settingsScrollView.scrollY < cardHeight
        } else {
            true
        }
        onCardVisibilityChanged(isVisible)
    }

    private fun resetToLandscapeState() {
        with(binding) {
            cardVideoPreview.translationX = 0f
            cardVideoPreview.translationY = 0f
            cardVideoPreview.translationZ = 0f
            cardVideoPreview.scaleX = 1f
            cardVideoPreview.scaleY = 1f
            cardVideoPreview.alpha = 1f
            buttonPickVideo.alpha = 0.85f
            buttonPickVideo.isClickable = true
            buttonPickVideo.visibility = View.VISIBLE
            buttonDismissFloating.visibility = View.GONE
            buttonDismissFloating.alpha = 0f
            buttonDismissFloating.isClickable = false
        }
    }

    private fun applyDismissedHeroState() {
        with(binding) {
            cardVideoPreview.translationX = 0f
            cardVideoPreview.translationY = 0f
            cardVideoPreview.translationZ = 0f
            cardVideoPreview.scaleX = 1f
            cardVideoPreview.scaleY = 1f
            cardVideoPreview.alpha = 1f
            buttonPickVideo.alpha = 0.85f
            buttonPickVideo.isClickable = true
            buttonPickVideo.visibility = View.VISIBLE
            ivFloatingBadge.setImageResource(R.drawable.ic_tombstone_badge)
            buttonDismissFloating.contentDescription = root.context.getString(R.string.restore_preview)
            buttonDismissFloating.visibility = View.VISIBLE
            buttonDismissFloating.alpha = 1f
            buttonDismissFloating.isClickable = true
        }
    }

    private fun setupScrollListener(density: Float) {
        val collapseDistance = (COLLAPSE_DISTANCE_DP * density).coerceAtLeast(1f)
        val glideDistance = GLIDE_DISTANCE_DP * density

        binding.settingsScrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val resources = binding.root.resources
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

            if (isLandscape || isDismissed) {
                val cardHeight = binding.cardVideoPreview.height.takeIf { it > 0 } ?: (COLLAPSE_DISTANCE_DP * density).toInt()
                val isVisible = scrollY < cardHeight
                onCardVisibilityChanged(isVisible)
                return@setOnScrollChangeListener
            }

            // Normal floating portrait mode
            onCardVisibilityChanged(true)
            val progress = calculateProgress(scrollY, collapseDistance)

            if (progress > 0f) {
                with(binding) {
                    cardVideoPreview.visibility = View.VISIBLE
                    cardVideoPreview.isClickable = true
                    cardVideoPreview.alpha = 1f
                    cardVideoPreview.translationY = scrollY.toFloat()
                    cardVideoPreview.translationX = calculateGlideX(progress, glideDistance)
                    cardVideoPreview.pivotX = 0f
                    cardVideoPreview.pivotY = 0f
                    val scale = calculateScale(progress)
                    cardVideoPreview.scaleX = scale
                    cardVideoPreview.scaleY = scale
                    cardVideoPreview.translationZ = calculateElevationZ(progress, density)

                    val btnAlpha = calculateButtonPickAlpha(progress)
                    buttonPickVideo.alpha = btnAlpha
                    buttonPickVideo.isClickable = btnAlpha > 0.1f
                    buttonPickVideo.visibility = if (btnAlpha == 0f) View.GONE else View.VISIBLE

                    ivFloatingBadge.setImageResource(R.drawable.ic_skull)
                    buttonDismissFloating.contentDescription = root.context.getString(R.string.dismiss_preview)
                    val dismissAlpha = calculateDismissBadgeAlpha(progress)
                    buttonDismissFloating.alpha = dismissAlpha
                    buttonDismissFloating.visibility = if (dismissAlpha > 0.05f) View.VISIBLE else View.GONE
                    buttonDismissFloating.isClickable = dismissAlpha > 0.5f
                }
            } else {
                with(binding) {
                    cardVideoPreview.visibility = View.VISIBLE
                    cardVideoPreview.isClickable = true
                    cardVideoPreview.alpha = 1f
                    cardVideoPreview.translationX = 0f
                    cardVideoPreview.translationY = 0f
                    cardVideoPreview.translationZ = 0f
                    cardVideoPreview.scaleX = 1f
                    cardVideoPreview.scaleY = 1f
                    buttonPickVideo.alpha = 0.85f
                    buttonPickVideo.isClickable = true
                    buttonPickVideo.visibility = View.VISIBLE
                    buttonDismissFloating.visibility = View.GONE
                    buttonDismissFloating.alpha = 0f
                    buttonDismissFloating.isClickable = false
                }
            }
        }
    }

    fun dismiss() {
        performDismissAnimation()
    }

    private fun performDismissAnimation() {
        HapticHelper.performGestureFeedback(binding.root.context)
        with(binding) {
            buttonDismissFloating.isClickable = false
            buttonDismissFloating.animate().alpha(0f).setDuration(100L).start()
            cardVideoPreview.isClickable = false
            cardVideoPreview.animate()
                .alpha(0f)
                .scaleX(cardVideoPreview.scaleX * 0.85f)
                .scaleY(cardVideoPreview.scaleY * 0.85f)
                .setDuration(180L)
                .withEndAction {
                    dismissForSession()
                }
                .start()
        }
    }

    private fun dismissForSession() {
        isDismissed = true
        preferencesManager.setFloatingPreviewEnabled(false)
        applyDismissedHeroState()
        val density = binding.root.resources.displayMetrics.density
        val cardHeight = binding.cardVideoPreview.height.takeIf { it > 0 } ?: (COLLAPSE_DISTANCE_DP * density).toInt()
        onCardVisibilityChanged(binding.settingsScrollView.scrollY < cardHeight)
    }

    fun resurrect() {
        HapticHelper.performGestureFeedback(binding.root.context)
        isDismissed = false
        preferencesManager.setFloatingPreviewEnabled(true)
        onCardVisibilityChanged(true)

        val density = binding.root.resources.displayMetrics.density
        val collapseDistance = (COLLAPSE_DISTANCE_DP * density).coerceAtLeast(1f)
        val glideDistance = GLIDE_DISTANCE_DP * density
        val scrollY = binding.settingsScrollView.scrollY
        val progress = calculateProgress(scrollY, collapseDistance)

        with(binding) {
            if (progress > 0f) {
                val targetY = scrollY.toFloat()
                val targetX = calculateGlideX(progress, glideDistance)
                val targetScale = calculateScale(progress)
                val targetZ = calculateElevationZ(progress, density)

                ivFloatingBadge.setImageResource(R.drawable.ic_skull)
                buttonDismissFloating.contentDescription = root.context.getString(R.string.dismiss_preview)
                cardVideoPreview.animate()
                    .translationY(targetY)
                    .translationX(targetX)
                    .scaleX(targetScale)
                    .scaleY(targetScale)
                    .translationZ(targetZ)
                    .setDuration(220L)
                    .withEndAction {
                        applyCurrentState()
                    }
                    .start()
            } else {
                buttonDismissFloating.animate().alpha(0f).setDuration(150L).withEndAction {
                    buttonDismissFloating.visibility = View.GONE
                    ivFloatingBadge.setImageResource(R.drawable.ic_skull)
                    buttonDismissFloating.contentDescription = root.context.getString(R.string.dismiss_preview)
                    applyCurrentState()
                }.start()
            }
        }
    }

    fun cleanup() {
        binding.buttonDismissFloating.animate().cancel()
        binding.cardVideoPreview.animate().cancel()
        binding.settingsScrollView.setOnScrollChangeListener(null as NestedScrollView.OnScrollChangeListener?)
    }

    companion object {
        const val COLLAPSE_DISTANCE_DP = 180f
        const val GLIDE_DISTANCE_DP = 12f
        const val ELEVATION_DP = 24f

        fun calculateProgress(scrollY: Int, collapseDistancePx: Float): Float {
            return (scrollY.toFloat() / collapseDistancePx).coerceIn(0f, 1f)
        }

        fun calculateScale(progress: Float): Float {
            return 1f - (0.40f * progress)
        }

        fun calculateGlideX(progress: Float, glideDistancePx: Float): Float {
            return glideDistancePx * progress
        }

        fun calculateElevationZ(progress: Float, density: Float): Float {
            return ELEVATION_DP * density * progress
        }

        fun calculateButtonPickAlpha(progress: Float): Float {
            return (1f - progress * 2.5f).coerceIn(0f, 1f)
        }

        fun calculateDismissBadgeAlpha(progress: Float): Float {
            return ((progress - 0.3f) / 0.4f).coerceIn(0f, 1f)
        }
    }
}
