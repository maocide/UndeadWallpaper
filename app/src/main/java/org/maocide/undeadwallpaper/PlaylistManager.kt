package org.maocide.undeadwallpaper

import android.content.Context
import android.net.Uri
import android.util.Log
import org.maocide.undeadwallpaper.model.PlaybackMode
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

    // We keep a local "bag" of unplayed filenames to ensure a true shuffle
    // (no repeats until every video has been played once).
    private val shuffleBag = mutableListOf<String>()

    /**
     * Determines the next video URI to play based on the current mode and playlist.
     *
     * @param currentUriString The currently playing video URI.
     * @param mode The current playback mode.
     * @return The next URI to play, or null if it cannot be determined or should fall back to default.
     */
    fun getNextVideoUri(currentUriString: String?, mode: PlaybackMode): String? {
        val playlist = prefs.getRecentFilesList()

        if (playlist.isEmpty()) {
            return null
        }

        // Get the actual filename from the URI
        var currentFileName: String? = null
        if (currentUriString != null) {
             currentFileName = try {
                 File(Uri.parse(currentUriString).path!!).name
             } catch (e: Exception) {
                 null
             }
        }

        return when (mode) {
            PlaybackMode.LOOP_ALL -> getNextSequential(playlist, currentFileName)
            PlaybackMode.SHUFFLE -> getNextShuffle(playlist)
            else -> currentUriString // Fallback for LOOP or ONE_SHOT
        }
    }

    private fun getNextSequential(playlist: List<String>, currentFileName: String?): String? {
        val currentIndex = playlist.indexOf(currentFileName)
        val nextIndex = if (currentIndex == -1 || currentIndex >= playlist.size - 1) {
            0 // Start from the beginning if not found or at the end
        } else {
            currentIndex + 1
        }
        return buildUriFromFileName(playlist[nextIndex])
    }

    private fun getNextShuffle(playlist: List<String>): String? {
        // Synchronize the shuffle bag with the current playlist state
        // Remove items from the bag that are no longer in the playlist
        shuffleBag.retainAll(playlist)

        // If the bag is empty, refill it with the current playlist
        if (shuffleBag.isEmpty()) {
            shuffleBag.addAll(playlist)
        }

        if (shuffleBag.isEmpty()) return null

        // Pick a random index from the remaining unplayed bag
        val randomIndex = Random.nextInt(shuffleBag.size)
        val nextFileName = shuffleBag.removeAt(randomIndex)

        return buildUriFromFileName(nextFileName)
    }

    private fun buildUriFromFileName(fileName: String): String? {
        val videoManager = VideoFileManager(context)
        // Reflection of the `videos` directory path from VideoFileManager
        val videosDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)?.let { File(it, "videos") }

        if (videosDir == null) return null

        val file = File(videosDir, fileName)
        if (file.exists()) {
            return Uri.fromFile(file).toString()
        } else {
             Log.w(TAG, "File in playlist not found on disk: $fileName")
             return null
        }
    }
}
