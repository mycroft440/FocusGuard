package com.focusguard.accessibility.website.blocking

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebsiteBlockDecisionPolicyTest {
    @Test
    fun `configured hard and password rules have no owner while runtime is reset`() {
        val resolution = WebsiteBlockDecisionPolicy.resolve(
            candidate = "https://m.example.com/path",
            passwordRules = setOf("example.com"),
            strongerRules = setOf("example.com")
        )
        assertThat(resolution.owner).isEqualTo(WebsiteBlockDecisionPolicy.Owner.NONE)
        assertThat(resolution.matchedRule).isNull()
    }

    @Test
    fun `configured password rule has no owner while runtime is reset`() {
        val resolution = WebsiteBlockDecisionPolicy.resolve(
            candidate = "https://example.com/path",
            passwordRules = setOf("example.com"),
            strongerRules = setOf("other.example")
        )
        assertThat(resolution.owner).isEqualTo(WebsiteBlockDecisionPolicy.Owner.NONE)
        assertThat(resolution.matchedRule).isNull()
    }

    @Test
    fun `unmatched candidate has no owner`() {
        val resolution = WebsiteBlockDecisionPolicy.resolve(
            candidate = "https://allowed.example/",
            passwordRules = setOf("locked.example"),
            strongerRules = setOf("hard.example")
        )
        assertThat(resolution.owner).isEqualTo(WebsiteBlockDecisionPolicy.Owner.NONE)
        assertThat(resolution.matchedRule).isNull()
    }
}
