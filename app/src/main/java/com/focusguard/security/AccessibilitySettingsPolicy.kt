package com.focusguard.security

import java.text.Normalizer
import java.util.Locale

/**
 * Pure classifier used by the accessibility service to keep interception scoped
 * to Accessibility settings instead of generic app details or permissions pages.
 */
object AccessibilitySettingsPolicy {

    data class TextSignals(
        val accessibility: Boolean,
        val installedAccessibilityApps: Boolean,
        val accessibilityDisclosure: Boolean
    )

    // Must be initialized before the pre-normalized dictionaries below.
    private val COMBINING_MARKS_REGEX = "\\p{M}+".toRegex()

    /**
     * Telas que listam recursos e serviços de acessibilidade.
     *
     * A política de autoproteção não fecha toda a área de Acessibilidade: ela usa
     * estes marcadores junto com o rótulo de apps/serviços instalados para fechar
     * especificamente o caminho capaz de desligar o serviço do FocusGuard.
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
     * remain protected only when the service in question is FocusGuard's own.
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
     * possa fechar exatamente a rota mostrada por One UI e variantes de OEM.
     */
    internal val installedAccessibilityAppsTerms = listOf(
        "Aplicativos instalados",
        "Apps instalados",
        "Serviços instalados",
        "Aplicativos de acessibilidade instalados",
        "Apps de acessibilidade instalados",
        "Serviços de acessibilidade instalados",
        "Installed apps",
        "Installed services",
        "Installed accessibility apps",
        "Installed accessibility services",
        "Aplicaciones instaladas",
        "Servicios instalados",
        "Aplicaciones de accesibilidad instaladas",
        "Servicios de accesibilidad instalados"
    )

    /**
     * Short labels used only to locate the Installed apps row when One UI
     * exposes the text on a child node while the clickable parent has no text.
     * Full classification still uses [installedAccessibilityAppsTerms].
     */
    internal val installedAccessibilityAppsNodeSearchTerms = listOf(
        "Aplicativos instalados",
        "Apps instalados",
        "Serviços instalados",
        "Installed apps",
        "Installed services",
        "Aplicaciones instaladas",
        "Servicios instalados"
    )

    /**
     * Texto da divulgação de privacidade exibida pelo Android enquanto um
     * serviço de acessibilidade observa a tela. A notificação continua visível;
     * este marcador serve apenas para distinguir o toque que abre diretamente
     * o interruptor do FocusGuard de qualquer outra notificação do aplicativo.
     */
    internal val accessibilityDisclosureSearchTerms = listOf(
        "pode ver o que você está fazendo",
        "pode ver o que voce esta fazendo",
        "pode ver o que vc está fazendo",
        "pode ver o que vc esta fazendo",
        "toque para revisar",
        "can see what you're doing",
        "can see what you are doing",
        "tap to review"
    )

    /**
     * Cheap child-node locators used by the service before it performs broader
     * tree scans. `HardBlock`/`Hard Block` are locator-only entries: they let a
     * textless One UI row expose the current app label before App Info opens,
     * but they are deliberately NOT accessibility-disclosure classifiers.
     */
    internal val accessibilityDisclosureNodeSearchTerms = listOf(
        "HardBlock",
        "Hard Block",
        "pode ver",
        "toque para revisar",
        "can see",
        "tap to review"
    )

    private val normalizedSearchTerms = searchTerms.map(::normalize)
    private val normalizedInstalledAccessibilityAppsTerms =
        installedAccessibilityAppsTerms.map(::normalize)
    private val normalizedAccessibilityDisclosureSearchTerms =
        accessibilityDisclosureSearchTerms.map(::normalize)

    fun classTargetsAccessibility(className: String): Boolean {
        return accessibilityClassMarkers.any { marker ->
            className.contains(marker, ignoreCase = true)
        }
    }

    /** True only for the per-service screen that carries an on/off switch. */
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

    fun classifyText(values: Iterable<CharSequence?>): TextSignals {
        val normalizedValues = normalizeValues(values)
        return TextSignals(
            accessibility = valuesContainAnyNormalized(normalizedValues, normalizedSearchTerms),
            installedAccessibilityApps = valuesContainAnyNormalized(
                normalizedValues,
                normalizedInstalledAccessibilityAppsTerms
            ),
            accessibilityDisclosure = valuesContainAnyNormalized(
                normalizedValues,
                normalizedAccessibilityDisclosureSearchTerms
            )
        )
    }

    fun textTargetsAccessibility(values: Iterable<CharSequence?>): Boolean =
        valuesContainAnyNormalized(normalizeValues(values), normalizedSearchTerms)

    fun textTargetsInstalledAccessibilityApps(
        values: Iterable<CharSequence?>
    ): Boolean = valuesContainAnyNormalized(
        normalizeValues(values),
        normalizedInstalledAccessibilityAppsTerms
    )

    fun textTargetsAccessibilityDisclosure(
        values: Iterable<CharSequence?>
    ): Boolean = valuesContainAnyNormalized(
        normalizeValues(values),
        normalizedAccessibilityDisclosureSearchTerms
    )

    private fun normalizeValues(values: Iterable<CharSequence?>): List<String> =
        values.mapNotNull { value ->
            normalize(value?.toString().orEmpty()).takeIf(String::isNotBlank)
        }

    private fun valuesContainAnyNormalized(
        normalizedValues: Iterable<String>,
        normalizedTerms: Iterable<String>
    ): Boolean = normalizedValues.any { normalized ->
        normalizedTerms.any(normalized::contains)
    }

    private fun normalize(value: String): String {
        if (value.isBlank()) return ""
        val lowercase = value.lowercase(Locale.ROOT)
        if (lowercase.all { character -> character.code < 128 }) return lowercase
        return Normalizer.normalize(lowercase, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS_REGEX, "")
    }

}
