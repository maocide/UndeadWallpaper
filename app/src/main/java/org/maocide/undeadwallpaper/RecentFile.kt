package org.maocide.undeadwallpaper

import android.graphics.Bitmap
import java.io.File

/**
 * Data class representing a recent file.
 *
 * Row model for the playlist. It carries a little item state now too.
 */
data class RecentFile(
    val itemId: String,
    val file: File,
    val thumbnail: Bitmap?,
    val enabled: Boolean,
    val loopCount: Int
)
