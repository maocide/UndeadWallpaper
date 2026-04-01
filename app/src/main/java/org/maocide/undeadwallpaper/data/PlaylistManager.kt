package org.maocide.undeadwallpaper.data

import org.maocide.undeadwallpaper.model.PlaybackMode

import android.content.Context
import android.net.Uri
import android.util.Log

import java.io.File
import kotlin.random.Random

/**
 * Manages playback sequence logic for LOOP_ALL and SHUFFLE modes.
 * It reads the latest state from SharedPreferences when deciding the next video.
 */
class PlaylistManager(
    private val context: Context,
    private val prefs: PreferencesManager
) {
    private val TAG = javaClass.simpleName

    /**
     * Returns a list of valid URIs for all items in the playlist.
     * If a file does not exist on disk, it is excluded.
     */
    fun getPlaylistUris(): List<String> {
        val playlistSettings = prefs.getPlaylistSettings()
        if (playlistSettings.isEmpty()) {
            return emptyList()
        }

        val videosDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)?.let { File(it, "videos") }
        if (videosDir == null) return emptyList()

        val validUris = mutableListOf<String>()
        for (setting in playlistSettings) {
            val file = File(videosDir, setting.fileName)
            if (file.exists()) {
                validUris.add(Uri.fromFile(file).toString())
            } else {
                Log.w(TAG, "File in playlist not found on disk: ${setting.fileName}")
            }
        }
        return validUris
    }
}
