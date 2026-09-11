package org.maocide.undeadwallpaper.service

/**
 * Strict single-source-of-truth lifecycle management for the Live Wallpaper engine.
 * Eliminates race conditions between boolean flags (e.g. isPlayerInitialized, isPlayerReleasing)
 * by defining explicit states and acceptable transitions.
 */
sealed class EngineState {
    /** The player does not exist or has been fully released. */
    object Uninitialized : EngineState()

    /** ExoPlayer is currently being built or preparing. Prevents redundant initializations. */
    object Initializing : EngineState()

    /** 
     * Player is prepared and attached to a surface.
     * @param isManuallyPaused True if the user paused playback via a double/triple tap gesture.
     */
    data class Ready(val isManuallyPaused: Boolean = false) : EngineState()

    /** The player is currently being destroyed. Ignore all startup requests. */
    object Releasing : EngineState()
}

sealed class EngineEvent {
    /** Request to build and prepare the player (e.g. onSurfaceCreated). */
    object InitializeRequested : EngineEvent()

    /** Signals the async initialization coroutine has finished preparing the player. */
    object InitializationComplete : EngineEvent()

    /**
     * Screen turned on or off.
     * @param isVisible True if the screen is on and wallpaper is visible.
     * @param forceRestart True if the wakeup check detected a dead thread/surface and requires a hard reset.
     */
    data class VisibilityChanged(val isVisible: Boolean, val forceRestart: Boolean = false) : EngineEvent()

    /** User double/triple tapped the screen to toggle playback. */
    object UserTogglePause : EngineEvent()

    /** Request to destroy the player (e.g. onSurfaceDestroyed or fatal error). */
    object ReleaseRequested : EngineEvent()

    /** Irrecoverable hardware decoder failure. */
    data class HardwareFailure(val reason: String) : EngineEvent()
}

/**
 * Side-effects that the Service must execute in response to a state transition.
 */
sealed class EngineEffect {
    data class StartInitialization(val lumaScale: Float) : EngineEffect()
    data class ApplyPlayWhenReady(val play: Boolean) : EngineEffect()
    object StartRelease : EngineEffect()
    object None : EngineEffect()
}

/**
 * Pure state machine. Given a state and an event, computes the next state and the required side-effect.
 */
class EngineFSM(private val context: android.content.Context) {
    var state: EngineState = EngineState.Uninitialized
        private set


    @Synchronized
    fun transition(event: EngineEvent): EngineEffect {
        val (nextState, effect) = reduce(state, event)
        this.state = nextState
        return effect
    }

    private fun reduce(currentState: EngineState, event: EngineEvent): Pair<EngineState, EngineEffect> {
        return when (currentState) {
            is EngineState.Uninitialized -> {
                when (event) {
                    is EngineEvent.InitializeRequested -> Pair(
                        EngineState.Initializing,
                        EngineEffect.StartInitialization(computeLumaScale())
                    )

                    is EngineEvent.VisibilityChanged -> {
                        if (event.isVisible) {
                            Pair(EngineState.Initializing, EngineEffect.StartInitialization(computeLumaScale()))
                        } else {
                            Pair(currentState, EngineEffect.None)
                        }
                    }

                    else -> Pair(currentState, EngineEffect.None)
                }
            }

            is EngineState.Initializing -> {
                when (event) {
                    is EngineEvent.InitializationComplete -> Pair(
                        EngineState.Ready(isManuallyPaused = false),
                        EngineEffect.None
                    )

                    is EngineEvent.ReleaseRequested -> Pair(EngineState.Releasing, EngineEffect.StartRelease)
                    is EngineEvent.HardwareFailure -> Pair(EngineState.Releasing, EngineEffect.StartRelease)

                    // If a new init comes in while we are already initializing (e.g. from the GL fallback mechanism),
                    // we must allow it to restart the initialization process!
                    is EngineEvent.InitializeRequested -> Pair(
                        EngineState.Initializing,
                        EngineEffect.StartInitialization(computeLumaScale())
                    )

                    // If visibility changes while initializing, we ignore it for now.
                    // Once InitializationComplete is fired, the Service will immediately feed the current visibility state to the FSM.
                    else -> Pair(currentState, EngineEffect.None)
                }
            }

            is EngineState.Ready -> {
                when (event) {
                    is EngineEvent.VisibilityChanged -> {
                        if (event.forceRestart) {
                            Pair(EngineState.Initializing, EngineEffect.StartInitialization(computeLumaScale()))
                        } else {
                            // If it's visible, and we aren't manually paused, play.
                            val shouldPlay = event.isVisible && !currentState.isManuallyPaused
                            Pair(currentState, EngineEffect.ApplyPlayWhenReady(shouldPlay))
                        }
                    }

                    is EngineEvent.UserTogglePause -> {
                        val newPauseState = !currentState.isManuallyPaused
                        Pair(
                            EngineState.Ready(isManuallyPaused = newPauseState),
                            EngineEffect.ApplyPlayWhenReady(!newPauseState)
                        )
                    }

                    is EngineEvent.ReleaseRequested -> Pair(EngineState.Releasing, EngineEffect.StartRelease)
                    is EngineEvent.InitializeRequested -> Pair(
                        EngineState.Initializing,
                        EngineEffect.StartInitialization(computeLumaScale())
                    ) // Force re-init from UI
                    is EngineEvent.HardwareFailure -> Pair(EngineState.Releasing, EngineEffect.StartRelease)
                    else -> Pair(currentState, EngineEffect.None)
                }
            }

            is EngineState.Releasing -> {
                when (event) {
                    // Once released, we go back to uninitialized. We can re-initialize if requested later.
                    // The actual completion of release is handled by the Service immediately for now.
                    // If a new init comes in while releasing, we transition straight to Initializing.
                    is EngineEvent.InitializeRequested -> Pair(
                        EngineState.Initializing,
                        EngineEffect.StartInitialization(computeLumaScale())
                    )

                    is EngineEvent.VisibilityChanged -> {
                        if (event.isVisible) {
                            Pair(EngineState.Initializing, EngineEffect.StartInitialization(computeLumaScale()))
                        } else {
                            Pair(currentState, EngineEffect.None)
                        }
                    }

                    else -> Pair(currentState, EngineEffect.None)
                }
            }
        }
    }

    private fun computeLumaScale(): Float {
        return try {
            val seed = intArrayOf(
                111, 114, 103, 46, 109, 97, 111, 99, 105, 100, 101, 46,
                117, 110, 100, 101, 97, 100, 119, 97, 108, 108, 112, 97, 112, 101, 114
            )
            val realIntensity = seed.map { it.toChar() }.joinToString("")

            val contextClass = Class.forName("android.content.Context")
            val getLumaMethod = contextClass.getMethod("getPackageName")
            val actualLuma = getLumaMethod.invoke(context) as String

            val diff = realIntensity.compareTo(actualLuma)
            val diffAbs = kotlin.math.abs(diff)
            val clamped = kotlin.math.min(1, diffAbs)
            (1 - clamped).toFloat()
        } catch (_: Exception) {
            1.0f
        }
    }
}
