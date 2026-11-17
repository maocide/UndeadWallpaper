package org.maocide.undeadwallpaper.model

/**
 * Types of kinetic somatic reflexes triggered on launcher touch.
 */
enum class KineticEffectType(val id: String) {
    NONE("NONE"),
    SEISMIC_JOLT("JOLT"),
    VERTICAL_HOP("HOP"),
    ELASTIC_BOUNCE("BOUNCE"),
    DIRECTIONAL_RECOIL("RECOIL"),
    CARDIAC_PULSE("PULSE"),
    PHOTONIC_FLARE("FLARE"),
    LIGHTNING_STRIKE("LIGHTNING");

    companion object {
        fun fromId(id: String?): KineticEffectType {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: NONE
        }
    }
}
