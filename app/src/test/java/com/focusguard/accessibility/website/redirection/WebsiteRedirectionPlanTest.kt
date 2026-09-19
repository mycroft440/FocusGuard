package com.focusguard.accessibility.website.redirection

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebsiteRedirectionPlanTest {
    @Test
    fun `only one same tab retry is allowed before fail closed`() {
        assertThat(WebsiteRedirectionPlan.MAX_SAME_TAB_ATTEMPTS).isEqualTo(2)
        assertThat(WebsiteRedirectionPlan.canRetry(1)).isTrue()
        assertThat(WebsiteRedirectionPlan.canRetry(2)).isFalse()
    }
}
