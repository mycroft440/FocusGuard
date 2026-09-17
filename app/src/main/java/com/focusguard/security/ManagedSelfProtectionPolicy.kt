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

    private val COMBINING_MARKS_REGEX = "\\p{M}+".toRegex()
    private val NON_LETTER_REGEX = "[^a-z0-9]+".toRegex()

    private val deviceAdminClassMarkers = setOf(
        "DeviceAdminSettings",
        "DeviceAdminAdd",
        "DeviceAdministratorSettings",
        "DeviceAdministratorsSettings"
    )

    private val appDetailsClassMarkers = setOf(
        "InstalledAppDetails",
        "AppInfoDashboardFragment",
        "AppInfoDashboardActivity",
        "AppInfoActivity",
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
        "BatteryOptimizationSettings",
        "AlarmsAndReminders"
    )

    internal val deviceAdminSearchTerms = listOf(
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
        "device_admin",
        "deviceadmin"
    )

    /**
     * A generic Device Admin row must never be enough to trigger protection.
     * Keeping this list empty also disables the service's old fast-path that
     * searched the clicked subtree only for a Device Admin label and then armed
     * the global transition guard without knowing which administrator was targeted.
     */
    internal val deviceAdminNodeSearchTerms: List<String> = emptyList()

    private val deviceAdminWordPrefixes = listOf(
        "admin",
        "administr",
        "administra"
    )

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
        "HardBlock",
        "Hard Block",
        "FocusGuard",
        "Focus Guard",
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
        "Unrestricted battery",
        "Alarmes e lembretes",
        "Alarms & reminders",
        "Alarms and reminders",
        "Alarmas y recordatorios"
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

    /**
     * Returns true only when a Device Admin class is being observed after the
     * current interaction has already been explicitly tied to FocusGuard.
     * Class name by itself is context, not target identity.
     */
    fun classTargetsDeviceAdmin(className: String): Boolean =
        classLooksLikeDeviceAdminSurface(className) &&
            SelfProtectionTargetScope.isFocusGuardTargetConfirmed()

    fun classLooksLikeDeviceAdminSurface(className: String): Boolean =
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
