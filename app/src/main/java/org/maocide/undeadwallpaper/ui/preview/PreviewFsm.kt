package org.maocide.undeadwallpaper.ui.preview

import androidx.media3.exoplayer.ExoPlayer
import org.maocide.undeadwallpaper.utils.FileLogger

/**
 * Finite State Machine governing the preview ExoPlayer lifecycle, power, and rendering states
 * within SettingsFragment.
 *
 * Prevents redundant decode operations by pausing the player when:
 * 1. An occluding BottomSheet (e.g. VideoSettingsSheet) is active on top of the host.
 * 2. The preview card has scrolled off-screen (e.g. in dismissed hero state or landscape).
 * 3. The host Fragment is paused or destroyed.
 */
class PreviewFsm(
    private val onStateChanged: ((oldState: PreviewState, newState: PreviewState) -> Unit)? = null
) {
    companion object {
        private const val TAG = "PreviewFsm"
    }

    sealed interface PreviewState {
        object Idle : PreviewState
        data class Playing(val player: ExoPlayer) : PreviewState
        data class PausedOccluded(val player: ExoPlayer) : PreviewState
        data class PausedOffscreen(val player: ExoPlayer) : PreviewState
    }

    sealed interface PreviewEvent {
        data class PlayerAttached(val player: ExoPlayer) : PreviewEvent
        object PlayerReleased : PreviewEvent
        data class HostResumed(val isResumed: Boolean) : PreviewEvent
        data class SheetVisibilityChanged(val isOccluded: Boolean) : PreviewEvent
        data class CardVisibilityChanged(val isVisibleOnScreen: Boolean) : PreviewEvent
    }

    var state: PreviewState = PreviewState.Idle
        private set

    private var currentPlayer: ExoPlayer? = null
    private var isHostResumed: Boolean = false
    private var isOccludedBySheet: Boolean = false
    private var isCardVisibleOnScreen: Boolean = true

    fun processEvent(event: PreviewEvent) {
        val oldState = state
        when (event) {
            is PreviewEvent.PlayerAttached -> {
                currentPlayer = event.player
            }
            is PreviewEvent.PlayerReleased -> {
                currentPlayer = null
            }
            is PreviewEvent.HostResumed -> {
                isHostResumed = event.isResumed
            }
            is PreviewEvent.SheetVisibilityChanged -> {
                isOccludedBySheet = event.isOccluded
            }
            is PreviewEvent.CardVisibilityChanged -> {
                isCardVisibleOnScreen = event.isVisibleOnScreen
            }
        }

        val player = currentPlayer
        val nextState = when {
            player == null || !isHostResumed -> PreviewState.Idle
            isOccludedBySheet -> PreviewState.PausedOccluded(player)
            !isCardVisibleOnScreen -> PreviewState.PausedOffscreen(player)
            else -> PreviewState.Playing(player)
        }

        if (nextState != oldState) {
            state = nextState
            handleStateTransition(oldState, nextState)
            onStateChanged?.invoke(oldState, nextState)
        }
    }

    private fun handleStateTransition(oldState: PreviewState, newState: PreviewState) {
        FileLogger.d(TAG, "State transition: ${oldState.javaClass.simpleName} -> ${newState.javaClass.simpleName}")
        when (newState) {
            is PreviewState.Playing -> {
                newState.player.playWhenReady = true
            }
            is PreviewState.PausedOccluded -> {
                newState.player.playWhenReady = false
            }
            is PreviewState.PausedOffscreen -> {
                newState.player.playWhenReady = false
            }
            is PreviewState.Idle -> {
                if (oldState is PreviewState.Playing) {
                    oldState.player.playWhenReady = false
                }
            }
        }
    }
}
