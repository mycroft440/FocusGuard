package com.focusguard.security

/**
 * Centraliza a regra de entrada no FocusGuard.
 *
 * Uma senha cadastrada só bloqueia a entrada enquanto existir uma sessão
 * ativa do tipo PASSWORD. Assim, a senha funciona como credencial para
 * revogar o bloqueio, sem manter o aplicativo trancado depois que ele acaba.
 */
object AppEntryLockPolicy {
    fun requiresPassword(
        hasStoredPassword: Boolean,
        activeSessionTypes: Collection<String>
    ): Boolean {
        return hasStoredPassword &&
            activeSessionTypes.any { it.equals("PASSWORD", ignoreCase = true) }
    }
}
