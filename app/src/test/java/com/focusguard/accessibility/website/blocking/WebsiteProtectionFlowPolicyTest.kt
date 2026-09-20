package com.focusguard.accessibility.website.blocking

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebsiteProtectionFlowPolicyTest {
    @Test
    fun `configured hard candidate is allowed while website runtime is reset`() {
        val decision = WebsiteProtectionFlowPolicy.afterIdentification(
            identifiedCandidate = "https://www.youtube.com/watch?v=1",
            passwordRules = emptySet(),
            strongerRules = setOf("youtube.com")
        )

        assertThat(decision.owner).isEqualTo(WebsiteBlockDecisionPolicy.Owner.NONE)
        assertThat(decision.matchedRule).isNull()
        assertThat(decision.nextStep).isEqualTo(WebsiteProtectionFlowPolicy.NextStep.ALLOW)
    }

    @Test
    fun `configured password candidate is allowed while website runtime is reset`() {
        val decision = WebsiteProtectionFlowPolicy.afterIdentification(
            identifiedCandidate = "reddit.com/r/android",
            passwordRules = setOf("reddit.com"),
            strongerRules = emptySet()
        )

        assertThat(decision.owner).isEqualTo(WebsiteBlockDecisionPolicy.Owner.NONE)
        assertThat(decision.matchedRule).isNull()
        assertThat(decision.nextStep).isEqualTo(WebsiteProtectionFlowPolicy.NextStep.ALLOW)
    }

    @Test
    fun `candidate configured in both layers is allowed while website runtime is reset`() {
        val decision = WebsiteProtectionFlowPolicy.afterIdentification(
            identifiedCandidate = "https://example.com/path",
            passwordRules = setOf("example.com"),
            strongerRules = setOf("example.com")
        )

        assertThat(decision.owner).isEqualTo(WebsiteBlockDecisionPolicy.Owner.NONE)
        assertThat(decision.matchedRule).isNull()
        assertThat(decision.nextStep).isEqualTo(WebsiteProtectionFlowPolicy.NextStep.ALLOW)
    }

    @Test
    fun `unmatched identified candidate is allowed`() {
        val decision = WebsiteProtectionFlowPolicy.afterIdentification(
            identifiedCandidate = "https://openai.com/",
            passwordRules = setOf("reddit.com"),
            strongerRules = setOf("youtube.com")
        )

        assertThat(decision.owner).isEqualTo(WebsiteBlockDecisionPolicy.Owner.NONE)
        assertThat(decision.matchedRule).isNull()
        assertThat(decision.nextStep).isEqualTo(WebsiteProtectionFlowPolicy.NextStep.ALLOW)
    }

    @Test
    fun `blank candidate never enters redirect pipeline`() {
        val decision = WebsiteProtectionFlowPolicy.afterIdentification(
            identifiedCandidate = "   ",
            passwordRules = setOf("example.com"),
            strongerRules = setOf("example.com")
        )

        assertThat(decision.owner).isEqualTo(WebsiteBlockDecisionPolicy.Owner.NONE)
        assertThat(decision.matchedRule).isNull()
        assertThat(decision.nextStep).isEqualTo(WebsiteProtectionFlowPolicy.NextStep.ALLOW)
    }
}
