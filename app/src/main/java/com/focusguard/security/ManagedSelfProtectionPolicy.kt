package com.focusguard.security

import java.text.Normalizer
import java.util.Locale

/**
 * Pure classifier for system screens that can weaken FocusGuard self-protection.
 *
 * The accessibility service uses it only while a user-created protection is active and the
 * authenticated maintenance window is closed. Decisions still require FocusGuard identity;
 * a screen class alone must never affect another package.
 */
object ManagedSelfProtectionPolicy {

    data class TextSignals(
        val deviceAdmin: Boolean,
        val appInfoGateway: Boolean,
        val focusGuard: Boolean,
        val destructiveControl: Boolean,
        val essentialSpecialAccess: Boolean
    )

    // These helpers must exist before the pre-normalized dictionaries are built.
    private val COMBINING_MARKS_REGEX = "\\p{M}+".toRegex()
    private val NON_LETTER_REGEX = "[^a-z0-9]+".toRegex()

    private val deviceAdminClassMarkers = setOf(
        "DeviceAdminSettings",
        "DeviceAdminAdd",
        "DeviceAdministratorSettings",
        "DeviceAdministratorsSettings"
    )

    private val appDetailsClassMarkers = setOf(
        // Somente superfícies de detalhes de um app. A lista geral de aplicativos
        // precisa continuar disponível para administrar qualquer outro pacote.
        "InstalledAppDetails",
        "AppInfoDashboardFragment",
        "AppInfoDashboardActivity",
        "AppInfoActivity",
        // Android 14+ pode encaminhar Informações do app para a arquitetura SPA.
        // A política ainda exige a identidade do FocusGuard, portanto uma SpaActivity
        // de qualquer outro aplicativo continua livre.
        "SpaActivity",
        "SpaAppBridgeActivity"
    )

    private val uninstallClassMarkers = setOf(
        "UninstallerActivity",
        "UninstallActivity",
        "UninstallAlertDialogActivity",
        "UninstallAppProgress"
    )

    private val essentialSpecialAccessClassMarkers = setOf(
        "UsageAccessSettings",
        "UsageAccessDetails",
        "HighPowerApplicationsActivity",
        "HighPowerDetail",
        "BatteryOptimizationSettings"
    )

    internal val deviceAdminSearchTerms = listOf(
        // One UI pt-BR: keep the observed gateway first so the rare broad root
        // fallback short-circuits on its first query.
        "Apps administradores do sistema",
        "Apps do administrador do aparelho",
        "Aplicativos administradores do sistema",
        "System admin apps",
        "Administrador do dispositivo",
        "Administradores do dispositivo",
        "Device admin apps",
        "Device administrator",
        "Device administrators",
        "Aplicaciones de administración del dispositivo",
        "Administradores del dispositivo",
        // Accessibility events also expose viewIdResourceName. OEM Settings
        // commonly uses these stable fragments even when the clickable row itself
        // has no visible text, letting us classify without a subtree expansion.
        "device_admin",
        "deviceadmin"
    )

    /**
     * Locator-only prefixes for OEM rows. The service still validates the full
     * returned node text with [textTargetsDeviceAdmin] before blocking.
     */
    internal val deviceAdminNodeSearchTerms = listOf(
        // Current One UI pt-BR wording first; these are locators only and the
        // returned node still passes textTargetsDeviceAdmin before any block.
        "Apps administradores",
        "Apps do administr",
        "Device admin"
    )

    /**
     * Prefixos de "administrador" que aparecem abreviados nas telas.
     *
     * A One UI corta o rótulo para caber — "Apps do administr. do aparelho" — e
     * nenhum termo escrito por extenso casa com isso. Em vez de tentar listar
     * cada corte de cada fabricante, o casamento é feito por par: uma palavra
     * que comece com um destes prefixos, mais uma palavra de aparelho na mesma
     * frase. Sozinho, "admin" apareceria em contexto inocente demais.
     */
    private val deviceAdminWordPrefixes = listOf(
        "admin",
        "administr",
        "administra"
    )

    /**
     * Prefixos, e não palavras inteiras: a barra de título também corta o outro
     * lado do rótulo — "Apps do administr. do aparel…" — então exigir "aparelho"
     * completo deixaria passar justamente a tela que se quer barrar.
     */
    private val deviceWordPrefixes = listOf(
        "aparel",
        "dispositiv",
        "device",
        "telefon",
        "phone",
        "celular"
    )

    internal val appInfoGatewaySearchTerms = listOf(
        "Informações do aplicativo",
        "Informações do app",
        "App info",
        "Application info",
        "Información de la aplicación",
        "Información de app"
    )

    internal val focusGuardSearchTerms = listOf(
        // Current installed label. Keep it first: One UI's App Info screen exposes
        // this label immediately, so the accessibility service normally resolves
        // our identity with a single tree lookup instead of trying legacy labels.
        "HardBlock",
        "Hard Block",
        "FocusGuard",
        "Focus Guard",
        // Stable package identifiers remain fallbacks when an OEM exposes the
        // package name instead of the user-visible label.
        "com.focusguard.v2",
        "com.focusguard.v2.debug",
        "com.focusguard.v2.ci"
    )

    internal val destructiveControlSearchTerms = listOf(
        "Desinstalar",
        "Desativar",
        "Forçar parada",
        "Limpar dados",
        "Uninstall",
        "Disable",
        "Force stop",
        "Clear data",
        "Desinstalar aplicación",
        "Inhabilitar",
        "Forzar detención",
        "Borrar datos"
    )

