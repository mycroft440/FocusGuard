package com.focusguard.security

import java.text.Normalizer
import java.util.Locale

/**
 * Pure classifier used by the accessibility service to keep interception scoped
 * to Accessibility settings instead of generic app details or permissions pages.
 */
object AccessibilitySettingsPolicy {

    /**
     * Screens that only *list* accessibility features. Browsing these is normal
     * device use — font size, TalkBack, colour correction — and must stay
     * reachable even while a block is running.
     */
    private val accessibilityListClassMarkers = setOf(
        "AccessibilitySettings",
        "InstalledAccessibilityService"
    )

    /**
     * Screens that expose a single accessibility service's on/off switch. These
     * are the only accessibility screens worth intercepting, and only when the
     * service in question is FocusGuard's own.
     */
    private val accessibilityServiceToggleClassMarkers = setOf(
        "AccessibilityServiceSettings",
        "AccessibilityDetailsSettings",
        "AccessibilityShortcutPreferenceFragment",
        "ToggleAccessibilityServicePreferenceFragment"
    )

    private val accessibilityClassMarkers =
        accessibilityListClassMarkers + accessibilityServiceToggleClassMarkers

    internal val searchTerms = listOf(
        "Acessibilidade",
        "Accessibility",
        "Accesibilidad",
        "Accessibilité",
        "Barrierefreiheit",
        "Доступность",
        "Доступност",
        "辅助功能",
        "إمكانية الوصول",
        "सुलभता"
    )

    fun classTargetsAccessibility(className: String): Boolean {
        return accessibilityClassMarkers.any { marker ->
            className.contains(marker, ignoreCase = true)
        }
    }

    /**
     * True only for the per-service screen that carries an on/off switch.
     *
     * Interception is scoped to this screen — combined with evidence that the
     * service shown is FocusGuard's — so the rest of the accessibility section
     * stays usable during a block. Font size, TalkBack and colour correction are
     * normal device use, and Google Play treats blocking the whole section as
     * misuse of the Accessibility API.
     */
    fun classTargetsAccessibilityServiceToggle(className: String): Boolean {
        return accessibilityServiceToggleClassMarkers.any { marker ->
            className.contains(marker, ignoreCase = true)
        }
    }

    /** True for list screens that merely enumerate accessibility features. */
    fun classTargetsAccessibilityList(className: String): Boolean {
        return accessibilityListClassMarkers.any { marker ->
            className.contains(marker, ignoreCase = true)
        }
    }

    fun textTargetsAccessibility(values: Iterable<CharSequence?>): Boolean {
        return values.any { value ->
            val normalized = normalize(value?.toString().orEmpty())
            normalized.isNotBlank() && searchTerms.any { term ->
                normalized.contains(normalize(term))
            }
        }
    }

    private fun normalize(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .lowercase(Locale.ROOT)
    }
}
