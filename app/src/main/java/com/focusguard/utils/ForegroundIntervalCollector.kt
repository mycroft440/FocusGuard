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
 */
internal class ForegroundIntervalCollector private constructor(
    private var currentPackage: String?,
    private var currentActivity: String?,
    private var currentStartMillis: Long,
    private var pendingExitMillis: Long?,
    private var lastEventMillis: Long,
    private val closed: MutableMap<String, MutableList<LongRange>>
) {
    constructor() : this(
        currentPackage = null,
        currentActivity = null,
        currentStartMillis = 0L,
        pendingExitMillis = null,
        lastEventMillis = Long.MIN_VALUE,
        closed = hashMapOf()
    )

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
            val openEnd = pendingExitMillis
                ?: if (deviceInteractive) untilMillis else lastEventMillis
            val end = minOf(openEnd, untilMillis)
            if (end > currentStartMillis) result += currentStartMillis until end
        }
        return result
    }

    fun copy(): ForegroundIntervalCollector = ForegroundIntervalCollector(
        currentPackage = currentPackage,
        currentActivity = currentActivity,
        currentStartMillis = currentStartMillis,
        pendingExitMillis = pendingExitMillis,
        lastEventMillis = lastEventMillis,
        closed = closed.mapValuesTo(hashMapOf()) { (_, value) -> value.toMutableList() }
    )

    private fun touch(atMillis: Long) {
        if (atMillis > lastEventMillis) lastEventMillis = atMillis
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