    internal val essentialSpecialAccessSearchTerms = listOf(
        "Acesso ao uso",
        "Acesso de uso",
        "Usage access",
        "Otimização da bateria",
        "Uso irrestrito da bateria",
        "Sem restrições de bateria",
        "Battery optimization",
        "Unrestricted battery"
    )

    private val normalizedDeviceAdminSearchTerms = deviceAdminSearchTerms.map(::normalize)
    private val normalizedAppInfoGatewaySearchTerms =
        appInfoGatewaySearchTerms.map(::normalize)
    private val normalizedFocusGuardSearchTerms = focusGuardSearchTerms.map(::normalize)
    private val normalizedFocusGuardLabels = setOf(
        normalize("HardBlock"),
        normalize("Hard Block"),
        normalize("FocusGuard"),
        normalize("Focus Guard")
    )
    private val normalizedFocusGuardPackageIds = setOf(
        normalize("com.focusguard.v2"),
        normalize("com.focusguard.v2.debug"),
        normalize("com.focusguard.v2.ci")
    )
    private val normalizedDestructiveControlSearchTerms =
        destructiveControlSearchTerms.map(::normalize)
    private val normalizedEssentialSpecialAccessSearchTerms =
        essentialSpecialAccessSearchTerms.map(::normalize)

    fun classTargetsDeviceAdmin(className: String): Boolean =
        containsAny(className, deviceAdminClassMarkers)

    fun classTargetsAppDetails(className: String): Boolean =
        containsAny(className, appDetailsClassMarkers)

    fun classTargetsUninstall(className: String): Boolean =
        containsAny(className, uninstallClassMarkers)

    fun classTargetsEssentialSpecialAccess(className: String): Boolean =
        containsAny(className, essentialSpecialAccessClassMarkers)

    fun classifyText(values: Iterable<CharSequence?>): TextSignals {
        val normalizedValues = normalizeValues(values)
        return TextSignals(
            deviceAdmin = matchesDeviceAdmin(normalizedValues),
            appInfoGateway = valuesContainAnyNormalized(
                normalizedValues,
                normalizedAppInfoGatewaySearchTerms
            ),
            focusGuard = valuesContainAnyNormalized(
                normalizedValues,
                normalizedFocusGuardSearchTerms
            ) && matchesFocusGuardIdentity(normalizedValues),
            destructiveControl = valuesContainAnyNormalized(
                normalizedValues,
                normalizedDestructiveControlSearchTerms
            ),
            essentialSpecialAccess = valuesContainAnyNormalized(
                normalizedValues,
                normalizedEssentialSpecialAccessSearchTerms
            )
        )
    }

    fun textTargetsDeviceAdmin(values: Iterable<CharSequence?>): Boolean =
        matchesDeviceAdmin(normalizeValues(values))

    fun textTargetsAppInfoGateway(values: Iterable<CharSequence?>): Boolean =
        valuesContainAnyNormalized(
            normalizeValues(values),
            normalizedAppInfoGatewaySearchTerms
        )

    fun textTargetsFocusGuard(values: Iterable<CharSequence?>): Boolean {
        val normalizedValues = normalizeValues(values)
        return valuesContainAnyNormalized(
            normalizedValues,
            normalizedFocusGuardSearchTerms
        ) && matchesFocusGuardIdentity(normalizedValues)
    }

    fun textTargetsDestructiveControl(values: Iterable<CharSequence?>): Boolean =
        valuesContainAnyNormalized(
            normalizeValues(values),
            normalizedDestructiveControlSearchTerms
        )

    fun textTargetsEssentialSpecialAccess(values: Iterable<CharSequence?>): Boolean =
        valuesContainAnyNormalized(
            normalizeValues(values),
            normalizedEssentialSpecialAccessSearchTerms
        )

    private fun matchesDeviceAdmin(normalizedValues: List<String>): Boolean {
        if (valuesContainAnyNormalized(normalizedValues, normalizedDeviceAdminSearchTerms)) {
            return true
        }
        return normalizedValues.any(::looksLikeDeviceAdminLabel)
    }

    private fun looksLikeDeviceAdminLabel(value: String): Boolean {
        val words = value.split(' ').filter(String::isNotEmpty)
        val hasAdmin = words.any { word ->
            deviceAdminWordPrefixes.any(word::startsWith)
        }
        if (!hasAdmin) return false
        return words.any { word ->
            deviceWordPrefixes.any(word::startsWith)
        }
    }

    private fun matchesFocusGuardIdentity(normalizedValues: List<String>): Boolean =
        normalizedValues.any { normalizedValue ->
            normalizedValue in normalizedFocusGuardLabels ||
                normalizedValue in normalizedFocusGuardPackageIds
        }

    private fun normalizeValues(values: Iterable<CharSequence?>): List<String> =
        values.mapNotNull { value ->
            value?.toString()?.takeIf(String::isNotBlank)?.let(::normalize)
        }

    private fun valuesContainAnyNormalized(
        normalizedValues: Iterable<String>,
        normalizedTerms: Iterable<String>
    ): Boolean = normalizedValues.any { value ->
        normalizedTerms.any { term -> value.contains(term) }
    }

    private fun containsAny(value: String, terms: Iterable<String>): Boolean =
        terms.any { term -> value.contains(term, ignoreCase = true) }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS_REGEX, "")
            .lowercase(Locale.ROOT)
            .replace(NON_LETTER_REGEX, " ")
            .trim()
            .replace(Regex("\\s+"), " ")
}
