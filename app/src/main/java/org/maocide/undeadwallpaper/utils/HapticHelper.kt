package org.maocide.undeadwallpaper.utils

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat

object HapticHelper {
    private const val TAG = "HapticHelper"

    /**
     * Performs a haptic click for successful gesture activations.
     * Adapts between modern linear haptic motors (Pixel/Samsung) and ERM/MIUI motors.
     */
    fun performGestureFeedback(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = ContextCompat.getSystemService(context, VibratorManager::class.java)
                manager?.defaultVibrator
            } else {
                ContextCompat.getSystemService(context, Vibrator::class.java)
            }

            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val supportsClick = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        vibrator.areAllEffectsSupported(VibrationEffect.EFFECT_CLICK) == Vibrator.VIBRATION_EFFECT_SUPPORT_YES
                    } else {
                        false
                    }

                    // Fallback to 30ms pulse if hardware HAL lacks EFFECT_CLICK (common on Redmi / ERM motors)
                    val effect = if (supportsClick) {
                        VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                    } else {
                        VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val attrs = VibrationAttributes.Builder()
                            .setUsage(VibrationAttributes.USAGE_TOUCH)
                            .build()
                        vibrator.vibrate(effect, attrs)
                    } else {
                        vibrator.vibrate(effect)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(35)
                }
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to perform gesture haptic feedback", e)
        }
    }
}
