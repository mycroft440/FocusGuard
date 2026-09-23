package com.focusguard.receiver

import com.focusguard.database.BlockSession
import java.util.Calendar
import java.util.TimeZone

/** Cálculo puro, separado do Android AlarmManager para permitir testes determinísticos. */
internal object BlockingScheduleCalculator {

    fun nextLocalMidnight(
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long = Calendar.getInstance(timeZone).apply {
        timeInMillis = nowMillis
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /**
     * Valida a faixa diária usada por sessões recorrentes.
     *
     * `24:00` é aceito somente como término para manter compatibilidade com sessões
     * de dia inteiro criadas por versões anteriores. Novas seleções de horário usam
     * normalmente 00:00..23:59. Início e fim iguais não formam uma janela válida.
     */
    fun isValidRecurringWindow(
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int
    ): Boolean {
        val startValid = startHour in 0..23 && startMinute in 0..59
        val endValid = (endHour in 0..23 && endMinute in 0..59) ||
            (endHour == 24 && endMinute == 0)
        if (!startValid || !endValid) return false

        val startMinutes = startHour * 60 + startMinute
        val endMinutes = endHour * 60 + endMinute
        return startMinutes != endMinutes
    }

    /**
     * Intervalos `[início, fim)` em que [session] bloqueia dentro de
     * `[rangeStartMillis, rangeEndMillis)`.
     *
     * Sessões fixas contam a partir do próprio início. Já a faixa diária de uma
     * sessão recorrente define "quando o alvo pode ser usado" para o dia inteiro,
     * inclusive horas anteriores à criação: quem agenda às 15h que só pode usar
     * das 19h às 21h espera que o uso das 10h também fique fora da conta do limite.
     */
    fun blockingIntervals(
        session: BlockSession,
        rangeStartMillis: Long,
        rangeEndMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): List<LongRange> {
        if (!session.isActive || rangeEndMillis <= rangeStartMillis) return emptyList()
        val sessionEnd = session.endTime ?: Long.MAX_VALUE
        if (session.isFixed24h) {
            val start = maxOf(session.startTime, rangeStartMillis)
            val end = minOf(sessionEnd, rangeEndMillis)
            return if (end > start) listOf(start until end) else emptyList()
        }
        if (!isValidRecurringWindow(
                startHour = session.recurringStartHour,
                startMinute = session.recurringStartMinute,
                endHour = session.recurringEndHour,
                endMinute = session.recurringEndMinute
            )
        ) return emptyList()

        val startMinutes = session.recurringStartHour * 60 + session.recurringStartMinute
        val endMinutes = session.recurringEndHour * 60 + session.recurringEndMinute
        val allowedDays = session.recurringDaysOfWeek.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
        val logicalDay = Calendar.getInstance(timeZone).apply {
            timeInMillis = rangeStartMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // O dia anterior carrega o final de janelas que cruzam a meia-noite.
            add(Calendar.DAY_OF_YEAR, -1)
        }

        val intervals = mutableListOf<LongRange>()
        while (logicalDay.timeInMillis < rangeEndMillis) {
            val dayAllowed = allowedDays.isEmpty() ||
                logicalDay.get(Calendar.DAY_OF_WEEK).toString() in allowedDays
            if (dayAllowed) {
                val windowStart = (logicalDay.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, session.recurringStartHour)
                    set(Calendar.MINUTE, session.recurringStartMinute)
                }.timeInMillis
                val windowEnd = (logicalDay.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, session.recurringEndHour)
                    set(Calendar.MINUTE, session.recurringEndMinute)
                    if (endMinutes < startMinutes) add(Calendar.DAY_OF_YEAR, 1)
                }.timeInMillis
                val start = maxOf(windowStart, rangeStartMillis)
                val end = minOf(windowEnd, sessionEnd, rangeEndMillis)
                if (end > start) intervals += start until end
            }
            logicalDay.add(Calendar.DAY_OF_YEAR, 1)
        }
        return intervals
    }

    fun nextBoundary(
        sessions: Collection<BlockSession>,
        additionalBoundaries: Collection<Long>,
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long? {
        var nextBoundary = additionalBoundaries
            .asSequence()
            .filter { it > nowMillis }
            .minOrNull()

        fun consider(candidate: Long, sessionEnd: Long? = null) {
            if (candidate <= nowMillis) return
            if (sessionEnd != null && candidate > sessionEnd) return
            if (nextBoundary == null || candidate < nextBoundary!!) nextBoundary = candidate
        }

        sessions.asSequence().filter { it.isActive }.forEach { session ->
            session.endTime?.let { consider(it) }
            if (session.isFixed24h) return@forEach
            if (!isValidRecurringWindow(
                    startHour = session.recurringStartHour,
                    startMinute = session.recurringStartMinute,
                    endHour = session.recurringEndHour,
                    endMinute = session.recurringEndMinute
                )
            ) return@forEach

            val startMinutes = session.recurringStartHour * 60 + session.recurringStartMinute
            val endMinutes = session.recurringEndHour * 60 + session.recurringEndMinute

            val allowedDays = session.recurringDaysOfWeek.split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()
            val baseDay = Calendar.getInstance(timeZone).apply {
                timeInMillis = nowMillis
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            // O dia anterior é necessário para o término de janelas que cruzam meia-noite.
            for (offset in -1..7) {
                val logicalDay = (baseDay.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, offset)
                }
                if (allowedDays.isNotEmpty() &&
                    logicalDay.get(Calendar.DAY_OF_WEEK).toString() !in allowedDays
                ) continue

                val start = (logicalDay.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, session.recurringStartHour)
                    set(Calendar.MINUTE, session.recurringStartMinute)
                }
                val end = (logicalDay.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, session.recurringEndHour)
                    set(Calendar.MINUTE, session.recurringEndMinute)
                    if (endMinutes < startMinutes) add(Calendar.DAY_OF_YEAR, 1)
                }
                consider(start.timeInMillis, session.endTime)
                consider(end.timeInMillis, session.endTime)
            }
        }
        return nextBoundary
    }
}
