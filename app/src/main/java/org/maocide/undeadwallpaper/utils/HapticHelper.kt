package org.maocide.undeadwallpaper.utils

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import org.maocide.undeadwallpaper.model.KineticEffectType

object HapticHelper {
    private const val TAG = "HapticHelper"

    private fun getSafeVibrator(context: Context): Vibrator? {
        val manager = ContextCompat.getSystemService(context, VibratorManager::class.java)
        val vibrator = manager?.defaultVibrator
        return if (vibrator != null && vibrator.hasVibrator()) vibrator else null
    }

    private fun vibrateCompat(vibrator: Vibrator, effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attrs = VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_TOUCH)
                .build()
            vibrator.vibrate(effect, attrs)
        } else {
            vibrator.vibrate(effect)
        }
    }

    /**
     * Performs a haptic click for successful gesture activations.
     * Adapts between modern linear haptic motors (Pixel/Samsung) and ERM/MIUI motors.
     */
    fun performGestureFeedback(context: Context) {
        try {
            val vibrator = getSafeVibrator(context) ?: return
            val supportsClick = vibrator.areAllEffectsSupported(VibrationEffect.EFFECT_CLICK) ==
                Vibrator.VIBRATION_EFFECT_SUPPORT_YES

            // Fallback to 30ms pulse if hardware HAL lacks EFFECT_CLICK (common on Redmi / ERM motors)
            val effect = if (supportsClick) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            } else {
                VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            vibrateCompat(vibrator, effect)
        } catch (t: Throwable) {
            FileLogger.e(TAG, "Failed to perform gesture haptic feedback", t)
        }
    }

    /**
     * Performs a dual-short tactile confirmation pulse ("bzt-bzt") when Force Feedback is toggled on.
     * Safely guarded against missing or broken OEM vibrator HALs.
     */
    fun performDualShortFeedback(context: Context) {
        try {
            val vibrator = getSafeVibrator(context) ?: return
            if (vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.8f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 65)
                    .compose()
                vibrateCompat(vibrator, effect)
            } else {
                // Crisp dual-short waveform fallback for ERM motors:
                // 0ms delay -> 20ms pulse -> 65ms gap -> 25ms pulse
                val timings = longArrayOf(0L, 20L, 65L, 25L)
                val effect = VibrationEffect.createWaveform(timings, -1)
                vibrateCompat(vibrator, effect)
            }
        } catch (t: Throwable) {
            FileLogger.e(TAG, "Failed to perform dual-short haptic feedback", t)
        }
    }

    /**
     * Performs synchronized tactile haptic feedback for kinetic touch affects.
     * Differentiates vibration profile based on effect type (Click for Bounce, Heavy Click for Jolt).
     */
    fun performKineticImpactFeedback(context: Context, type: KineticEffectType) {
        if (type == KineticEffectType.NONE) return
        try {
            val vibrator = getSafeVibrator(context) ?: return
            val targetEffectId = if (type == KineticEffectType.SEISMIC_JOLT) {
                VibrationEffect.EFFECT_HEAVY_CLICK
            } else {
                VibrationEffect.EFFECT_CLICK
            }

            val supportsEffect = vibrator.areAllEffectsSupported(targetEffectId) ==
                Vibrator.VIBRATION_EFFECT_SUPPORT_YES

            val fallbackMs = if (type == KineticEffectType.SEISMIC_JOLT) 40L else 25L
            val effect = if (supportsEffect) {
                VibrationEffect.createPredefined(targetEffectId)
            } else {
                VibrationEffect.createOneShot(fallbackMs, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            vibrateCompat(vibrator, effect)
        } catch (t: Throwable) {
            FileLogger.e(TAG, "Failed to perform kinetic haptic feedback", t)
        }
    }
}
