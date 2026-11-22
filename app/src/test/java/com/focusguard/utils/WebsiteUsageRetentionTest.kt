package com.focusguard.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebsiteUsageRetentionTest {

    @Test
    fun `keeps the retention window and drops only older days`() {
        assertThat(WebsiteUsageRetention.oldestDateToKeep("2026-03-03"))
            .isEqualTo("2025-12-31")
    }

    @Test
    fun `the cutoff compares as text like the stored dates`() {
        val cutoff = WebsiteUsageRetention.oldestDateToKeep("2026-11-22")!!

        // A consulta apaga `date < cutoff`, comparando o texto yyyy-MM-dd.
        assertThat("2026-09-20" < cutoff).isTrue()
        assertThat("2026-09-21" < cutoff).isFalse()
        assertThat("2026-11-22" < cutoff).isFalse()
    }

    @Test
    fun `an invalid date never deletes anything`() {
        assertThat(WebsiteUsageRetention.oldestDateToKeep("22/11/2026")).isNull()
    }
}
