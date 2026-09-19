package com.focusguard.accessibility.website.redirection

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebsiteRedirectionPlanTest {
    @Test
    fun `redirection layers follow the defensive same tab order`() {
        assertThat(WebsiteRedirectionPlan.orderedLayers).containsExactly(
            WebsiteRedirectionPhase.ACTIVATE_ADDRESS_BAR,
            WebsiteRedirectionPhase.REIDENTIFY_EDITOR,
            WebsiteRedirectionPhase.SELECT_ALL,
            WebsiteRedirectionPhase.SET_TEXT,
            WebsiteRedirectionPhase.PASTE_FALLBACK,
            WebsiteRedirectionPhase.REIDENTIFY_SUBMITTER,
            WebsiteRedirectionPhase.IME_ENTER,
            WebsiteRedirectionPhase.ANNOUNCED_EDITOR_ACTION,
            WebsiteRedirectionPhase.CERTIFIED_GO_BUTTON,
            WebsiteRedirectionPhase.BACK_AND_RETRY,
            WebsiteRedirectionPhase.VERIFY_DESTINATION,
            WebsiteRedirectionPhase.REDIRECT_CONFIRMED,
            WebsiteRedirectionPhase.FAIL_CLOSED
        ).inOrder()
    }

    @Test
    fun `only one same tab retry is allowed before fail closed`() {
        assertThat(WebsiteRedirectionPlan.MAX_SAME_TAB_ATTEMPTS).isEqualTo(2)
        assertThat(WebsiteRedirectionPlan.canRetry(1)).isTrue()
        assertThat(WebsiteRedirectionPlan.canRetry(2)).isFalse()
    }

}
