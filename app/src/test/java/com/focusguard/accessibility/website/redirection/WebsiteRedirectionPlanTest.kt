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

    @Test
    fun `submit alternatives are independent from whole redirect attempts`() {
        assertThat(WebsiteRedirectionPlan.MAX_SUBMIT_ALTERNATIVES).isEqualTo(3)
    }

    @Test
    fun `website curtain budget exceeds all confirmation windows`() {
        val confirmationBudget =
            WebsiteRedirectionPlan.MAX_SAME_TAB_ATTEMPTS *
                WebsiteRedirectionPlan.MAX_SUBMIT_ALTERNATIVES *
                WebsiteRedirectionPlan.DESTINATION_CONFIRM_TIMEOUT_MILLIS

        val fullRedirectConfirmationBudget =
            confirmationBudget + WebsiteRedirectionPlan.DESTINATION_CONFIRM_TIMEOUT_MILLIS

        assertThat(WebsiteRedirectionPlan.CURTAIN_FAILSAFE_MILLIS)
            .isGreaterThan(fullRedirectConfirmationBudget)
        assertThat(WebsiteRedirectionPlan.CURTAIN_FAILSAFE_MILLIS).isGreaterThan(5_000L)
    }
}
