package org.maocide.undeadwallpaper

import android.graphics.Bitmap
import java.io.File

/**
 * Data class representing a recent file.
 *
 * @param file The file object.
 * @param thumbnail The thumbnail of the file.
 */
data class RecentFile(
    val file: File,
    val thumbnail: Bitmap?
)
