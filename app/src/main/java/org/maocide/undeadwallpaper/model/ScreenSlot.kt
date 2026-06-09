package org.maocide.undeadwallpaper.model

import kotlinx.serialization.Serializable

/**
 * A single home-screen "slot" for the per-screen wallpaper feature.
 * The index of a slot in the saved list corresponds to the launcher's
 * home-screen page index (slot 0 = first page, slot 1 = second, ...).
 *
 * @param videoFileName Name of the video (in the app's videos dir) to play on this page.
 * @param bridgeImageFileName Name of the per-page bridge image (in the app's images dir),
 *        used only when [BridgeMode.PER_PAGE_IMAGE] is selected.
 */
@Serializable
data class ScreenSlot(
    val videoFileName: String? = null,
    val bridgeImageFileName: String? = null
)
