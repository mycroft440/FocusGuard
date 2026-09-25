package com.focusguard.security

import java.text.Normalizer
import java.util.Locale

/**
 * Identifies Android Settings surfaces that can grant REQUEST_INSTALL_PACKAGES to
 * another app. This is intentionally limited to Settings packages; package-installer
 * UIs are not blocked so trusted installs keep working normally.
 */
object UnknownSourcesInterceptionPolicy {

    val searchTerms: List<String> = listOf(
        "Install unknown apps",
        "Allow from this source",
        "Instalar apps desconhecidos",
        "Permitir desta fonte",
        "Instalar aplicativos desconhecidos",
        "Instalar aplicaciones desconocidas",
        "Permitir desde esta fuente",
        "external_sources_settings_switch"
    )

    private val normalizedTextMarkers = searchTerms
        .map(::normalize)
        .filter(String::isNotBlank)
        .toSet()

    private val classMarkers = setOf(
        "ManageExternalSourcesActivity",
        "ManageAppExternalSourcesActivity",
        "ExternalSourcesDetails",
        "UnknownAppSources",
        "UnknownSources",
        "InstallUnknownApps",
        "InstallOtherApps"
    )

    fun classTargetsUnknownSources(className: String): Boolean =
        className.isNotBlank() && classMarkers.any { marker ->
            className.contains(marker, ignoreCase = true)
        }

    fun textTargetsUnknownSources(values: Iterable<CharSequence?>): Boolean =
        values.any { value ->
            val normalized = normalize(value?.toString().orEmpty())
            normalized.isNotBlank() && normalizedTextMarkers.any(normalized::contains)
        }

    fun shouldProtect(
        packageName: String,
        className: String,
        values: Iterable<CharSequence?>
    ): Boolean = packageName in SettingsInterceptionPolicy.settingsPackages &&
        (classTargetsUnknownSources(className) || textTargetsUnknownSources(values))

    private fun normalize(value: String): String {
        if (value.isBlank()) return ""
        return Normalizer.normalize(value.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(COMBINING_MARKS_REGEX, "")
            .replace(WHITESPACE_REGEX, " ")
    }

    private val COMBINING_MARKS_REGEX = "\\p{M}+".toRegex()
    private val WHITESPACE_REGEX = "\\s+".toRegex()
}
