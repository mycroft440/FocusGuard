package com.focusguard.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebsiteProtectionHierarchyPolicyTest {
    @Test
    fun `configured stronger and password rules have no owner while runtime is reset`() {
        val result = WebsiteProtectionHierarchyPolicy.resolve(
            candidate = "https://www.youtube.com/watch?v=1",
            passwordRules = setOf("youtube.com"),
            strongerRules = setOf("youtube.com")
        )

        assertThat(result.owner).isEqualTo(WebsiteProtectionHierarchyPolicy.Owner.NONE)
        assertThat(result.matchedRule).isNull()
        assertThat(result.nextStep).isEqualTo(WebsiteProtectionHierarchyPolicy.NextStep.ALLOW)
    }

    @Test
    fun `configured password rule has no owner while runtime is reset`() {
        val result = WebsiteProtectionHierarchyPolicy.resolve(
            candidate = "https://m.youtube.com/shorts/1",
            passwordRules = setOf("youtube.com"),
            strongerRules = emptySet()
        )

        assertThat(result.owner).isEqualTo(WebsiteProtectionHierarchyPolicy.Owner.NONE)
        assertThat(result.matchedRule).isNull()
        assertThat(result.nextStep).isEqualTo(WebsiteProtectionHierarchyPolicy.NextStep.ALLOW)
    }

    @Test
    fun `unrelated rule has no hierarchy owner`() {
        val result = WebsiteProtectionHierarchyPolicy.resolve(
            candidate = "https://example.com",
            passwordRules = setOf("youtube.com"),
            strongerRules = setOf("reddit.com")
        )

        assertThat(result.owner).isEqualTo(WebsiteProtectionHierarchyPolicy.Owner.NONE)
        assertThat(result.matchedRule).isNull()
        assertThat(result.nextStep).isEqualTo(WebsiteProtectionHierarchyPolicy.NextStep.ALLOW)
    }
}
