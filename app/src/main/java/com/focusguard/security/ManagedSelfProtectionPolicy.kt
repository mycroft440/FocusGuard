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
    private val NON_LETTER_REGEX = "[^a-z]+".toRegex()

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
        "Apps do administrador do aparelho",
        "Apps administradores do sistema",
        "Aplicativos administradores do sistema",
        "System admin apps",
        "Administrador do dispositivo",
        "Administradores do dispositivo",
        "Device admin apps",
        "Device administrator",
        "Device administrators",
        "Aplicaciones de administración del dispositivo",
        "Administradores del dispositivo"
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
        "com.focusguard.v2.debug"
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
            ),
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
        valuesContainAnyNormalized(normalizeValues(values), normalizedAppInfoGatewaySearchTerms)

    fun textTargetsFocusGuard(values: Iterable<CharSequence?>): Boolean =
        valuesContainAnyNormalized(normalizeValues(values), normalizedFocusGuardSearchTerms)

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

    private fun matchesDeviceAdmin(normalizedValues: List<String>): Boolean =
        valuesContainAnyNormalized(normalizedValues, normalizedDeviceAdminSearchTerms) ||
            normalizedValues.any(::mentionsAbbreviatedDeviceAdminNormalized)

    /**
     * "administr. do aparelho" e companhia: palavra de administração abreviada
     * mais palavra de aparelho, no mesmo texto.
     */
    private fun mentionsAbbreviatedDeviceAdminNormalized(normalized: String): Boolean {
        if (normalized.isBlank()) return false
        if (deviceWordPrefixes.none(normalized::contains)) return false
        return normalized
            .split(NON_LETTER_REGEX)
            .any { word -> deviceAdminWordPrefixes.any(word::startsWith) }
    }

    private fun containsAny(value: String, markers: Iterable<String>): Boolean =
        markers.any { marker -> value.contains(marker, ignoreCase = true) }

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
