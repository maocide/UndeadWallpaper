package org.maocide.undeadwallpaper

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class RecentFilesAdapter(
    private val recentFiles: MutableList<RecentFile>,
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
            }
        }
    }
}
