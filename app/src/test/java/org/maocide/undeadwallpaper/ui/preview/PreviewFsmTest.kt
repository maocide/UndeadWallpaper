package org.maocide.undeadwallpaper.ui.preview

import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class PreviewFsmTest {

    private class MockPlayerHolder {
        var playWhenReady: Boolean = false

        val player: ExoPlayer = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java)
        ) { _, method, args ->
            when (method.name) {
                "setPlayWhenReady" -> {
                    playWhenReady = args[0] as Boolean
                    null
                }
                "getPlayWhenReady" -> playWhenReady
                "toString" -> "MockExoPlayer"
                "hashCode" -> 42
                "equals" -> args?.get(0) != null
                else -> null
            }
        } as ExoPlayer
    }

    @Test
    fun testInitialState_isIdle() {
        val fsm = PreviewFsm()
        assertTrue(fsm.state is PreviewFsm.PreviewState.Idle)
    }

    @Test
    fun testAttachPlayer_whileHostNotResumed_staysIdle() {
        val fsm = PreviewFsm()
        val holder = MockPlayerHolder()

        fsm.processEvent(PreviewFsm.PreviewEvent.PlayerAttached(holder.player))
        assertTrue(fsm.state is PreviewFsm.PreviewState.Idle)
        assertFalse(holder.playWhenReady)
    }

    @Test
    fun testHostResumed_withAttachedPlayer_transitionsToPlaying() {
        val fsm = PreviewFsm()
        val holder = MockPlayerHolder()

        fsm.processEvent(PreviewFsm.PreviewEvent.PlayerAttached(holder.player))
        fsm.processEvent(PreviewFsm.PreviewEvent.HostResumed(true))

        assertTrue(fsm.state is PreviewFsm.PreviewState.Playing)
        assertTrue(holder.playWhenReady)
    }

    @Test
    fun testSheetOpened_whilePlaying_transitionsToPausedOccluded() {
        val fsm = PreviewFsm()
        val holder = MockPlayerHolder()

        fsm.processEvent(PreviewFsm.PreviewEvent.PlayerAttached(holder.player))
        fsm.processEvent(PreviewFsm.PreviewEvent.HostResumed(true))
        assertTrue(holder.playWhenReady)

        // Sheet opens (e.g. VideoSettingsSheet)
        fsm.processEvent(PreviewFsm.PreviewEvent.SheetVisibilityChanged(isOccluded = true))

        assertTrue(fsm.state is PreviewFsm.PreviewState.PausedOccluded)
        assertFalse(holder.playWhenReady)

        // Sheet closes
        fsm.processEvent(PreviewFsm.PreviewEvent.SheetVisibilityChanged(isOccluded = false))

        assertTrue(fsm.state is PreviewFsm.PreviewState.Playing)
        assertTrue(holder.playWhenReady)
    }

    @Test
    fun testCardScrolledOffscreen_transitionsToPausedOffscreen() {
        val fsm = PreviewFsm()
        val holder = MockPlayerHolder()

        fsm.processEvent(PreviewFsm.PreviewEvent.PlayerAttached(holder.player))
        fsm.processEvent(PreviewFsm.PreviewEvent.HostResumed(true))
        assertTrue(holder.playWhenReady)

        // User scrolls down in landscape or dismissed hero mode
        fsm.processEvent(PreviewFsm.PreviewEvent.CardVisibilityChanged(isVisibleOnScreen = false))

        assertTrue(fsm.state is PreviewFsm.PreviewState.PausedOffscreen)
        assertFalse(holder.playWhenReady)

        // User scrolls back to the top
        fsm.processEvent(PreviewFsm.PreviewEvent.CardVisibilityChanged(isVisibleOnScreen = true))

        assertTrue(fsm.state is PreviewFsm.PreviewState.Playing)
        assertTrue(holder.playWhenReady)
    }

    @Test
    fun testCompoundOcclusion_sheetAndOffscreen() {
        val fsm = PreviewFsm()
        val holder = MockPlayerHolder()

        fsm.processEvent(PreviewFsm.PreviewEvent.PlayerAttached(holder.player))
        fsm.processEvent(PreviewFsm.PreviewEvent.HostResumed(true))

        // 1. Scrolled offscreen first
        fsm.processEvent(PreviewFsm.PreviewEvent.CardVisibilityChanged(isVisibleOnScreen = false))
        assertTrue(fsm.state is PreviewFsm.PreviewState.PausedOffscreen)
        assertFalse(holder.playWhenReady)

        // 2. Open sheet while offscreen
        fsm.processEvent(PreviewFsm.PreviewEvent.SheetVisibilityChanged(isOccluded = true))
        assertTrue(fsm.state is PreviewFsm.PreviewState.PausedOccluded)
        assertFalse(holder.playWhenReady)

        // 3. Close sheet: should return to PausedOffscreen, NOT Playing!
        fsm.processEvent(PreviewFsm.PreviewEvent.SheetVisibilityChanged(isOccluded = false))
        assertTrue(fsm.state is PreviewFsm.PreviewState.PausedOffscreen)
        assertFalse(holder.playWhenReady)

        // 4. Finally scroll back to top: now it should enter Playing
        fsm.processEvent(PreviewFsm.PreviewEvent.CardVisibilityChanged(isVisibleOnScreen = true))
        assertTrue(fsm.state is PreviewFsm.PreviewState.Playing)
        assertTrue(holder.playWhenReady)
    }

    @Test
    fun testHostPaused_transitionsToIdle() {
        val fsm = PreviewFsm()
        val holder = MockPlayerHolder()

        fsm.processEvent(PreviewFsm.PreviewEvent.PlayerAttached(holder.player))
        fsm.processEvent(PreviewFsm.PreviewEvent.HostResumed(true))
        assertTrue(fsm.state is PreviewFsm.PreviewState.Playing)

        // App backgrounded
        fsm.processEvent(PreviewFsm.PreviewEvent.HostResumed(false))
        assertTrue(fsm.state is PreviewFsm.PreviewState.Idle)
        assertFalse(holder.playWhenReady)
    }

    @Test
    fun testPlayerReleased_transitionsToIdle() {
        val fsm = PreviewFsm()
        val holder = MockPlayerHolder()

        fsm.processEvent(PreviewFsm.PreviewEvent.PlayerAttached(holder.player))
        fsm.processEvent(PreviewFsm.PreviewEvent.HostResumed(true))
        assertTrue(fsm.state is PreviewFsm.PreviewState.Playing)

        fsm.processEvent(PreviewFsm.PreviewEvent.PlayerReleased)
        assertTrue(fsm.state is PreviewFsm.PreviewState.Idle)
    }
}
