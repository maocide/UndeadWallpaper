package org.maocide.undeadwallpaper

import android.graphics.Bitmap
import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections

// Rows are tied to playlist items now, not just files.
class RecentFilesAdapter(
    private val recentFiles: MutableList<RecentFile>,
    var currentItemId: String?,
    private val onItemClick: (RecentFile) -> Unit,
    private val onEditClick: (RecentFile) -> Unit
) : RecyclerView.Adapter<RecentFilesAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.recent_file_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val recentFile = recentFiles[position]
        holder.bind(recentFile)
    }

    override fun getItemCount(): Int = recentFiles.size

    fun updateData(newFiles: List<RecentFile>) {
        recentFiles.clear()
        recentFiles.addAll(newFiles)
        notifyDataSetChanged()
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

    fun onItemDismiss(position: Int) {
        recentFiles.removeAt(position)
        notifyItemRemoved(position)
    }

    fun getItems(): List<RecentFile> {
        return recentFiles.toList()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.thumbnail)
        private val fileName: TextView = itemView.findViewById(R.id.file_name)
        private val statusLabel: TextView = itemView.findViewById(R.id.item_status)
        private val editButton: ImageView = itemView.findViewById(R.id.edit_item)

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(recentFiles[position])
                }
            }
            editButton.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onEditClick(recentFiles[position])
                }
            }
        }

        fun bind(recentFile: RecentFile) {
            fileName.text = recentFile.file.name
            if (recentFile.thumbnail != null) {
                thumbnail.setImageBitmap(recentFile.thumbnail)
            } else {
                thumbnail.setImageDrawable(null) // clear if recycled
            }

            // Small row summary for disabled state and loop count.
            val statusText = buildList {
                if (!recentFile.enabled) add(itemView.context.getString(R.string.disabled_status))
                if (recentFile.loopCount > 1) {
                    add(itemView.context.getString(R.string.loop_count_summary, recentFile.loopCount))
                }
            }.joinToString(" • ")
            statusLabel.isVisible = statusText.isNotEmpty()
            statusLabel.text = statusText

            // Highlight by item ID so reorder/delete does not confuse selection.
            if (recentFile.itemId == currentItemId) {
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

            // Disabled items stay visible, so dim them instead.
            val alpha = if (recentFile.enabled) 1f else 0.5f
            thumbnail.alpha = alpha
            fileName.alpha = alpha
            statusLabel.alpha = alpha
        }
    }
}
