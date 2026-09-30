package com.focusguard.security

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * Limita tentativas de senha/padrão por credencial de bloqueio PASSWORD (a chave vem
 * de [PasswordAppUnlockStore.throttleKey]: todos os alvos com a mesma senha somam).
 *
 * Senhas de 4 caracteres e padrões de 4 pontos têm poucas combinações; sem limite,
 * dava para testar todas em sequência. Após [FREE_ATTEMPTS] erros seguidos, novas
 * tentativas esperam [BASE_LOCKOUT_MILLIS], dobrando a cada nova rodada de erros até
 * [MAX_LOCKOUT_MILLIS]. Um acerto zera a conta. A digital não passa por aqui.
 */
object TargetCredentialThrottle {
    internal const val FREE_ATTEMPTS = 5
    internal const val BASE_LOCKOUT_MILLIS = 30_000L
    internal const val MAX_LOCKOUT_MILLIS = 15 * 60_000L

    private data class State(val failures: Int, val lockedUntilElapsed: Long)

    private val states = ConcurrentHashMap<String, State>()

    /** Milissegundos que ainda faltam para liberar novas tentativas; 0 se liberado. */
    fun remainingLockoutMillis(
        targetId: String,
        nowElapsed: Long = SystemClock.elapsedRealtime()
    ): Long = (states[targetId]?.lockedUntilElapsed ?: 0L).minus(nowElapsed).coerceAtLeast(0L)

    fun recordFailure(targetId: String, nowElapsed: Long = SystemClock.elapsedRealtime()) {
        states.compute(targetId) { _, previous ->
            val failures = (previous?.failures ?: 0) + 1
            State(failures = failures, lockedUntilElapsed = lockoutUntil(failures, nowElapsed))
        }
    }

    fun recordSuccess(targetId: String) {
        states.remove(targetId)
    }

    internal fun lockoutUntil(failures: Int, nowElapsed: Long): Long {
        if (failures < FREE_ATTEMPTS) return 0L
        val rounds = ((failures - FREE_ATTEMPTS) / FREE_ATTEMPTS).coerceAtMost(10)
        val duration = (BASE_LOCKOUT_MILLIS shl rounds).coerceAtMost(MAX_LOCKOUT_MILLIS)
        // Trava ao completar cada rodada de erros; entre elas, só o que já estava.
        return if ((failures - FREE_ATTEMPTS) % FREE_ATTEMPTS == 0) nowElapsed + duration else 0L
    }

    internal fun resetForTest() = states.clear()
}
