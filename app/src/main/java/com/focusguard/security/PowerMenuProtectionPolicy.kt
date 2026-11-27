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

    /**
     * Partes do id de telas do System UI que nunca são o menu de energia: a cortina
     * de notificações, a barra de status, a tela de bloqueio e as configurações
     * rápidas. Elas mostram texto de notificações de qualquer app ("Reiniciar
     * agora", "Desligar" de um ponto de acesso) e não podem ser tomadas pelo menu.
     */
    private val knownNonPowerResourceIdMarkers = setOf(
        "notification_stack_scroller",
        "notification_panel",
        "notification_shade",
        "notification_shelf",
        "notification_container",
        "status_bar",
        "statusbar",
        "keyguard",
        "lock_screen",
        "lockscreen",
        "quick_settings",
        "qs_panel",
        "qs_frame",
        "qs_footer",
        "qs_header",
        "heads_up",
        "navigation_bar",
        "volume_dialog"
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
     * True when a label is the action name itself, not a sentence that merely
     * mentions it (a hint such as "Para desligar, mantenha pressionado…").
     */
    fun isExactActionLabel(action: Action, values: Iterable<CharSequence?>): Boolean {
        val normalizedTerms = normalizedTermsByAction.getValue(action)
        return values.any { value ->
            normalize(value?.toString().orEmpty()).trim() in normalizedTerms
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
        // Without the class, the two actions must be buttons named exactly after
        // them ("Desligar" and "Reiniciar"), never sentences that merely mention
        // them: the notification shade and the lock screen show notifications from
        // any app ("Reiniciar agora", "Toque para desligar"), and those were taken
        // for the power menu, which then opened on its own. A tree that carries a
        // shade, status bar, lock screen or quick settings id is never the menu.
        return hasIndependentPrimaryPowerActions(values) && !hasNonPowerSurfaceId(values)
    }

    private fun hasIndependentPrimaryPowerActions(values: Iterable<CharSequence?>): Boolean {
        val powerOffTerms = normalizedTermsByAction.getValue(Action.POWER_OFF)
        val restartTerms = normalizedTermsByAction.getValue(Action.RESTART)
        var powerOffEvidence = false
        var restartEvidence = false

        // Single pass with no intermediate list allocation: the accessibility service
        // runs this classifier on the latency-sensitive interception path.
        for (value in values) {
            val normalizedValue = normalize(value?.toString().orEmpty()).trim()
            if (normalizedValue.isEmpty()) continue
            if (normalizedValue in powerOffTerms) powerOffEvidence = true
            if (normalizedValue in restartTerms) restartEvidence = true
            if (powerOffEvidence && restartEvidence) return true
        }
        return false
    }

    /** O valor é o id de uma tela do System UI que nunca é o menu de energia. */
    private fun hasNonPowerSurfaceId(values: Iterable<CharSequence?>): Boolean =
        values.any { value ->
            val text = value?.toString() ?: return@any false
            val idStart = text.indexOf(RESOURCE_ID_SEPARATOR)
            if (idStart < 0) return@any false
            val idName = text.substring(idStart + RESOURCE_ID_SEPARATOR.length).lowercase(Locale.ROOT)
            knownNonPowerResourceIdMarkers.any(idName::contains)
        }

    private const val RESOURCE_ID_SEPARATOR = ":id/"

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS_REGEX, "")
            .lowercase(Locale.ROOT)

}
