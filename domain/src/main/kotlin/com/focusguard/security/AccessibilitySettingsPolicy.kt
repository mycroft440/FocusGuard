package com.focusguard.security

import java.text.Normalizer
import java.util.Locale

/**
 * Pure classifier used by the accessibility service to keep interception scoped
 * to Accessibility settings instead of generic app details or permissions pages.
 */
object AccessibilitySettingsPolicy {

    /**
     * Telas que apenas listam recursos e serviços de acessibilidade.
     *
     * Elas continuam classificadas para diagnóstico, mas a política de
     * autoproteção não as fecha: somente o item ou o interruptor identificado
     * como FocusGuard pode ser interceptado.
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

    val searchTerms = listOf(
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
    val installedAccessibilityAppsTerms = listOf(
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
    val accessibilityDisclosureSearchTerms = listOf(
        "pode ver o que você está fazendo",
        "pode ver o que voce esta fazendo",
        "pode ver o que vc está fazendo",
        "pode ver o que vc esta fazendo",
        "toque para revisar",
        "can see what you're doing",
        "can see what you are doing",
        "tap to review"
    )

    /** Short partials used only to locate the notification body's child node. */
    val accessibilityDisclosureNodeSearchTerms = listOf(
        "pode ver",
        "toque para revisar",
        "can see",
        "tap to review"
    )

    fun classTargetsAccessibility(className: String): Boolean {
        return accessibilityClassMarkers.any { marker ->
            className.contains(marker, ignoreCase = true)
        }
    }

    /**
     * True only for the per-service screen that carries an on/off switch.
     *
     * O classificador continua separado da tela de lista para testes e para
     * registrar qual superfície foi detectada. A política de sessão bloqueia
     * ambas durante uma proteção ativa.
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

    fun textTargetsAccessibilityDisclosure(
        values: Iterable<CharSequence?>
    ): Boolean {
        return valuesContainAny(values, accessibilityDisclosureSearchTerms)
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

