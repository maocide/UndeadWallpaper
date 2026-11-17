package org.maocide.undeadwallpaper.ui

import android.os.Bundle
import android.transition.TransitionManager
import android.view.View
import android.view.ViewGroup
import kotlin.random.Random
import org.maocide.undeadwallpaper.data.PreferencesManager
import org.maocide.undeadwallpaper.databinding.FragmentSettingsBinding
import org.maocide.undeadwallpaper.utils.setSafeOnClickListener

/**
 * Identifies the collapsible accordion cards available in SettingsFragment.
 *
 * @param seenEpochKey Preference key tracking the highest feature epoch seen by the user.
 * @param featureEpoch Current feature epoch for this accordion. Bump this integer on a release
 *                     to light up the tilted `[ NEW! ]` stamp until the user opens or edits it.
 */
enum class AccordionType(
    val seenEpochKey: String,
    val featureEpoch: Int
) {
    GLOBAL_SETTINGS("accordion_seen_epoch_global", 0),
    UNDEAD_AFFECTS("accordion_seen_epoch_undead_affects", 1),
    PARALLAX("accordion_seen_epoch_parallax", 0)
}

/**
 * Controller managing the expandable/collapsible accordion cards in SettingsFragment.
 * Encapsulates SafeInput tapjacking defenses, smooth TransitionManager animations,
 * chevron rotations, `[ NEW! ]` feature stamp lifecycle, programmatic open/close controls,
 * and configuration-change state restoration.
 */
