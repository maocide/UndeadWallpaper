package org.maocide.undeadwallpaper

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.dataStore
import androidx.datastore.migrations.SharedPreferencesMigration
import androidx.datastore.migrations.SharedPreferencesView
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import org.maocide.undeadwallpaper.model.AppState
import org.maocide.undeadwallpaper.model.GlobalPlaybackState
import org.maocide.undeadwallpaper.model.ItemPlaybackSettings
import org.maocide.undeadwallpaper.model.PlaybackMode
import org.maocide.undeadwallpaper.model.PlaylistItemState
import org.maocide.undeadwallpaper.model.StartTime
import org.maocide.undeadwallpaper.model.StatusBarColor

// Legacy keys we still migrate from.
private val legacyKeysToMigrate = setOf(
    PreferencesManager.KEY_RECENT_FILES_LIST,
    PreferencesManager.KEY_VIDEO_URI,
    PreferencesManager.KEY_VIDEO_AUDIO_ENABLED,
    PreferencesManager.KEY_PLAYBACK_MODE,
    PreferencesManager.KEY_START_TIME,
    PreferencesManager.KEY_STATUSBAR_COLOR
)

private val Context.appStateDataStore: DataStore<AppState> by dataStore(
    fileName = "app_state.json",
    serializer = AppStateSerializer,
    produceMigrations = { context ->
        listOf(
            SharedPreferencesMigration(
                context = context,
                sharedPreferencesName = PreferencesManager.PREFS_NAME,
                keysToMigrate = legacyKeysToMigrate,
                shouldRunMigration = { current ->
                    current.playlist.isEmpty()
                },
                migrate = { legacyPrefs, current ->
                    migrateLegacyState(context, legacyPrefs, current)
                }
            )
        )
    }
)

