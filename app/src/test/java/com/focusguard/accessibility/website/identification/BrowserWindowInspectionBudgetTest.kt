package com.focusguard.accessibility.website.identification

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrowserWindowInspectionBudgetTest {

    @Test
    fun `shared budget stops each remote access category at its cap`() {
        var nowNanos = 0L
        val budget = BrowserWindowInspection.Budget(
            maxNodeVisits = 2,
            maxChildReads = 1,
            maxIdLookups = 1,
            maxDurationMillis = 100L,
            clockNanos = { nowNanos }
        )

        assertThat(budget.tryLookupId()).isTrue()
        assertThat(budget.tryVisitNode()).isTrue()
        assertThat(budget.tryVisitNode()).isTrue()
        assertThat(budget.tryReadChild()).isTrue()
        assertThat(budget.tryVisitNode()).isFalse()

        val metrics = budget.metrics()
        assertThat(metrics.idLookups).isEqualTo(1)
        assertThat(metrics.nodeVisits).isEqualTo(2)
        assertThat(metrics.childReads).isEqualTo(1)
        assertThat(metrics.exhausted).isTrue()
    }

    @Test
    fun `deadline prevents a new binder facing operation after it expires`() {
        var nowNanos = 10L
        val budget = BrowserWindowInspection.Budget(
            maxNodeVisits = 10,
            maxChildReads = 10,
            maxIdLookups = 10,
            maxDurationMillis = 2L,
            clockNanos = { nowNanos }
        )

        assertThat(budget.tryVisitNode()).isTrue()
        nowNanos += 2_000_000L

        assertThat(budget.tryReadChild()).isFalse()
        assertThat(budget.canContinue()).isFalse()
        assertThat(budget.metrics().exhausted).isTrue()
    }
}
