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
            // power menu. Require rendered primary power actions before accepting it.
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
        val specificClass = specificClassMarkers.any {
            className.contains(it, ignoreCase = true)
        }

        if (specificClass) {
            // Explicit OEM/AOSP global-actions classes are already a strong signal.
            // Keep this fast path so actual power-menu protection still appears on
            // the first accessibility event, before the full node tree is populated.
            return matchesAction(Action.POWER_OFF, values) ||
                matchesAction(Action.RESTART, values)
        }

        // Generic SystemUI surfaces such as Samsung's notification/quick-settings
        // shade can expose a power shortcut and text such as "Chamadas de
        // emergência" at the same time. Emergency text therefore must never be
        // identity evidence for a power menu. Outside a known global-actions class,
        // require distinct rendered evidence for both primary power actions.
        //
        // This is intentionally a single pass with no intermediate list allocation:
        // the accessibility service executes this classifier on the latency-sensitive
        // interception path.
        return hasIndependentPrimaryPowerActions(values)
    }

    private fun hasIndependentPrimaryPowerActions(values: Iterable<CharSequence?>): Boolean {
        val powerOffTerms = normalizedTermsByAction.getValue(Action.POWER_OFF)
        val restartTerms = normalizedTermsByAction.getValue(Action.RESTART)
        var powerOffEvidence = false
        var restartEvidence = false

        for (value in values) {
            val normalizedValue = normalize(value?.toString().orEmpty())
            if (normalizedValue.isBlank()) continue
            val matchesPowerOff = powerOffTerms.any(normalizedValue::contains)
            val matchesRestart = restartTerms.any(normalizedValue::contains)

            // A single combined shortcut/description is not two independent actions.
            if (matchesPowerOff && !matchesRestart) powerOffEvidence = true
            if (matchesRestart && !matchesPowerOff) restartEvidence = true
            if (powerOffEvidence && restartEvidence) return true
        }
        return false
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS_REGEX, "")
            .lowercase(Locale.ROOT)

}
