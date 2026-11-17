package org.maocide.undeadwallpaper.data

import org.maocide.undeadwallpaper.model.PlaybackMode
import org.maocide.undeadwallpaper.model.ScalingMode
import org.maocide.undeadwallpaper.model.StartTime
import org.maocide.undeadwallpaper.model.StatusBarColor
import org.maocide.undeadwallpaper.model.KineticEffectType

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.maocide.undeadwallpaper.model.VideoSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import android.net.Uri
import org.maocide.undeadwallpaper.utils.FileLogger
import java.io.File

/**
 * Manages SharedPreferences for the application.
 * This class encapsulates the logic for storing and retrieving user preferences.
 *
 * @param context The application context.
 */
class PreferencesManager(private val context: Context, prefsName: String = PREFS_NAME) {

    private val sharedPrefs: SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private val jsonParser = Json { ignoreUnknownKeys = true }

    private var cachedPlaylistSettingsString: String? = null
    private var cachedPlaylistSettings: List<VideoSettings>? = null

    private val checksumHelper = ConfigChecksumHelper()

    companion object {
        private const val TAG = "PreferencesManager"
        private const val PREFS_NAME = "DEFAULT"
        private const val KEY_VIDEO_URI = "video_uri"
        private const val KEY_ANCHOR_VIDEO_URI = "anchor_video_uri"
        private const val KEY_LOGGING_ENABLED = "logging_enabled"
        private const val KEY_PLAYBACK_MODE = "playback_mode"
        private const val KEY_SCALING_MODE = "scaling_mode"
        private const val KEY_POSITION_X = "video_position_x"
        private const val KEY_POSITION_Y = "video_position_y"
        private const val KEY_ZOOM = "video_zoom"
        private const val KEY_BRIGHTNESS = "video_brightness"
        private const val KEY_ROTATION = "video_rotation"

        private const val KEY_STATUSBAR_COLOR = "statusbar_color"

        private const val KEY_START_TIME = "start_time"

        private const val KEY_RECENT_FILES_LIST = "recent_files_list"
        private const val KEY_PLAYLIST_SETTINGS = "playlist_settings"
        private const val KEY_ACTIVE_PAGE = "active_page"

        private const val KEY_PARALLAX_ENABLED = "parallax_enabled"
        private const val KEY_PARALLAX_STRENGTH = "parallax_strength"
        private const val KEY_FLOATING_PREVIEW_ENABLED = "floating_preview_enabled"

        private const val KEY_KINETIC_EFFECT_TYPE = "kinetic_effect_type"
        private const val KEY_KINETIC_INTENSITY = "kinetic_intensity"
        private const val KEY_KINETIC_HAPTICS_ENABLED = "kinetic_haptics_enabled"

        const val KEY_TOMBSTONE_LEAN_ANGLE = "tombstone_lean_angle"
        const val KEY_TOMBSTONE_IS_BROKEN = "tombstone_is_broken"
        const val KEY_TOMBSTONE_MANUAL_TAP_COUNT = "tombstone_manual_tap_count"

        const val KEY_RAW_SCREEN_LONG_SIDE = "raw_screen_long_side"
        const val KEY_RAW_SCREEN_SHORT_SIDE = "raw_screen_short_side"

        // Transient State for ConfigCRC
        private var transientIsUndead = false
    }

    init {
        migrateToPerVideoSettings()
        migrateParallaxSettings()
        migrateIntegritySeal()
    }

