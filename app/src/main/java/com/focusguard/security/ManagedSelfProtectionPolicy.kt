package com.focusguard.security

import java.text.Normalizer
import java.util.Locale

/**
 * Pure classifier for system screens that can weaken a fully managed FocusGuard device.
 *
 * The accessibility service only uses this policy while FocusGuard is the real Device Owner,
 * a block is being enforced and the authenticated maintenance window is closed.
 */
object ManagedSelfProtectionPolicy {

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
        "AppInfoActivity"
    )

    private val uninstallClassMarkers = setOf(
        "UninstallerActivity",
        "UninstallActivity",
        "UninstallAlertDialogActivity",
        "UninstallAppProgress"
    )

    internal val deviceAdminSearchTerms = listOf(
        "Apps do administrador do aparelho",
        "Administrador do dispositivo",
        "Administradores do dispositivo",
        "Device admin apps",
        "Device administrator",
        "Device administrators",
        "Aplicaciones de administración del dispositivo",
        "Administradores del dispositivo"
    )

    internal val focusGuardSearchTerms = listOf(
        "FocusGuard",
        "Focus Guard",
        "com.focusguard.v2"
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

    fun classTargetsDeviceAdmin(className: String): Boolean =
        containsAny(className, deviceAdminClassMarkers)

    fun classTargetsAppDetails(className: String): Boolean =
        containsAny(className, appDetailsClassMarkers)

    fun classTargetsUninstall(className: String): Boolean =
        containsAny(className, uninstallClassMarkers)

    fun textTargetsDeviceAdmin(values: Iterable<CharSequence?>): Boolean =
        valuesContainAny(values, deviceAdminSearchTerms)

    fun textTargetsFocusGuard(values: Iterable<CharSequence?>): Boolean =
        valuesContainAny(values, focusGuardSearchTerms)

    fun textTargetsDestructiveControl(values: Iterable<CharSequence?>): Boolean =
        valuesContainAny(values, destructiveControlSearchTerms)

    private fun containsAny(value: String, markers: Iterable<String>): Boolean =
        markers.any { marker -> value.contains(marker, ignoreCase = true) }

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

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .lowercase(Locale.ROOT)
}
