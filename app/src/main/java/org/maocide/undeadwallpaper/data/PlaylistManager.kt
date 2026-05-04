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

    // In-memory state for maintaining a true non-repeating shuffle sequence
    private var shuffledIndices: List<Int>? = null

    /**
     * Returns a list of valid URIs for all items in the playlist.
     * If a file does not exist on disk, it is excluded.
     */
    fun getPlaylistUris(): List<String> {
        val playlistSettings = prefs.getPlaylistSettings()

        // If playlist size changes, invalidate the shuffle cache
        if (shuffledIndices?.size != playlistSettings.size) {
            shuffledIndices = null
        }

        if (playlistSettings.isEmpty()) {
            return emptyList()
        }

        val videosDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)?.let { File(it, "videos") }
        if (videosDir == null) return emptyList()

        // Fetch directory contents once and use a Set for fast O(1) lookups
        val physicalFilesSet = videosDir.list()?.toSet() ?: emptySet()

        val validUris = mutableListOf<String>()
        for (setting in playlistSettings) {
            if (physicalFilesSet.contains(setting.fileName)) {
                val file = File(videosDir, setting.fileName)
                validUris.add(Uri.fromFile(file).toString())
            } else {
                Log.w(TAG, "File in playlist not found on disk: ${setting.fileName}")
            }
        }
        return validUris
    }

    /**
     * Returns the cached shuffled order for the playlist.
     * If the cache is empty, it generates a new non-repeating random sequence.
     */
    private fun getShuffleOrder(playlistSize: Int): List<Int> {
        if (shuffledIndices == null || shuffledIndices!!.size != playlistSize) {
            regenerateShuffleOrder(playlistSize)
        }
        return shuffledIndices ?: emptyList()
    }

    /**
     * Forces a new random sequence to be generated.
     * Used when a shuffle sequence completes a full loop.
     */
    fun regenerateShuffleOrder(playlistSize: Int) {
        if (playlistSize > 0) {
            shuffledIndices = (0 until playlistSize).shuffled()
        } else {
            shuffledIndices = null
        }
    }

    /**
     * Returns a contiguous sequence (chunk) of media URIs starting with [currentUri].
     * The sequence continues as long as subsequent videos in the playlist share the
     * exact same visual transform settings (scaling, pan, zoom, rotation, brightness).
     * If a video requires a visual change, the chunk ends, forcing the player to
     * hit a playback boundary so the renderer can safely apply the new settings.
     */
    fun getGaplessChunkUris(currentUri: String, playbackMode: PlaybackMode): List<String> {
        val playlistUris = getPlaylistUris()
        if (playlistUris.isEmpty()) return emptyList()

        val chunkUris = mutableListOf<String>()
        chunkUris.add(currentUri)

        if (playbackMode != PlaybackMode.LOOP_ALL && playbackMode != PlaybackMode.SHUFFLE) {
            return chunkUris
        }

        val baseUriParsed = Uri.parse(currentUri)
        val baseFileName = baseUriParsed.lastPathSegment ?: ""
        val baseSettings = prefs.getVideoSettings(baseFileName)

        // Figure out our current position in the sequence
        var currentIndex = playlistUris.indexOf(currentUri)
        if (currentIndex == -1) currentIndex = 0

        val sequenceOrder = if (playbackMode == PlaybackMode.SHUFFLE) {
            getShuffleOrder(playlistUris.size)
        } else {
            (0 until playlistUris.size).toList() // Linear order 0, 1, 2, ...
        }

        // Find where our current video sits in this sequence
        val currentPositionInSequence = sequenceOrder.indexOf(currentIndex).takeIf { it != -1 } ?: 0

        // Look ahead in the sequence
        for (i in 1 until playlistUris.size) {
            val nextPositionInSequence = currentPositionInSequence + i

            // For SHUFFLE mode, do not cross the sequence boundary (end of the playlist) within a single chunk!
            // Wrapping around inside a chunk breaks true shuffle logic because it re-uses the OLD sequence order.
            // By stopping the chunk strictly at the end of the sequence, we force the player to hit STATE_ENDED.
            // This guarantees getNextUri() will be called, allowing it to correctly regenerate the shuffle order for the next loop.
            if (playbackMode == PlaybackMode.SHUFFLE && nextPositionInSequence >= playlistUris.size) {
                break
            }

            // For LOOP_ALL, we can safely wrap around the sequence (e.g. from index 2 back to 0).
            val wrappedPositionInSequence = nextPositionInSequence % playlistUris.size
            val nextLogicalIndex = sequenceOrder[wrappedPositionInSequence]

            val nextUriStr = playlistUris[nextLogicalIndex]
            val nextUriParsed = Uri.parse(nextUriStr)
            val nextFileName = nextUriParsed.lastPathSegment ?: ""
            val nextSettings = prefs.getVideoSettings(nextFileName)

            // Check transforms difference in VideoSettings model that would require GL matrix/uniforms updates
            if (baseSettings.hasSameVisualTransformsAs(nextSettings)) {
                chunkUris.add(nextUriStr)
            } else {
                // Settings differ! Break the batch.
                break
            }
        }

        return chunkUris
    }

    /**
     * Returns the next URI in the playlist sequence.
     * Handles linear looping and non-repeating shuffle advancement.
     */
    fun getNextUri(currentUri: String, playbackMode: PlaybackMode): String? {
        val playlistUris = getPlaylistUris()
        if (playlistUris.isEmpty()) return null

        var currentIndex = playlistUris.indexOf(currentUri)
        if (currentIndex == -1) currentIndex = 0

        // Determine the sequence. If SHUFFLE, use shuffle map. Otherwise, use linear.
        // This allows ONE_SHOT and LOOP to behave like LOOP_ALL when manually skipped.
        val sequenceOrder = if (playbackMode == PlaybackMode.SHUFFLE) {
            getShuffleOrder(playlistUris.size)
        } else {
            (0 until playlistUris.size).toList()
        }

        val currentPositionInSequence = sequenceOrder.indexOf(currentIndex).takeIf { it != -1 } ?: 0
        val nextPositionInSequence = currentPositionInSequence + 1

        if (nextPositionInSequence >= playlistUris.size) {
            // We reached the very end of the playlist sequence!
            if (playbackMode == PlaybackMode.SHUFFLE) {
                regenerateShuffleOrder(playlistUris.size)
                val newOrder = getShuffleOrder(playlistUris.size)
                return playlistUris[newOrder[0]]
            } else {
                // Loop linear back to start (handles LOOP_ALL, LOOP, and ONE_SHOT skips)
                return playlistUris[0]
            }
        }

        return playlistUris[sequenceOrder[nextPositionInSequence]]
    }
}
