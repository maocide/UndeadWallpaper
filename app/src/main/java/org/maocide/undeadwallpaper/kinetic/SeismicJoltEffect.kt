package org.maocide.undeadwallpaper.kinetic

import org.maocide.undeadwallpaper.model.KineticEffectType
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Seismic Jolt Effect:
 * High-frequency decaying sinusoidal oscillator on X and Y with randomized phase jitter.
 * Simulates an intense physical tremor or seismic shock on touch that shudders the video
 * and decays rapidly within ~250ms.
 */
class SeismicJoltEffect : KineticEffect {
    override val type: KineticEffectType = KineticEffectType.SEISMIC_JOLT

    private var amplitude = 0.0f
    private var elapsedTimeS = 0.0f
    private var phaseX = 0.0f
    private var phaseY = 0.0f
    private var _isActive = false

    override val isActive: Boolean
        get() = _isActive

    // Oscillation constants
    private val omega = 75.0f     // ~12 Hz rapid shudder frequency
    private var decayRate = 14.0f // Dynamically scaled with intensity

    override fun onTouch(normalizedX: Float, normalizedY: Float, intensity: Float) {
        val clampedIntensity = intensity.coerceIn(0.5f, 2.0f)
        // Quadratic response curve for perceptible dynamic range across the slider
        amplitude = 0.095f * (clampedIntensity * clampedIntensity)
        decayRate = 11.0f - (clampedIntensity * 1.5f)
        elapsedTimeS = 0.0f

        // Random phase offset seeded with touch coordinates for distinct tremor directions
        phaseX = (normalizedX * 3.14159f) + Random.nextFloat()
        phaseY = (normalizedY * 3.14159f) + Random.nextFloat()
        _isActive = true
    }

    override fun update(deltaTimeMs: Float, outDeltas: KineticDeltas) {
        if (!_isActive) {
            outDeltas.deltaX = 0f
            outDeltas.deltaY = 0f
            return
        }

        val dt = (deltaTimeMs / 1000.0f).coerceIn(0.001f, 0.033f)
        elapsedTimeS += dt

        val envelope = amplitude * exp(-decayRate * elapsedTimeS)
        if (envelope < 0.0005f) {
            amplitude = 0.0f
            elapsedTimeS = 0.0f
            _isActive = false
            outDeltas.deltaX = 0f
            outDeltas.deltaY = 0f
        } else {
            outDeltas.deltaX = envelope * sin(omega * elapsedTimeS + phaseX)
            outDeltas.deltaY = envelope * cos(omega * elapsedTimeS + phaseY)
        }
    }

    override fun reset() {
        amplitude = 0.0f
        elapsedTimeS = 0.0f
        _isActive = false
    }
}
