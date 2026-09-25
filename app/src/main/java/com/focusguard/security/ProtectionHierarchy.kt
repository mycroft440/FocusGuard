package com.focusguard.security

import com.focusguard.database.BlockSession
import com.focusguard.receiver.BlockingScheduleCalculator
import java.util.TimeZone

/**
 * Ordem única entre os bloqueios que podem existir ao mesmo tempo num alvo.
 *
 * Todos os bloqueios são camadas independentes: um app pode ter senha, limite
 * diário, período agendado e jejum ao mesmo tempo. Em cada instante só a camada
 * mais alta que está segurando o alvo decide o acesso; as de baixo aguardam.
 *
 * 1. [Layer.FOCUS_MODE] — Modo Foco: sua lista de permitidos é uma exceção
 *    temporária explícita e fica acima de tudo.
 * 2. [Layer.TIME] — Jejum de dopamina (contínuo) e períodos agendados, estes
 *    apenas dentro da faixa diária.
 * 3. [Layer.DAILY_LIMIT] — Limite de uso: só bloqueia depois de esgotado, e o
 *    relógio dele só anda enquanto nenhuma camada acima segura o alvo.
 * 4. [Layer.PASSWORD] — Senha: pede credencial apenas quando nada acima bloqueia.
 *
 * "Aguardar" tem consequência concreta para o limite: se o usuário tem 5 minutos
 * de limite e um período agendado que só libera das 19h às 21h, os 5 minutos são
 * contados somente dentro dessa janela, porque fora dela é o período quem manda.
 */
object ProtectionHierarchy {

    enum class Layer {
        FOCUS_MODE,
        TIME,
        DAILY_LIMIT,
        PASSWORD;

        fun outranks(other: Layer): Boolean = ordinal < other.ordinal
    }

    /** Uma sessão persistida com os alvos que ela segura. */
    data class SessionTargets(
        val session: BlockSession,
        val appPackages: Set<String> = emptySet(),
        val websiteRules: Set<String> = emptySet()
    )

    /** Camada de uma sessão, ou null quando ela não participa de bloqueio. */
    fun layerOf(session: BlockSession): Layer? = when {
        session.sessionType.equals(BlockTargetPolicy.SESSION_TYPE_PASSWORD, true) ->
            Layer.PASSWORD
        // Sessões POMODORO vêm do antigo Pomodoro rigoroso, que foi removido.
        session.sessionType.equals(BlockTargetPolicy.SESSION_TYPE_POMODORO, true) -> null
        else -> Layer.TIME
    }

    /**
     * Intervalos em `[rangeStartMillis, rangeEndMillis)` nos quais uma camada
     * acima do limite diário segurava [packageName]. Nesses trechos o limite
     * aguarda: o tempo não entra na conta.
     */
    fun appLimitWaitingIntervals(
        packageName: String,
        sessions: Collection<SessionTargets>,
        rangeStartMillis: Long,
        rangeEndMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): List<LongRange> = mergeIntervals(
        sessions.flatMap { targets ->
            val layer = layerOf(targets.session) ?: return@flatMap emptyList()
            if (!layer.outranks(Layer.DAILY_LIMIT)) return@flatMap emptyList()
            if (packageName !in targets.appPackages) return@flatMap emptyList()
            BlockingScheduleCalculator.blockingIntervals(
                session = targets.session,
                rangeStartMillis = rangeStartMillis,
                rangeEndMillis = rangeEndMillis,
                timeZone = timeZone
            )
        }
    )

    /**
     * Tempo que conta para o limite: primeiro plano em `[countFromMillis,
     * untilMillis)` menos os trechos em que o limite aguardava outra camada.
     */
    fun countedUsageMillis(
        foregroundIntervals: Collection<LongRange>,
        waitingIntervals: Collection<LongRange>,
        countFromMillis: Long,
        untilMillis: Long
    ): Long {
        if (untilMillis <= countFromMillis) return 0L
        val waiting = mergeIntervals(waitingIntervals)
        return mergeIntervals(foregroundIntervals).sumOf { interval ->
            val start = maxOf(interval.first, countFromMillis)
            val end = minOf(interval.last + 1, untilMillis)
            if (end <= start) 0L else (end - start) - overlapMillis(start, end, waiting)
        }.coerceAtLeast(0L)
    }

    internal fun mergeIntervals(intervals: Collection<LongRange>): List<LongRange> {
        val sorted = intervals.filter { !it.isEmpty() }.sortedBy { it.first }
        if (sorted.isEmpty()) return emptyList()
        val merged = mutableListOf<LongRange>()
        var current = sorted.first()
        sorted.drop(1).forEach { next ->
            current = if (next.first <= current.last + 1) {
                current.first..maxOf(current.last, next.last)
            } else {
                merged += current
                next
            }
        }
        merged += current
        return merged
    }

    private fun overlapMillis(start: Long, end: Long, merged: List<LongRange>): Long =
        merged.sumOf { interval ->
            val overlapStart = maxOf(start, interval.first)
            val overlapEnd = minOf(end, interval.last + 1)
            (overlapEnd - overlapStart).coerceAtLeast(0L)
        }
}
