package org.maocide.undeadwallpaper.ui

import android.widget.Toast
import com.google.android.material.chip.ChipGroup
import org.maocide.undeadwallpaper.R
import org.maocide.undeadwallpaper.data.PreferencesManager
import org.maocide.undeadwallpaper.databinding.FragmentSettingsBinding
import org.maocide.undeadwallpaper.event.WallpaperEvent
import org.maocide.undeadwallpaper.event.WallpaperEventBus
import org.maocide.undeadwallpaper.model.GestureType
import org.maocide.undeadwallpaper.model.WallpaperAction
import org.maocide.undeadwallpaper.utils.preventDoubleInput

/**
 * Controller managing home screen touch gestures (Double Tap and Triple Tap actions).
 * Encapsulates ChipGroup selection listeners, tapjack touch protection, warning toast
 * debouncing, preference persistence, and UI synchronization.
 */
class GestureControlsController(
    private val binding: FragmentSettingsBinding,
    private val preferencesManager: PreferencesManager,
    private val isUpdatingUi: () -> Boolean,
    private val onShowWarningToast: (() -> Unit)? = null
) {
    private var hasWarnedAboutGestures = false

    /**
     * Attaches tapjack protection to each gesture chip and binds checked state change listeners.
     */
    fun setup() {
        // Double Tap Gesture chips tapjack & overlay protection
        binding.doubleTapNone.preventDoubleInput()
        binding.doubleTapPause.preventDoubleInput()
        binding.doubleTapSkip.preventDoubleInput()
        setupGestureToggleGroup(
            binding.doubleTapGroup,
            binding.doubleTapPause.id,
            binding.doubleTapSkip.id,
            GestureType.DOUBLE_TAP
        )

        // Triple Tap Gesture chips tapjack & overlay protection
        binding.tripleTapNone.preventDoubleInput()
        binding.tripleTapPause.preventDoubleInput()
        binding.tripleTapSkip.preventDoubleInput()
        setupGestureToggleGroup(
            binding.tripleTapGroup,
            binding.tripleTapPause.id,
            binding.tripleTapSkip.id,
            GestureType.TRIPLE_TAP
        )
    }

    /**
     * Synchronizes the UI chips with the current values saved in [PreferencesManager].
     * @return true if either double-tap or triple-tap has an active action configured.
     */
    fun syncFromPreferences(): Boolean {
        val doubleTapAction = preferencesManager.getActionForGesture(GestureType.DOUBLE_TAP)
        val doubleCheckId = actionToChipId(
            doubleTapAction,
            binding.doubleTapNone.id,
            binding.doubleTapPause.id,
            binding.doubleTapSkip.id
        )
        binding.doubleTapGroup.check(doubleCheckId)

        val tripleTapAction = preferencesManager.getActionForGesture(GestureType.TRIPLE_TAP)
        val tripleCheckId = actionToChipId(
            tripleTapAction,
            binding.tripleTapNone.id,
            binding.tripleTapPause.id,
            binding.tripleTapSkip.id
        )
        binding.tripleTapGroup.check(tripleCheckId)

        return isAnyGestureActive(doubleTapAction, tripleTapAction)
    }

    private fun setupGestureToggleGroup(
        group: ChipGroup,
        pauseId: Int,
        skipId: Int,
        gestureType: GestureType
    ) {
        group.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isUpdatingUi() || checkedIds.isEmpty()) return@setOnCheckedStateChangeListener

            val action = chipIdToAction(checkedIds[0], pauseId, skipId)
            preferencesManager.setActionForGesture(gestureType, action)

            if (action != WallpaperAction.NONE) {
                showGestureWarningIfNeeded()
            }

            WallpaperEventBus.emit(WallpaperEvent.TouchControlsChanged)
        }
    }

    private fun showGestureWarningIfNeeded() {
        if (!hasWarnedAboutGestures) {
            if (onShowWarningToast != null) {
                onShowWarningToast.invoke()
            } else {
                Toast.makeText(
                    binding.root.context,
                    R.string.gesture_launcher_warning,
                    Toast.LENGTH_LONG
                ).show()
            }
            hasWarnedAboutGestures = true
        }
    }

    companion object {
        /**
         * Maps a checked Chip view ID to its corresponding [WallpaperAction].
         */
        fun chipIdToAction(checkedId: Int, pauseId: Int, skipId: Int): WallpaperAction {
            return when (checkedId) {
                pauseId -> WallpaperAction.PLAY_PAUSE
                skipId -> WallpaperAction.SKIP_NEXT
                else -> WallpaperAction.NONE
            }
        }

        /**
         * Maps a [WallpaperAction] to the corresponding Chip view ID in a group.
         */
        fun actionToChipId(
            action: WallpaperAction,
            noneId: Int,
            pauseId: Int,
            skipId: Int
        ): Int {
            return when (action) {
                WallpaperAction.NONE -> noneId
                WallpaperAction.PLAY_PAUSE -> pauseId
                WallpaperAction.SKIP_NEXT -> skipId
            }
        }

        /**
         * Checks if any gesture has an action other than [WallpaperAction.NONE].
         */
        fun isAnyGestureActive(
            doubleTap: WallpaperAction,
            tripleTap: WallpaperAction
        ): Boolean {
            return doubleTap != WallpaperAction.NONE || tripleTap != WallpaperAction.NONE
        }
    }
}