class AppStateRepository private constructor(
    private val context: Context,
    scope: CoroutineScope? = null
) {
    companion object {
        @Volatile
        private var instance: AppStateRepository? = null

        fun getInstance(context: Context): AppStateRepository {
            return instance ?: synchronized(this) {
                instance ?: AppStateRepository(
                    context = context.applicationContext
                ).also { instance = it }
            }
        }
    }

    private val dataStore = context.appStateDataStore
    private val repositoryScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Warm snapshot for service-side reads.
    private val latestState = MutableStateFlow<AppState?>(null)

    val state: StateFlow<AppState> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(AppState())
            } else {
                throw error
            }
        }
        .map { appState ->
            latestState.value = appState
            appState
        }
        .stateIn(
            scope = repositoryScope,
            started = SharingStarted.Eagerly,
            initialValue = AppState()
        )

    fun snapshot(): AppState? {
        return latestState.value
    }

    suspend fun ensureLoaded(): AppState {
        return state.first().also { latestState.value = it }
    }

    fun activeItem(): Flow<PlaylistItemState?> {
        return state.map { appState ->
            appState.playlist.firstOrNull { it.id == appState.activeItemId }
        }
    }

    suspend fun selectItem(itemId: String?) {
        updateState { current ->
            current.copy(
                activeItemId = current.playlist
                    .firstOrNull { it.id == itemId && it.enabled }
                    ?.id
                    ?: current.activeEnabledItemId()
            )
        }
    }

    suspend fun setPlaybackMode(mode: PlaybackMode) {
        updateState { current ->
            current.copy(global = current.global.copy(playbackMode = mode))
        }
    }

    suspend fun setAudioEnabled(enabled: Boolean) {
        updateState { current ->
            current.copy(global = current.global.copy(audioEnabled = enabled))
        }
    }

    suspend fun setStartTime(startTime: StartTime) {
        updateState { current ->
            current.copy(global = current.global.copy(startTime = startTime))
        }
    }

    suspend fun setStatusBarColor(color: StatusBarColor) {
        updateState { current ->
            current.copy(global = current.global.copy(statusBarColor = color))
        }
    }

    suspend fun reorderPlaylist(fromIndex: Int, toIndex: Int) {
        updateState { current ->
            if (fromIndex !in current.playlist.indices || toIndex !in current.playlist.indices) {
                current
            } else {
                val reordered = current.playlist.toMutableList()
                val movedItem = reordered.removeAt(fromIndex)
                reordered.add(toIndex, movedItem)
                current.copy(
                    playlist = reordered,
                    activeItemId = current.resolveActiveItemId(reordered)
                )
            }
        }
    }

    suspend fun setPlaylistOrder(itemIds: List<String>) {
        updateState { current ->
            if (itemIds.isEmpty()) {
                current
            } else {
                val itemsById = current.playlist.associateBy(PlaylistItemState::id)
                val reordered = itemIds.mapNotNull(itemsById::get) +
                    current.playlist.filterNot { it.id in itemIds.toSet() }
                current.copy(
                    playlist = reordered,
                    activeItemId = current.resolveActiveItemId(reordered)
                )
            }
        }
    }

    suspend fun addImportedFile(fileName: String, makeActive: Boolean = true): String {
        // Reuse the same item ID if this file is already known.
        val existingItem = snapshot()?.playlist?.firstOrNull { it.fileName == fileName }
        val itemId = existingItem?.id ?: UUID.randomUUID().toString()
        updateState { current ->
            val importedItem = current.playlist.firstOrNull { it.fileName == fileName }?.copy(
                id = itemId,
                fileName = fileName
            ) ?: PlaylistItemState(
                id = itemId,
                fileName = fileName
            )
            val updatedPlaylist = listOf(importedItem) + current.playlist.filterNot { it.id == itemId }
            current.copy(
                playlist = updatedPlaylist,
                activeItemId = if (makeActive) itemId else current.resolveActiveItemId(updatedPlaylist)
            )
        }
        return itemId
    }

    suspend fun removeItem(itemId: String) {
        updateState { current ->
            val updatedPlaylist = current.playlist.filterNot { it.id == itemId }
            current.copy(
                playlist = updatedPlaylist,
                activeItemId = current.resolveActiveItemId(updatedPlaylist)
            )
        }
    }

    suspend fun setItemEnabled(itemId: String, enabled: Boolean) {
        updateState { current ->
            val updatedPlaylist = current.playlist.map { item ->
                if (item.id == itemId) {
                    item.copy(enabled = enabled)
                } else {
                    item
                }
            }
            current.copy(
                playlist = updatedPlaylist,
                activeItemId = current.resolveActiveItemId(updatedPlaylist)
            )
        }
    }

    suspend fun setItemLoopCount(itemId: String, loopCount: Int?) {
        updateState { current ->
            current.copy(
                playlist = current.playlist.map { item ->
                    if (item.id == itemId) {
                        // We normalize to at least 1 because playlist turns became explicit counts,
                        // not nullable/infinite semantics.
                        item.copy(loopCount = loopCount?.takeIf { it > 0 } ?: 1)
                    } else {
                        item
                    }
                }
            )
        }
    }

    suspend fun updateItemSettings(
        itemId: String,
        transform: (ItemPlaybackSettings) -> ItemPlaybackSettings
    ) {
        updateState { current ->
            current.copy(
                playlist = current.playlist.map { item ->
                    if (item.id == itemId) {
                        item.copy(settings = transform(item.settings))
                    } else {
                        item
                    }
                }
            )
        }
    }

    suspend fun resetItemSettings(itemId: String) {
        updateState { current ->
            current.copy(
                playlist = current.playlist.map { item ->
                    if (item.id == itemId) {
                        item.copy(settings = ItemPlaybackSettings())
                    } else {
                        item
                    }
                }
            )
        }
    }

    suspend fun reconcileWithDisk() {
        // The repository owns the "what files actually exist?" question now. That keeps disk sync
        // and playlist identity in one place instead of re-implementing it in each caller.
        val reconciledPlaylist = mergePlaylistWithDisk(
            context = context,
            existingItems = ensureLoaded().playlist
        )
        updateState { current ->
            current.copy(
                playlist = reconciledPlaylist,
                activeItemId = current.resolveActiveItemId(reconciledPlaylist)
            )
        }
    }

    private suspend fun updateState(transform: (AppState) -> AppState) {
        dataStore.updateData { current ->
            transform(current).normalize()
        }.also { updated ->
            latestState.update { updated }
        }
    }
}

private fun AppState.normalize(): AppState {
    // One normalize pass lets callers stay simple. If we ever write duplicate IDs or a stale active
    // selection, this is the last guardrail before the state hits disk.
    val dedupedPlaylist = playlist.distinctBy { it.id }
    return copy(
        schemaVersion = AppState.CURRENT_SCHEMA_VERSION,
        playlist = dedupedPlaylist,
        activeItemId = resolveActiveItemId(dedupedPlaylist)
    )
}

