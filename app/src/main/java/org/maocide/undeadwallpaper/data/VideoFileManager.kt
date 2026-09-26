package org.maocide.undeadwallpaper.data

import org.maocide.undeadwallpaper.R
import org.maocide.undeadwallpaper.BuildConfig

import org.maocide.undeadwallpaper.model.RecentFile
import org.maocide.undeadwallpaper.event.WallpaperEvent
import org.maocide.undeadwallpaper.event.WallpaperEventBus

import android.content.Context
import android.graphics.Bitmap
import org.maocide.undeadwallpaper.utils.MediaAnalyzer
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.maocide.undeadwallpaper.utils.FileLogger

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Manages video files for the application.
 * This class encapsulates the logic for storing, retrieving, and managing video files.
 *
 * @param context The application context.
 */
class VideoFileManager(
    private val context: Context,
    private val prefsName: String = "DEFAULT",
    private val videosDirectoryName: String = "videos"
) {

    sealed class CopyResult {
        data class Success(val file: File, val originalName: String) : CopyResult()
        object SizeLimitExceeded : CopyResult()
        object DimensionsExceeded : CopyResult()
        object CorruptFile : CopyResult()
        object Error : CopyResult()
    }

    class SizeLimitExceededException : Exception("File exceeds maximum supported size")

    private val tag: String = javaClass.simpleName

    /**
     * Copies a raw resource to the app's video storage if it doesn't exist.
     * Used for the default Zombillie asset.
     *
     * @param resourceId The R.raw ID of the asset.
     * @param fileName The desired filename (e.g., "zombillie_default.mp4").
     * @return The File object of the created or existing video.
     */
    suspend fun createDefaultFileFromResource(resourceId: Int): File? {
        val outputDir = getAppSpecificAlbumStorageDir(context, videosDirectoryName)
        val outputFile = File(outputDir, "video_${java.util.UUID.randomUUID()}.mp4")

        return try {
            context.resources.openRawResource(resourceId).use { inputStream ->
                // We enforce a strict 2MB limit here to prevent a tampered APK from 
                // unpacking a massive dummy payload (zip-bomb) to internal storage.
                copyStreamToFile(inputStream, outputFile, maxBytes = 2_000_000L)
            }

            // Strict compile-time exact size validation against the bundled asset
            if (BuildConfig.DEFAULT_ASSET_SIZE > 0 && outputFile.length() != BuildConfig.DEFAULT_ASSET_SIZE) {
                FileLogger.e(tag, "Default asset corrupt.")
                outputFile.delete()
                return null
            }

            outputFile
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                FileLogger.e(tag, "Failed to copy default resource", e)
            } else {
                FileLogger.e(tag, "Failed to copy default resource", e)
            }
            null
        }
    }


    /**
     * Creates a file in the app's specific storage from a content URI.
     * It uses a random UUID for the physical filename, and returns the original display name.
     *
     * @param fileUri The URI of the file to be copied.
     * @return A CopyResult indicating Success, Error, or SizeLimitExceeded.
     */
    suspend fun createFileFromContentUri(fileUri: Uri): CopyResult {
        var originalFileName = ""

        // Try to query the display name
        try {
            context.contentResolver.query(fileUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        originalFileName = cursor.getString(nameIndex)
                    }

                    // Early validation: Check size upfront to avoid streaming massive files to disk
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0) {
                        val sizeBytes = cursor.getLong(sizeIndex)
                        // 524_288_000L = 500 MB
                        if (sizeBytes > 524_288_000L) {
                            FileLogger.w(
                                tag,
                                "Upfront file size check failed. File is ${sizeBytes / 1_048_576}MB (Max: 500MB)."
                            )
                            return CopyResult.SizeLimitExceeded
                        }
                    }

                    // Early validation: Check dimensions before streaming file
                    val isValid = MediaAnalyzer.validateVideoDimensions(context, fileUri)
                    if (!isValid) {
                        FileLogger.w(tag, "Upfront dimension check failed.")
                        return CopyResult.DimensionsExceeded
                    }
                }
            }
            originalFileName = java.io.File(originalFileName).name
        } catch (e: Exception) {
            FileLogger.w(tag, "Could not query file name, generating fallback.", e)
        }

        // Use UUID for the fallback display name
        if (originalFileName.isBlank()) {
            originalFileName = "imported_video_${java.util.UUID.randomUUID()}.mp4"
        }

        val outputDir = getAppSpecificAlbumStorageDir(context, videosDirectoryName)
        val outputFile = File(outputDir, "video_${java.util.UUID.randomUUID()}.mp4")

        try {
            context.contentResolver.openInputStream(fileUri)?.use { iStream ->
                copyStreamToFile(iStream, outputFile)
            }
        } catch (_: SizeLimitExceededException) {
            FileLogger.w(tag, "File copy aborted: exceeds maximum size limit.")
            if (outputFile.exists()) {
                outputFile.delete()
            }
            return CopyResult.SizeLimitExceeded
        } catch (e: kotlinx.coroutines.CancellationException) {
            FileLogger.i(tag, "File copy cancelled. Deleting partial file.")
            if (outputFile.exists()) {
                outputFile.delete()
            }
            throw e
        } catch (e: Exception) {
            FileLogger.e(tag, "Error copying file from URI", e)
            if (outputFile.exists()) {
                outputFile.delete()
            }
            return CopyResult.Error
        }

        // Pre-Flight Validation Gate
        val thumbnail = getOrGenerateThumbnail(outputFile)
        if (thumbnail == null) {
            FileLogger.w(tag, "Corrupt or truncated media file rejected.")
            if (outputFile.exists()) {
                outputFile.delete()
            }
            return CopyResult.CorruptFile
        }

        return CopyResult.Success(outputFile, originalFileName)
    }


    /**
     * Loads the list of recent video files from the app's storage,
     * synchronizing it with the persisted order.
     *
     * @param performMaintenance If true, executes data migration, orphaned file garbage collection,
     * and thumbnail pruning. If false, performs a non-destructive read suitable for routine pagination.
     * @return A list of [RecentFile] objects in the correct order.
     */
    suspend fun loadRecentFiles(performMaintenance: Boolean = false): List<RecentFile> = fileOpsMutex.withLock {
        withContext(Dispatchers.IO) {
            val videosDir = getAppSpecificAlbumStorageDir(context, videosDirectoryName)
            var physicalFiles = videosDir.listFiles() ?: return@withContext emptyList()
            val preferencesManager = PreferencesManager(context, prefsName)

            // Get the persisted list of settings
            val rawSettings = preferencesManager.getPlaylistSettings()

            // Data Migration
            val (migratedSettings, wasMigrated) = if (performMaintenance) {
                val (settings, migrated) = performDataMigration(
                    videosDir,
                    physicalFiles,
                    rawSettings,
                    preferencesManager
                )
                if (migrated) {
                    physicalFiles = videosDir.listFiles() ?: emptyArray() // Refresh physical files
                }
                Pair(settings, migrated)
            } else {
                Pair(rawSettings.toMutableList(), false)
            }

            // Garbage Collection
            val (cleanFiles, cleanSettings) = if (performMaintenance) {
                performGarbageCollection(physicalFiles, migratedSettings)
            } else {
                // Non-destructive in-memory reconciliation (no physical file deletion)
                val persistedFileNames = migratedSettings.map { it.fileName }.toSet()
                val physicalFileNames = physicalFiles.map { it.name }.toSet()
                val safeFiles = physicalFiles.filter { it.name in persistedFileNames }.toTypedArray()
                migratedSettings.retainAll { it.fileName in physicalFileNames }
                Pair(safeFiles, migratedSettings)
            }

            // Pagination Clean-up
            val playlistManager = PlaylistManager(context, preferencesManager)
            val collapseOccurred = playlistManager.collapseEmptyPages(cleanSettings)

            // Save & Notify if needed
            if (wasMigrated || cleanSettings.size != rawSettings.size || collapseOccurred) {
                preferencesManager.savePlaylistSettings(cleanSettings)
                WallpaperEventBus.emit(WallpaperEvent.PlaylistReordered)
            }

            // Generate Thumbnails & Return
            return@withContext generateThumbnailsAsync(cleanFiles, cleanSettings, preferencesManager)
        }
    }

    /**
     * Performs cold-boot maintenance on storage and playlist state without generating thumbnails.
     * Executes legacy data migration, orphaned file garbage collection (sparing files in grace period),
     * and collapses empty playlist pages.
     */
    suspend fun performStartupMaintenance(): Unit = fileOpsMutex.withLock {
        withContext(Dispatchers.IO) {
            val videosDir = getAppSpecificAlbumStorageDir(context, videosDirectoryName)
            var physicalFiles = videosDir.listFiles() ?: return@withContext
            val preferencesManager = PreferencesManager(context, prefsName)

            val rawSettings = preferencesManager.getPlaylistSettings()

            // 1. Data Migration
            val (migratedSettings, wasMigrated) = performDataMigration(
                videosDir,
                physicalFiles,
                rawSettings,
                preferencesManager
            )
            if (wasMigrated) {
                physicalFiles = videosDir.listFiles() ?: emptyArray()
            }

            // 2. Garbage Collection (with 60s grace period)
            val (_, cleanSettings) = performGarbageCollection(physicalFiles, migratedSettings)

            // 3. Pagination Clean-up
            val playlistManager = PlaylistManager(context, preferencesManager)
            val collapseOccurred = playlistManager.collapseEmptyPages(cleanSettings)

            // 4. Save & Notify if changed
            if (wasMigrated || cleanSettings.size != rawSettings.size || collapseOccurred) {
                preferencesManager.savePlaylistSettings(cleanSettings)
                WallpaperEventBus.emit(WallpaperEvent.PlaylistReordered)
            }
        }
    }

    /**
     * Migrates legacy video files to the new UUID-based naming convention and cleans up corrupted files.
     *
     * @param videosDir The directory where video files are physically stored.
     * @param physicalFiles The array of currently existing physical video files.
     * @param persistedSettings The list of VideoSettings loaded from PreferencesManager.
     * @param preferencesManager The manager instance to sync the active video URI if a rename occurs.
     * @return A Pair containing the updated list of settings and a boolean indicating if changes (like renames/deletes) occurred.
     */
    private fun performDataMigration(
        videosDir: java.io.File,
        physicalFiles: Array<java.io.File>,
        persistedSettings: List<org.maocide.undeadwallpaper.model.VideoSettings>,
        preferencesManager: PreferencesManager
    ): Pair<MutableList<org.maocide.undeadwallpaper.model.VideoSettings>, Boolean> {
        var settingsChanged = false
        val updatedSettings = mutableListOf<org.maocide.undeadwallpaper.model.VideoSettings>()

        for (setting in persistedSettings) {
            val file = physicalFiles.find { it.name == setting.fileName }
            if (file != null) {
                if (setting.expectedFileSize == null) {
                    settingsChanged = true

                    // Verify if it is the legacy default file, check size
                    val defaultFileName = context.getString(org.maocide.undeadwallpaper.R.string.default_video_filename)
                    if (setting.fileName == defaultFileName) {
                        if (BuildConfig.DEFAULT_ASSET_SIZE > 0 && file.length() != BuildConfig.DEFAULT_ASSET_SIZE) {
                            FileLogger.e(tag, "File is corrupted, deleting video ${file.name}")
                            file.delete()
                            continue
                        }
                    }

                    // Rename ALL legacy files to UUID
                    val originalName = setting.fileName
                    val newFileName = "video_${java.util.UUID.randomUUID()}.mp4"
                    val newFile = java.io.File(videosDir, newFileName)
                    if (file.renameTo(newFile)) {
                        updatedSettings.add(
                            setting.copy(
                                fileName = newFileName,
                                expectedFileSize = newFile.length(),
                                displayName = originalName
                            )
                        )

                        // Keep the active video in sync
                        val activeUri = preferencesManager.getActiveVideoUri()
                        if (activeUri != null && android.net.Uri.parse(activeUri).lastPathSegment == originalName) {
                            preferencesManager.saveActiveVideoUri(android.net.Uri.fromFile(newFile).toString())
                        }
                    } else {
                        updatedSettings.add(setting.copy(expectedFileSize = file.length(), displayName = originalName))
                    }
                } else if (setting.expectedFileSize != file.length()) {
                    // Inconsistency found
                    FileLogger.e(tag, "File is corrupted, deleting video ${file.name}")
                    file.delete()
                    settingsChanged = true
                    // Do not add to updatedSettings so it gets removed from the playlist
                } else {
                    updatedSettings.add(setting)
                }
            } else {
                updatedSettings.add(setting) // File missing physically, will be filtered out below
            }
        }
        return Pair(updatedSettings, settingsChanged)
    }

    /**
     * Reconciles physical files with persisted settings, securely deleting unindexed "orphaned" 
     * physical files and purging settings that point to missing physical files.
     *
     * @param physicalFiles The array of currently existing physical video files.
     * @param persistedSettings The mutable list of VideoSettings after migration.
     * @return A Pair containing the cleaned array of physical files and the cleaned list of VideoSettings.
     */
    private fun performGarbageCollection(
        physicalFiles: Array<java.io.File>,
        persistedSettings: MutableList<org.maocide.undeadwallpaper.model.VideoSettings>
    ): Pair<Array<java.io.File>, MutableList<org.maocide.undeadwallpaper.model.VideoSettings>> {
        val persistedFileNames = persistedSettings.map { it.fileName }.toMutableList()

        // Identify physical files that are NOT in the persisted list (e.g., injected or orphaned files)
        val physicalFileNames = physicalFiles.map { it.name }.toSet()
        val orphanedFiles = physicalFiles.filter { it.name !in persistedFileNames }

        // Security / Garbage Collection: Delete unindexed physical files
        var cleanPhysicalFiles = physicalFiles
        if (orphanedFiles.isNotEmpty()) {
            val now = System.currentTimeMillis()
            for (file in orphanedFiles) {
                val lastModified = file.lastModified()
                val ageMs = now - lastModified
                if (lastModified > 0L && ageMs < ORPHAN_GRACE_PERIOD_MS) {
                    FileLogger.d(tag, "Skipping fresh unindexed file (in grace period, age=${ageMs}ms): ${file.name}")
                    continue
                }
                FileLogger.w(tag, "Deleting unindexed/injected physical file: ${file.name}")
                file.delete()
            }
            cleanPhysicalFiles = physicalFiles.filter { it.name in persistedFileNames }.toTypedArray()
        }

        // Identify files in the persisted list that no longer exist physically
        persistedFileNames.retainAll(physicalFileNames)
        persistedSettings.retainAll { it.fileName in physicalFileNames }

        // Security / Garbage Collection: Prune orphaned cached thumbnails
        try {
            val thumbnailsDir = getAppSpecificAlbumStorageDir(context, "thumbnails")
            val physicalThumbnailFiles = thumbnailsDir.listFiles() ?: emptyArray()
            val expectedThumbnailNames = cleanPhysicalFiles.map { "${it.nameWithoutExtension}.jpg" }.toSet()
            val deadline = System.currentTimeMillis() + THUMBNAIL_GC_TIME_BUDGET_MS
            var prunedCount = 0

            val now = System.currentTimeMillis()
            for (thumb in physicalThumbnailFiles) {
                if (prunedCount >= MAX_THUMBNAIL_PRUNES_PER_PASS || System.currentTimeMillis() >= deadline) {
                    FileLogger.d(tag, "Thumbnail GC budget reached (pruned: $prunedCount). Deferring remainder.")
                    break
                }
                val thumbAgeMs = now - thumb.lastModified()
                if (thumb.lastModified() > 0L && thumbAgeMs < ORPHAN_GRACE_PERIOD_MS) {
                    continue
                }
                if (thumb.isFile && !thumb.name.startsWith(".") && thumb.name !in expectedThumbnailNames) {
                    if (thumb.delete()) {
                        prunedCount++
                        FileLogger.w(tag, "Deleting orphaned thumbnail: ${thumb.name}")
                    }
                }
            }
        } catch (e: Exception) {
            FileLogger.e(tag, "Failed to garbage collect thumbnails", e)
        }

        return Pair(cleanPhysicalFiles, persistedSettings)
    }

    companion object {
        private val fileOpsMutex = Mutex()
        private const val ORPHAN_GRACE_PERIOD_MS = 60_000L
        private const val MAX_THUMBNAIL_PRUNES_PER_PASS = 50
        private const val THUMBNAIL_GC_TIME_BUDGET_MS = 15L
    }

    /**
     * Asynchronously generates thumbnails and extracts metadata/colors for the cleaned video files.
     * Limits concurrent processing to avoid OOM errors.
     *
     * @param physicalFiles The cleaned array of physical video files.
     * @param persistedSettings The cleaned list of VideoSettings.
     * @param preferencesManager The manager instance to persist newly extracted metadata/colors.
     * @return A fully populated list of RecentFile objects ready for the UI layer.
     */
    private suspend fun generateThumbnailsAsync(
        physicalFiles: Array<java.io.File>,
        persistedSettings: List<org.maocide.undeadwallpaper.model.VideoSettings>,
        preferencesManager: PreferencesManager
    ): List<RecentFile> = kotlinx.coroutines.coroutineScope {
        val physicalFileMap = physicalFiles.associateBy { it.name }
        val semaphore = kotlinx.coroutines.sync.Semaphore(4) // Limit to 4 concurrent thumbnail generations

        persistedSettings.mapNotNull { setting ->
            val fileName = setting.fileName
            val file = physicalFileMap[fileName]
            if (file != null) {
                async {
                    semaphore.withPermit {
                        try {
                            kotlinx.coroutines.currentCoroutineContext()
                                .ensureActive() // Allow cancellation before heavy processing

                            val currentSettings = preferencesManager.getVideoSettings(fileName)
                            val thumbnail = getOrGenerateThumbnail(file)

                            // Extract WallpaperColors if needed
                            if (thumbnail != null) {
                                if (currentSettings.primaryColor == null) {
                                    val colors = MediaAnalyzer.extractWallpaperColors(thumbnail)
                                    if (colors != null) {
                                        preferencesManager.updateVideoSettings(fileName) { settings ->
                                            settings.copy(
                                                primaryColor = colors.primaryColor,
                                                secondaryColor = colors.secondaryColor,
                                                tertiaryColor = colors.tertiaryColor,
                                                colorHints = colors.colorHints
                                            )
                                        }
                                    }
                                }
                            }

                            var durationMs = currentSettings.durationMs
                            var width = currentSettings.width
                            var height = currentSettings.height
                            var fps = currentSettings.fps

                            // If metadata is missing, extract and save it
                            if (durationMs == null || width == null || height == null || fps == null) {
                                try {
                                    kotlinx.coroutines.currentCoroutineContext()
                                        .ensureActive() // Check before extracting metadata

                                    val metadata = MediaAnalyzer.extractVideoMetadata(file.path)

                                    durationMs = metadata.durationMs
                                    width = metadata.width
                                    height = metadata.height
                                    fps = metadata.fps

                                    synchronized(preferencesManager) {
                                        preferencesManager.updateVideoSettings(fileName) { settings ->
                                            settings.copy(
                                                durationMs = durationMs,
                                                width = width,
                                                height = height,
                                                fps = fps
                                            )
                                        }
                                    }
                                } catch (e: Exception) {
                                    FileLogger.e(tag, "Error extracting metadata", e)
                                }
                            }

                            RecentFile(
                                file = file,
                                thumbnail = thumbnail,
                                durationMs = durationMs ?: 0L,
                                width = width ?: 0,
                                height = height ?: 0,
                                sizeBytes = file.length(),
                                fps = fps ?: 0
                            )
                        } catch (e: Exception) {
                            FileLogger.e(tag, "Error loading thumbnail", e)
                            RecentFile(file, null, sizeBytes = file.length())
                        }
                    }
                }
            } else {
                null
            }
        }.awaitAll()
    }

    /**
     * Extracts a scaled thumbnail from a video file without loading massive 4K raw frames into memory.
     *
     * @param filePath The path of the video file.
     * @return The scaled thumbnail bitmap, or null if creation failed.
     */
    private fun createVideoThumbnail(filePath: String): Bitmap? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(filePath)
            // minSdk is 28: getScaledFrameAtTime scales hardware-decoded frame directly to max 512x512
            retriever.getScaledFrameAtTime(
                -1L,
                android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                512,
                512
            ) ?: retriever.frameAtTime
        } catch (_: Exception) {
            FileLogger.w(tag, "Thumbnail failure. Media corrupted.")
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // Ignore release exceptions
            }
        }
    }

    /**
     * Gets a cached thumbnail from disk, or generates and caches one if missing.
     * Downsamples legacy cached files if they exceed thumbnail bounds.
     */
    fun getOrGenerateThumbnail(videoFile: File): Bitmap? {
        val thumbnailsDir = getAppSpecificAlbumStorageDir(context, "thumbnails")
        val thumbnailFile = File(thumbnailsDir, "${videoFile.nameWithoutExtension}.jpg")

        if (thumbnailFile.exists()) {
            val boundsOptions = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeFile(thumbnailFile.absolutePath, boundsOptions)
            val sampleSize = calculateInSampleSize(boundsOptions, 512, 512)
            val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565 // Conserves 50% memory for thumbnails
            }
            return android.graphics.BitmapFactory.decodeFile(thumbnailFile.absolutePath, decodeOptions)
        }

        val bitmap = createVideoThumbnail(videoFile.absolutePath)
        if (bitmap != null) {
            try {
                java.io.FileOutputStream(thumbnailFile).use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                }
            } catch (e: Exception) {
                FileLogger.e(tag, "Failed to cache thumbnail", e)
            }
        }
        return bitmap
    }

    /**
     * Deletes a video file along with its cached thumbnail from disk.
     *
     * @param videoFile The video file to be deleted.
     * @return True if the physical video file was deleted, false otherwise.
     */
    fun deleteVideoAndThumbnail(videoFile: File): Boolean {
        val thumbnailsDir = getAppSpecificAlbumStorageDir(context, "thumbnails")
        val thumbnailFile = File(thumbnailsDir, "${videoFile.nameWithoutExtension}.jpg")
        if (thumbnailFile.exists()) {
            thumbnailFile.delete()
        }
        return if (videoFile.exists()) videoFile.delete() else false
    }

    private fun calculateInSampleSize(
        options: android.graphics.BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * Gets the app-specific album storage directory.
     *
     * @param context The application context.
     * @param albumName The name of the album.
     * @return The directory file.
     */
    private fun getAppSpecificAlbumStorageDir(context: Context, albumName: String): File {
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), albumName)
        if (!file.exists() && !file.mkdirs()) {
            FileLogger.e(tag, "Failed to create directory: $albumName")
        }
        return file
    }

    /**
     * Copies an input stream to a file.
     *
     * @param inputStream The input stream to copy from.
     * @param outputFile The file to copy to.
     */
    private suspend fun copyStreamToFile(inputStream: InputStream, outputFile: File, maxBytes: Long = 524_288_000L) {
        inputStream.use { input ->
            FileOutputStream(outputFile).use { output ->
                val buffer = ByteArray(64 * 1024)
                var byteCount: Int
                var totalBytesCopied = 0L
                while (input.read(buffer).also { byteCount = it } != -1) {
                    kotlinx.coroutines.currentCoroutineContext()
                        .ensureActive() // Checks for coroutine cancellation without yielding thread

                    totalBytesCopied += byteCount
                    if (totalBytesCopied > maxBytes) {
                        throw SizeLimitExceededException()
                    }

                    output.write(buffer, 0, byteCount)
                }
            }
        }
    }
}