    private fun migrateIntegritySeal() {
        val hasHardwareKey = checksumHelper.hasHardwareKey()
        val storedChecksum = sharedPrefs.getString(ConfigChecksumHelper.KEY_CONFIG_CRC, null)
        val hasChecksum = storedChecksum != null

        when (hasHardwareKey to hasChecksum) {
            false to false -> {
                // Genuine 1.4.0 Upgrade (First-time initialization)
                val rawUri = sharedPrefs.getString(KEY_VIDEO_URI, null)
                if (rawUri == "null" || rawUri.isNullOrBlank()) {
                    sharedPrefs.edit(commit = true) { remove(KEY_VIDEO_URI) }
                }
                checksumHelper.generateHardwareKey()
                updateSeal()
            }

            true to true -> {
                // Normal Steady State
                val playlistJson = sharedPrefs.getString(KEY_PLAYLIST_SETTINGS, null)
                val activeUri = sharedPrefs.getString(KEY_VIDEO_URI, null)
                if (!checksumHelper.verifyChecksum(playlistJson, activeUri, storedChecksum)) {
                    FileLogger.e(TAG, "Config checksum mismatch on boot, restoring defaults.")
                    resetCorruptedPrefs()
                }
            }

            true to false -> {
                // Unverified migration state
                FileLogger.e(TAG, "Config checksum missing on migration, resetting state.")
                resetCorruptedPrefs()
            }

            false to true -> {
                // Orphaned configuration state
                FileLogger.e(TAG, "Unrecognized configuration state, restoring defaults.")
                resetCorruptedPrefs()
            }
        }
    }

    private fun updateSeal() {
        val playlistJson = sharedPrefs.getString(KEY_PLAYLIST_SETTINGS, null)
        val activeUri = sharedPrefs.getString(KEY_VIDEO_URI, null)
        val checksum = checksumHelper.computeChecksum(playlistJson, activeUri)
        sharedPrefs.edit(commit = true) {
            putString(ConfigChecksumHelper.KEY_CONFIG_CRC, checksum)
        }
    }

    private fun resetCorruptedPrefs() {
        sharedPrefs.edit(commit = true) {
            remove(KEY_PLAYLIST_SETTINGS)
            remove(KEY_VIDEO_URI)
            remove(KEY_ANCHOR_VIDEO_URI)
            remove(KEY_ACTIVE_PAGE)
            remove(ConfigChecksumHelper.KEY_CONFIG_CRC)
        }
        cachedPlaylistSettingsString = null
        cachedPlaylistSettings = null

        checksumHelper.generateHardwareKey()
        updateSeal()
    }

    private fun migrateParallaxSettings() {
        if (sharedPrefs.contains(KEY_PARALLAX_STRENGTH)) {
            val current = sharedPrefs.getFloat(KEY_PARALLAX_STRENGTH, 0.4f)
            if (current > 1.5f) {
                sharedPrefs.edit { putFloat(KEY_PARALLAX_STRENGTH, 0.4f) }
            }
        }
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

        val newPlaylistSettings = mutableListOf<VideoSettings>()
        val activeFileName = legacyUri?.let { Uri.parse(it).lastPathSegment }
        var activeFileAdded = false

        if (legacyListString != null) {
            try {
                val fileNames = jsonParser.decodeFromString<List<String>>(legacyListString)
                for (fileName in fileNames) {
                    if (fileName == activeFileName) {
                        newPlaylistSettings.add(
                            VideoSettings(
                                fileName = fileName,
                                scalingMode = scalingMode,
                                positionX = positionX,
                                positionY = positionY,
                                zoom = zoom,
                                rotation = rotation,
                                brightness = brightness
                            )
                        )
                        activeFileAdded = true
                    } else {
                        newPlaylistSettings.add(VideoSettings(fileName = fileName))
                    }
                }
            } catch (_: Exception) {
                // Ignore parsing errors and fall back to single URI if applicable
            }
        }

        if (!activeFileAdded && activeFileName != null) {
            newPlaylistSettings.add(
                VideoSettings(
                    fileName = activeFileName,
                    scalingMode = scalingMode,
                    positionX = positionX,
                    positionY = positionY,
                    zoom = zoom,
                    rotation = rotation,
                    brightness = brightness
                )
            )
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
            remove("video_speed")
            remove("video_audio_enabled")
        }
    }

