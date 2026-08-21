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
        "AppInfoActivity"
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

    val deviceAdminSearchTerms = listOf(
        "Apps do administrador do aparelho",
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

    val focusGuardSearchTerms = listOf(
        "FocusGuard",
        "Focus Guard",
        "com.focusguard.v2",
        "com.focusguard.v2.debug"
    )

    val destructiveControlSearchTerms = listOf(
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

    val essentialSpecialAccessSearchTerms = listOf(
        "Acesso ao uso",
        "Acesso de uso",
        "Usage access",
        "Otimização da bateria",
        "Uso irrestrito da bateria",
        "Sem restrições de bateria",
        "Battery optimization",
        "Unrestricted battery"
    )

    fun classTargetsDeviceAdmin(className: String): Boolean =
        containsAny(className, deviceAdminClassMarkers)

    fun classTargetsAppDetails(className: String): Boolean =
        containsAny(className, appDetailsClassMarkers)

    fun classTargetsUninstall(className: String): Boolean =
        containsAny(className, uninstallClassMarkers)

    fun classTargetsEssentialSpecialAccess(className: String): Boolean =
        containsAny(className, essentialSpecialAccessClassMarkers)

    fun textTargetsDeviceAdmin(values: Iterable<CharSequence?>): Boolean =
        valuesContainAny(values, deviceAdminSearchTerms) ||
            values.any { value -> mentionsAbbreviatedDeviceAdmin(value?.toString().orEmpty()) }

    /**
     * "administr. do aparelho" e companhia: palavra de administração abreviada
     * mais palavra de aparelho, no mesmo texto.
     */
    private fun mentionsAbbreviatedDeviceAdmin(value: String): Boolean {
        val normalized = normalize(value)
        if (normalized.isBlank()) return false
        if (deviceWordPrefixes.none(normalized::contains)) return false
        return normalized
            .split(NON_LETTER_REGEX)
            .any { word -> deviceAdminWordPrefixes.any(word::startsWith) }
    }

    fun textTargetsFocusGuard(values: Iterable<CharSequence?>): Boolean =
        valuesContainAny(values, focusGuardSearchTerms)

    fun textTargetsDestructiveControl(values: Iterable<CharSequence?>): Boolean =
        valuesContainAny(values, destructiveControlSearchTerms)

    fun textTargetsEssentialSpecialAccess(values: Iterable<CharSequence?>): Boolean =
        valuesContainAny(values, essentialSpecialAccessSearchTerms)

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

    private val NON_LETTER_REGEX = "[^a-z]+".toRegex()
}

