package com.focusguard.data

import com.focusguard.data.RecoveryJourney.Stage
import com.focusguard.data.RecoveryJourney.Status
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecoveryJourneyTest {

    @Test
    fun `the journey is two readings followed by protection`() {
        assertThat(RecoveryJourney.stages)
            .containsExactly(Stage.UNDERSTAND, Stage.REWIRE, Stage.PROTECT)
            .inOrder()
    }

    @Test
    fun `nothing done leaves only the first stage open`() {
        val completed = emptySet<Stage>()

        assertThat(RecoveryJourney.currentStage(completed)).isEqualTo(Stage.UNDERSTAND)
        assertThat(RecoveryJourney.statusOf(Stage.UNDERSTAND, completed))
            .isEqualTo(Status.CURRENT)
        assertThat(RecoveryJourney.statusOf(Stage.REWIRE, completed)).isEqualTo(Status.LOCKED)
        assertThat(RecoveryJourney.statusOf(Stage.PROTECT, completed)).isEqualTo(Status.LOCKED)
    }

    @Test
    fun `finishing the first stage opens the second`() {
        val completed = setOf(Stage.UNDERSTAND)

        assertThat(RecoveryJourney.statusOf(Stage.UNDERSTAND, completed)).isEqualTo(Status.DONE)
        assertThat(RecoveryJourney.statusOf(Stage.REWIRE, completed)).isEqualTo(Status.CURRENT)
        assertThat(RecoveryJourney.statusOf(Stage.PROTECT, completed)).isEqualTo(Status.LOCKED)
        assertThat(RecoveryJourney.isJourneyComplete(completed)).isFalse()
    }

    @Test
    fun `finishing both readings opens protection instead of completing journey`() {
        val completed = setOf(Stage.UNDERSTAND, Stage.REWIRE)

        assertThat(RecoveryJourney.currentStage(completed)).isEqualTo(Stage.PROTECT)
        assertThat(RecoveryJourney.statusOf(Stage.PROTECT, completed)).isEqualTo(Status.CURRENT)
        assertThat(RecoveryJourney.isJourneyComplete(completed)).isFalse()
        assertThat(RecoveryJourney.progress(completed)).isEqualTo(2f / 3f)
    }

    @Test
    fun `all three stages complete the journey`() {
        val completed = Stage.entries.toSet()

        assertThat(RecoveryJourney.currentStage(completed)).isNull()
        assertThat(RecoveryJourney.isJourneyComplete(completed)).isTrue()
        assertThat(RecoveryJourney.progress(completed)).isEqualTo(1f)
        assertThat(RecoveryJourney.statusOf(Stage.PROTECT, completed))
            .isEqualTo(Status.DONE)
    }

    @Test
    fun `a stage finished out of order does not skip the one before it`() {
        val completed = setOf(Stage.REWIRE, Stage.PROTECT)

        assertThat(RecoveryJourney.currentStage(completed)).isEqualTo(Stage.UNDERSTAND)
        assertThat(RecoveryJourney.statusOf(Stage.UNDERSTAND, completed))
            .isEqualTo(Status.CURRENT)
        assertThat(RecoveryJourney.statusOf(Stage.REWIRE, completed)).isEqualTo(Status.DONE)
        assertThat(RecoveryJourney.statusOf(Stage.PROTECT, completed)).isEqualTo(Status.DONE)
    }

    @Test
    fun `progress advances one third at a time`() {
        assertThat(RecoveryJourney.progress(emptySet())).isEqualTo(0f)
        assertThat(RecoveryJourney.progress(setOf(Stage.UNDERSTAND))).isEqualTo(1f / 3f)
        assertThat(RecoveryJourney.completedCount(setOf(Stage.UNDERSTAND))).isEqualTo(1)
    }
}