class AccordionController(
    private val rootView: ViewGroup,
    private val binding: FragmentSettingsBinding,
    private val preferencesManager: PreferencesManager
) {
    private val expandedStates = mutableMapOf(
        AccordionType.GLOBAL_SETTINGS to false,
        AccordionType.UNDEAD_AFFECTS to false,
        AccordionType.PARALLAX to false
    )

    private val sections by lazy {
        mapOf(
            AccordionType.GLOBAL_SETTINGS to AccordionViews(
                header = binding.headerGlobalSettingsChevron,
                content = binding.contentGlobalSettings,
                chevron = binding.iconGlobalSettingsChevron,
                newBadge = binding.badgeNewGlobalSettings
            ),
            AccordionType.UNDEAD_AFFECTS to AccordionViews(
                header = binding.headerUndeadAffects,
                content = binding.contentUndeadAffects,
                chevron = binding.iconUndeadAffectsChevron,
                newBadge = binding.badgeNewUndeadAffects
            ),
            AccordionType.PARALLAX to AccordionViews(
                header = binding.headerParallax,
                content = binding.contentParallax,
                chevron = binding.iconParallaxChevron,
                newBadge = binding.badgeNewParallax
            )
        )
    }

    private data class AccordionViews(
        val header: View,
        val content: View,
        val chevron: View,
        val newBadge: View
    )

    companion object {
        const val KEY_ACCORDION_GLOBAL = "accordion_global_settings_expanded"
        const val KEY_ACCORDION_UNDEAD_AFFECTS = "accordion_undead_affects_expanded"
        const val KEY_ACCORDION_PARALLAX = "accordion_parallax_expanded"
        const val DEBOUNCE_ACCORDION_MS = 200L
        const val ANIMATION_DURATION_MS = 200L
        const val STAMP_MIN_ANGLE_DEG = 4.5f
        const val STAMP_MAX_ANGLE_DEG = 10.5f

        /**
         * Resolves the target expanded state given an optional saved boolean value
         * and the fallback default value.
         */
        fun resolveExpandedState(
            savedValue: Boolean?,
            defaultValue: Boolean
        ): Boolean {
            return savedValue ?: defaultValue
        }

        /**
         * Determines whether the `[ NEW! ]` stamp should be shown for an accordion.
         */
        fun shouldShowNewBadge(seenEpoch: Int, featureEpoch: Int): Boolean {
            return featureEpoch > 0 && seenEpoch < featureEpoch
        }

        /**
         * Computes a natural hand-stamped tilt angle in `[-10.5, -4.5] U [4.5, 10.5]` degrees,
         * avoiding dead-flat 0-degree alignment so the stamp always looks organic.
         */
        fun computeRandomStampRotation(random: Random = Random.Default): Float {
            val magnitude = STAMP_MIN_ANGLE_DEG +
                random.nextFloat() * (STAMP_MAX_ANGLE_DEG - STAMP_MIN_ANGLE_DEG)
            return if (random.nextBoolean()) magnitude else -magnitude
        }
    }

    /**
     * Binds safe click listeners to accordion headers with tapjack and obscuration protection,
     * and initializes any active `[ NEW! ]` feature stamps with a randomized tilt.
     */
    fun setup() {
        for ((type, views) in sections) {
            val seenEpoch = preferencesManager.getAccordionSeenEpoch(type.seenEpochKey)
            if (shouldShowNewBadge(seenEpoch, type.featureEpoch)) {
                views.newBadge.visibility = View.VISIBLE
                views.newBadge.alpha = 1f
                views.newBadge.scaleX = 1f
                views.newBadge.scaleY = 1f
                views.newBadge.rotation = computeRandomStampRotation()
            } else {
                views.newBadge.visibility = View.GONE
            }

            views.header.setSafeOnClickListener(debounceMs = DEBOUNCE_ACCORDION_MS) {
                toggle(type, animate = true)
            }
        }
    }

    /**
     * Toggles the expanded state of the specified accordion section and dismisses its `[ NEW! ]` badge.
     */
    fun toggle(type: AccordionType, animate: Boolean = true) {
        val currentState = expandedStates[type] ?: false
        dismissNewBadge(type, animate = animate)
        setExpanded(type, !currentState, animate)
    }

    /**
     * Dismisses the `[ NEW! ]` stamp for the specified accordion section if currently active,
     * persisting the seen epoch to [PreferencesManager] so it stays cleared across launches.
     */
    fun dismissNewBadge(type: AccordionType, animate: Boolean = true) {
        if (type.featureEpoch <= 0) return
        val seenEpoch = preferencesManager.getAccordionSeenEpoch(type.seenEpochKey)
        if (seenEpoch < type.featureEpoch) {
            preferencesManager.setAccordionSeenEpoch(type.seenEpochKey, type.featureEpoch)
        }

        val badge = sections[type]?.newBadge ?: return
        if (badge.visibility == View.VISIBLE) {
            if (animate) {
                badge.animate()
                    .alpha(0f)
                    .scaleX(0.7f)
                    .scaleY(0.7f)
                    .setDuration(ANIMATION_DURATION_MS)
                    .withEndAction {
                        badge.visibility = View.GONE
                    }
                    .start()
            } else {
                badge.visibility = View.GONE
            }
        }
    }

    /**
     * Programmatically sets the expanded state of an accordion section.
     */
    fun setExpanded(type: AccordionType, expanded: Boolean, animate: Boolean = false) {
        expandedStates[type] = expanded
        val views = sections[type] ?: return

        if (animate) {
            TransitionManager.beginDelayedTransition(rootView)
            views.content.visibility = if (expanded) View.VISIBLE else View.GONE
            views.chevron.animate()
                .rotation(if (expanded) 180f else 0f)
                .setDuration(ANIMATION_DURATION_MS)
                .start()
        } else {
            views.content.visibility = if (expanded) View.VISIBLE else View.GONE
            views.chevron.rotation = if (expanded) 180f else 0f
        }
    }

    /**
     * Queries whether an accordion section is currently expanded.
     */
    fun isExpanded(type: AccordionType): Boolean {
        return expandedStates[type] ?: false
    }

    fun expand(type: AccordionType, animate: Boolean = true) = setExpanded(type, true, animate)

    fun collapse(type: AccordionType, animate: Boolean = true) = setExpanded(type, false, animate)

    /**
     * Saves the current expansion states into outState for configuration changes / process recreation.
     */
    fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_ACCORDION_GLOBAL, isExpanded(AccordionType.GLOBAL_SETTINGS))
        outState.putBoolean(KEY_ACCORDION_UNDEAD_AFFECTS, isExpanded(AccordionType.UNDEAD_AFFECTS))
        outState.putBoolean(KEY_ACCORDION_PARALLAX, isExpanded(AccordionType.PARALLAX))
    }

    /**
     * Restores expansion states from savedInstanceState if present, or initializes from defaults.
     */
    fun restoreOrInitialize(
        savedInstanceState: Bundle?,
        defaultGlobal: Boolean,
        defaultUndeadAffects: Boolean = false,
        defaultParallax: Boolean
    ) {
        val globalSaved = if (savedInstanceState?.containsKey(KEY_ACCORDION_GLOBAL) == true) savedInstanceState.getBoolean(KEY_ACCORDION_GLOBAL) else null
        val undeadAffectsSaved = if (savedInstanceState?.containsKey(KEY_ACCORDION_UNDEAD_AFFECTS) == true) savedInstanceState.getBoolean(KEY_ACCORDION_UNDEAD_AFFECTS) else null
        val parallaxSaved = if (savedInstanceState?.containsKey(KEY_ACCORDION_PARALLAX) == true) savedInstanceState.getBoolean(KEY_ACCORDION_PARALLAX) else null

        val globalExpanded = resolveExpandedState(globalSaved, defaultGlobal)
        val undeadAffectsExpanded = resolveExpandedState(undeadAffectsSaved, defaultUndeadAffects)
        val parallaxExpanded = resolveExpandedState(parallaxSaved, defaultParallax)

        setExpanded(AccordionType.GLOBAL_SETTINGS, globalExpanded, animate = false)
        setExpanded(AccordionType.UNDEAD_AFFECTS, undeadAffectsExpanded, animate = false)
        setExpanded(AccordionType.PARALLAX, parallaxExpanded, animate = false)
    }
}
