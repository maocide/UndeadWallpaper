package org.maocide.undeadwallpaper

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.core.net.toUri

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
}
