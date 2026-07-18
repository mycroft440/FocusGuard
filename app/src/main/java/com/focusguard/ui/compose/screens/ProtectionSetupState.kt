package com.focusguard.ui.compose.screens

internal enum class ProtectionMode {
    LIMIT,
    PASSWORD,
    DOPAMINE_FAST
}

internal data class ProtectionDraft(
    val appPackageNames: Set<String> = emptySet(),
    val websiteRules: Set<String> = emptySet()
) {
    val hasTargets: Boolean
        get() = appPackageNames.isNotEmpty() || websiteRules.isNotEmpty()

    val availableModes: List<ProtectionMode>
        get() = if (hasTargets) ProtectionMode.entries else emptyList()
}

/** Converte a duração informada na UI em um limite diário válido de até 24 horas. */
internal fun parseDailyLimitMinutes(hoursText: String, minutesText: String): Int? {
    val hoursValue = hoursText.trim()
    val minutesValue = minutesText.trim()
    val hours = if (hoursValue.isEmpty()) 0 else hoursValue.toIntOrNull() ?: return null
    val minutes = if (minutesValue.isEmpty()) 0 else minutesValue.toIntOrNull() ?: return null

    if (hours !in 0..24 || minutes !in 0..59) return null

    val totalMinutes = hours * 60 + minutes
    return totalMinutes.takeIf { it in 1..24 * 60 }
}
