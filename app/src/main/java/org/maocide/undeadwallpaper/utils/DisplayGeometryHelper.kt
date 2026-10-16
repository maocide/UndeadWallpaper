package org.maocide.undeadwallpaper.utils

import android.util.DisplayMetrics
import org.maocide.undeadwallpaper.data.PreferencesManager

/**
 * Utility for resolving physical display aspect ratios and dimensions.
 *
 * Reconciles View-layer window metrics (which can be truncated by OEM insets,
 * navigation bars, or system taskbars on devices such as Amazon Fire/Kindle)
 * with ground-truth full-surface dimensions reported by WallpaperService.Engine.
 */
object DisplayGeometryHelper {

    /**
     * Records the raw physical surface dimensions reported by WallpaperService.Engine.
     */
    fun recordSurfaceDimensions(prefs: PreferencesManager, width: Int, height: Int) {
        prefs.saveRawScreenDimensions(width, height)
    }

    /**
     * Resolves the effective screen aspect ratio (width / height) normalized for current orientation,
     * preferring ground-truth surface dimensions recorded in [PreferencesManager] if available.
     */
    fun getEffectiveAspectRatio(
        metrics: DisplayMetrics,
        prefs: PreferencesManager
    ): Float {
        return getEffectiveAspectRatio(
            metricsWidth = metrics.widthPixels,
            metricsHeight = metrics.heightPixels,
            rawLongSide = prefs.getRawScreenLongSide(),
            rawShortSide = prefs.getRawScreenShortSide()
        )
    }

    /**
     * Pure calculation of aspect ratio from primitive dimensions.
     * Maps long/short sides according to whether [metricsWidth] > [metricsHeight].
     */
    fun getEffectiveAspectRatio(
        metricsWidth: Int,
        metricsHeight: Int,
        rawLongSide: Int,
        rawShortSide: Int
    ): Float {
        if (metricsWidth <= 0 || metricsHeight <= 0) return 1f
        val isLandscape = metricsWidth > metricsHeight
        if (rawLongSide > 0 && rawShortSide > 0) {
            val effectiveWidth = if (isLandscape) rawLongSide else rawShortSide
            val effectiveHeight = if (isLandscape) rawShortSide else rawLongSide
            return effectiveWidth.toFloat() / effectiveHeight.toFloat()
        }
        return metricsWidth.toFloat() / metricsHeight.toFloat()
    }

    /**
     * Resolves effective screen dimensions (width, height) matching current orientation,
     * preferring ground-truth surface dimensions recorded in [PreferencesManager] if available.
     */
    fun getEffectiveScreenDimensions(
        metrics: DisplayMetrics,
        prefs: PreferencesManager
    ): Pair<Int, Int> {
        return getEffectiveScreenDimensions(
            metricsWidth = metrics.widthPixels,
            metricsHeight = metrics.heightPixels,
            rawLongSide = prefs.getRawScreenLongSide(),
            rawShortSide = prefs.getRawScreenShortSide()
        )
    }

    /**
     * Pure calculation of effective screen dimensions from primitive dimensions.
     */
    fun getEffectiveScreenDimensions(
        metricsWidth: Int,
        metricsHeight: Int,
        rawLongSide: Int,
        rawShortSide: Int
    ): Pair<Int, Int> {
        if (metricsWidth <= 0 || metricsHeight <= 0) return Pair(0, 0)
        val isLandscape = metricsWidth > metricsHeight
        if (rawLongSide > 0 && rawShortSide > 0) {
            val effectiveWidth = if (isLandscape) rawLongSide else rawShortSide
            val effectiveHeight = if (isLandscape) rawShortSide else rawLongSide
            return Pair(effectiveWidth, effectiveHeight)
        }
        return Pair(metricsWidth, metricsHeight)
    }
}
