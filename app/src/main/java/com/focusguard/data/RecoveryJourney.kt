package com.focusguard.data

/**
 * A ordem de leitura da aba AntiPorn, transformada em etapas.
 *
 * A sequência não é decoração: as Instruções do Criador dizem o que é a
 * armadilha, e o EasyPeasy desfaz a crença que a sustenta. Ler o segundo antes
 * do primeiro é começar a resposta sem ter ouvido a pergunta, então a segunda
 * etapa só abre quando a primeira fecha — a tela conduz um caminho em vez de
 * oferecer uma prateleira onde os dois parecem intercambiáveis.
 */
object RecoveryJourney {

    enum class Stage {
        /** Ler as instruções do criador: entender o que é a armadilha. */
        UNDERSTAND,

        /** Ler o EasyPeasy: desfazer a crença que sustenta a vontade. */
        REWIRE
    }

    enum class Status {
        /** Já cumprida; continua acessível para revisitar. */
        DONE,

        /** A etapa da vez, a única acionável. */
        CURRENT,

        /** Ainda trancada: falta concluir a anterior. */
        LOCKED
    }

    val stages: List<Stage> = Stage.entries.toList()

    /**
     * A etapa da vez, ou null quando as duas já foram concluídas.
     *
     * Tolera um conjunto fora de ordem — se por algum caminho a segunda for
     * concluída antes da primeira, a primeira continua sendo a da vez, em vez
     * de a tela pular um passo que a pessoa não deu.
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
