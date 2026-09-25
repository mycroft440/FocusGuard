package com.focusguard.security

import com.focusguard.data.PredefinedWebsites
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Sites e palavras-chave saíram dos bloqueios: o bloqueio de sites tem lista própria. */
class BlockTargetPolicyTest {

    @Test
    fun `every block type targets apps only`() {
        listOf("PASSWORD", "time", "POMODORO", "other").forEach { type ->
            val kinds = BlockTargetPolicy.forSessionType(type)
            assertThat(kinds.apps).isTrue()
            assertThat(kinds.websites).isFalse()
            assertThat(kinds.keywords).isFalse()
            assertThat(kinds.needsTabs).isFalse()
        }
        assertThat(BlockTargetPolicy.DAILY_LIMIT).isEqualTo(BlockTargetPolicy.APPS_ONLY)
    }

    @Test
    fun `no website keyword or category rule is ever persisted`() {
        val rules = listOf("example.com", "keyword:casino", PredefinedWebsites.PORNOGRAPHY_RULE)

        assertThat(BlockTargetPolicy.acceptedRulesForSessionType("TIME", rules)).isEmpty()
        assertThat(BlockTargetPolicy.acceptedRulesForSessionType("PASSWORD", rules)).isEmpty()
        assertThat(BlockTargetPolicy.acceptedRules(BlockTargetPolicy.DAILY_LIMIT, rules)).isEmpty()
    }
}
