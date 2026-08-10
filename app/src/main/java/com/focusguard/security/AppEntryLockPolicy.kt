package com.focusguard.security

/**
 * Centraliza a regra de entrada no FocusGuard.
 *
 * A senha mestra bloqueia a entrada enquanto houver uma proteção reversível
 * que dependa dela: uma sessão PASSWORD ou um limite configurado para liberar
 * com senha. Limites sem senha e bloqueios irreversíveis por tempo não entram
 * nesta regra.
 */
object AppEntryLockPolicy {
    fun requiresPassword(
        hasMasterCredential: Boolean,
        activeSessionTypes: Collection<String>,
        hasPasswordProtectedAppLimit: Boolean = false,
        hasPasswordProtectedWebsiteLimit: Boolean = false
    ): Boolean {
        if (!hasMasterCredential) return false
        return activeSessionTypes.any { it.equals("PASSWORD", ignoreCase = true) } ||
            hasPasswordProtectedAppLimit ||
            hasPasswordProtectedWebsiteLimit
    }
}
