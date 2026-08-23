package com.focusguard.security

import java.text.Normalizer
import java.util.Locale

/**
 * Classifies the system power menu and its safe single-press actions.
 */
object PowerMenuProtectionPolicy {
    enum class Action { POWER_OFF, RESTART, EMERGENCY, MEDICAL_INFO }

    val systemUiPackages = setOf(
        "com.android.systemui",
        "com.samsung.android.systemui"
    )

    private val classMarkers = setOf(
        "GlobalActions",
        "GlobalActionsDialog",
        "GlobalActionsDialogLite",
        "ActionsDialog",
        "PowerOptions",
        "PowerMenu",
        "SecGlobalActions"
    )

    private val termsByAction = mapOf(
        Action.POWER_OFF to listOf("Desligar", "Power off", "Turn off", "Apagar"),
        Action.RESTART to listOf("Reiniciar", "Restart", "Reboot"),
        Action.EMERGENCY to listOf(
            "Chamada de emergência", "Emergência", "Emergency call", "Emergency",
            "Llamada de emergencia", "Emergencia"
        ),
        Action.MEDICAL_INFO to listOf(
            "Informações médicas", "Informacao medica", "Medical info",
            "Medical information", "Información médica", "Informacion medica"
        )
    )

    fun isSystemUiPackage(packageName: String): Boolean = packageName in systemUiPackages

    fun termsFor(action: Action): List<String> = termsByAction.getValue(action)

    fun matchesAction(action: Action, values: Iterable<CharSequence?>): Boolean {
        val normalizedTerms = termsFor(action).map(::normalize)
        return values.any { value ->
            val normalizedValue = normalize(value?.toString().orEmpty())
            normalizedValue.isNotBlank() && normalizedTerms.any(normalizedValue::contains)
        }
    }

    fun isPowerMenu(
        packageName: String,
        className: String,
        values: Iterable<CharSequence?>
    ): Boolean {
        if (!isSystemUiPackage(packageName)) return false
        val rendered = values.toList()
        val hasPowerOff = matchesAction(Action.POWER_OFF, rendered)
        val hasRestart = matchesAction(Action.RESTART, rendered)
        val hasEmergency = matchesAction(Action.EMERGENCY, rendered)
        val knownClass = classMarkers.any { className.contains(it, ignoreCase = true) }
        return when {
            knownClass -> hasPowerOff || hasRestart
            hasPowerOff && hasRestart -> true
            hasPowerOff && hasEmergency -> true
            hasRestart && hasEmergency -> true
            else -> false
        }
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .lowercase(Locale.ROOT)
}
