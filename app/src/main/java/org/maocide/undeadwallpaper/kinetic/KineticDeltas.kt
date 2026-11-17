package org.maocide.undeadwallpaper.kinetic

import kotlin.math.abs

/**
 * Carries transient physical deviations (deltaX, deltaY, deltaZoom, deltaRotation, deltaBrightness)
 * computed by kinetic touch effects.
 *
 * Pre-allocated and mutated in-place to guarantee 0 heap allocations during 60/90/120Hz frame ticks.
 */
class KineticDeltas(
    var deltaX: Float = 0.0f,
    var deltaY: Float = 0.0f,
    var deltaZoom: Float = 0.0f,
    var deltaRotation: Float = 0.0f,
    var deltaBrightness: Float = 0.0f
) {
    /**
     * Returns true when all physical deviations have decayed below perceptual thresholds.
     */
    val isDormant: Boolean
        get() = abs(deltaX) < 0.0005f &&
                abs(deltaY) < 0.0005f &&
                abs(deltaZoom) < 0.0005f &&
                abs(deltaRotation) < 0.05f &&
                abs(deltaBrightness) < 0.01f

    fun set(
        x: Float = 0f,
        y: Float = 0f,
        zoom: Float = 0f,
        rotation: Float = 0f,
        brightness: Float = 0f
    ): KineticDeltas {
        this.deltaX = x
        this.deltaY = y
        this.deltaZoom = zoom
        this.deltaRotation = rotation
        this.deltaBrightness = brightness
        return this
    }

    fun reset(): KineticDeltas = set(0f, 0f, 0f, 0f, 0f)

    fun copyFrom(other: KineticDeltas): KineticDeltas {
        this.deltaX = other.deltaX
        this.deltaY = other.deltaY
        this.deltaZoom = other.deltaZoom
        this.deltaRotation = other.deltaRotation
        this.deltaBrightness = other.deltaBrightness
        return this
    }

    fun add(other: KineticDeltas): KineticDeltas {
        this.deltaX += other.deltaX
        this.deltaY += other.deltaY
        this.deltaZoom += other.deltaZoom
        this.deltaRotation += other.deltaRotation
        this.deltaBrightness += other.deltaBrightness
        return this
    }

    companion object {
        /**
         * Immutable resting baseline reference (all deltas = 0).
         */
        val ZERO = KineticDeltas()
    }
}
