package com.focusguard.accessibility.website.identification

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebsiteIdentificationFailClosedPolicyTest {
    @Test
    fun `unobservable foreground browser fails closed after grace`() {
        val unidentified = WebsiteIdentificationResult(
            status = WebsiteIdentificationStatus.UNOBSERVABLE,
            evidence = setOf(WebsiteIdentificationLayer.FAIL_CLOSED),
            webContentObserved = true
        )

        assertThat(
            WebsiteIdentificationFailClosedPolicy.shouldBlock(
                websiteProtectionRequiresObservation = true,
                browserStillForeground = true,
                identification = unidentified,
                firstUnobservableElapsed = 1_000L,
                nowElapsed = 1_800L,
                graceMillis = 800L
            )
        ).isTrue()
    }

    @Test
    fun `unobservable surface without proven web content does not fail closed`() {
        val unidentified = WebsiteIdentificationResult(
            status = WebsiteIdentificationStatus.UNOBSERVABLE,
            evidence = setOf(WebsiteIdentificationLayer.FAIL_CLOSED),
            webContentObserved = false
        )

        assertThat(
            WebsiteIdentificationFailClosedPolicy.shouldBlock(
                websiteProtectionRequiresObservation = true,
                browserStillForeground = true,
                identification = unidentified,
                firstUnobservableElapsed = 1_000L,
                nowElapsed = 1_800L,
                graceMillis = 800L
            )
        ).isFalse()
    }

    @Test
    fun `observable address bar never enters opaque fail closed fallback`() {
        val observable = WebsiteIdentificationResult(
            status = WebsiteIdentificationStatus.ADDRESS_BAR_OBSERVABLE,
            browserPackageName = "com.example.browser",
            windowId = 7,
            evidence = setOf(
                WebsiteIdentificationLayer.BROWSER_PACKAGE_AND_WINDOW,
                WebsiteIdentificationLayer.STRONG_ADDRESS_BAR_ID
            )
        )

        assertThat(
            WebsiteIdentificationFailClosedPolicy.shouldBlock(
                websiteProtectionRequiresObservation = true,
                browserStillForeground = true,
                identification = observable,
                firstUnobservableElapsed = 1_000L,
                nowElapsed = 9_000L,
                graceMillis = 800L
            )
        ).isFalse()
    }
}
