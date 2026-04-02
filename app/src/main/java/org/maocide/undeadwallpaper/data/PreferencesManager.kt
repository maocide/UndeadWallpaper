package org.maocide.undeadwallpaper.data

import org.maocide.undeadwallpaper.model.PlaybackMode
import org.maocide.undeadwallpaper.model.ScalingMode
import org.maocide.undeadwallpaper.model.StartTime
import org.maocide.undeadwallpaper.model.StatusBarColor

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.maocide.undeadwallpaper.model.VideoSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import android.net.Uri

/**
 * Manages SharedPreferences for the application.
 * This class encapsulates the logic for storing and retrieving user preferences.
 *
 * @param context The application context.
 */
class PreferencesManager(context: Context) {

    private val sharedPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val jsonParser = Json { ignoreUnknownKeys = true }

    companion object {
        private const val PREFS_NAME = "DEFAULT"
        private const val KEY_VIDEO_URI = "video_uri"
        private const val KEY_VIDEO_AUDIO_ENABLED = "video_audio_enabled"
        private const val KEY_VIDEO_START_MS = "video_start_ms"
        private const val KEY_LOGGING_ENABLED = "logging_enabled"
        private const val KEY_VIDEO_END_MS = "video_end_ms"
        private const val KEY_PLAYBACK_MODE = "playback_mode"
        private const val KEY_SCALING_MODE = "scaling_mode"
        private const val KEY_POSITION_X = "video_position_x"
        private const val KEY_POSITION_Y = "video_position_y"
        private const val KEY_ZOOM = "video_zoom"
        private const val KEY_BRIGHTNESS = "video_brightness"
        private const val KEY_ROTATION = "video_rotation"

        private const val KEY_STATUSBAR_COLOR = "statusbar_color"

        private const val KEY_START_TIME = "start_time"
        private const val KEY_SPEED = "video_speed"

        private const val KEY_RECENT_FILES_LIST = "recent_files_list"
        private const val KEY_PLAYLIST_SETTINGS = "playlist_settings"
    }

    init {
        migrateToPerVideoSettings()
    }

    private fun migrateToPerVideoSettings() {
        // If the new playlist settings already exist, migration is complete.
        if (sharedPrefs.contains(KEY_PLAYLIST_SETTINGS)) {
            return
        }

        val legacyListString = sharedPrefs.getString(KEY_RECENT_FILES_LIST, null)
        val legacyUri = sharedPrefs.getString(KEY_VIDEO_URI, null)

        if (legacyListString == null && legacyUri == null) {
            // Nothing to migrate
            return
        }

        // Read global settings
        val scalingModeOrdinal = sharedPrefs.getInt(KEY_SCALING_MODE, ScalingMode.FILL.ordinal)
        val scalingMode = ScalingMode.entries.getOrElse(scalingModeOrdinal) { ScalingMode.FILL }
        val positionX = sharedPrefs.getFloat(KEY_POSITION_X, 0.0f)
        val positionY = sharedPrefs.getFloat(KEY_POSITION_Y, 0.0f)
        val zoom = sharedPrefs.getFloat(KEY_ZOOM, 1.0f)
        val rotation = sharedPrefs.getFloat(KEY_ROTATION, 0.0f)
        val brightness = sharedPrefs.getFloat(KEY_BRIGHTNESS, 1.0f)
        val speed = sharedPrefs.getFloat(KEY_SPEED, 1.0f)
        val audioEnabled = sharedPrefs.getBoolean(KEY_VIDEO_AUDIO_ENABLED, false)
        val volume = if (audioEnabled) 1.0f else 0.0f
        val startTimeOrdinal = sharedPrefs.getInt(KEY_START_TIME, StartTime.RESUME.ordinal)
        val startTime = StartTime.entries.getOrElse(startTimeOrdinal) { StartTime.RESUME }

        val newPlaylistSettings = mutableListOf<VideoSettings>()

        if (legacyListString != null) {
            try {
                val fileNames = jsonParser.decodeFromString<List<String>>(legacyListString)
                for (fileName in fileNames) {
                    newPlaylistSettings.add(
                        VideoSettings(
                            fileName = fileName,
                            scalingMode = scalingMode,
                            positionX = positionX,
                            positionY = positionY,
                            zoom = zoom,
                            rotation = rotation,
                            brightness = brightness,
                            speed = speed,
                            volume = volume
                        )
                    )
                }
            } catch (e: Exception) {
                // Ignore parsing errors and fall back to single URI if applicable
            }
        }

        if (newPlaylistSettings.isEmpty() && legacyUri != null) {
            val fileName = Uri.parse(legacyUri).lastPathSegment
            if (fileName != null) {
                newPlaylistSettings.add(
                    VideoSettings(
                        fileName = fileName,
                        scalingMode = scalingMode,
                        positionX = positionX,
                        positionY = positionY,
                        zoom = zoom,
                        rotation = rotation,
                        brightness = brightness,
                        speed = speed,
                        volume = volume
                    )
                )
            }
        }

        // Save new format
        savePlaylistSettings(newPlaylistSettings)

        // Cleanup old keys
        sharedPrefs.edit {
            remove(KEY_RECENT_FILES_LIST)
            // Do NOT remove KEY_VIDEO_URI since it tracks the active video!
            remove(KEY_SCALING_MODE)
            remove(KEY_POSITION_X)
            remove(KEY_POSITION_Y)
            remove(KEY_ZOOM)
            remove(KEY_ROTATION)
            remove(KEY_BRIGHTNESS)
            remove(KEY_SPEED)
            remove(KEY_VIDEO_AUDIO_ENABLED)
            remove(KEY_START_TIME)
        }
    }

