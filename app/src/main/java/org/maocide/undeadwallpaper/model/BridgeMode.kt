package org.maocide.undeadwallpaper.model

/**
 * Source of the "bridge" image shown between home-screen pages while the
 * per-screen wallpaper transitions from one video to the next.
 *
 * - [FROZEN_FRAME]: capture the current video frame at swipe start (no asset).
 * - [SHARED_IMAGE]: one user-picked image used for every transition.
 * - [PER_PAGE_IMAGE]: each screen slot has its own user-picked image.
 */
enum class BridgeMode {
    FROZEN_FRAME,
    SHARED_IMAGE,
    PER_PAGE_IMAGE
}
