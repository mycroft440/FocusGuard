package com.focusguard.security

import java.text.Normalizer
import java.util.Locale

/**
 * Classifies the system power menu and its safe single-press actions.
 */
object PowerMenuProtectionPolicy {
    enum class Action { POWER_OFF, RESTART, EMERGENCY, MEDICAL_INFO }
    enum class DirectDecision { MATCH, UNKNOWN, NOT_MATCH }

    private val COMBINING_MARKS_REGEX = "\\p{M}+".toRegex()

    val systemUiPackages = setOf(
        "com.android.systemui",
        "com.samsung.android.systemui"
    )

    private val specificClassMarkers = setOf(
        "GlobalActions",
        "GlobalActionsDialog",
        "GlobalActionsDialogLite",
        "PowerOptions",
        "PowerMenu",
        "SecGlobalActions"
    )
    private val ambiguousClassMarkers = setOf("ActionsDialog")
    private val classMarkers = specificClassMarkers + ambiguousClassMarkers

    private val knownNonPowerClassMarkers = setOf(
        "Notification",
        "StatusBar",
        "QuickSettings",
        "QSTile",
        "HeadsUp",
        "NavigationBar",
        "VolumeDialog",
        "Screenshot",
        "Biometric",
        "Keyguard"
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
    private val normalizedTermsByAction = termsByAction.mapValues { (_, terms) ->
        terms.map(::normalize)
    }

    fun isSystemUiPackage(packageName: String): Boolean = packageName in systemUiPackages

    fun termsFor(action: Action): List<String> = termsByAction.getValue(action)

    fun matchesAction(action: Action, values: Iterable<CharSequence?>): Boolean {
        val normalizedTerms = normalizedTermsByAction.getValue(action)
        return values.any { value ->
            val normalizedValue = normalize(value?.toString().orEmpty())
            normalizedValue.isNotBlank() && normalizedTerms.any(normalizedValue::contains)
        }
    }

    /**
     * Uses only event fields. A known global-actions class is sufficient because
     * waiting for its node tree costs the first visible menu frames.
     */
    fun classifyDirect(
        packageName: String,
        className: String,
        values: Iterable<CharSequence?>
    ): DirectDecision {
        if (!isSystemUiPackage(packageName)) return DirectDecision.NOT_MATCH
        if (specificClassMarkers.any { className.contains(it, ignoreCase = true) }) {
            return DirectDecision.MATCH
        }
        if (knownNonPowerClassMarkers.any { className.contains(it, ignoreCase = true) }) {
            return DirectDecision.NOT_MATCH
        }
        if (ambiguousClassMarkers.any { className.contains(it, ignoreCase = true) }) {
            // `ActionsDialog` is reused by SystemUI for surfaces that are not the
            // power menu. Require rendered power actions before accepting it.
            return if (isPowerMenu(packageName, className, values)) {
                DirectDecision.MATCH
            } else {
                DirectDecision.UNKNOWN
            }
        }
        return if (isPowerMenu(packageName, className, values)) {
            DirectDecision.MATCH
        } else {
            DirectDecision.UNKNOWN
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
        val specificClass = specificClassMarkers.any {
            className.contains(it, ignoreCase = true)
        }
        val ambiguousClass = ambiguousClassMarkers.any {
            className.contains(it, ignoreCase = true)
        }
        val evidenceCount = listOf(hasPowerOff, hasRestart, hasEmergency).count { it }

        return when {
            // Explicit OEM/AOSP global-actions classes are already a strong signal.
            specificClass -> hasPowerOff || hasRestart
            // ActionsDialog is reused by SystemUI. One word such as “Restart” is
            // not enough: require two independent power-menu actions.
            ambiguousClass -> evidenceCount >= 2 && (hasPowerOff || hasRestart)
            hasPowerOff && hasRestart -> true
            hasPowerOff && hasEmergency -> true
            hasRestart && hasEmergency -> true
            else -> false
        }
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS_REGEX, "")
            .lowercase(Locale.ROOT)

}
