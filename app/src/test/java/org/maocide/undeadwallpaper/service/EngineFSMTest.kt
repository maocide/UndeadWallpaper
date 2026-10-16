package org.maocide.undeadwallpaper.service

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class EngineFSMTest {

    private class FakeContext(private val pkg: String = "org.maocide.undeadwallpaper") : android.content.ContextWrapper(null) {
        override fun getPackageName(): String = pkg
    }

    private lateinit var fsm: EngineFSM

    @Before
    fun setUp() {
        fsm = EngineFSM(FakeContext())
    }

    @Test
    fun initialState_isUninitialized() {
        assertEquals(EngineState.Uninitialized, fsm.state)
    }

    @Test
    fun initializeRequested_fromUninitialized_transitionsToInitializing() {
        val effect = fsm.transition(EngineEvent.InitializeRequested)
        assertEquals(EngineState.Initializing, fsm.state)
        assertTrue(effect is EngineEffect.StartInitialization)
        assertEquals(1.0f, (effect as EngineEffect.StartInitialization).lumaScale, 0.001f)
    }

    @Test
    fun initializationComplete_fromInitializing_transitionsToReady() {
        fsm.transition(EngineEvent.InitializeRequested)
        val effect = fsm.transition(EngineEvent.InitializationComplete)
        assertEquals(EngineState.Ready(isManuallyPaused = false), fsm.state)
        assertEquals(EngineEffect.None, effect)
    }

    @Test
    fun userTogglePause_inReadyState_togglesPause() {
        fsm.transition(EngineEvent.InitializeRequested)
        fsm.transition(EngineEvent.InitializationComplete)

        // First tap: pauses
        val effect1 = fsm.transition(EngineEvent.UserTogglePause)
        assertEquals(EngineState.Ready(isManuallyPaused = true), fsm.state)
        assertEquals(EngineEffect.ApplyPlayWhenReady(false), effect1)

        // Second tap: resumes
        val effect2 = fsm.transition(EngineEvent.UserTogglePause)
        assertEquals(EngineState.Ready(isManuallyPaused = false), fsm.state)
        assertEquals(EngineEffect.ApplyPlayWhenReady(true), effect2)
    }

    @Test
    fun enterThanatosis_fromInitializing_transitionsToThanatosis() {
        fsm.transition(EngineEvent.InitializeRequested)

        val effect = fsm.transition(EngineEvent.EnterThanatosis("Corrupted MP4 container", isZeroDecoder = false))
        assertEquals(EngineState.Thanatosis("Corrupted MP4 container", isZeroDecoder = false), fsm.state)
        assertTrue(effect is EngineEffect.StartThanatosis)
        val thanatosisEffect = effect as EngineEffect.StartThanatosis
        assertEquals("Corrupted MP4 container", thanatosisEffect.cause)
        assertEquals(false, thanatosisEffect.isZeroDecoder)
    }

    @Test
    fun enterThanatosis_fromReady_transitionsToThanatosisZeroDecoder() {
        fsm.transition(EngineEvent.InitializeRequested)
        fsm.transition(EngineEvent.InitializationComplete)

        val effect = fsm.transition(EngineEvent.EnterThanatosis("Hardware VPU Starvation", isZeroDecoder = true))
        assertEquals(EngineState.Thanatosis("Hardware VPU Starvation", isZeroDecoder = true), fsm.state)
        assertTrue(effect is EngineEffect.StartThanatosis)
        val thanatosisEffect = effect as EngineEffect.StartThanatosis
        assertEquals("Hardware VPU Starvation", thanatosisEffect.cause)
        assertEquals(true, thanatosisEffect.isZeroDecoder)
    }

    @Test
    fun thanatosis_ignoresUserTogglePause_playsDead() {
        fsm.transition(EngineEvent.EnterThanatosis("Fatal media error", isZeroDecoder = false))
        assertEquals(EngineState.Thanatosis("Fatal media error", isZeroDecoder = false), fsm.state)

        // User tries double/triple tap: organism ignores tactile stimuli
        val effect = fsm.transition(EngineEvent.UserTogglePause)
        assertEquals(EngineState.Thanatosis("Fatal media error", isZeroDecoder = false), fsm.state)
        assertEquals(EngineEffect.None, effect)
    }

    @Test
    fun thanatosis_typeA_throttlesOnVisibilityChanged() {
        fsm.transition(EngineEvent.EnterThanatosis("Missing URI", isZeroDecoder = false))

        // Screen turned off in pocket: pause quarantine video
        val effectOff = fsm.transition(EngineEvent.VisibilityChanged(isVisible = false))
        assertEquals(EngineState.Thanatosis("Missing URI", isZeroDecoder = false), fsm.state)
        assertEquals(EngineEffect.ApplyPlayWhenReady(false), effectOff)

        // Screen turned on: resume quarantine video
        val effectOn = fsm.transition(EngineEvent.VisibilityChanged(isVisible = true))
        assertEquals(EngineState.Thanatosis("Missing URI", isZeroDecoder = false), fsm.state)
        assertEquals(EngineEffect.ApplyPlayWhenReady(true), effectOn)
    }

    @Test
    fun thanatosis_typeB_zeroDecoder_ignoresVisibilityThrottle() {
        fsm.transition(EngineEvent.EnterThanatosis("Decoder OOM", isZeroDecoder = true))

        // Screen turned off: zero-decoder static texture requires no ExoPlayer playWhenReady calls
        val effectOff = fsm.transition(EngineEvent.VisibilityChanged(isVisible = false))
        assertEquals(EngineState.Thanatosis("Decoder OOM", isZeroDecoder = true), fsm.state)
        assertEquals(EngineEffect.None, effectOff)

        val effectOn = fsm.transition(EngineEvent.VisibilityChanged(isVisible = true))
        assertEquals(EngineState.Thanatosis("Decoder OOM", isZeroDecoder = true), fsm.state)
        assertEquals(EngineEffect.None, effectOn)
    }

    @Test
    fun thanatosis_upgradesToZeroDecoderIfNewlyRequested() {
        fsm.transition(EngineEvent.EnterThanatosis("Initial container error", isZeroDecoder = false))
        assertEquals(EngineState.Thanatosis("Initial container error", isZeroDecoder = false), fsm.state)

        // Subsequent hardware error elevates to zero-decoder
        val effect = fsm.transition(EngineEvent.EnterThanatosis("Secondary codec exhaustion", isZeroDecoder = true))
        assertEquals(EngineState.Thanatosis("Secondary codec exhaustion", isZeroDecoder = true), fsm.state)
        assertEquals(EngineEffect.StartThanatosis("Secondary codec exhaustion", isZeroDecoder = true), effect)

        // Redundant zero-decoder does not re-emit StartThanatosis
        val redundantEffect = fsm.transition(EngineEvent.EnterThanatosis("Another error", isZeroDecoder = true))
        assertEquals(EngineState.Thanatosis("Another error", isZeroDecoder = true), fsm.state)
        assertEquals(EngineEffect.None, redundantEffect)
    }

    @Test
    fun thanatosis_resurrectionExitRamp_onInitializeRequested() {
        fsm.transition(EngineEvent.EnterThanatosis("Dead media", isZeroDecoder = false))

        // User picked a new valid video in the Settings UI
        val effect = fsm.transition(EngineEvent.InitializeRequested)
        assertEquals(EngineState.Initializing, fsm.state)
        assertTrue(effect is EngineEffect.StartInitialization)

        // Resurrected to Ready
        fsm.transition(EngineEvent.InitializationComplete)
        assertEquals(EngineState.Ready(isManuallyPaused = false), fsm.state)
    }

    @Test
    fun thanatosis_releaseRequested_transitionsToReleasing() {
        fsm.transition(EngineEvent.EnterThanatosis("Dead media", isZeroDecoder = false))

        val effect = fsm.transition(EngineEvent.ReleaseRequested)
        assertEquals(EngineState.Releasing, fsm.state)
        assertEquals(EngineEffect.StartRelease, effect)
    }
}
