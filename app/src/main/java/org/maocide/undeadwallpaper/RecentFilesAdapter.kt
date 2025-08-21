package org.maocide.undeadwallpaper

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

data class RecentFile(
    val file: File,
    val thumbnail: Bitmap?
)

class RecentFilesAdapter(
    private val recentFiles: MutableList<RecentFile>,
    private val onItemClick: (RecentFile) -> Unit,
    private val onDeleteClick: (RecentFile) -> Unit
) : RecyclerView.Adapter<RecentFilesAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.recent_file_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val recentFile = recentFiles[position]
        holder.bind(recentFile, onItemClick, onDeleteClick)
    }

    override fun getItemCount(): Int = recentFiles.size

    fun updateData(newFiles: List<RecentFile>) {
        recentFiles.clear()
        recentFiles.addAll(newFiles)
        notifyDataSetChanged()
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.thumbnail)
        private val fileName: TextView = itemView.findViewById(R.id.file_name)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.delete_button)

        fun bind(
            recentFile: RecentFile,
            onItemClick: (RecentFile) -> Unit,
            onDeleteClick: (RecentFile) -> Unit
        ) {
            fileName.text = recentFile.file.name
            if (recentFile.thumbnail != null) {
                thumbnail.setImageBitmap(recentFile.thumbnail)
            }
            itemView.setOnClickListener { onItemClick(recentFile) }
            deleteButton.setOnClickListener { onDeleteClick(recentFile) }
        }
    }
}
