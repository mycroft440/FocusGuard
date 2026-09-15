package com.focusguard.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebsiteObservabilityPolicyTest {
    @Test
    fun `opaque browser fails closed after grace while website protection is active`() {
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
    fun `observable browser never enters opaque fail closed fallback`() {
        assertThat(
            WebsiteObservabilityPolicy.shouldBlockOpaqueBrowser(
                websiteProtectionRequiresObservation = true,
                browserStillForeground = true,
                addressBarObservable = true,
                firstUnobservableElapsed = 1_000L,
                nowElapsed = 9_000L
            )
        ).isFalse()
    }

    @Test
    fun `inactive protection or background browser never enters opaque fallback`() {
        assertThat(
            WebsiteObservabilityPolicy.shouldBlockOpaqueBrowser(
                websiteProtectionRequiresObservation = false,
                browserStillForeground = true,
                addressBarObservable = false,
                firstUnobservableElapsed = 1_000L,
                nowElapsed = 9_000L
            )
        ).isFalse()
        assertThat(
            WebsiteObservabilityPolicy.shouldBlockOpaqueBrowser(
                websiteProtectionRequiresObservation = true,
                browserStillForeground = false,
                addressBarObservable = false,
                firstUnobservableElapsed = 1_000L,
                nowElapsed = 9_000L
            )
        ).isFalse()
    }

    @Test
    fun `opaque browser receives grace before fail closed fallback`() {
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

    @Test
    fun `legacy observation alias keeps the same fail closed threshold`() {
        assertThat(
            WebsiteObservabilityPolicy.shouldStopObservingOpaqueBrowser(
                websiteProtectionRequiresObservation = true,
                browserStillForeground = true,
                addressBarObservable = false,
                firstUnobservableElapsed = 1_000L,
                nowElapsed = 1_800L,
                graceMillis = 800L
            )
        ).isEqualTo(
            WebsiteObservabilityPolicy.shouldBlockOpaqueBrowser(
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
