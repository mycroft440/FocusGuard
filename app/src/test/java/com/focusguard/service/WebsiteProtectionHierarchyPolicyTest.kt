package com.focusguard.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebsiteProtectionHierarchyPolicyTest {
    @Test
    fun `strong website layer always outranks PASSWORD`() {
        val result = WebsiteProtectionHierarchyPolicy.resolve(
            candidate = "https://www.youtube.com/watch?v=1",
            passwordRules = setOf("youtube.com"),
            strongerRules = setOf("youtube.com")
        )

        assertThat(result.owner).isEqualTo(WebsiteProtectionHierarchyPolicy.Owner.HARD)
        assertThat(result.matchedRule).isEqualTo("youtube.com")
        assertThat(result.nextStep)
            .isEqualTo(WebsiteProtectionHierarchyPolicy.NextStep.REDIRECT_BLOCKED_TAB)
    }

    @Test
    fun `PASSWORD owns site while no stronger layer is active`() {
        val result = WebsiteProtectionHierarchyPolicy.resolve(
            candidate = "https://m.youtube.com/shorts/1",
            passwordRules = setOf("youtube.com"),
            strongerRules = emptySet()
        )

        assertThat(result.owner).isEqualTo(WebsiteProtectionHierarchyPolicy.Owner.PASSWORD)
        assertThat(result.matchedRule).isEqualTo("youtube.com")
        assertThat(result.nextStep)
            .isEqualTo(WebsiteProtectionHierarchyPolicy.NextStep.SHOW_PASSWORD_BLOCK)
    }

    @Test
    fun `unrelated rule has no hierarchy owner`() {
        val result = WebsiteProtectionHierarchyPolicy.resolve(
            candidate = "https://example.com",
            passwordRules = setOf("youtube.com"),
            strongerRules = setOf("reddit.com")
        )

        assertThat(result.owner).isEqualTo(WebsiteProtectionHierarchyPolicy.Owner.NONE)
        assertThat(result.nextStep).isEqualTo(WebsiteProtectionHierarchyPolicy.NextStep.ALLOW)
    }
}
