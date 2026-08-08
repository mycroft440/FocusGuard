package com.focusguard.data

import java.util.concurrent.TimeUnit

/**
 * A ordem em que sair da pornografia costuma funcionar, transformada nas etapas
 * da aba AntiPorn.
 *
 * A sequência não é decoração. Ler antes de bloquear deixa a pessoa entendendo
 * a armadilha com a porta ainda aberta; bloquear antes de ler tira o acesso mas
 * não tira a vontade, e o bloqueio vira uma parede contra a qual se bate. Por
 * isso uma etapa só abre quando a anterior fecha: a tela conduz um caminho em
 * vez de oferecer uma prateleira de recursos onde tudo parece opcional.
 *
 * [MAINTAIN] é o destino, não uma tarefa — ninguém "conclui" seguir livre —,
 * então ela nunca entra em `completed` e não conta no progresso.
 */
object RecoveryJourney {

    enum class Stage {
        /** Ler as instruções do criador: entender o que é a armadilha. */
        UNDERSTAND,

        /** Armar o bloqueio de pornografia: tirar o acesso fácil do caminho. */
        SHIELD,

        /** Ler o EasyPeasy: desfazer a crença que sustenta a vontade. */
        REWIRE,

        /** Seguir livre, contando os dias desde que o bloqueio foi armado. */
        MAINTAIN
    }

    enum class Status {
        /** Já cumprida; continua acessível para revisitar. */
        DONE,

        /** A etapa da vez, a única acionável. */
        CURRENT,

        /** Ainda trancada: falta concluir alguma anterior. */
        LOCKED
    }

    val stages: List<Stage> = Stage.entries.toList()

    /** Etapas que o usuário conclui; [Stage.MAINTAIN] não é uma delas. */
    val completableStages: List<Stage> = stages.filterNot(::isFinal)

    fun isFinal(stage: Stage): Boolean = stage == Stage.MAINTAIN

    /**
     * A etapa da vez: a primeira que ainda não foi concluída.
     *
     * Tolera um conjunto fora de ordem — se por algum caminho a terceira for
     * concluída antes da segunda, a segunda continua sendo a da vez, em vez de
     * a tela pular um passo que a pessoa não deu.
     */
    fun currentStage(completed: Set<Stage>): Stage =
        stages.firstOrNull { isFinal(it) || it !in completed } ?: Stage.MAINTAIN

    fun statusOf(stage: Stage, completed: Set<Stage>): Status = when {
        !isFinal(stage) && stage in completed -> Status.DONE
        stage == currentStage(completed) -> Status.CURRENT
        else -> Status.LOCKED
    }

    fun completedCount(completed: Set<Stage>): Int =
        completableStages.count { it in completed }

    /** Fração para a barra de progresso, entre 0 e 1. */
    fun progress(completed: Set<Stage>): Float =
        completedCount(completed).toFloat() / completableStages.size

    fun isJourneyComplete(completed: Set<Stage>): Boolean =
        completedCount(completed) == completableStages.size

    /**
     * Dias inteiros desde que o bloqueio foi armado, ou null se ainda não foi.
     *
     * Conta dias fechados: às 23h do primeiro dia ainda é "0 dias", porque
     * arredondar para cima entregaria um número que a pessoa não viveu.
     */
    fun daysFree(armedAtMillis: Long?, nowMillis: Long): Int? {
        if (armedAtMillis == null || armedAtMillis <= 0L) return null
        val elapsed = nowMillis - armedAtMillis
        if (elapsed < 0L) return 0
        return TimeUnit.MILLISECONDS.toDays(elapsed).toInt()
    }
}
