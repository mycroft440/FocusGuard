package com.focusguard.accessibility.website.redirection

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebsiteTabNeutralizationPolicyTest {
    @Test
    fun `same window state event after phase start does not invalidate activation`() {
        val policy = WebsiteTabNeutralizationPolicy(
            browserPackageName = "com.brave.browser",
            expectedWindowId = 41
        )

        assertThat(
            policy.mayActivateBlockedAddressBar(
                activePackageName = "com.brave.browser",
                activeWindowId = 41,
                phaseStartedAtUptimeMillis = 100L,
                latestWindowTransitionEventUptimeMillis = 150L
            )
        ).isTrue()
    }

    @Test
    fun `same window state event after text replacement does not invalidate submit`() {
        val policy = WebsiteTabNeutralizationPolicy(
            browserPackageName = "com.android.chrome",
            expectedWindowId = 9
        )
        policy.markSafeAddressSet(200L)

        assertThat(
            policy.maySubmitSafeAddress(
                activePackageName = "com.android.chrome",
                activeWindowId = 9,
                latestWindowTransitionEventUptimeMillis = 260L
            )
        ).isTrue()
    }

    @Test
    fun `different accessibility window still cannot be touched`() {
        val policy = WebsiteTabNeutralizationPolicy(
            browserPackageName = "com.android.chrome",
            expectedWindowId = 9
        )

        assertThat(
            policy.mayActivateBlockedAddressBar(
                activePackageName = "com.android.chrome",
                activeWindowId = 10,
                phaseStartedAtUptimeMillis = 100L,
                latestWindowTransitionEventUptimeMillis = 150L
            )
        ).isFalse()

        policy.markSafeAddressSet(200L)
        assertThat(
            policy.maySubmitSafeAddress(
                activePackageName = "com.android.chrome",
                activeWindowId = 10,
                latestWindowTransitionEventUptimeMillis = 260L
            )
        ).isFalse()
    }
}
