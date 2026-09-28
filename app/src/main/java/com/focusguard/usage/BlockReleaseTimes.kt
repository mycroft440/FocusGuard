package com.focusguard.usage

import com.focusguard.database.AppUsageLimit
import com.focusguard.database.BlockSession
import com.focusguard.utils.UsageLimitBehaviorPolicy
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Quando um bloqueio por período do dia ou um limite de uso diário libera o app.
 *
 * Funções puras (recebem o instante e o fuso) para que a tela de impacto e os
 * testes calculem o mesmo prazo que o motor de bloqueio aplica.
 */
object BlockReleaseTimes {

    /** Trecho do dia em que um bloqueio por períodos deixa o app liberado. */
    data class AllowedWindow(
        val startHour: Int,
        val startMinute: Int,
        val endHour: Int,
        val endMinute: Int,
        /** Dias em que o bloqueio vale; vazio = todos os dias. */
        val restrictedToDays: Boolean
    )

    /**
     * Fim da janela bloqueada em curso de um período do dia (ex.: 21:00–19:00 às
     * 23:00 → 19:00 de amanhã), limitado pelo fim da própria sessão.
     */
    fun scheduledWindowEnd(
        session: BlockSession,
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long? {
        if (session.isFixed24h) return session.endTime
        val now = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes = session.recurringStartHour * 60 + session.recurringStartMinute
        val endMinutes = session.recurringEndHour * 60 + session.recurringEndMinute

        val end = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, session.recurringEndHour)
            set(Calendar.MINUTE, session.recurringEndMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // Janela que atravessa a meia-noite e ainda está na parte da noite:
            // termina no dia seguinte.
            if (startMinutes > endMinutes && currentMinutes >= startMinutes) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }.timeInMillis
        return session.endTime?.let { minOf(it, end) } ?: end
    }

    /** Período liberado de um bloqueio por períodos: o complemento da janela bloqueada. */
    fun allowedWindow(session: BlockSession): AllowedWindow? {
        if (session.isFixed24h) return null
        val startMinutes = session.recurringStartHour * 60 + session.recurringStartMinute
        val endMinutes = session.recurringEndHour * 60 + session.recurringEndMinute
        if (startMinutes == endMinutes) return null
        return AllowedWindow(
            startHour = session.recurringEndHour,
            startMinute = session.recurringEndMinute,
            endHour = session.recurringStartHour,
            endMinute = session.recurringStartMinute,
            restrictedToDays = session.recurringDaysOfWeek.isNotBlank()
        )
    }

    /**
     * Até quando um limite de uso diário esgotado mantém o app bloqueado.
     *
     * O uso volta a zero na virada do dia, então nenhum modo segura além da
     * próxima meia-noite. A pausa de 30 minutos segura só até o fim da pausa, e
     * a regra inteira nunca passa do seu prazo final.
     */
    fun usageLimitBlockedUntil(
        limit: AppUsageLimit,
        nowMillis: Long,
        pauseBlockedUntil: Long?,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long {
        val midnight = nextMidnight(nowMillis, timeZone)
        val mode = limit.lockMode.uppercase(Locale.ROOT)
        val candidate = when {
            UsageLimitBehaviorPolicy.isPauseMode(limit.lockMode) ->
                pauseBlockedUntil?.takeIf { it > nowMillis } ?: midnight
            UsageLimitBehaviorPolicy.isBlockUntilTomorrowMode(limit.lockMode) -> midnight
            mode == "TIME" -> limit.lockUntilTimestamp
                ?.takeIf { it > nowMillis }
                ?.let { minOf(it, midnight) }
                ?: midnight
            else -> midnight
        }
        val ruleEnd = limit.lockUntilTimestamp
            ?.takeIf { UsageLimitBehaviorPolicy.isDailyBehaviorMode(limit.lockMode) && it > nowMillis }
        return ruleEnd?.let { minOf(it, candidate) } ?: candidate
    }

    internal fun nextMidnight(nowMillis: Long, timeZone: TimeZone): Long =
        Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMillis
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
