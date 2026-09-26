package org.maocide.undeadwallpaper.ui

import android.content.Context
import android.net.Uri
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.net.toUri
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maocide.undeadwallpaper.R
import org.maocide.undeadwallpaper.data.PreferencesManager
import org.maocide.undeadwallpaper.data.VideoFileManager
import org.maocide.undeadwallpaper.databinding.FragmentSettingsBinding
import org.maocide.undeadwallpaper.event.WallpaperEvent
import org.maocide.undeadwallpaper.event.WallpaperEventBus
import org.maocide.undeadwallpaper.model.VideoSettings
import org.maocide.undeadwallpaper.model.RecentFile
import org.maocide.undeadwallpaper.utils.setSafeOnClickListener
import java.io.File

/**
 * Controller responsible for the playlist card:
 * - RecyclerView adapter and layout manager setup
 * - ItemTouchHelper drag-to-reorder and swipe-to-dismiss gestures
 * - File removal confirmation dialog
 * - Order persistence to PreferencesManager
 * - Pagination controls, slot selection dropdown, and slide animations
 * - Empty playlist view toggling
 */
class PlaylistController(
    private val binding: FragmentSettingsBinding,
    private val preferencesManager: PreferencesManager,
    private val videoFileManager: VideoFileManager,
    private val coroutineScope: CoroutineScope,
    private val fragmentManager: FragmentManager,
    private val onVideoSelected: (Uri, Boolean) -> Unit,
    private val onAddVideoClick: () -> Unit,
    private val onEnsureDefaultVideo: suspend () -> Unit,
    private val getActiveVideoUri: () -> String?
) {
    private val context: Context get() = binding.root.context

    private lateinit var recentFilesAdapter: RecentFilesAdapter
    private val recentFiles = mutableListOf<RecentFile>()

    var currentPage: Int = preferencesManager.getActivePage()
        private set

    val currentVideoUriString: String?
        get() = if (::recentFilesAdapter.isInitialized) recentFilesAdapter.currentVideoUriString else null

    private var engineSyncJob: Job? = null

    fun setup() {
        setupRecyclerView()
        setupListeners()
        updatePaginationUI()
    }

    private fun setupRecyclerView() {
        val currentUri = getActiveVideoUri()
        recentFilesAdapter = RecentFilesAdapter(
            recentFiles,
            currentVideoUriString = currentUri,
            preferencesManager = preferencesManager,
            onItemClick = { recentFile ->
                val fileUri = Uri.fromFile(recentFile.file)
                engineSyncJob?.cancel()
                onVideoSelected(fileUri, true)
            },
            onSettingsClick = { recentFile ->
                val bottomSheet =
                    VideoSettingsSheet.newInstance(recentFile.file.name, recentFile.getFormattedMetadata())
                bottomSheet.show(fragmentManager, "VideoSettingsBottomSheet")
            }
        )
        binding.recyclerViewRecentFiles.layoutManager = LinearLayoutManager(context)
        binding.recyclerViewRecentFiles.adapter = recentFilesAdapter

        val itemTouchHelperCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            private var isOrderChanged = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                recentFilesAdapter.onItemMove(fromPos, toPos)
                isOrderChanged = true
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                val item = recentFilesAdapter.getItems()[position]

                if (recentFilesAdapter.itemCount <= 1 && currentPage == 0) {
                    Toast.makeText(context, context.getString(R.string.error_cannot_remove_last_video), Toast.LENGTH_SHORT)
                        .show()
                    recentFilesAdapter.notifyItemChanged(position)
                    return
                }

                showRemoveFileDialog(item)
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                if (isOrderChanged) {
                    isOrderChanged = false
                    saveCurrentPlaylistOrder()
                    WallpaperEventBus.emit(WallpaperEvent.PlaylistReordered)
                }
            }

            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return 0
                return super.getSwipeDirs(recyclerView, viewHolder)
            }
        }

        ItemTouchHelper(itemTouchHelperCallback).attachToRecyclerView(binding.recyclerViewRecentFiles)
    }

    private fun setupListeners() {
        binding.btnPrevPage.setSafeOnClickListener(debounceMs = 250L) {
            changePage(currentPage - 1, slideRight = false)
        }
        binding.btnNextPage.setSafeOnClickListener(debounceMs = 250L) {
            changePage(currentPage + 1, slideRight = true)
        }
        binding.btnAddToPlaylist.setSafeOnClickListener {
            onAddVideoClick()
        }
        binding.layoutEmptyPlaylist.setSafeOnClickListener {
            onAddVideoClick()
        }
        binding.tvPageNumber.setSafeOnClickListener(debounceMs = 150L) {
            showPaginationDropdown()
        }
    }

    private fun showRemoveFileDialog(item: RecentFile) {
        val settings = preferencesManager.getVideoSettings(item.file.name)
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.remove_file_title))
            .setMessage(context.getString(R.string.remove_file_message, settings.getEffectiveDisplayName()))
            .setPositiveButton(context.getString(R.string.remove_action)) { _, _ ->
                handlePlaylistItemRemoved(item)
            }
            .setNegativeButton(context.getString(R.string.cancel)) { dialog, _ ->
                recentFilesAdapter.restoreItem(item)
                dialog.dismiss()
            }
            .setOnCancelListener {
                recentFilesAdapter.restoreItem(item)
            }
            .show()
    }

    private fun handlePlaylistItemRemoved(item: RecentFile) {
        val deletedUriString = Uri.fromFile(item.file).toString()
        val uiSelectedUriString = getActiveVideoUri()
        val backgroundActiveUriString = preferencesManager.getActiveVideoUri()

        recentFilesAdapter.onItemDismiss(item)
        videoFileManager.deleteVideoAndThumbnail(item.file)
        saveCurrentPlaylistOrder()

        val wasActive =
            (deletedUriString == uiSelectedUriString || deletedUriString == backgroundActiveUriString)

        if (recentFilesAdapter.itemCount == 0) {
            engineSyncJob?.cancel()
            engineSyncJob = coroutineScope.launch {
                loadRecentFiles(performMaintenance = false)
                val reloadedNextItem = recentFilesAdapter.getItems().firstOrNull()

                if (reloadedNextItem != null) {
                    if (wasActive) {
                        onVideoSelected(Uri.fromFile(reloadedNextItem.file), true)
                    } else {
                        WallpaperEventBus.emit(WallpaperEvent.PlaylistReordered)
                    }
                } else {
                    if (currentPage > 0) {
                        changePage(currentPage - 1, slideRight = false)
                    } else {
                        preferencesManager.saveActiveVideoUri("")
                        onEnsureDefaultVideo()
                        val defaultUri = preferencesManager.getActiveVideoUri()
                        if (defaultUri != null) {
                            onVideoSelected(defaultUri.toUri(), true)
                            loadRecentFiles()
                        }
                    }
                }
                updatePaginationUI()
            }
        } else {
            if (wasActive) {
                val nextItem = recentFilesAdapter.getItems().first()
                onVideoSelected(Uri.fromFile(nextItem.file), true)
            } else {
                WallpaperEventBus.emit(WallpaperEvent.PlaylistReordered)
            }
            updatePaginationUI()
        }
    }

    private fun saveCurrentPlaylistOrder() {
        val currentFileNames = recentFilesAdapter.getItems().map { it.file.name }
        val currentSettings = preferencesManager.getPlaylistSettings()
        val newSettings = calculateReorderedPlaylistSettings(currentFileNames, currentSettings, currentPage)
        preferencesManager.savePlaylistSettings(newSettings)
    }

    suspend fun loadRecentFiles(performMaintenance: Boolean = false) {
        val files = withContext(Dispatchers.IO) {
            videoFileManager.loadRecentFiles(performMaintenance = performMaintenance)
        }

        val settings = preferencesManager.getPlaylistSettings()
        val filteredFiles = filterFilesForPage(files, settings, currentPage)

        recentFiles.clear()
        recentFiles.addAll(filteredFiles)

        recentFilesAdapter.currentVideoUriString = getActiveVideoUri()

        if (recentFiles.isEmpty()) {
            binding.recyclerViewRecentFiles.visibility = View.GONE
            binding.layoutEmptyPlaylist.visibility = View.VISIBLE
            binding.textPlaylistInstructions.setText(R.string.playlist_instructions_empty)
        } else {
            binding.recyclerViewRecentFiles.visibility = View.VISIBLE
            binding.layoutEmptyPlaylist.visibility = View.GONE
            binding.textPlaylistInstructions.setText(R.string.playlist_instructions)
        }

        recentFilesAdapter.notifyDataSetChanged()
        binding.recyclerViewRecentFiles.scheduleLayoutAnimation()
        updatePaginationUI()
    }

    fun updatePaginationUI() {
        binding.tvPageNumber.text = String.format("%02d", currentPage + 1)

        binding.btnPrevPage.isEnabled = currentPage > 0
        binding.btnPrevPage.alpha = if (currentPage > 0) 1.0f else 0.3f

        val settings = preferencesManager.getPlaylistSettings()
        val hasItemsOnCurrentPage = settings.any { it.page == currentPage }

        binding.btnNextPage.isEnabled = hasItemsOnCurrentPage
        binding.btnNextPage.alpha = if (hasItemsOnCurrentPage) 1.0f else 0.3f
    }

    private fun showPaginationDropdown() {
        val settings = preferencesManager.getPlaylistSettings()
        val targetMaxPage = calculateTargetMaxPage(settings)

        val popup = PopupMenu(context, binding.tvPageNumber)
        for (i in 0..targetMaxPage) {
            val title = if (i == targetMaxPage && i > 0 && settings.none { it.page == i }) {
                "Slot ${String.format("%02d", i + 1)} (New)"
            } else {
                "Slot ${String.format("%02d", i + 1)}"
            }
            popup.menu.add(0, i, 0, title)
        }

        popup.setOnMenuItemClickListener { item ->
            val targetPage = item.itemId
            val slideRight = targetPage > currentPage
            changePage(targetPage, slideRight)
            true
        }
        popup.show()
    }

    fun changePage(newPage: Int, slideRight: Boolean) {
        if (newPage == currentPage) return
        currentPage = newPage
        preferencesManager.saveActivePage(currentPage)
        updatePaginationUI()

        val animRes = if (slideRight) R.anim.layout_anim_slide_right else R.anim.layout_anim_slide_left
        val controller = AnimationUtils.loadLayoutAnimation(context, animRes)
        binding.recyclerViewRecentFiles.layoutAnimation = controller

        engineSyncJob?.cancel()
        engineSyncJob = coroutineScope.launch {
            loadRecentFiles(performMaintenance = false)

            if (!isCurrentVideoValid()) {
                val firstFileOnPage = recentFilesAdapter.getItems().firstOrNull()
                if (firstFileOnPage != null) {
                    onVideoSelected(Uri.fromFile(firstFileOnPage.file), true)
                }
            }
        }
    }

    private suspend fun isCurrentVideoValid(): Boolean = withContext(Dispatchers.IO) {
        val currentUriString = getActiveVideoUri()
        if (currentUriString.isNullOrEmpty() || currentUriString == "null") return@withContext false

        val path = currentUriString.toUri().path ?: return@withContext false
        val file = File(path)
        if (!file.exists() || !file.isFile) return@withContext false

        val settings = preferencesManager.getPlaylistSettings()
        settings.any { it.fileName == file.name }
    }

    fun setActiveVideoUri(uriString: String?) {
        if (::recentFilesAdapter.isInitialized) {
            recentFilesAdapter.currentVideoUriString = uriString
            recentFilesAdapter.notifyDataSetChanged()
        }
    }

    fun notifyDataSetChanged() {
        if (::recentFilesAdapter.isInitialized) {
            recentFilesAdapter.notifyDataSetChanged()
        }
    }

    fun cleanup() {
        engineSyncJob?.cancel()
        engineSyncJob = null
    }

    companion object {
        fun calculateReorderedPlaylistSettings(
            currentFileNames: List<String>,
            allSettings: List<VideoSettings>,
            currentPage: Int
        ): List<VideoSettings> {
            val otherPagesSettings = allSettings.filter { it.page != currentPage }
            val settingsMap = allSettings.associateBy { it.fileName }
            val currentPageSettings = currentFileNames.mapNotNull { settingsMap[it] }
            return (otherPagesSettings + currentPageSettings).sortedBy { it.page }
        }

        fun calculateTargetMaxPage(settings: List<VideoSettings>): Int {
            val maxPage = settings.maxOfOrNull { it.page } ?: 0
            return if (settings.any { it.page == maxPage }) maxPage + 1 else maxPage
        }

        fun filterFilesForPage(
            files: List<RecentFile>,
            settings: List<VideoSettings>,
            currentPage: Int
        ): List<RecentFile> {
            val filesOnPage = settings.filter { it.page == currentPage }.map { it.fileName }.toSet()
            return files.filter { it.file.name in filesOnPage }
        }
    }
}
