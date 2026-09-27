package com.focusguard.utils

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import com.focusguard.database.AppUsageLimit
import com.focusguard.security.ProtectionHierarchy
import java.util.Calendar
import java.util.TimeZone

/**
 * Mede quanto do limite diário de cada app já foi gasto.
 *
 * Só conta tempo de primeiro plano real, reconstruído dos eventos de ciclo de
 * vida em vez do agregado `totalTimeInForeground`, que o Android arredonda para
 * baldes maiores que o dia e que alguns fabricantes inflam com uso em segundo
 * plano. Além disso respeita a [ProtectionHierarchy]: enquanto um bloqueio mais
 * forte (período agendado, jejum) segura o app, o limite
 * aguarda e esse tempo não entra na conta.
 *
 * Todos os pontos que decidem ou exibem o limite (serviço de acessibilidade,
 * reconciliação, tela de limites e tela de impacto) passam por aqui para nunca
 * discordarem sobre o mesmo app.
 */
object AppUsageLimitMeter {

    /** Uso contado a partir da ativação do limite, em milissegundos, por pacote. */
    fun usedMillisByPackage(
        usageStatsManager: UsageStatsManager,
        limits: Collection<AppUsageLimit>,
        waitingSessions: Collection<ProtectionHierarchy.SessionTargets>,
        nowMillis: Long,
        isDeviceInteractive: Boolean,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Map<String, Long> {
        if (limits.isEmpty()) return emptyMap()
        val dayStart = startOfDay(nowMillis, timeZone)
        val packages = limits.mapTo(linkedSetOf()) { it.packageName }
        val foreground = AppForegroundIntervalReader.intervals(
            usageStatsManager = usageStatsManager,
            packages = packages,
            dayStartMillis = dayStart,
            nowMillis = nowMillis,
            isDeviceInteractive = isDeviceInteractive
        ) ?: return emptyMap()

        return limits.associate { limit ->
            limit.packageName to countedMillis(
                limit = limit,
                foregroundIntervals = foreground[limit.packageName].orEmpty(),
                waitingSessions = waitingSessions,
                dayStartMillis = dayStart,
                nowMillis = nowMillis,
                timeZone = timeZone
            )
        }
    }

    /** Parte pura do cálculo, exposta para testes determinísticos. */
    internal fun countedMillis(
        limit: AppUsageLimit,
        foregroundIntervals: List<LongRange>,
        waitingSessions: Collection<ProtectionHierarchy.SessionTargets>,
        dayStartMillis: Long,
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long {
        // Editar ou criar o limite zera a conta do dia a partir daquele instante.
        val countFrom = maxOf(dayStartMillis, limit.createdAt)
        if (countFrom >= nowMillis) return 0L
        val waiting = ProtectionHierarchy.appLimitWaitingIntervals(
            packageName = limit.packageName,
            sessions = waitingSessions,
            rangeStartMillis = countFrom,
            rangeEndMillis = nowMillis,
            timeZone = timeZone
        )
        return ProtectionHierarchy.countedUsageMillis(
            foregroundIntervals = foregroundIntervals,
            waitingIntervals = waiting,
            countFromMillis = countFrom,
            untilMillis = nowMillis
        )
    }

    fun startOfDay(nowMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): Long =
        Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}

/**
 * Leitura incremental dos eventos de primeiro plano do dia.
 *
 * O pulso de um segundo do serviço consulta o app em uso a cada segundo; reler o
 * dia inteiro a cada pulso seria caro. O estado confirmado avança só até alguns
 * segundos atrás (eventos recentes ainda podem estar sendo gravados) e a cauda
 * até agora é projetada numa cópia descartável.
 */
internal object AppForegroundIntervalReader {
    private const val EVENT_ACTIVITY_RESUMED = 1
    private const val EVENT_ACTIVITY_PAUSED = 2
    private const val EVENT_SCREEN_NON_INTERACTIVE = 16
    private const val EVENT_ACTIVITY_STOPPED = 23
    private const val EVENT_DEVICE_SHUTDOWN = 26

    /** Um app aberto antes da meia-noite precisa do RESUMED do dia anterior. */
    private const val LOOKBACK_MILLIS = 24L * 60L * 60L * 1_000L
    private const val SETTLE_MILLIS = 5_000L

    private var originMillis = Long.MIN_VALUE
    private var committedUntilMillis = Long.MIN_VALUE
    private var committed = ForegroundIntervalCollector()

    /** Null quando o Android recusou a leitura (por exemplo, sem Acesso de uso). */
    @Synchronized
    fun intervals(
        usageStatsManager: UsageStatsManager,
        packages: Set<String>,
        dayStartMillis: Long,
        nowMillis: Long,
        isDeviceInteractive: Boolean
    ): Map<String, List<LongRange>>? = try {
        val origin = (dayStartMillis - LOOKBACK_MILLIS).coerceAtLeast(0L)
        if (origin != originMillis) {
            originMillis = origin
            committedUntilMillis = origin
            committed = ForegroundIntervalCollector()
        }

        val view = if (nowMillis < committedUntilMillis) {
            // Chamador com um "agora" mais antigo que o estado confirmado (ou
            // relógio que voltou): lê avulso, sem descartar o cache dos demais.
            ForegroundIntervalCollector().also {
                feed(it, usageStatsManager, origin, nowMillis)
            }
        } else {
            val commitTarget = nowMillis - SETTLE_MILLIS
            if (commitTarget > committedUntilMillis) {
                val next = committed.copy()
                feed(next, usageStatsManager, committedUntilMillis, commitTarget)
                committed = next
                committedUntilMillis = commitTarget
            }
            committed.copy().also {
                feed(it, usageStatsManager, committedUntilMillis, nowMillis)
            }
        }
        packages.associateWith { packageName ->
            view.intervalsFor(packageName, nowMillis, isDeviceInteractive)
        }
    } catch (_: RuntimeException) {
        null
    }

    private fun feed(
        collector: ForegroundIntervalCollector,
        usageStatsManager: UsageStatsManager,
        fromMillis: Long,
        untilMillis: Long
    ) {
        if (untilMillis <= fromMillis) return
        val events = usageStatsManager.queryEvents(fromMillis, untilMillis) ?: return
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                EVENT_ACTIVITY_RESUMED -> collector.onActivityResumed(
                    event.packageName,
                    event.className,
                    event.timeStamp
                )
                EVENT_ACTIVITY_PAUSED,
                EVENT_ACTIVITY_STOPPED -> collector.onActivityLeft(
                    event.packageName,
                    event.className,
                    event.timeStamp
                )
                EVENT_SCREEN_NON_INTERACTIVE,
                EVENT_DEVICE_SHUTDOWN -> collector.onDeviceInactive(event.timeStamp)
            }
        }
    }
}
