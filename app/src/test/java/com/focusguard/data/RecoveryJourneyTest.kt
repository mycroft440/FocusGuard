package com.focusguard.data

import com.focusguard.data.RecoveryJourney.Stage
import com.focusguard.data.RecoveryJourney.Status
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import org.junit.Test

class RecoveryJourneyTest {

    @Test
    fun `nothing done leaves only the first stage open`() {
        val completed = emptySet<Stage>()

        assertThat(RecoveryJourney.statusOf(Stage.UNDERSTAND, completed))
            .isEqualTo(Status.CURRENT)
        assertThat(RecoveryJourney.statusOf(Stage.SHIELD, completed)).isEqualTo(Status.LOCKED)
        assertThat(RecoveryJourney.statusOf(Stage.REWIRE, completed)).isEqualTo(Status.LOCKED)
        assertThat(RecoveryJourney.statusOf(Stage.MAINTAIN, completed)).isEqualTo(Status.LOCKED)
    }

    @Test
    fun `finishing a stage opens exactly the next one`() {
        val completed = setOf(Stage.UNDERSTAND)

        assertThat(RecoveryJourney.statusOf(Stage.UNDERSTAND, completed)).isEqualTo(Status.DONE)
        assertThat(RecoveryJourney.statusOf(Stage.SHIELD, completed)).isEqualTo(Status.CURRENT)
        assertThat(RecoveryJourney.statusOf(Stage.REWIRE, completed)).isEqualTo(Status.LOCKED)
    }

    @Test
    fun `the final stage opens only after every other one is done`() {
        val almost = setOf(Stage.UNDERSTAND, Stage.SHIELD)
        assertThat(RecoveryJourney.statusOf(Stage.MAINTAIN, almost)).isEqualTo(Status.LOCKED)

        val all = setOf(Stage.UNDERSTAND, Stage.SHIELD, Stage.REWIRE)
        assertThat(RecoveryJourney.statusOf(Stage.MAINTAIN, all)).isEqualTo(Status.CURRENT)
        assertThat(RecoveryJourney.isJourneyComplete(all)).isTrue()
    }

    @Test
    fun `staying free is never marked as done`() {
        // Ninguém "conclui" seguir livre: mesmo listada como concluída, a etapa
        // final continua sendo a atual em vez de sumir do caminho.
        val completed = setOf(Stage.UNDERSTAND, Stage.SHIELD, Stage.REWIRE, Stage.MAINTAIN)

        assertThat(RecoveryJourney.statusOf(Stage.MAINTAIN, completed)).isEqualTo(Status.CURRENT)
        assertThat(RecoveryJourney.completedCount(completed)).isEqualTo(3)
        assertThat(RecoveryJourney.progress(completed)).isEqualTo(1f)
    }

    @Test
    fun `a stage finished out of order does not skip the one before it`() {
        val completed = setOf(Stage.UNDERSTAND, Stage.REWIRE)

        assertThat(RecoveryJourney.currentStage(completed)).isEqualTo(Stage.SHIELD)
        assertThat(RecoveryJourney.statusOf(Stage.REWIRE, completed)).isEqualTo(Status.DONE)
        assertThat(RecoveryJourney.statusOf(Stage.MAINTAIN, completed)).isEqualTo(Status.LOCKED)
    }

    @Test
    fun `progress counts only the stages the user can finish`() {
        assertThat(RecoveryJourney.completableStages).hasSize(3)
        assertThat(RecoveryJourney.progress(emptySet())).isEqualTo(0f)
        assertThat(RecoveryJourney.progress(setOf(Stage.UNDERSTAND)))
            .isWithin(0.001f)
            .of(1f / 3f)
    }

    @Test
    fun `days free counts closed days only`() {
        val armedAt = 1_000_000_000_000L

        assertThat(RecoveryJourney.daysFree(null, armedAt)).isNull()
        assertThat(RecoveryJourney.daysFree(armedAt, armedAt)).isEqualTo(0)
        // 23 horas ainda não é um dia vivido.
        assertThat(RecoveryJourney.daysFree(armedAt, armedAt + TimeUnit.HOURS.toMillis(23)))
            .isEqualTo(0)
        assertThat(RecoveryJourney.daysFree(armedAt, armedAt + TimeUnit.DAYS.toMillis(9)))
            .isEqualTo(9)
    }

    @Test
    fun `a clock that went backwards does not report negative days`() {
        val armedAt = 1_000_000_000_000L

        assertThat(RecoveryJourney.daysFree(armedAt, armedAt - TimeUnit.DAYS.toMillis(3)))
            .isEqualTo(0)
    }
}
