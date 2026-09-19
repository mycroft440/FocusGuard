package com.focusguard.accessibility.website.blocking

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebsiteBlockDecisionPolicyTest {
    @Test
    fun `stronger protection wins when hard and password both match`() {
        val resolution = WebsiteBlockDecisionPolicy.resolve(
            candidate = "https://m.example.com/path",
            passwordRules = setOf("example.com"),
            strongerRules = setOf("example.com")
        )
        assertThat(resolution.owner).isEqualTo(WebsiteBlockDecisionPolicy.Owner.HARD)
        assertThat(resolution.matchedRule).isEqualTo("example.com")
    }

    @Test
    fun `password owns candidate when no stronger rule matches`() {
        val resolution = WebsiteBlockDecisionPolicy.resolve(
            candidate = "https://example.com/path",
            passwordRules = setOf("example.com"),
            strongerRules = setOf("other.example")
        )
        assertThat(resolution.owner).isEqualTo(WebsiteBlockDecisionPolicy.Owner.PASSWORD)
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
