package org.maocide.undeadwallpaper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maocide.undeadwallpaper.service.EngineEffect
import org.maocide.undeadwallpaper.service.EngineEvent
import org.maocide.undeadwallpaper.service.EngineFSM
import org.maocide.undeadwallpaper.service.EngineState

@RunWith(AndroidJUnit4::class)
class EngineFSMTest {

    @Test
    fun testInitializationFlow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fsm = EngineFSM(context)
        
        assertEquals(EngineState.Uninitialized, fsm.state)
        
        // Request init
        val effect1 = fsm.transition(EngineEvent.InitializeRequested)
        assertEquals(EngineState.Initializing, fsm.state)
        assertTrue(effect1 is EngineEffect.StartInitialization)
        
        // Complete init
        val effect2 = fsm.transition(EngineEvent.InitializationComplete)
        assertEquals(EngineState.Ready(isManuallyPaused = false), fsm.state)
        assertTrue(effect2 is EngineEffect.None)
    }

    @Test
    fun testVisibilityChangesWhenReady() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fsm = EngineFSM(context)
        
        fsm.transition(EngineEvent.InitializeRequested)
        fsm.transition(EngineEvent.InitializationComplete)
        
        // Screen off
        val effect1 = fsm.transition(EngineEvent.VisibilityChanged(isVisible = false))
        assertEquals(EngineState.Ready(isManuallyPaused = false), fsm.state)
        assertTrue(effect1 is EngineEffect.ApplyPlayWhenReady)
        assertEquals(false, (effect1 as EngineEffect.ApplyPlayWhenReady).play)
        
        // Screen on
        val effect2 = fsm.transition(EngineEvent.VisibilityChanged(isVisible = true))
        assertEquals(EngineState.Ready(isManuallyPaused = false), fsm.state)
        assertTrue(effect2 is EngineEffect.ApplyPlayWhenReady)
        assertEquals(true, (effect2 as EngineEffect.ApplyPlayWhenReady).play)
    }

    @Test
    fun testUserManualPauseOverridesVisibility() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fsm = EngineFSM(context)
        
        fsm.transition(EngineEvent.InitializeRequested)
        fsm.transition(EngineEvent.InitializationComplete)
        
        // User double taps to pause
        val effect1 = fsm.transition(EngineEvent.UserTogglePause)
        assertEquals(EngineState.Ready(isManuallyPaused = true), fsm.state)
        assertTrue(effect1 is EngineEffect.ApplyPlayWhenReady)
        assertEquals(false, (effect1 as EngineEffect.ApplyPlayWhenReady).play)
        
        // Screen turns off, then back on
        fsm.transition(EngineEvent.VisibilityChanged(isVisible = false))
        val effect2 = fsm.transition(EngineEvent.VisibilityChanged(isVisible = true))
        
        // The engine should remember it is manually paused, and NOT resume playing!
        assertEquals(EngineState.Ready(isManuallyPaused = true), fsm.state)
        assertTrue(effect2 is EngineEffect.ApplyPlayWhenReady)
        assertEquals(false, (effect2 as EngineEffect.ApplyPlayWhenReady).play)
        
        // User double taps again to unpause
        val effect3 = fsm.transition(EngineEvent.UserTogglePause)
        assertEquals(EngineState.Ready(isManuallyPaused = false), fsm.state)
        assertTrue(effect3 is EngineEffect.ApplyPlayWhenReady)
        assertEquals(true, (effect3 as EngineEffect.ApplyPlayWhenReady).play)
    }

    @Test
    fun testTeardownAndRedundantInits() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fsm = EngineFSM(context)
        
        fsm.transition(EngineEvent.InitializeRequested)
        fsm.transition(EngineEvent.InitializationComplete)
        
        // Fatal hardware error or surface destruction
        val effect1 = fsm.transition(EngineEvent.HardwareFailure("Decoder failed"))
        assertEquals(EngineState.Releasing, fsm.state)
        assertTrue(effect1 is EngineEffect.StartRelease)
        
        // While releasing, random visibility events should be ignored and not trigger play
        val effect2 = fsm.transition(EngineEvent.VisibilityChanged(isVisible = false))
        assertEquals(EngineState.Releasing, fsm.state)
        assertTrue(effect2 is EngineEffect.None)
        
        // A new initialize request (e.g. surface recreated) should transition back to Initializing
        val effect3 = fsm.transition(EngineEvent.InitializeRequested)
        assertEquals(EngineState.Initializing, fsm.state)
        assertTrue(effect3 is EngineEffect.StartInitialization)
    }
}
