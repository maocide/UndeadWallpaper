package org.maocide.undeadwallpaper.kinetic

import org.maocide.undeadwallpaper.model.KineticEffectType

/**
 * Modifier interface for kinetic somatic touch effects.
 *
 * Implementations evaluate physical formulas (springs, harmonics, decay envelopes)
 * and write transient delta values into outDeltas in place without allocations.
 */
interface KineticEffect {
    val type: KineticEffectType

    /**
     * True while the physical simulation is actively oscillating or decaying.
     * Prevents premature cancellation during harmonic zero-crossings.
     */
    val isActive: Boolean

    /**
     * Fires when a physical touch is confirmed.
     *
     * @param normalizedX Touch X coordinate mapped to [-1.0 .. 1.0] (center is 0.0)
     * @param normalizedY Touch Y coordinate mapped to [-1.0 .. 1.0] (center is 0.0)
     * @param intensity User-selected multiplier [0.5 .. 2.0]
     */
    fun onTouch(normalizedX: Float, normalizedY: Float, intensity: Float)

    /**
     * Evaluates one physics time step and writes transient delta values into outDeltas.
     * Zero-allocation hot path.
     *
     * @param deltaTimeMs Elapsed time since previous tick in milliseconds (clamped)
     * @param outDeltas Pre-allocated destination container
     */
    fun update(deltaTimeMs: Float, outDeltas: KineticDeltas)

    /**
     * Forcefully cancels animation to resting state immediately (e.g. on visibility loss).
     */
    fun reset()
}
