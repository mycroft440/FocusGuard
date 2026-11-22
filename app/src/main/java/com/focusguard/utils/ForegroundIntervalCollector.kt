package com.focusguard.utils

/**
 * Reconstrói, a partir dos eventos de ciclo de vida do UsageStatsManager, os
 * trechos em que cada app esteve de fato em primeiro plano.
 *
 * Apenas uma Activity retomada (ACTIVITY_RESUMED) conta como uso. Áudio em
 * segundo plano, serviço em primeiro plano, picture-in-picture e tela apagada não
 * geram trechos. Um PAUSED seguido de RESUMED do mesmo pacote é só troca de tela
 * dentro do app; já a retomada de outro pacote encerra o trecho no instante do
 * PAUSED, e não no da retomada.
 *
 * O coletor é incremental: eventos podem ser entregues em lotes consecutivos, e
 * [copy] permite projetar o trecho ainda aberto sem alterar o estado confirmado.
 *
 * O Android 9 não grava DEVICE_SHUTDOWN. Com [legacyBootMillis] (a hora do último
 * boot, ver [LegacyBootClock]) o aparelho desligado nesse meio fecha o trecho aberto no
 * último evento antes do boot, em vez de dar ao último app todo o tempo desligado.
 */
internal class ForegroundIntervalCollector private constructor(
    private val legacyBootMillis: Long?,
    private var currentPackage: String?,
    private var currentActivity: String?,
    private var currentStartMillis: Long,
    private var pendingExitMillis: Long?,
    private var lastEventMillis: Long,
    private var lastAnyEventMillis: Long,
    private val closed: MutableMap<String, MutableList<LongRange>>
) {
    constructor(legacyBootMillis: Long? = null) : this(
        legacyBootMillis = legacyBootMillis,
        currentPackage = null,
        currentActivity = null,
        currentStartMillis = 0L,
        pendingExitMillis = null,
        lastEventMillis = Long.MIN_VALUE,
        lastAnyEventMillis = Long.MIN_VALUE,
        closed = hashMapOf()
    )

    /**
     * Entrada única para um evento do UsageEvents: todo leitor do primeiro plano
     * (limites, painel, tela de impacto) trata os mesmos tipos do mesmo jeito.
     */
    fun onUsageEvent(
        eventType: Int,
        packageName: String?,
        activityClassName: String?,
        atMillis: Long
    ) {
        crossLegacyBoot(atMillis)
        when (eventType) {
            EVENT_ACTIVITY_RESUMED -> onActivityResumed(packageName, activityClassName, atMillis)
            EVENT_ACTIVITY_PAUSED,
            EVENT_ACTIVITY_STOPPED -> onActivityLeft(packageName, activityClassName, atMillis)
            EVENT_SCREEN_NON_INTERACTIVE,
            EVENT_DEVICE_SHUTDOWN -> onDeviceInactive(atMillis)
        }
    }

    /** Todo pacote que já teve um trecho, fechado ou ainda aberto. */
    fun packagesSeen(): Set<String> = buildSet {
        addAll(closed.keys)
        currentPackage?.let(::add)
    }

    fun onActivityResumed(packageName: String?, activityClassName: String?, atMillis: Long) {
        val resumed = packageName?.takeIf(String::isNotBlank) ?: return
        touch(atMillis)
        val current = currentPackage
        if (current == resumed) {
            currentActivity = activityClassName
            pendingExitMillis = null
            return
        }
        if (current != null) close(pendingExitMillis ?: atMillis)
        currentPackage = resumed
        currentActivity = activityClassName
        currentStartMillis = atMillis
        pendingExitMillis = null
    }

    /** ACTIVITY_PAUSED ou ACTIVITY_STOPPED da Activity que está em primeiro plano. */
    fun onActivityLeft(packageName: String?, activityClassName: String?, atMillis: Long) {
        touch(atMillis)
        if (packageName == null || packageName != currentPackage) return
        val sameActivity = currentActivity == null ||
            activityClassName == null ||
            currentActivity == activityClassName
        if (sameActivity && pendingExitMillis == null) pendingExitMillis = atMillis
    }

    /** Tela apagada ou aparelho desligando: nada continua em primeiro plano. */
    fun onDeviceInactive(atMillis: Long) {
        touch(atMillis)
        if (currentPackage != null) close(pendingExitMillis ?: atMillis)
    }

    /**
     * Trechos de [packageName] até [untilMillis]. O trecho aberto só é estendido
     * até agora quando [deviceInteractive]; com a tela apagada ele termina no
     * último evento observado, nunca no relógio atual.
     */
    fun intervalsFor(
        packageName: String,
        untilMillis: Long,
        deviceInteractive: Boolean
    ): List<LongRange> {
        val result = closed[packageName].orEmpty().toMutableList()
        if (currentPackage == packageName) {
            // Ainda sem evento depois do boot: o trecho parou quando o aparelho desligou.
            val bootCut = legacyBootMillis?.takeIf { bootMillis ->
                lastAnyEventMillis != Long.MIN_VALUE &&
                    lastAnyEventMillis < bootMillis &&
                    untilMillis >= bootMillis
            }?.let { lastAnyEventMillis }
            val openEnd = pendingExitMillis
                ?: bootCut
                ?: if (deviceInteractive) untilMillis else lastEventMillis
            val end = minOf(openEnd, untilMillis)
            if (end > currentStartMillis) result += currentStartMillis until end
        }
        return result
    }

    fun copy(): ForegroundIntervalCollector = ForegroundIntervalCollector(
        legacyBootMillis = legacyBootMillis,
        currentPackage = currentPackage,
        currentActivity = currentActivity,
        currentStartMillis = currentStartMillis,
        pendingExitMillis = pendingExitMillis,
        lastEventMillis = lastEventMillis,
        lastAnyEventMillis = lastAnyEventMillis,
        closed = closed.mapValuesTo(hashMapOf()) { (_, value) -> value.toMutableList() }
    )

    private fun touch(atMillis: Long) {
        if (atMillis > lastEventMillis) lastEventMillis = atMillis
    }

    /**
     * Um evento depois do boot com o anterior (de qualquer tipo) antes dele: o aparelho
     * desligou nesse meio. Como no DEVICE_SHUTDOWN do Android 10, o trecho aberto
     * termina no último instante em que se sabe que o aparelho estava ligado.
     */
    private fun crossLegacyBoot(atMillis: Long) {
        val previous = lastAnyEventMillis
        if (atMillis > lastAnyEventMillis) lastAnyEventMillis = atMillis
        val bootMillis = legacyBootMillis ?: return
        if (previous == Long.MIN_VALUE || previous >= bootMillis || atMillis < bootMillis) return
        onDeviceInactive(previous)
    }

    private companion object {
        // Valores de android.app.usage.UsageEvents.Event, repetidos para o coletor
        // rodar nos testes fora do Android.
        const val EVENT_ACTIVITY_RESUMED = 1
        const val EVENT_ACTIVITY_PAUSED = 2
        const val EVENT_SCREEN_NON_INTERACTIVE = 16
        const val EVENT_ACTIVITY_STOPPED = 23
        const val EVENT_DEVICE_SHUTDOWN = 26
    }

    private fun close(endMillis: Long) {
        val packageName = currentPackage ?: return
        if (endMillis > currentStartMillis) {
            closed.getOrPut(packageName) { mutableListOf() } += currentStartMillis until endMillis
        }
        currentPackage = null
        currentActivity = null
        pendingExitMillis = null
    }
}
