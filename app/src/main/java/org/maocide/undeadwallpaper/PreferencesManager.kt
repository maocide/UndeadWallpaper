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

}
