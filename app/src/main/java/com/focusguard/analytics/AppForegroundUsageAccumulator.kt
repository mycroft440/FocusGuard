package com.focusguard.analytics

/**
 * Reconstrói o tempo realmente gasto em primeiro plano dentro de um intervalo
 * exato a partir dos eventos de ciclo de vida do UsageStatsManager.
 *
 * Diferente de queryUsageStats(INTERVAL_DAILY), este acumulador não arredonda a
 * consulta para o começo/fim de um dia. Isso evita somar horas que ficaram fora
 * da janela solicitada quando o período começa no meio do dia.
 */
internal class AppForegroundUsageAccumulator(
    private val rangeStartMs: Long,
    private val rangeEndMs: Long,
    private val isEligibleApp: (String) -> Boolean
) {
    private data class ForegroundSession(
        val packageName: String,
        var activityClassName: String?,
        var startedAtMs: Long,
        val eligible: Boolean
    )

    private val totals = linkedMapOf<String, Long>()
    private var foregroundSession: ForegroundSession? = null
    private var pendingExitAtMs: Long? = null
    private var observedLifecycleEvent = false

    fun onActivityResumed(
        packageName: String?,
        activityClassName: String?,
        timestampMs: Long
    ) {
        val resumedPackage = packageName?.takeIf(String::isNotBlank) ?: return
        observedLifecycleEvent = true
        val current = foregroundSession

        if (current == null) {
            foregroundSession = newSession(resumedPackage, activityClassName, timestampMs)
            pendingExitAtMs = null
            return
        }

        if (current.packageName == resumedPackage) {
            // Troca de Activity dentro do mesmo app: PAUSED/RESUMED não é uma
            // saída real do aplicativo.
            current.activityClassName = activityClassName
            pendingExitAtMs = null
            return
        }

        closeCurrent(pendingExitAtMs ?: timestampMs)
        foregroundSession = newSession(resumedPackage, activityClassName, timestampMs)
        pendingExitAtMs = null
    }

    fun onActivityPaused(
        packageName: String?,
        activityClassName: String?,
        timestampMs: Long
    ) {
        val current = foregroundSession ?: return
        if (packageName != current.packageName) return

        val belongsToCurrentActivity = current.activityClassName == null ||
            activityClassName == null ||
            current.activityClassName == activityClassName
        if (belongsToCurrentActivity) {
            observedLifecycleEvent = true
            pendingExitAtMs = timestampMs
        }
    }

    fun onDeviceBecameInactive(timestampMs: Long) {
        if (foregroundSession != null) observedLifecycleEvent = true
        closeCurrent(pendingExitAtMs ?: timestampMs)
    }

    fun finish(): Map<String, Long> {
        val current = foregroundSession
        if (current != null) {
            closeCurrent(pendingExitAtMs ?: rangeEndMs)
        }
        return totals.toMap()
    }

    fun hasObservedLifecycleEvents(): Boolean = observedLifecycleEvent

    private fun newSession(
        packageName: String,
        activityClassName: String?,
        timestampMs: Long
    ) = ForegroundSession(
        packageName = packageName,
        activityClassName = activityClassName,
        startedAtMs = timestampMs,
        eligible = isEligibleApp(packageName)
    )

    private fun closeCurrent(endMs: Long) {
        val current = foregroundSession ?: return
        if (current.eligible) {
            val clippedStart = current.startedAtMs.coerceAtLeast(rangeStartMs)
            val clippedEnd = endMs.coerceAtMost(rangeEndMs)
            val duration = (clippedEnd - clippedStart).coerceAtLeast(0L)
            if (duration > 0L) {
                totals[current.packageName] =
                    (totals[current.packageName] ?: 0L) + duration
            }
        }
        foregroundSession = null
        pendingExitAtMs = null
    }
}
