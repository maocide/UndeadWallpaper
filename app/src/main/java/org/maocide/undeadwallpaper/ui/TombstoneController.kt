package org.maocide.undeadwallpaper.ui

import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.BounceInterpolator
import androidx.core.view.doOnLayout
import org.maocide.undeadwallpaper.data.PreferencesManager

/**
 * Controller managing the haunted tombstone FloatingActionButton.
 * Encapsulates the "Unlucky 13" manual interaction break lifecycle,
 * density-aware translation lift (-42dp for topple), haptic shudder/pulse,
 * and the resurrection bounce.
 */
class TombstoneController(
    private val fab: View,
    private val preferencesManager: PreferencesManager,
    private val performHaptic: ((HapticType) -> Unit)? = null
) {

    enum class HapticType {
        BREAK_SHUDDER,
        RESURRECT_PULSE
    }

    val isBroken: Boolean
        get() = preferencesManager.isTombstoneBroken()

    val leanAngle: Float
        get() = preferencesManager.getTombstoneLeanAngle()

    fun setup() {
        fab.doOnLayout {
            // Anchor pivot to the bottom-right corner so it stands and leans like a tombstone
            fab.pivotX = fab.width.toFloat()
            fab.pivotY = fab.height.toFloat()

            val angle = preferencesManager.getTombstoneLeanAngle()
            val broken = preferencesManager.isTombstoneBroken()

            if (broken) {
                fab.rotation = angle
                fab.translationY = calculateTranslationY(angle, fab.resources.displayMetrics.density)
            } else {
                fab.rotation = ANGLE_UPRIGHT
                fab.translationY = 0f
            }
        }
    }

    /**
     * Records a manual video interaction from the playlist and tests against
     * the modulo 13 checkpoint with a 50% chance roll.
     * Returns true if the tombstone broke and triggered the animation, false otherwise.
     */
    fun onManualTap(
        threshold: Int = BREAK_TAP_THRESHOLD,
        chanceRoll: () -> Boolean = { (0..1).random() == 1 },
        angleRoller: () -> Float = { rollBreakAngle() }
    ): Boolean {
        if (isBroken) {
            return false
        }
        val nextTaps = preferencesManager.getTombstoneTapCount() + 1
        return if (nextTaps % threshold == 0 && chanceRoll()) {
            val angle = angleRoller()
            preferencesManager.saveTombstoneState(angle = angle, isBroken = true, tapCount = nextTaps)
            triggerBreak(angle)
            true
        } else {
            preferencesManager.saveTombstoneTapCount(nextTaps)
            false
        }
    }

    fun triggerBreak(angle: Float) {
        val density = fab.resources.displayMetrics.density
        val targetTranslationY = calculateTranslationY(angle, density)

        if (performHaptic != null) {
            performHaptic.invoke(HapticType.BREAK_SHUDDER)
        } else {
            fab.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        fab.animate()
            .rotation(angle)
            .translationY(targetTranslationY)
            .setDuration(DURATION_BREAK_MS)
            .setInterpolator(BounceInterpolator())
            .start()
    }

    fun resurrect(onComplete: () -> Unit) {
        if (performHaptic != null) {
            performHaptic.invoke(HapticType.RESURRECT_PULSE)
        } else {
            fab.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            fab.postDelayed({
                fab.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }, 100L)
        }

        fab.animate()
            .rotation(ANGLE_UPRIGHT)
            .translationY(0f)
            .setDuration(DURATION_RESURRECT_MS)
            .setInterpolator(BounceInterpolator())
            .withEndAction {
                preferencesManager.saveTombstoneState(angle = ANGLE_UPRIGHT, isBroken = false, tapCount = 0)
                onComplete()
            }
            .start()
    }

    companion object {
        const val BREAK_TAP_THRESHOLD = 13
        const val ANGLE_UPRIGHT = 0f
        const val ANGLE_SAG = -12f
        const val ANGLE_TOPPLE = -85f
        const val TRANSLATION_Y_TOPPLE_DP = -42f
        const val DURATION_BREAK_MS = 750L
        const val DURATION_RESURRECT_MS = 400L

        /**
         * Calculates density-aware translation lift for toppled states.
         * For toppled angles (-85°), returns -42dp * density to keep the tombstone resting
         * gracefully along the bezel/margin instead of clipping beneath the screen edge.
         */
        fun calculateTranslationY(angle: Float, density: Float): Float {
            return if (angle <= -45f) {
                TRANSLATION_Y_TOPPLE_DP * density
            } else {
                0f
            }
        }

        /**
         * Resolves the break angle: rolls between weathered sag (-12°) and full topple (-85°).
         */
        fun rollBreakAngle(isTopple: Boolean = (0..1).random() == 1): Float {
            return if (isTopple) ANGLE_TOPPLE else ANGLE_SAG
        }

        /**
         * Evaluates a manual interaction tap against threshold (modulo), chance roll, and current break state.
         */
        fun evaluateTap(
            currentTaps: Int,
            isAlreadyBroken: Boolean,
            threshold: Int = BREAK_TAP_THRESHOLD,
            chanceRoll: () -> Boolean = { (0..1).random() == 1 },
            rollAngle: () -> Float = { rollBreakAngle() }
        ): TapEvaluation {
            if (isAlreadyBroken) {
                return TapEvaluation.Ignored(currentTaps)
            }
            val nextTaps = currentTaps + 1
            return if (nextTaps % threshold == 0 && chanceRoll()) {
                TapEvaluation.TriggerBreak(nextTaps, rollAngle())
            } else {
                TapEvaluation.Progress(nextTaps)
            }
        }
    }

    sealed class TapEvaluation {
        data class Ignored(val currentTaps: Int) : TapEvaluation()
        data class Progress(val newTapCount: Int) : TapEvaluation()
        data class TriggerBreak(val newTapCount: Int, val angle: Float) : TapEvaluation()
    }
}