private fun AppState.resolveActiveItemId(updatedPlaylist: List<PlaylistItemState>): String? {
    return updatedPlaylist.firstOrNull { it.id == activeItemId && it.enabled }?.id
        ?: updatedPlaylist.firstOrNull(PlaylistItemState::enabled)?.id
}

private fun AppState.activeEnabledItemId(): String? {
    return playlist.firstOrNull(PlaylistItemState::enabled)?.id
}

private fun migrateLegacyState(
    context: Context,
    legacyPrefs: SharedPreferencesView,
    current: AppState
): AppState {
    if (current.playlist.isNotEmpty()) {
        return current.normalize()
    }

    // The upstream shape was basically "recent file names + active URI + global prefs". This is the
    // bridge that lifts that shape into a stable playlist model without losing existing installs.
    val legacyFileNames = decodeLegacyRecentFilesList(
        legacyPrefs.getString(PreferencesManager.KEY_RECENT_FILES_LIST, null)
    )
    val playlistItems = mergePlaylistWithDisk(
        context = context,
        existingItems = legacyFileNames.map { fileName ->
            PlaylistItemState(
                id = UUID.randomUUID().toString(),
                fileName = fileName
            )
        }
    )
    val appVideosDir = videosDir(context)

    val activeUri = legacyPrefs.getString(PreferencesManager.KEY_VIDEO_URI, null)
    val activeItemId = playlistItems.firstOrNull { item ->
        val file = appVideosDir?.let { File(it, item.fileName) }
        file != null && Uri.fromFile(file).toString() == activeUri
    }?.id

    return AppState(
        schemaVersion = AppState.CURRENT_SCHEMA_VERSION,
        activeItemId = activeItemId,
        global = GlobalPlaybackState(
            audioEnabled = legacyPrefs.getBoolean(PreferencesManager.KEY_VIDEO_AUDIO_ENABLED, false),
            playbackMode = decodePlaybackMode(
                legacyPrefs.getInt(PreferencesManager.KEY_PLAYBACK_MODE, PlaybackMode.LOOP.ordinal)
            ),
            startTime = decodeStartTime(
                legacyPrefs.getInt(PreferencesManager.KEY_START_TIME, StartTime.RESUME.ordinal)
            ),
            statusBarColor = decodeStatusBarColor(
                legacyPrefs.getInt(
                    PreferencesManager.KEY_STATUSBAR_COLOR,
                    StatusBarColor.AUTO.ordinal
                )
            )
        ),
        playlist = playlistItems
    ).normalize()
}

private fun mergePlaylistWithDisk(
    context: Context,
    existingItems: List<PlaylistItemState>
): List<PlaylistItemState> {
    // New files are still prepended by recency like upstream, but existing logical items keep their
    // IDs so reorder and per-item settings do not evaporate on the next scan.
    val appVideosDir = videosDir(context) ?: return existingItems
    val physicalFiles = appVideosDir.listFiles()?.toList().orEmpty()
    val physicalFileNames = physicalFiles.map(File::getName).toSet()

    val retainedItems = existingItems.filter { it.fileName in physicalFileNames }
    val retainedNames = retainedItems.map(PlaylistItemState::fileName).toSet()
    val newItems = physicalFiles
        .filterNot { it.name in retainedNames }
        .sortedByDescending(File::lastModified)
        .map { file ->
        PlaylistItemState(
            id = UUID.randomUUID().toString(),
            fileName = file.name
        )
    }
    return newItems + retainedItems
}

private fun decodeLegacyRecentFilesList(jsonString: String?): List<String> {
    if (jsonString.isNullOrBlank()) {
        return emptyList()
    }

    return try {
        Json.decodeFromString<List<String>>(jsonString)
    } catch (_: Exception) {
        emptyList()
    }
}

private fun decodePlaybackMode(ordinal: Int): PlaybackMode {
    return PlaybackMode.entries.getOrElse(ordinal) { PlaybackMode.LOOP }
}

private fun decodeStartTime(ordinal: Int): StartTime {
    return StartTime.entries.getOrElse(ordinal) { StartTime.RESUME }
}

private fun decodeStatusBarColor(ordinal: Int): StatusBarColor {
    return StatusBarColor.entries.getOrElse(ordinal) { StatusBarColor.AUTO }
}

private fun videosDir(context: Context): File? {
    val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: return null
    return File(moviesDir, "videos")
}
