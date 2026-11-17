package org.maocide.undeadwallpaper.ui

import org.maocide.undeadwallpaper.R

import org.maocide.undeadwallpaper.model.RecentFile
import org.maocide.undeadwallpaper.model.VideoSettings
import org.maocide.undeadwallpaper.model.ScalingMode
import org.maocide.undeadwallpaper.data.PreferencesManager
import org.maocide.undeadwallpaper.data.VideoFileManager
import org.maocide.undeadwallpaper.utils.setSafeOnClickListener

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

class RecentFilesAdapter(
    private val recentFiles: MutableList<RecentFile>,
    var currentVideoUriString: String?,
    private val preferencesManager: PreferencesManager,
    private val videoFileManager: VideoFileManager,
    private val coroutineScope: CoroutineScope,
    private val onItemClick: (RecentFile) -> Unit,
    private val onSettingsClick: (RecentFile) -> Unit
) : RecyclerView.Adapter<RecentFilesAdapter.ViewHolder>() {

    private val decodeSemaphore = Semaphore(4)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.recent_file_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val recentFile = recentFiles[position]
        holder.bind(recentFile)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelThumbnailLoad()
    }

    override fun getItemCount(): Int = recentFiles.size

    fun updateData(newFiles: List<RecentFile>) {
        recentFiles.clear()
        recentFiles.addAll(newFiles)
        notifyDataSetChanged()
    }

    fun setActiveUri(newUriString: String?) {
        val oldUriString = currentVideoUriString
        if (oldUriString == newUriString) return
        currentVideoUriString = newUriString

        val oldIndex = recentFiles.indexOfFirst { Uri.fromFile(it.file).toString() == oldUriString }
        val newIndex = recentFiles.indexOfFirst { Uri.fromFile(it.file).toString() == newUriString }

        if (oldIndex != -1) notifyItemChanged(oldIndex)
        if (newIndex != -1) notifyItemChanged(newIndex)
        if (oldIndex == -1 && newIndex == -1) notifyDataSetChanged()
    }

    fun onItemMove(fromPosition: Int, toPosition: Int) {
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(recentFiles, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(recentFiles, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
    }

    fun onItemDismiss(item: RecentFile) {
        val currentPosition = recentFiles.indexOf(item)
        if (currentPosition != -1) {
            recentFiles.removeAt(currentPosition)
            notifyItemRemoved(currentPosition)
        }
    }

    fun restoreItem(item: RecentFile) {
        val currentPosition = recentFiles.indexOf(item)
        if (currentPosition != -1) {
            notifyItemChanged(currentPosition)
        }
    }

    fun getItems(): List<RecentFile> {
        return recentFiles.toList()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.thumbnail)
        private val fileName: TextView = itemView.findViewById(R.id.file_name)
        private val settingsButton: ImageView = itemView.findViewById(R.id.button_settings)
        private val breadcrumbContainer: View = itemView.findViewById(R.id.breadcrumb_container)
        private val breadcrumbText: TextView = itemView.findViewById(R.id.breadcrumb_text)

        private var boundFile: File? = null
        private var thumbnailJob: Job? = null

        init {
            itemView.setSafeOnClickListener(debounceMs = 300L) {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(recentFiles[position])
                }
            }

            settingsButton.setSafeOnClickListener(debounceMs = 300L) {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onSettingsClick(recentFiles[position])
                }
            }
        }

        fun bind(recentFile: RecentFile) {
            cancelThumbnailLoad()
            boundFile = recentFile.file

            val settings = preferencesManager.getVideoSettings(recentFile.file.name)
            fileName.text = settings.getEffectiveDisplayName()

            val cachedBitmap = recentFile.thumbnail ?: videoFileManager.getCachedThumbnail(recentFile.file.name)
            if (cachedBitmap != null && !cachedBitmap.isRecycled) {
                thumbnail.setImageBitmap(cachedBitmap)
            } else {
                thumbnail.setImageDrawable(null) // clear if recycled
                val targetFile = recentFile.file
                thumbnailJob = coroutineScope.launch(Dispatchers.IO) {
                    decodeSemaphore.withPermit {
                        ensureActive()
                        val decoded = videoFileManager.getOrLoadThumbnailCached(targetFile)
                        ensureActive()
                        if (decoded != null) {
                            withContext(Dispatchers.Main) {
                                if (boundFile == targetFile) {
                                    thumbnail.setImageBitmap(decoded)
                                }
                            }
                        }
                    }
                }
            }

            val itemUriString = Uri.fromFile(recentFile.file).toString()
            if (itemUriString == currentVideoUriString) {
                // Highlight the active row using the app's primary color with 20% opacity
                val typedValue = TypedValue()
                itemView.context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true)
                val primaryColor = ContextCompat.getColor(itemView.context, typedValue.resourceId)
                // 51 is roughly 20% alpha (0.2 * 255)
                val highlightColor = androidx.core.graphics.ColorUtils.setAlphaComponent(primaryColor, 51)

                itemView.findViewById<View>(R.id.item_container).setBackgroundColor(highlightColor)
            } else {
                // Reset background
                itemView.findViewById<View>(R.id.item_container).setBackgroundColor(Color.TRANSPARENT)
            }

            // Update Breadcrumb
            val breadcrumbString = settings.getBreadcrumbText(itemView.context)

            if (breadcrumbString == null) {
                breadcrumbContainer.visibility = View.GONE
            } else {
                breadcrumbContainer.visibility = View.VISIBLE
                breadcrumbText.text = breadcrumbString
            }
        }

        fun cancelThumbnailLoad() {
            thumbnailJob?.cancel()
            thumbnailJob = null
        }
    }
}
