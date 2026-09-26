package org.maocide.undeadwallpaper.ui

import android.os.Bundle
import android.transition.TransitionManager
import android.view.View
import android.view.ViewGroup
import org.maocide.undeadwallpaper.databinding.FragmentSettingsBinding
import org.maocide.undeadwallpaper.utils.setSafeOnClickListener

/**
 * Identifies the collapsible accordion cards available in SettingsFragment.
 */
enum class AccordionType {
    GLOBAL_SETTINGS,
    TOUCH_CONTROLS,
    PARALLAX
}

/**
 * Controller managing the expandable/collapsible accordion cards in SettingsFragment.
 * Encapsulates SafeInput tapjacking defenses, smooth TransitionManager animations,
 * chevron rotations, programmatic open/close controls, and configuration-change state restoration.
 */
class AccordionController(
    private val rootView: ViewGroup,
    private val binding: FragmentSettingsBinding
) {
    private val expandedStates = mutableMapOf(
        AccordionType.GLOBAL_SETTINGS to false,
        AccordionType.TOUCH_CONTROLS to false,
        AccordionType.PARALLAX to false
    )

    private val sections by lazy {
        mapOf(
            AccordionType.GLOBAL_SETTINGS to AccordionViews(
                header = binding.headerGlobalSettingsChevron,
                content = binding.contentGlobalSettings,
                chevron = binding.iconGlobalSettingsChevron
            ),
            AccordionType.TOUCH_CONTROLS to AccordionViews(
                header = binding.headerTouchControls,
                content = binding.contentTouchControls,
                chevron = binding.iconTouchChevron
            ),
            AccordionType.PARALLAX to AccordionViews(
                header = binding.headerParallax,
                content = binding.contentParallax,
                chevron = binding.iconParallaxChevron
            )
        )
    }

    private data class AccordionViews(
        val header: View,
        val content: View,
        val chevron: View
    )

    companion object {
        const val KEY_ACCORDION_GLOBAL = "accordion_global_settings_expanded"
        const val KEY_ACCORDION_TOUCH = "accordion_touch_controls_expanded"
        const val KEY_ACCORDION_PARALLAX = "accordion_parallax_expanded"
        const val DEBOUNCE_ACCORDION_MS = 200L
        const val ANIMATION_DURATION_MS = 200L

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
    }

    /**
     * Binds safe click listeners to accordion headers with tapjack and obscuration protection.
     */
    fun setup() {
        for ((type, views) in sections) {
            views.header.setSafeOnClickListener(debounceMs = DEBOUNCE_ACCORDION_MS) {
                toggle(type, animate = true)
            }
        }
    }

    /**
     * Toggles the expanded state of the specified accordion section.
     */
    fun toggle(type: AccordionType, animate: Boolean = true) {
        val currentState = expandedStates[type] ?: false
        setExpanded(type, !currentState, animate)
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
        outState.putBoolean(KEY_ACCORDION_TOUCH, isExpanded(AccordionType.TOUCH_CONTROLS))
        outState.putBoolean(KEY_ACCORDION_PARALLAX, isExpanded(AccordionType.PARALLAX))
    }

    /**
     * Restores expansion states from savedInstanceState if present, or initializes from defaults.
     */
    fun restoreOrInitialize(
        savedInstanceState: Bundle?,
        defaultGlobal: Boolean,
        defaultTouch: Boolean,
        defaultParallax: Boolean
    ) {
        val globalSaved = if (savedInstanceState?.containsKey(KEY_ACCORDION_GLOBAL) == true) savedInstanceState.getBoolean(KEY_ACCORDION_GLOBAL) else null
        val touchSaved = if (savedInstanceState?.containsKey(KEY_ACCORDION_TOUCH) == true) savedInstanceState.getBoolean(KEY_ACCORDION_TOUCH) else null
        val parallaxSaved = if (savedInstanceState?.containsKey(KEY_ACCORDION_PARALLAX) == true) savedInstanceState.getBoolean(KEY_ACCORDION_PARALLAX) else null

        val globalExpanded = resolveExpandedState(globalSaved, defaultGlobal)
        val touchExpanded = resolveExpandedState(touchSaved, defaultTouch)
        val parallaxExpanded = resolveExpandedState(parallaxSaved, defaultParallax)

        setExpanded(AccordionType.GLOBAL_SETTINGS, globalExpanded, animate = false)
        setExpanded(AccordionType.TOUCH_CONTROLS, touchExpanded, animate = false)
        setExpanded(AccordionType.PARALLAX, parallaxExpanded, animate = false)
    }
}
