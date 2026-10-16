package com.focusguard.analytics

/**
 * Conta acessos completos a aplicativos a partir dos eventos de ciclo de vida.
 *
 * Um acesso só é confirmado quando o aplicativo entrou em primeiro plano e
 * depois foi realmente deixado. Pausas causadas pela troca de Activity dentro
 * do mesmo aplicativo não criam acessos extras.
 */
internal class AppAccessAccumulator(
    private val isEligibleApp: (String) -> Boolean
) {
    private data class ForegroundSession(
        val packageName: String,
        var activityClassName: String?,
        val eligible: Boolean,
        val startedAtMs: Long
    )

    private val completedAccesses = linkedMapOf<String, Int>()
    private var foregroundSession: ForegroundSession? = null
    private var hasPendingExit = false

    fun onActivityResumed(
        packageName: String?,
        activityClassName: String?,
        timestampMs: Long = 0L
    ) {
        val resumedPackage = packageName?.takeIf(String::isNotBlank) ?: return
        val current = foregroundSession

        if (current == null) {
            foregroundSession = newSession(resumedPackage, activityClassName, timestampMs)
            hasPendingExit = false
            return
        }

        if (current.packageName == resumedPackage) {
            // Troca de Activity dentro do mesmo app: a pausa anterior não foi
            // uma saída do aplicativo e não deve virar um novo acesso.
            current.activityClassName = activityClassName
            hasPendingExit = false
            return
        }

        completeCurrentSession()
        foregroundSession = newSession(resumedPackage, activityClassName, timestampMs)
    }

    /**
     * Uma tela de bloqueio assumiu a frente. O app que acabou de entrar e já deu
     * lugar a ela foi interceptado na entrada: não conta como acesso. Um app que
     * estava em uso há mais tempo (um limite que esgotou no meio do uso) conta.
     */
    fun onBlockSurfaceShown(timestampMs: Long) {
        val current = foregroundSession
        val intercepted = current != null &&
            current.startedAtMs > 0L &&
            timestampMs - current.startedAtMs in 0..BlockSurfaceUsage.INTERCEPTED_ENTRY_MILLIS
        if (!intercepted) completeCurrentSession()
        foregroundSession = null
        hasPendingExit = false
    }

    fun onActivityPaused(packageName: String?, activityClassName: String?) {
        val current = foregroundSession ?: return
        if (packageName != current.packageName) return

        // Uma pausa atrasada de uma Activity antiga pode chegar depois que
        // outra Activity do mesmo app já foi retomada. Nesse caso o app ainda
        // está em primeiro plano e não existe uma saída pendente.
        val belongsToCurrentActivity = current.activityClassName == null ||
            activityClassName == null ||
            current.activityClassName == activityClassName
        if (belongsToCurrentActivity) {
            hasPendingExit = true
        }
    }

    fun onDeviceBecameInactive() {
        completeCurrentSession()
    }

    /**
     * Fecha somente uma sessão que já recebeu um evento de saída. Uma sessão
     * ainda em primeiro plano no fim da consulta permanece incompleta.
     */
    fun finish(): Map<String, Int> {
        if (hasPendingExit) {
            completeCurrentSession()
        }
        return completedAccesses.toMap()
    }

    private fun newSession(
        packageName: String,
        activityClassName: String?,
        timestampMs: Long
    ) = ForegroundSession(
        packageName = packageName,
        activityClassName = activityClassName,
        eligible = isEligibleApp(packageName),
        startedAtMs = timestampMs
    )

    private fun completeCurrentSession() {
        val completed = foregroundSession
        if (completed?.eligible == true) {
            completedAccesses[completed.packageName] =
                (completedAccesses[completed.packageName] ?: 0) + 1
        }
        foregroundSession = null
        hasPendingExit = false
    }
}
