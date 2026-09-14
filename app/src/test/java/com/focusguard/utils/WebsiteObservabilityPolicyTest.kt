package com.focusguard.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebsiteObservabilityPolicyTest {
    @Test
    fun `opaque browser reaches observation fallback after grace while protection is active`() {
        assertThat(
            WebsiteObservabilityPolicy.shouldStopObservingOpaqueBrowser(
                websiteProtectionRequiresObservation = true,
                browserStillForeground = true,
                addressBarObservable = false,
                firstUnobservableElapsed = 1_000L,
                nowElapsed = 1_800L,
                graceMillis = 800L
            )
        ).isTrue()
    }

    @Test
    fun `observable browser and inactive protection never reach opaque fallback`() {
        assertThat(
            WebsiteObservabilityPolicy.shouldStopObservingOpaqueBrowser(
                websiteProtectionRequiresObservation = true,
                browserStillForeground = true,
                addressBarObservable = true,
                firstUnobservableElapsed = 1_000L,
                nowElapsed = 9_000L
            )
        ).isFalse()
        assertThat(
            WebsiteObservabilityPolicy.shouldStopObservingOpaqueBrowser(
                websiteProtectionRequiresObservation = false,
                browserStillForeground = true,
                addressBarObservable = false,
                firstUnobservableElapsed = 1_000L,
                nowElapsed = 9_000L
            )
        ).isFalse()
    }

    @Test
    fun `opaque browser gets its grace window before observation fallback`() {
        assertThat(
            WebsiteObservabilityPolicy.shouldStopObservingOpaqueBrowser(
                websiteProtectionRequiresObservation = true,
                browserStillForeground = true,
                addressBarObservable = false,
                firstUnobservableElapsed = 1_000L,
                nowElapsed = 1_799L,
                graceMillis = 800L
            )
        ).isFalse()
    }

    @Test
    fun `legacy block decision name keeps the same fallback threshold`() {
        assertThat(
            WebsiteObservabilityPolicy.shouldBlockOpaqueBrowser(
                websiteProtectionRequiresObservation = true,
                browserStillForeground = true,
                addressBarObservable = false,
                firstUnobservableElapsed = 1_000L,
                nowElapsed = 1_800L,
                graceMillis = 800L
            )
        ).isEqualTo(
            WebsiteObservabilityPolicy.shouldStopObservingOpaqueBrowser(
                websiteProtectionRequiresObservation = true,
                browserStillForeground = true,
                addressBarObservable = false,
                firstUnobservableElapsed = 1_000L,
                nowElapsed = 1_800L,
                graceMillis = 800L
            )
        )
    }
}
