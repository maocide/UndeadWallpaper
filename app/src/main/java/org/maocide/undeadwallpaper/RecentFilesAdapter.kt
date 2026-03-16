package org.maocide.undeadwallpaper

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
import java.io.File
import java.util.Collections

class RecentFilesAdapter(
    private val recentFiles: MutableList<RecentFile>,
    var currentVideoUriString: String?,
    private val onItemClick: (RecentFile) -> Unit
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

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(recentFiles[position])
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
        }
    }
}
