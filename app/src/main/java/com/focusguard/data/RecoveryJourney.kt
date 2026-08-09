package com.focusguard.data

/**
 * A ordem da jornada AntiPorn, transformada em etapas.
 *
 * Primeiro a pessoa entende a armadilha, depois desfaz a crença que a sustenta
 * e, só então, pode firmar o compromisso de proteção. A sequência conduz um
 * caminho em vez de oferecer ações soltas que parecem intercambiáveis.
 */
object RecoveryJourney {

    enum class Stage {
        /** Ler as instruções do criador: entender o que é a armadilha. */
        UNDERSTAND,

        /** Ler o EasyPeasy: desfazer a crença que sustenta a vontade. */
        REWIRE,

        /** Ativar pornografia sem prazo e redes sociais por seis meses. */
        PROTECT
    }

    enum class Status {
        /** Já cumprida; leituras continuam acessíveis para revisitar. */
        DONE,

        /** A etapa da vez, a única acionável. */
        CURRENT,

        /** Ainda trancada: falta concluir a anterior. */
        LOCKED
    }

    val stages: List<Stage> = Stage.entries.toList()

    /**
     * A etapa da vez, ou null quando as três já foram concluídas.
     *
     * Tolera um conjunto fora de ordem: a primeira etapa ausente continua sendo
     * a da vez, em vez de a tela pular um passo que a pessoa não deu.
     */
    fun currentStage(completed: Set<Stage>): Stage? =
        stages.firstOrNull { it !in completed }

    fun statusOf(stage: Stage, completed: Set<Stage>): Status = when {
        stage in completed -> Status.DONE
        stage == currentStage(completed) -> Status.CURRENT
        else -> Status.LOCKED
    }

    fun completedCount(completed: Set<Stage>): Int = stages.count { it in completed }

    /** Fração para a barra de progresso, entre 0 e 1. */
    fun progress(completed: Set<Stage>): Float =
        completedCount(completed).toFloat() / stages.size

    fun isJourneyComplete(completed: Set<Stage>): Boolean = currentStage(completed) == null
}
