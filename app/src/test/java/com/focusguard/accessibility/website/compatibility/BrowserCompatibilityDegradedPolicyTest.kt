package com.focusguard.accessibility.website.compatibility

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrowserCompatibilityDegradedPolicyTest {
    @Test
    fun `two redirect failures enter degraded rediscovery without disabling browser`() {
        assertThat(BrowserCompatibilityStore.isRedirectionDegraded(2)).isTrue()
        assertThat(
            BrowserCompatibilityStore.statusAfterRedirectionFailure(
                previousStatus = BrowserCompatibilityStatus.SUPPORTED,
                failures = 2
            )
        ).isEqualTo(BrowserCompatibilityStatus.SUPPORTED)
    }

    @Test
    fun `continued redirect failures eventually mark browser unsupported`() {
        assertThat(BrowserCompatibilityStore.isRedirectionDegraded(5)).isFalse()
        assertThat(
            BrowserCompatibilityStore.statusAfterRedirectionFailure(
                previousStatus = BrowserCompatibilityStatus.SUPPORTED,
                failures = 5
            )
        ).isEqualTo(BrowserCompatibilityStatus.UNSUPPORTED)
    }

    @Test
    fun `single transient redirect failure preserves previous status`() {
        assertThat(BrowserCompatibilityStore.isRedirectionDegraded(1)).isFalse()
        assertThat(
            BrowserCompatibilityStore.statusAfterRedirectionFailure(
                previousStatus = BrowserCompatibilityStatus.SUPPORTED,
                failures = 1
            )
        ).isEqualTo(BrowserCompatibilityStatus.SUPPORTED)
    }

    @Test
    fun `supported exact version remains strong browser evidence while rediscovery is allowed`() {
        val record = BrowserCompatibilityRecord(
            packageName = "example.browser",
            status = BrowserCompatibilityStatus.SUPPORTED,
            packageVersionCode = 42L,
            consecutiveRedirectionFailures = 2
        )

        assertThat(
            BrowserCompatibilityStore.hasStrongBrowserEvidenceForVersion(record, 42L)
        ).isTrue()
    }

    @Test
    fun `firefox discovery order starts with gecko entries`() {
        val entries = BrowserCompatibilityStore.prioritizeUrlEntryNames(
            packageName = "org.mozilla.firefox",
            defaults = listOf("url_bar", "mozac_browser_toolbar_url_view", "custom_url")
        )

        assertThat(entries.first()).isEqualTo("mozac_browser_toolbar_url_view")
        assertThat(entries).contains("url_bar")
        assertThat(entries).contains("custom_url")
    }

    @Test
    fun `brave discovery order starts with chromium entries`() {
        val entries = BrowserCompatibilityStore.prioritizeUrlEntryNames(
            packageName = "com.brave.browser",
            defaults = listOf("toolbar_url", "url_bar", "custom_url")
        )

        assertThat(entries.first()).isEqualTo("url_bar")
        assertThat(entries).contains("toolbar_url")
        assertThat(entries).contains("custom_url")
    }
}
