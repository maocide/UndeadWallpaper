package org.maocide.undeadwallpaper.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maocide.undeadwallpaper.ui.TombstoneController.Companion.ANGLE_SAG
import org.maocide.undeadwallpaper.ui.TombstoneController.Companion.ANGLE_TOPPLE
import org.maocide.undeadwallpaper.ui.TombstoneController.Companion.ANGLE_UPRIGHT
import org.maocide.undeadwallpaper.ui.TombstoneController.Companion.BREAK_TAP_THRESHOLD
import org.maocide.undeadwallpaper.ui.TombstoneController.Companion.DURATION_BREAK_MS
import org.maocide.undeadwallpaper.ui.TombstoneController.Companion.DURATION_RESURRECT_MS
import org.maocide.undeadwallpaper.ui.TombstoneController.Companion.TRANSLATION_Y_TOPPLE_DP
import org.maocide.undeadwallpaper.ui.TombstoneController.Companion.calculateTranslationY
import org.maocide.undeadwallpaper.ui.TombstoneController.Companion.evaluateTap
import org.maocide.undeadwallpaper.ui.TombstoneController.Companion.rollBreakAngle

class TombstoneControllerTest {

    private val delta = 0.001f

    @Test
    fun `constants are properly configured for the Unlucky 13 lore`() {
        assertEquals(13, BREAK_TAP_THRESHOLD)
        assertEquals(0f, ANGLE_UPRIGHT, delta)
        assertEquals(-12f, ANGLE_SAG, delta)
        assertEquals(-85f, ANGLE_TOPPLE, delta)
        assertEquals(-42f, TRANSLATION_Y_TOPPLE_DP, delta)
        assertEquals(750L, DURATION_BREAK_MS)
        assertEquals(400L, DURATION_RESURRECT_MS)
    }

    @Test
    fun `rollBreakAngle resolves to either sag or topple`() {
        assertEquals(ANGLE_TOPPLE, rollBreakAngle(isTopple = true), delta)
        assertEquals(ANGLE_SAG, rollBreakAngle(isTopple = false), delta)
    }

    @Test
    fun `calculateTranslationY returns -42dp scaled by density for topple angle`() {
        // Density 1.0 (mdpi)
        assertEquals(-42f, calculateTranslationY(ANGLE_TOPPLE, 1.0f), delta)
        // Density 2.5
        assertEquals(-105f, calculateTranslationY(ANGLE_TOPPLE, 2.5f), delta)
        // Density 3.0 (xxhdpi, typical 1080p phone)
        assertEquals(-126f, calculateTranslationY(ANGLE_TOPPLE, 3.0f), delta)
    }

    @Test
    fun `calculateTranslationY returns 0 for upright and weathered sag angles`() {
        assertEquals(0f, calculateTranslationY(ANGLE_UPRIGHT, 3.0f), delta)
        assertEquals(0f, calculateTranslationY(ANGLE_SAG, 3.0f), delta)
        assertEquals(0f, calculateTranslationY(-44f, 3.0f), delta)
    }

    @Test
    fun `evaluateTap increments count and does not break on taps 1 through 12`() {
        for (currentTaps in 0..11) {
            val result = evaluateTap(
                currentTaps = currentTaps,
                isAlreadyBroken = false
            )
            assertTrue(result is TombstoneController.TapEvaluation.Progress)
            val progress = result as TombstoneController.TapEvaluation.Progress
            assertEquals(currentTaps + 1, progress.newTapCount)
        }
    }

    @Test
    fun `evaluateTap triggers break on tap 13 when chanceRoll succeeds`() {
        // Tap 13 with topple roll and successful chance
        val toppleResult = evaluateTap(
            currentTaps = 12,
            isAlreadyBroken = false,
            chanceRoll = { true },
            rollAngle = { ANGLE_TOPPLE }
        )
        assertTrue(toppleResult is TombstoneController.TapEvaluation.TriggerBreak)
        val toppleBreak = toppleResult as TombstoneController.TapEvaluation.TriggerBreak
        assertEquals(13, toppleBreak.newTapCount)
        assertEquals(ANGLE_TOPPLE, toppleBreak.angle, delta)

        // Tap 13 with sag roll and successful chance
        val sagResult = evaluateTap(
            currentTaps = 12,
            isAlreadyBroken = false,
            chanceRoll = { true },
            rollAngle = { ANGLE_SAG }
        )
        assertTrue(sagResult is TombstoneController.TapEvaluation.TriggerBreak)
        val sagBreak = sagResult as TombstoneController.TapEvaluation.TriggerBreak
        assertEquals(13, sagBreak.newTapCount)
        assertEquals(ANGLE_SAG, sagBreak.angle, delta)
    }

    @Test
    fun `evaluateTap survives trial of fate on tap 13 when chanceRoll fails and tests again at tap 26`() {
        // Tap 13: trial fails, stone survives
        val survived13 = evaluateTap(
            currentTaps = 12,
            isAlreadyBroken = false,
            chanceRoll = { false }
        )
        assertTrue(survived13 is TombstoneController.TapEvaluation.Progress)
        assertEquals(13, (survived13 as TombstoneController.TapEvaluation.Progress).newTapCount)

        // Taps 14 through 25 progress normally
        for (currentTaps in 13..24) {
            val result = evaluateTap(
                currentTaps = currentTaps,
                isAlreadyBroken = false
            )
            assertTrue(result is TombstoneController.TapEvaluation.Progress)
            assertEquals(currentTaps + 1, (result as TombstoneController.TapEvaluation.Progress).newTapCount)
        }

        // Tap 26: rolls again and breaks!
        val breakAt26 = evaluateTap(
            currentTaps = 25,
            isAlreadyBroken = false,
            chanceRoll = { true },
            rollAngle = { ANGLE_TOPPLE }
        )
        assertTrue(breakAt26 is TombstoneController.TapEvaluation.TriggerBreak)
        assertEquals(26, (breakAt26 as TombstoneController.TapEvaluation.TriggerBreak).newTapCount)
    }

    @Test
    fun `evaluateTap is idempotent when already broken and ignores further taps`() {
        val result = evaluateTap(
            currentTaps = 13,
            isAlreadyBroken = true
        )
        assertTrue(result is TombstoneController.TapEvaluation.Ignored)
        val ignored = result as TombstoneController.TapEvaluation.Ignored
        assertEquals(13, ignored.currentTaps)

        // Even with higher tap counts, remains ignored
        val postResult = evaluateTap(
            currentTaps = 20,
            isAlreadyBroken = true
        )
        assertTrue(postResult is TombstoneController.TapEvaluation.Ignored)
        assertEquals(20, (postResult as TombstoneController.TapEvaluation.Ignored).currentTaps)
    }

    @Test
    fun `resurrection resets state and allows cycle to repeat`() {
        // Simulating post-resurrection state where taps = 0, isAlreadyBroken = false
        val firstTap = evaluateTap(
            currentTaps = 0,
            isAlreadyBroken = false
        )
        assertTrue(firstTap is TombstoneController.TapEvaluation.Progress)
        assertEquals(1, (firstTap as TombstoneController.TapEvaluation.Progress).newTapCount)

        // And reaches 13 again
        val breakAgain = evaluateTap(
            currentTaps = 12,
            isAlreadyBroken = false,
            chanceRoll = { true },
            rollAngle = { ANGLE_TOPPLE }
        )
        assertTrue(breakAgain is TombstoneController.TapEvaluation.TriggerBreak)
        assertEquals(13, (breakAgain as TombstoneController.TapEvaluation.TriggerBreak).newTapCount)
    }
}
