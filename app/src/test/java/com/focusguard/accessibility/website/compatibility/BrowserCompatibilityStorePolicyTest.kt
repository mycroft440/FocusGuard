package com.focusguard.accessibility.website.compatibility

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrowserCompatibilityStorePolicyTest {
    @Test
    fun `unsupported package without browser evidence is not retained`() {
        val record = BrowserCompatibilityRecord(
            packageName = "com.android.systemui",
            status = BrowserCompatibilityStatus.UNSUPPORTED,
            consecutiveObservationFailures = 4
        )

        assertThat(
            BrowserCompatibilityStore.shouldRetainRecord(
                record = record,
                verifiedHttpsHandlers = emptySet()
            )
        ).isFalse()
    }

    @Test
    fun `verified https handler remains eligible even when unsupported`() {
        val record = BrowserCompatibilityRecord(
            packageName = "com.brave.browser",
            status = BrowserCompatibilityStatus.UNSUPPORTED,
            consecutiveObservationFailures = 4
        )

        assertThat(
            BrowserCompatibilityStore.shouldRetainRecord(
                record = record,
                verifiedHttpsHandlers = setOf("com.brave.browser")
            )
        ).isTrue()
    }

    @Test
    fun `dynamically identified browser remains eligible without https handler registration`() {
        val record = BrowserCompatibilityRecord(
            packageName = "example.webview.browser",
            status = BrowserCompatibilityStatus.SUPPORTED,
            preferredAddressBarEntryName = "url_bar",
            identificationMethod = BrowserIdentificationMethod.SEMANTIC_TREE
        )

        assertThat(
            BrowserCompatibilityStore.shouldRetainRecord(
                record = record,
                verifiedHttpsHandlers = emptySet()
            )
        ).isTrue()
    }
}
