package org.maocide.undeadwallpaper.kinetic

import org.maocide.undeadwallpaper.model.KineticEffectType
import kotlin.math.abs

/**
 * Vertical Hop Effect:
 * Simulates a vertical character hop or spring jump on the Y-axis.
 * On tap, injects an upward impulse (+Y), followed by harmonic spring-mass restoration
 * with an underdamped landing knee-bend overshoot before settling back to baseline.
 */
class VerticalHopEffect : KineticEffect {
    override val type: KineticEffectType = KineticEffectType.VERTICAL_HOP

    private var currentY = 0.0f
    private var velocity = 0.0f
    private var _isActive = false

    override val isActive: Boolean
        get() = _isActive

    // Spring-mass-damper constants (m = 1.0)
    // Tuned for a snappy vertical leap and organic landing squish
    private val stiffness = 70.0f // k (softened for higher, more buoyant vertical arc)
    private val damping = 7.0f    // c (reduced damping for hangtime and springy landing)

    override fun onTouch(normalizedX: Float, normalizedY: Float, intensity: Float) {
        val clampedIntensity = intensity.coerceIn(0.5f, 2.0f)
        val effectiveIntensity = clampedIntensity * clampedIntensity
        val impulse = 2.2f * effectiveIntensity
        // In OpenGL orthographic projection, +Y is upward
        velocity = (velocity + impulse).coerceIn(-6.0f, 6.0f)
        _isActive = true
    }

    override fun update(deltaTimeMs: Float, outDeltas: KineticDeltas) {
        if (!_isActive) {
            outDeltas.deltaY = 0f
            return
        }

        val dt = (deltaTimeMs / 1000.0f).coerceIn(0.001f, 0.033f)

        val displacement = currentY
        val springForce = -stiffness * displacement
        val dampingForce = -damping * velocity
        val acceleration = springForce + dampingForce

        velocity += acceleration * dt
        currentY += velocity * dt

        // Check if settled to resting equilibrium
        if (abs(currentY) < 0.0005f && abs(velocity) < 0.005f) {
            currentY = 0.0f
            velocity = 0.0f
            _isActive = false
        }

        outDeltas.deltaY = currentY
    }

    override fun reset() {
        currentY = 0.0f
        velocity = 0.0f
        _isActive = false
    }
}
