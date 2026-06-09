package org.maocide.undeadwallpaper.ui

import org.maocide.undeadwallpaper.R
import org.maocide.undeadwallpaper.data.ImageFileManager
import org.maocide.undeadwallpaper.model.BridgeMode
import org.maocide.undeadwallpaper.model.ScreenSlot

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import androidx.recyclerview.widget.RecyclerView

/**
 * Lists the per-screen "slots". Each row lets the user pick the video for that
 * home page and, in [BridgeMode.PER_PAGE_IMAGE], a per-page bridge image.
 */
class ScreenSlotAdapter(
    private val slots: MutableList<ScreenSlot>,
    private val imageFileManager: ImageFileManager,
    private var bridgeMode: BridgeMode,
    private val onChooseVideo: (Int) -> Unit,
    private val onChooseImage: (Int) -> Unit,
    private val onRemove: (Int) -> Unit
) : RecyclerView.Adapter<ScreenSlotAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.screen_slot_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(slots[position], position)
    }

    override fun getItemCount(): Int = slots.size

    fun setBridgeMode(mode: BridgeMode) {
        bridgeMode = mode
        notifyDataSetChanged()
    }

    fun getSlots(): List<ScreenSlot> = slots

    fun updateSlots(newSlots: List<ScreenSlot>) {
        slots.clear()
        slots.addAll(newSlots)
        notifyDataSetChanged()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val screenNumber: TextView = itemView.findViewById(R.id.text_screen_number)
        private val chooseVideo: MaterialButton = itemView.findViewById(R.id.button_choose_video)
        private val selectedVideo: TextView = itemView.findViewById(R.id.text_selected_video)
        private val perPageImageLayout: View = itemView.findViewById(R.id.layout_per_page_image)
        private val chooseImage: MaterialButton = itemView.findViewById(R.id.button_choose_image)
        private val thumbnail: ImageView = itemView.findViewById(R.id.image_slot_thumbnail)
        private val removeButton: MaterialButton = itemView.findViewById(R.id.button_remove_screen)

        fun bind(slot: ScreenSlot, position: Int) {
            val context = itemView.context
            screenNumber.text = context.getString(R.string.per_screen_screen_number, position + 1)

            selectedVideo.text = slot.videoFileName
                ?: context.getString(R.string.per_screen_no_video)

            chooseVideo.setOnClickListener { onChooseVideo(bindingAdapterPosition) }
            removeButton.setOnClickListener { onRemove(bindingAdapterPosition) }

            if (bridgeMode == BridgeMode.PER_PAGE_IMAGE) {
                perPageImageLayout.visibility = View.VISIBLE
                chooseImage.setOnClickListener { onChooseImage(bindingAdapterPosition) }

                val bmp = imageFileManager.loadBitmap(slot.bridgeImageFileName, 240, 240)
                if (bmp != null) {
                    thumbnail.setImageBitmap(bmp)
                    thumbnail.visibility = View.VISIBLE
                } else {
                    thumbnail.setImageDrawable(null)
                    thumbnail.visibility = View.GONE
                }
            } else {
                perPageImageLayout.visibility = View.GONE
            }
        }
    }
}
