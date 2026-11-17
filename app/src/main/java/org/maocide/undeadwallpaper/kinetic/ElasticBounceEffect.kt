package org.maocide.undeadwallpaper.kinetic

import org.maocide.undeadwallpaper.model.KineticEffectType
import kotlin.math.abs

/**
 * Elastic Trampoline Bounce Effect:
 * Simulates pressing an elastic rubber membrane on the Z-axis (Zoom/Scale).
 * Uses an underdamped spring-mass-damper system to provide a springy indentation
 * and rebound with a subtle settling overshoot.
 */
class ElasticBounceEffect : KineticEffect {
    override val type: KineticEffectType = KineticEffectType.ELASTIC_BOUNCE

    private var currentZoom = 0.0f
    private var velocity = 0.0f
    private var _isActive = false

    override val isActive: Boolean
        get() = _isActive

    // Spring-mass-damper constants (m = 1.0)
    private val stiffness = 95.0f // k (softened for deeper elastic stretch)
    private val damping = 8.5f    // c (reduced for visible springy overshoot)

    override fun onTouch(normalizedX: Float, normalizedY: Float, intensity: Float) {
        val clampedIntensity = intensity.coerceIn(0.5f, 2.0f)
        val effectiveIntensity = clampedIntensity * clampedIntensity
        val impulse = 2.8f * effectiveIntensity
        // Accumulate velocity with physical bounds to prevent runaway acceleration on rapid tap spam
        velocity = (velocity + impulse).coerceIn(-6.5f, 6.5f)
        _isActive = true
    }

    override fun update(deltaTimeMs: Float, outDeltas: KineticDeltas) {
        if (!_isActive) {
            outDeltas.deltaZoom = 0f
            return
        }

        // Clamp delta time to avoid simulation instability during system hitches
        val dt = (deltaTimeMs / 1000.0f).coerceIn(0.001f, 0.033f)

        val displacement = currentZoom
        val springForce = -stiffness * displacement
        val dampingForce = -damping * velocity
        val acceleration = springForce + dampingForce

        velocity += acceleration * dt
        currentZoom += velocity * dt

        // Check if settled to resting equilibrium
        if (abs(currentZoom) < 0.0005f && abs(velocity) < 0.005f) {
            currentZoom = 0.0f
            velocity = 0.0f
            _isActive = false
        }

        outDeltas.deltaZoom = currentZoom
    }

    override fun reset() {
        currentZoom = 0.0f
        velocity = 0.0f
        _isActive = false
    }
}
