package com.focusguard.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebsiteObservabilityPolicyTest {
    @Test
    fun `opaque browser fails closed after grace while protection is active`() {
        assertThat(
            WebsiteObservabilityPolicy.shouldBlockOpaqueBrowser(
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
    fun `observable browser and inactive protection never fail closed`() {
        assertThat(
            WebsiteObservabilityPolicy.shouldBlockOpaqueBrowser(
                websiteProtectionRequiresObservation = true,
                browserStillForeground = true,
                addressBarObservable = true,
                firstUnobservableElapsed = 1_000L,
                nowElapsed = 9_000L
            )
        ).isFalse()
        assertThat(
            WebsiteObservabilityPolicy.shouldBlockOpaqueBrowser(
                websiteProtectionRequiresObservation = false,
                browserStillForeground = true,
                addressBarObservable = false,
                firstUnobservableElapsed = 1_000L,
                nowElapsed = 9_000L
            )
        ).isFalse()
    }

    @Test
    fun `opaque browser gets its grace window before blocking`() {
        assertThat(
            WebsiteObservabilityPolicy.shouldBlockOpaqueBrowser(
                websiteProtectionRequiresObservation = true,
                browserStillForeground = true,
                addressBarObservable = false,
                firstUnobservableElapsed = 1_000L,
                nowElapsed = 1_799L,
                graceMillis = 800L
            )
        ).isFalse()
    }
}