    /**
     * Getter for playlist video settings implementing a caching to avoid json decoding each call.
     * the cache is updated/invalidated by the save method.
     */
    fun getPlaylistSettings(): List<VideoSettings> {
        val jsonString = sharedPrefs.getString(KEY_PLAYLIST_SETTINGS, null)
        val activeUri = sharedPrefs.getString(KEY_VIDEO_URI, null)
        val storedChecksum = sharedPrefs.getString(ConfigChecksumHelper.KEY_CONFIG_CRC, null)

        if (!checksumHelper.verifyChecksum(jsonString, activeUri, storedChecksum)) {
            FileLogger.e(TAG, "Playlist checksum mismatch, restoring defaults.")
            resetCorruptedPrefs()
            return emptyList()
        }

        if (jsonString.isNullOrBlank()) {
            cachedPlaylistSettingsString = null
            cachedPlaylistSettings = null
            return emptyList()
        }
        val cachedSettings = cachedPlaylistSettings
        if (jsonString == cachedPlaylistSettingsString && cachedSettings != null) {
            return cachedSettings
        }
        return try {
            val decoded = jsonParser.decodeFromString<List<VideoSettings>>(jsonString)
            cachedPlaylistSettingsString = jsonString
            cachedPlaylistSettings = decoded
            decoded
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun savePlaylistSettings(playlist: List<VideoSettings>) {
        val jsonString = jsonParser.encodeToString(playlist)
        cachedPlaylistSettingsString = jsonString
        cachedPlaylistSettings = playlist
        val activeUri = sharedPrefs.getString(KEY_VIDEO_URI, null)
        val checksum = checksumHelper.computeChecksum(jsonString, activeUri)
        sharedPrefs.edit(commit = true) {
            putString(KEY_PLAYLIST_SETTINGS, jsonString)
            putString(ConfigChecksumHelper.KEY_CONFIG_CRC, checksum)
        }
    }

    fun getActivePage(): Int {
        return sharedPrefs.getInt(KEY_ACTIVE_PAGE, 0)
    }

    fun saveActivePage(page: Int) {
        sharedPrefs.edit { putInt(KEY_ACTIVE_PAGE, page) }
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
        if (uri.isBlank() || uri == "null") {
            FileLogger.w(TAG, "Attempted to save invalid URI string: '$uri', ignoring.")
            return
        }
        val playlistJson = sharedPrefs.getString(KEY_PLAYLIST_SETTINGS, null)
        val checksum = checksumHelper.computeChecksum(playlistJson, uri)
        sharedPrefs.edit(commit = true) {
            putString(KEY_VIDEO_URI, uri)
            putString(ConfigChecksumHelper.KEY_CONFIG_CRC, checksum)
        }
    }

    /**
     * Retrieves the active video URI from SharedPreferences.
     *
     * @return The saved video URI, or null if not found.
     */
    fun getActiveVideoUri(): String? {
        val jsonString = sharedPrefs.getString(KEY_PLAYLIST_SETTINGS, null)
        val activeUri = sharedPrefs.getString(KEY_VIDEO_URI, null)
        val storedChecksum = sharedPrefs.getString(ConfigChecksumHelper.KEY_CONFIG_CRC, null)

        if (!checksumHelper.verifyChecksum(jsonString, activeUri, storedChecksum)) {
            FileLogger.e(TAG, "Active URI checksum mismatch, restoring defaults.")
            resetCorruptedPrefs()
            return sharedPrefs.getString(KEY_VIDEO_URI, null)
        }
        return activeUri?.takeIf { it.isNotBlank() && it != "null" }
    }

    /**
     * Saves the anchor video URI to SharedPreferences.
     * This represents the user-selected video anchor for Material You dynamic theming.
     * Unlike [saveActiveVideoUri], this is NOT modified by automatic playlist advancement.
     */
    fun saveAnchorVideoUri(uri: String) {
        if (uri.isBlank() || uri == "null") {
            FileLogger.w(TAG, "Attempted to save invalid anchor URI string: '$uri', ignoring.")
            return
        }
        sharedPrefs.edit {
            putString(KEY_ANCHOR_VIDEO_URI, uri)
        }
    }

    /**
     * Retrieves the anchor video URI from SharedPreferences.
     * If uninitialized or blank, seamlessly falls back to [getActiveVideoUri].
     */
    fun getAnchorVideoUri(): String? {
        val anchorUri = sharedPrefs.getString(KEY_ANCHOR_VIDEO_URI, null)
        return if (!anchorUri.isNullOrBlank() && anchorUri != "null") {
            anchorUri
        } else {
            getActiveVideoUri()
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

    fun setParallaxEnabled(enabled: Boolean) {
        sharedPrefs.edit { putBoolean(KEY_PARALLAX_ENABLED, enabled) }
    }

    fun isParallaxEnabled(): Boolean {
        return sharedPrefs.getBoolean(KEY_PARALLAX_ENABLED, false)
    }

    fun setParallaxStrength(strength: Float) {
        sharedPrefs.edit { putFloat(KEY_PARALLAX_STRENGTH, strength) }
    }

    fun getParallaxStrength(): Float {
        return sharedPrefs.getFloat(KEY_PARALLAX_STRENGTH, 0.4f).coerceIn(0.1f, 1.5f)
    }

    fun setFloatingPreviewEnabled(enabled: Boolean) {
        sharedPrefs.edit { putBoolean(KEY_FLOATING_PREVIEW_ENABLED, enabled) }
    }

    fun isFloatingPreviewEnabled(): Boolean {
        return sharedPrefs.getBoolean(KEY_FLOATING_PREVIEW_ENABLED, true)
    }

    fun setKineticEffectType(type: KineticEffectType) {
        sharedPrefs.edit { putString(KEY_KINETIC_EFFECT_TYPE, type.id) }
    }

    fun getKineticEffectType(): KineticEffectType {
        val raw = sharedPrefs.getString(KEY_KINETIC_EFFECT_TYPE, KineticEffectType.NONE.id)
        return KineticEffectType.fromId(raw)
    }

    fun setKineticIntensity(intensity: Float) {
        sharedPrefs.edit { putFloat(KEY_KINETIC_INTENSITY, intensity.coerceIn(0.5f, 2.0f)) }
    }

    fun getKineticIntensity(): Float {
        return sharedPrefs.getFloat(KEY_KINETIC_INTENSITY, 1.0f).coerceIn(0.5f, 2.0f)
    }

    fun setKineticHapticsEnabled(enabled: Boolean) {
        sharedPrefs.edit { putBoolean(KEY_KINETIC_HAPTICS_ENABLED, enabled) }
    }

    fun isKineticHapticsEnabled(): Boolean {
        return sharedPrefs.getBoolean(KEY_KINETIC_HAPTICS_ENABLED, false)
    }

    fun setUndead(isUndead: Boolean) {
        transientIsUndead = isUndead
    }

    fun isUndead(): Boolean {
        return transientIsUndead
    }

    fun getTombstoneLeanAngle(): Float {
        return sharedPrefs.getFloat(KEY_TOMBSTONE_LEAN_ANGLE, 0f)
    }

    fun isTombstoneBroken(): Boolean {
        return sharedPrefs.getBoolean(KEY_TOMBSTONE_IS_BROKEN, false)
    }

    fun getTombstoneTapCount(): Int {
        return sharedPrefs.getInt(KEY_TOMBSTONE_MANUAL_TAP_COUNT, 0)
    }

    fun saveTombstoneState(angle: Float, isBroken: Boolean, tapCount: Int) {
        sharedPrefs.edit {
            putFloat(KEY_TOMBSTONE_LEAN_ANGLE, angle)
            putBoolean(KEY_TOMBSTONE_IS_BROKEN, isBroken)
            putInt(KEY_TOMBSTONE_MANUAL_TAP_COUNT, tapCount)
        }
    }

    fun saveTombstoneTapCount(tapCount: Int) {
        sharedPrefs.edit { putInt(KEY_TOMBSTONE_MANUAL_TAP_COUNT, tapCount) }
    }

    fun saveRawScreenDimensions(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val longSide = maxOf(width, height)
        val shortSide = minOf(width, height)
        if (getRawScreenLongSide() != longSide || getRawScreenShortSide() != shortSide) {
            sharedPrefs.edit {
                putInt(KEY_RAW_SCREEN_LONG_SIDE, longSide)
                putInt(KEY_RAW_SCREEN_SHORT_SIDE, shortSide)
            }
        }
    }

    fun getRawScreenLongSide(): Int = sharedPrefs.getInt(KEY_RAW_SCREEN_LONG_SIDE, 0)

    fun getRawScreenShortSide(): Int = sharedPrefs.getInt(KEY_RAW_SCREEN_SHORT_SIDE, 0)

    fun getAccordionSeenEpoch(key: String): Int = sharedPrefs.getInt(key, 0)

    fun setAccordionSeenEpoch(key: String, epoch: Int) {
        sharedPrefs.edit { putInt(key, epoch) }
    }
}

