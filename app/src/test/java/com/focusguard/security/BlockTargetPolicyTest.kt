package com.focusguard.security

import com.focusguard.data.PredefinedWebsites
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BlockTargetPolicyTest {

    @Test
    fun `password blocks protect apps only`() {
        val kinds = BlockTargetPolicy.forSessionType("PASSWORD")

        assertThat(kinds.apps).isTrue()
        assertThat(kinds.websites).isFalse()
        assertThat(kinds.keywords).isFalse()
        assertThat(kinds.needsTabs).isFalse()
    }

    @Test
    fun `time blocks accept apps sites and keywords`() {
        val kinds = BlockTargetPolicy.forSessionType("time")

        assertThat(kinds.apps).isTrue()
        assertThat(kinds.websites).isTrue()
        assertThat(kinds.keywords).isTrue()
        assertThat(kinds.needsTabs).isTrue()
    }

    @Test
    fun `daily limits take sites but never keywords`() {
        assertThat(BlockTargetPolicy.DAILY_LIMIT.websites).isTrue()
        assertThat(BlockTargetPolicy.DAILY_LIMIT.keywords).isFalse()
    }

    @Test
    fun `unknown session types fall back to apps only`() {
        assertThat(BlockTargetPolicy.forSessionType("SOMETHING_NEW"))
            .isEqualTo(BlockTargetPolicy.APPS_ONLY)
    }

    @Test
    fun `a password session drops every site and keyword rule`() {
        val accepted = BlockTargetPolicy.acceptedRulesForSessionType(
            sessionType = "PASSWORD",
            rules = listOf("youtube.com", "porn", PredefinedWebsites.PORNOGRAPHY_RULE)
        )

        assertThat(accepted).isEmpty()
    }

    @Test
    fun `a time session keeps sites keywords and the pornography category`() {
        val accepted = BlockTargetPolicy.acceptedRulesForSessionType(
            sessionType = "TIME",
            rules = listOf(
                "HTTPS://WWW.YouTube.com/watch?v=1",
                "aposta",
                PredefinedWebsites.PORNOGRAPHY_RULE
            )
        )

        assertThat(accepted).containsExactly(
            "youtube.com",
            "keyword:aposta",
            PredefinedWebsites.PORNOGRAPHY_RULE
        )
    }

    @Test
    fun `a daily limit keeps domains and drops keywords`() {
        val accepted = BlockTargetPolicy.acceptedRules(
            kinds = BlockTargetPolicy.DAILY_LIMIT,
            rules = listOf("youtube.com", "aposta")
        )

        assertThat(accepted).containsExactly("youtube.com")
    }

    @Test
    fun `invalid rules are dropped rather than stored raw`() {
        val accepted = BlockTargetPolicy.acceptedRulesForSessionType(
            sessionType = "TIME",
            rules = listOf("  ", "ab", "duas palavras", "category:unknown")
        )

        assertThat(accepted).isEmpty()
    }
}
