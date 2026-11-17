package org.maocide.undeadwallpaper.kinetic

import android.view.Choreographer
import org.maocide.undeadwallpaper.model.KineticEffectType

/**
 * Orchestrator for kinetic touch animations operating outside OpenGL ES.
 *
 * Runs on Android's Choreographer frame ticks on the Main thread.
 * Evaluates active physical modifiers, writes deltas into pre-allocated objects (0 GC),
 * and dispatches transient deltas to the wallpaper service and renderer sink.
 *
 * Guarantees zero idle overhead: when all effects settle, the ticker strictly unregisters
 * from Choreographer and draws a clean ZERO snap (0.00 mW idle battery).
 */
class KineticTransformPipeline(
    private val onDeltasUpdated: (deltas: KineticDeltas) -> Unit,
    private val requestRender: () -> Unit
) {
    var effectType: KineticEffectType = KineticEffectType.NONE
        set(value) {
            if (field != value) {
                field = value
                reset()
            }
        }

    var intensity: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.5f, 2.0f)
        }

    val isEnabled: Boolean
        get() = effectType != KineticEffectType.NONE

    // Pre-allocated effect instances (0 GC allocation at runtime)
    private val bounceEffect = ElasticBounceEffect()
    private val joltEffect = SeismicJoltEffect()
    private val hopEffect = VerticalHopEffect()

    // Object pool for delta accumulation
    private val accumulator = KineticDeltas()
    private val tempDeltas = KineticDeltas()

    private var isTicking = false
    private var lastFrameNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isTicking) return

            if (lastFrameNanos == 0L) {
                lastFrameNanos = frameTimeNanos
            }

            // Calculate true hardware delta time, clamped to prevent simulation blowup on hitches
            val dtMs = ((frameTimeNanos - lastFrameNanos) / 1_000_000.0f).coerceIn(4.0f, 32.0f)
            lastFrameNanos = frameTimeNanos

            accumulator.reset()
            val activeEffect = getActiveEffect()

            if (activeEffect != null && activeEffect.isActive) {
                tempDeltas.reset()
                activeEffect.update(dtMs, tempDeltas)
                accumulator.add(tempDeltas)

                onDeltasUpdated(accumulator)
                requestRender()

                // Continue ticking as long as physics are alive
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                // Settle to pure resting zero baseline and unregister from Choreographer
                onDeltasUpdated(KineticDeltas.ZERO)
                requestRender()
                isTicking = false
                lastFrameNanos = 0L
            }
        }
    }

    /**
     * Handles launcher touch input.
     *
     * @param x Touch X in pixels
     * @param y Touch Y in pixels
     * @param screenWidth Logical screen width
     * @param screenHeight Logical screen height
     * @return true if an effect was triggered
     */
    fun onTouch(x: Float, y: Float, screenWidth: Int, screenHeight: Int): Boolean {
        if (!isEnabled || screenWidth <= 0 || screenHeight <= 0) return false

        val activeEffect = getActiveEffect() ?: return false

        // Normalize coordinates to [-1.0 .. 1.0] (center is 0.0)
        val normX = ((x / screenWidth.toFloat()) * 2.0f - 1.0f).coerceIn(-1.0f, 1.0f)
        val normY = ((y / screenHeight.toFloat()) * 2.0f - 1.0f).coerceIn(-1.0f, 1.0f)

        activeEffect.onTouch(normX, normY, intensity)

        if (!isTicking) {
            isTicking = true
            lastFrameNanos = 0L
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
        return true
    }

    /**
     * Resets all animations and stops the frame ticker immediately.
     */
    fun reset() {
        if (isTicking) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            isTicking = false
            lastFrameNanos = 0L
        }
        bounceEffect.reset()
        joltEffect.reset()
        hopEffect.reset()
        onDeltasUpdated(KineticDeltas.ZERO)
    }

    private fun getActiveEffect(): KineticEffect? {
        return when (effectType) {
            KineticEffectType.ELASTIC_BOUNCE -> bounceEffect
            KineticEffectType.SEISMIC_JOLT -> joltEffect
            KineticEffectType.VERTICAL_HOP -> hopEffect
            else -> null
        }
    }
}
