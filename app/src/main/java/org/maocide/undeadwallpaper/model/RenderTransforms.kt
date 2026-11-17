package org.maocide.undeadwallpaper.model

import org.maocide.undeadwallpaper.kinetic.KineticDeltas

/**
 * Consolidated, mutable state snapshot for all visual transforms consumed by GLVideoRenderer.
 * Used with the Triple-Buffer Mailbox pattern to guarantee zero heap allocations in the hot render loop.
 */
class RenderTransforms(
    var scalingMode: ScalingMode = ScalingMode.FILL,
    var translateX: Float = 0.0f,
    var translateY: Float = 0.0f,
    var zoom: Float = 1.0f,
    var rotation: Float = 0.0f,
    var brightness: Float = 1.0f,
    var contrast: Float = 1.0f,
    var saturation: Float = 1.0f,
    var flipHorizontal: Boolean = false,
    var flipVertical: Boolean = false,
    var parallaxX: Float = 0.0f
) {
    /**
     * Synthesizes base video settings, physical kinetic deltas, and display luma scaling
     * directly into this instance without heap allocations.
     */
    fun synthesize(
        settings: VideoSettings,
        deltas: KineticDeltas,
        effectiveLumaScale: Float,
        parallaxX: Float = this.parallaxX
    ): RenderTransforms {
        val lumaOffset = -100.0f * (1.0f - effectiveLumaScale)
        val calculatedBrightness = (settings.brightness * effectiveLumaScale) + lumaOffset
        val finalZoom = (settings.zoom * (1.0f + deltas.deltaZoom)).coerceIn(0.1f, 10.0f)

        return set(
            scalingMode = settings.scalingMode,
            translateX = settings.positionX + deltas.deltaX,
            translateY = settings.positionY + deltas.deltaY,
            zoom = finalZoom,
            rotation = settings.rotation + deltas.deltaRotation,
            brightness = calculatedBrightness + deltas.deltaBrightness,
            contrast = settings.contrast,
            saturation = settings.saturation,
            flipHorizontal = settings.flipHorizontal,
            flipVertical = settings.flipVertical,
            parallaxX = parallaxX
        )
    }

    /**
     * Synthesizes base geometry and visual attributes directly into this instance without heap allocations.
     */
    fun set(
        scalingMode: ScalingMode,
        translateX: Float,
        translateY: Float,
        zoom: Float,
        rotation: Float,
        brightness: Float,
        contrast: Float,
        saturation: Float,
        flipHorizontal: Boolean,
        flipVertical: Boolean,
        parallaxX: Float = this.parallaxX
    ): RenderTransforms {
        this.scalingMode = scalingMode
        this.translateX = translateX
        this.translateY = translateY
        this.zoom = zoom
        this.rotation = rotation
        this.brightness = brightness
        this.contrast = contrast
        this.saturation = saturation
        this.flipHorizontal = flipHorizontal
        this.flipVertical = flipVertical
        this.parallaxX = parallaxX
        return this
    }

    fun copyFrom(other: RenderTransforms): RenderTransforms {
        this.scalingMode = other.scalingMode
        this.translateX = other.translateX
        this.translateY = other.translateY
        this.zoom = other.zoom
        this.rotation = other.rotation
        this.brightness = other.brightness
        this.contrast = other.contrast
        this.saturation = other.saturation
        this.flipHorizontal = other.flipHorizontal
        this.flipVertical = other.flipVertical
        this.parallaxX = other.parallaxX
        return this
    }

    fun reset(): RenderTransforms {
        return set(
            scalingMode = ScalingMode.FILL,
            translateX = 0f,
            translateY = 0f,
            zoom = 1f,
            rotation = 0f,
            brightness = 1f,
            contrast = 1f,
            saturation = 1f,
            flipHorizontal = false,
            flipVertical = false,
            parallaxX = 0f
        )
    }
}
