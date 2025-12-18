package org.maocide.undeadwallpaper

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.core.net.toUri
import org.maocide.undeadwallpaper.model.PlaybackMode
import org.maocide.undeadwallpaper.model.ScalingMode

/**
 * Manages SharedPreferences for the application.
 * This class encapsulates the logic for storing and retrieving user preferences.
 *
 * @param context The application context.
 */
class PreferencesManager(context: Context) {

    private val sharedPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "DEFAULT"
        private const val KEY_VIDEO_URI = "video_uri"
        private const val KEY_VIDEO_AUDIO_ENABLED = "video_audio_enabled"
        private const val KEY_VIDEO_START_MS = "video_start_ms"
        private const val KEY_VIDEO_END_MS = "video_end_ms"
        private const val KEY_PLAYBACK_MODE = "playback_mode"
        private const val KEY_SCALING_MODE = "scaling_mode"
        private const val KEY_POSITION_X = "video_position_x"
        private const val KEY_POSITION_Y = "video_position_y"
        private const val KEY_ZOOM = "video_zoom"
        private const val KEY_BRIGHTNESS = "video_brightness"
        private const val KEY_ROTATION = "video_rotation"
    }

    /**
     * Saves the video URI to SharedPreferences.
     *
     * @param uri The URI of the video to save.
     */
    fun saveVideoUri(uri: String) {
        sharedPrefs.edit {
            putString(KEY_VIDEO_URI, uri)
        }
    }

    /**
     * Retrieves the video URI from SharedPreferences.
     *
     * @return The saved video URI, or null if not found.
     */
    fun getVideoUri(): String? {
        return sharedPrefs.getString(KEY_VIDEO_URI, null)
    }

    /**
     * Saves the audio enabled status to SharedPreferences.
     *
     * @param isEnabled Whether the audio is enabled.
     */
    fun saveAudioEnabled(isEnabled: Boolean) {
        sharedPrefs.edit {
            putBoolean(KEY_VIDEO_AUDIO_ENABLED, isEnabled)
        }
    }

    /**
     * Retrieves the audio enabled status from SharedPreferences.
     *
     * @return True if audio is enabled, false otherwise.
     */
    fun isAudioEnabled(): Boolean {
        return sharedPrefs.getBoolean(KEY_VIDEO_AUDIO_ENABLED, false)
    }

    /**
     * Saves the video clipping times to SharedPreferences.
     *
     * @param startMs The start time in milliseconds.
     * @param endMs The end time in milliseconds.
     */
    fun saveClippingTimes(startMs: Long, endMs: Long) {
        sharedPrefs.edit {
            putLong(KEY_VIDEO_START_MS, startMs)
            putLong(KEY_VIDEO_END_MS, endMs)
        }
    }

    /**
     * Retrieves the video clipping times from SharedPreferences.
     *
     * @return A Pair containing the start and end times in milliseconds.
     *         Defaults to (0L, -1L), where -1L signifies end of source.
     */
    fun getClippingTimes(): Pair<Long, Long> {
        val startMs = sharedPrefs.getLong(KEY_VIDEO_START_MS, 0L)
        val endMs = sharedPrefs.getLong(KEY_VIDEO_END_MS, -1L)
        return Pair(startMs, endMs)
    }

    /**
     * Removes the video clipping times from SharedPreferences.
     */
    fun removeClippingTimes() {
        sharedPrefs.edit {
            remove(KEY_VIDEO_START_MS)
            remove(KEY_VIDEO_END_MS)
        }
    }

    /**
     * Gets the current playback mode from SharedPreferences.
     * @return The current playback mode.
     */
    fun getPlaybackMode(): PlaybackMode {
        val storedOrdinal = sharedPrefs.getInt(KEY_PLAYBACK_MODE, PlaybackMode.LOOP.ordinal)
        return PlaybackMode.entries.getOrElse(storedOrdinal) { PlaybackMode.LOOP }
    }

    /**
     * Sets the playback mode in SharedPreferences.
     * @param mode The new playback mode.
     */
    fun setPlaybackMode(mode: PlaybackMode) {
        sharedPrefs.edit {
            putInt(KEY_PLAYBACK_MODE, mode.ordinal)
        }
    }

    /**
     * Gets the current scaling mode from SharedPreferences.
     * @return The current scaling mode.
     */
    fun getScalingMode(): ScalingMode {
        val storedOrdinal = sharedPrefs.getInt(KEY_SCALING_MODE, ScalingMode.FILL.ordinal)
        return ScalingMode.entries.getOrElse(storedOrdinal) { ScalingMode.FILL }
    }

    /**
     * Sets the scaling mode in SharedPreferences.
     * @param mode The new scaling mode.
     */
    fun setScalingMode(mode: ScalingMode) {
        sharedPrefs.edit {
            putInt(KEY_SCALING_MODE, mode.ordinal)
        }
    }

    /**
     * Saves the horizontal/vertical position offsets.
     * Default is 0.0f (Centered).
     */
    fun savePositionX(x: Float) {
        sharedPrefs.edit { putFloat(KEY_POSITION_X, x) }
    }

    fun getPositionX(): Float {
        return sharedPrefs.getFloat(KEY_POSITION_X, 0.0f)
    }

    fun savePositionY(y: Float) {
        sharedPrefs.edit { putFloat(KEY_POSITION_Y, y) }
    }

    fun getPositionY(): Float {
        return sharedPrefs.getFloat(KEY_POSITION_Y, 0.0f)
    }

    /**
     * Saves the video zoom level.
     * Default is 1.0f (No Zoom).
     */
    fun saveZoom(zoom: Float) {
        sharedPrefs.edit { putFloat(KEY_ZOOM, zoom) }
    }

    fun getZoom(): Float {
        // Default to 1.0 if not set
        return sharedPrefs.getFloat(KEY_ZOOM, 1.0f)
    }

    /**
     * Saves the video rotation.
     * Default is 0.0f (flat).
     */
    fun saveRotation(rotation: Float) {
        sharedPrefs.edit { putFloat(KEY_ROTATION, rotation) }
    }

    fun getRotation(): Float {
        // Default to 0.0 if not set
        return sharedPrefs.getFloat(KEY_ROTATION, 0.0f)
    }

    /**
     * Saves the brightness multiplier.
     * Default is 1.0f (Normal Brightness).
     */
    fun saveBrightness(brightness: Float) {
        sharedPrefs.edit { putFloat(KEY_BRIGHTNESS, brightness) }
    }

    fun getBrightness(): Float {
        // Default to 1.0 if not set
        return sharedPrefs.getFloat(KEY_BRIGHTNESS, 1.0f)
    }




}
