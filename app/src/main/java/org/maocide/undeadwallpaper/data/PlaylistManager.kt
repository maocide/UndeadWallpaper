package org.maocide.undeadwallpaper.data

import org.maocide.undeadwallpaper.model.PlaybackMode

import android.content.Context
import android.net.Uri
import org.maocide.undeadwallpaper.utils.FileLogger

import java.io.File
import kotlin.random.Random
import androidx.core.net.toUri

/**
 * Manages playback sequence logic for LOOP_ALL and SHUFFLE modes.
 * It reads the latest state from SharedPreferences when deciding the next video.
 */
class PlaylistManager(
    private val context: Context,
    private val prefs: PreferencesManager,
    private val videosDirectoryName: String = "videos"
) {
    private val TAG = javaClass.simpleName

    // In-memory state for maintaining a true non-repeating shuffle sequence
    private var shuffledIndices: List<Int>? = null
    private var lastActivePage: Int = 0

    /**
     * Returns a list of valid URIs for all items in the playlist.
     * If a file does not exist on disk, it is excluded.
     */
    suspend fun getPlaylistUris(): List<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val playlistSettings = prefs.getPlaylistSettings()

        // Decouple Engine playback page from UI scroll state
        // Find which page the currently active video belongs to, and play that page
        val activeVideoFileName = prefs.getActiveVideoUri()?.toUri()?.lastPathSegment
        val activePage = playlistSettings.find { it.fileName == activeVideoFileName }?.page ?: 0

        val pagedSettings = playlistSettings.filter { it.page == activePage }

        // If playlist size changes or page changes, invalidate the shuffle cache
        if (shuffledIndices?.size != pagedSettings.size || lastActivePage != activePage) {
            shuffledIndices = null
            lastActivePage = activePage
        }

        if (pagedSettings.isEmpty()) {
            return@withContext emptyList()
        }

        val videosDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)?.let { File(it, videosDirectoryName) }
        if (videosDir == null) return@withContext emptyList()

        // Fetch directory contents once and use a Set for fast O(1) lookups
        val physicalFilesSet = videosDir.list()?.toSet() ?: emptySet()

        val validUris = mutableListOf<String>()
        for (setting in pagedSettings) {
            if (physicalFilesSet.contains(setting.fileName)) {
                val file = File(videosDir, setting.fileName)
                validUris.add(Uri.fromFile(file).toString())
            } else {
                FileLogger.w(TAG, "File in playlist not found on disk: ${setting.fileName}")
            }
        }
        validUris
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
    suspend fun getGaplessChunkUris(currentUri: String, playbackMode: PlaybackMode, playlistUris: List<String>? = null): List<String> {
        val actualPlaylistUris = playlistUris ?: getPlaylistUris()
        if (actualPlaylistUris.isEmpty()) return emptyList()

        val chunkUris = mutableListOf<String>()
        chunkUris.add(currentUri)

        if (playbackMode != PlaybackMode.LOOP_ALL && playbackMode != PlaybackMode.SHUFFLE) {
            return chunkUris
        }

        // Map generation before the loop (O(N) operation done once)
        val settingsMap = prefs.getPlaylistSettings().associateBy { it.fileName }

        val baseUriParsed = currentUri.toUri()
        val baseFileName = baseUriParsed.lastPathSegment ?: ""

        // Map lookup
        val baseSettings = settingsMap[baseFileName] ?: prefs.getVideoSettings(baseFileName)

        // Figure out our current position in the sequence
        var currentIndex = actualPlaylistUris.indexOf(currentUri)
        if (currentIndex == -1) currentIndex = 0

        val sequenceOrder = if (playbackMode == PlaybackMode.SHUFFLE) {
            getShuffleOrder(actualPlaylistUris.size)
        } else {
            (0 until actualPlaylistUris.size).toList()
        }

        // Find where our current video sits in this sequence
        val currentPositionInSequence = sequenceOrder.indexOf(currentIndex).takeIf { it != -1 } ?: 0

        // Look ahead in the sequence
        for (i in 1 until actualPlaylistUris.size) {
            val nextPositionInSequence = currentPositionInSequence + i

            // For SHUFFLE mode, do not cross the sequence boundary (end of the playlist) within a single chunk!
            // Wrapping around inside a chunk breaks true shuffle logic because it re-uses the OLD sequence order.
            // By stopping the chunk strictly at the end of the sequence, we force the player to hit STATE_ENDED.
            // This guarantees getNextUri() will be called, allowing it to correctly regenerate the shuffle order for the next loop.
            if (playbackMode == PlaybackMode.SHUFFLE && nextPositionInSequence >= actualPlaylistUris.size) {
                break
            }

            // For LOOP_ALL, we can safely wrap around the sequence (e.g. from index 2 back to 0).
            val wrappedPositionInSequence = nextPositionInSequence % actualPlaylistUris.size
            val nextLogicalIndex = sequenceOrder[wrappedPositionInSequence]

            val nextUriStr = actualPlaylistUris[nextLogicalIndex]
            val nextUriParsed = nextUriStr.toUri()
            val nextFileName = nextUriParsed.lastPathSegment ?: ""

            // O(1) Map lookup inside the loop
            val nextSettings = settingsMap[nextFileName] ?: prefs.getVideoSettings(nextFileName)

            // Check transforms difference in VideoSettings model that would require GL matrix/uniforms updates
            if (baseSettings.hasSameVisualTransformsAs(nextSettings)) {
                chunkUris.add(nextUriStr)
            } else {
                // Settings differ, break
                break
            }
        }

        return chunkUris
    }

    /**
     * Returns the next URI in the playlist sequence.
     * Handles linear looping and non-repeating shuffle advancement.
     */
    suspend fun getNextUri(currentUri: String, playbackMode: PlaybackMode): String? {
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

    /**
     * Collapses empty pages in a playlist and adjusts the active page if necessary.
     * @param settings The mutable list of settings to modify in-place.
     * @return True if a collapse occurred.
     */
    fun collapseEmptyPages(settings: MutableList<org.maocide.undeadwallpaper.model.VideoSettings>): Boolean {
        var collapseOccurred = false
        val maxPage = settings.maxOfOrNull { it.page } ?: -1
        if (maxPage > 0) {
            var p = 1
            while (p <= (settings.maxOfOrNull { it.page } ?: -1)) {
                if (settings.none { it.page == p }) {
                    // Page p is empty, shift all higher pages down
                    for (i in settings.indices) {
                        if (settings[i].page > p) {
                            settings[i] = settings[i].copy(page = settings[i].page - 1)
                        }
                    }
                    collapseOccurred = true
                    // Do not increment p, check the new contents of page p
                } else {
                    p++
                }
            }
            if (collapseOccurred) {
                val currentActivePage = prefs.getActivePage()
                val newMax = settings.maxOfOrNull { it.page } ?: 0
                if (currentActivePage > newMax) {
                    prefs.saveActivePage(newMax)
                }
            }
        }
        return collapseOccurred
    }
}