    fun getPlaylistSettings(): List<VideoSettings> {
        val jsonString = sharedPrefs.getString(KEY_PLAYLIST_SETTINGS, null)
        if (jsonString.isNullOrBlank()) {
            return emptyList()
        }
        return try {
            jsonParser.decodeFromString<List<VideoSettings>>(jsonString)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun savePlaylistSettings(playlist: List<VideoSettings>) {
        val jsonString = jsonParser.encodeToString(playlist)
        sharedPrefs.edit { putString(KEY_PLAYLIST_SETTINGS, jsonString) }
    }

    fun updateVideoSettings(fileName: String, updater: (VideoSettings) -> VideoSettings) {
        val currentList = getPlaylistSettings().toMutableList()
        val index = currentList.indexOfFirst { it.fileName == fileName }
        if (index != -1) {
            currentList[index] = updater(currentList[index])
        } else {
            currentList.add(updater(VideoSettings(fileName)))
        }
        savePlaylistSettings(currentList)
    }

    fun getVideoSettings(fileName: String): VideoSettings {
        return getPlaylistSettings().find { it.fileName == fileName } ?: VideoSettings(fileName)
    }

    /**
     * Saves the active video URI to SharedPreferences synchronously.
     * Synchronous save ensures that any broadcast listeners fired immediately after
     * will read the correct updated URI rather than racing the async disk write.
     *
     * @param uri The URI of the video to save.
     */
    fun saveActiveVideoUri(uri: String) {
        sharedPrefs.edit(commit = true) {
            putString(KEY_VIDEO_URI, uri)
        }
    }

    /**
     * Retrieves the active video URI from SharedPreferences.
     *
     * @return The saved video URI, or null if not found.
     */
    fun getActiveVideoUri(): String? {
        return sharedPrefs.getString(KEY_VIDEO_URI, null)
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
     * Saves the start time preference.
     * Default is 0 Resume, Start is 1, Random is 2
     */
    fun saveStartTime(startTime: StartTime) {
        sharedPrefs.edit { putInt(KEY_START_TIME, startTime.ordinal) }
    }

    fun getStartTime(): StartTime {
        val storedOrdinal = sharedPrefs.getInt(KEY_START_TIME, StartTime.RESUME.ordinal)
        return StartTime.entries.getOrElse(storedOrdinal) { StartTime.RESUME }
    }

    /**
     * Saves the status bar color.
     * Default is 0, Light is 1, Dark is 2
     */
    fun saveStatusBarColor(color: StatusBarColor) {
        sharedPrefs.edit { putInt(KEY_STATUSBAR_COLOR, color.ordinal) }
    }

    fun getStatusBarColor(): StatusBarColor {
        val storedOrdinal = sharedPrefs.getInt(KEY_STATUSBAR_COLOR, StatusBarColor.AUTO.ordinal)
        return StatusBarColor.entries.getOrElse(storedOrdinal) { StatusBarColor.AUTO }
    }

    fun saveLoggingEnabled(enabled: Boolean) {
        sharedPrefs.edit { putBoolean(KEY_LOGGING_ENABLED, enabled) }
    }

    fun isLoggingEnabled(): Boolean {
        return sharedPrefs.getBoolean(KEY_LOGGING_ENABLED, false)
    }

}
