package com.focusguard.security

import java.text.Normalizer
import java.util.Locale

/**
 * Pure classifier used by the accessibility service to keep interception scoped
 * to Accessibility settings instead of generic app details or permissions pages.
 */
object AccessibilitySettingsPolicy {

    /**
     * Telas que listam recursos e serviços de acessibilidade. Durante um
     * bloqueio elas também precisam ser fechadas, pois a One UI expõe nelas
     * "Aplicativos instalados", caminho direto para desligar o FocusGuard.
     */
    private val accessibilityListClassMarkers = setOf(
        "AccessibilitySettings",
        "AccessibilityActivity",
        "AccessibilityDashboard",
        "AccessibilityHomepage",
        "AccessibilityHome",
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

    /**
     * Rótulos do atalho que lista serviços de acessibilidade instalados.
     * É um sinal separado de "apps instalados" genérico para que a política
     * possa fechar exatamente a rota mostrada pela One UI.
     */
    internal val installedAccessibilityAppsTerms = listOf(
        "Aplicativos instalados",
        "Apps instalados",
        "Serviços instalados",
        "Installed apps",
        "Installed services",
        "Aplicaciones instaladas",
        "Servicios instalados"
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

    /** True para telas que enumeram recursos ou serviços de acessibilidade. */
    fun classTargetsAccessibilityList(className: String): Boolean {
        return accessibilityListClassMarkers.any { marker ->
            className.contains(marker, ignoreCase = true)
        }
    }

    fun textTargetsAccessibility(values: Iterable<CharSequence?>): Boolean {
        return valuesContainAny(values, searchTerms)
    }

    fun textTargetsInstalledAccessibilityApps(
        values: Iterable<CharSequence?>
    ): Boolean {
        return valuesContainAny(values, installedAccessibilityAppsTerms)
    }

    private fun valuesContainAny(
        values: Iterable<CharSequence?>,
        terms: Iterable<String>
    ): Boolean {
        val normalizedTerms = terms.map(::normalize)
        return values.any { value ->
            val normalized = normalize(value?.toString().orEmpty())
            normalized.isNotBlank() && normalizedTerms.any(normalized::contains)
        }
    }

    private fun normalize(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .lowercase(Locale.ROOT)
    }
}
