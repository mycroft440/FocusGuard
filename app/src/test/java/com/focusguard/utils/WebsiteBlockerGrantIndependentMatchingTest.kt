package com.focusguard.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebsiteBlockerGrantIndependentMatchingTest {
    @Test
    fun `accounting matcher still sees configured rule independently of visit grants`() {
        assertThat(
            WebsiteBlocker.findMatchingRulesIgnoringGrants(
                "https://m.youtube.com/watch?v=abc",
                setOf("youtube.com")
            )
        ).containsExactly("youtube.com")
    }

    @Test
    fun `accounting matcher preserves parent domain coverage`() {
        assertThat(
            WebsiteBlocker.findMatchingRulesIgnoringGrants(
                "https://news.example.com/article",
                setOf("example.com")
            )
        ).containsExactly("example.com")
    }
}
