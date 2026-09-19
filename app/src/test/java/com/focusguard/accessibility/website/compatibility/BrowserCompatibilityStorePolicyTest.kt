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

    @Test
    fun `identified url on exact installed version is strong browser evidence`() {
        val record = BrowserCompatibilityRecord(
            packageName = "example.browser",
            packageVersionCode = 42L,
            preferredUrlEntryName = "url_bar",
            preferredUrlMethod = BrowserIdentificationMethod.STRONG_RESOURCE_ID
        )

        assertThat(
            BrowserCompatibilityStore.hasStrongBrowserEvidenceForVersion(record, 42L)
        ).isTrue()
    }

    @Test
    fun `successful bounded url recovery on exact version is strong evidence`() {
        val record = BrowserCompatibilityRecord(
            packageName = "example.browser",
            packageVersionCode = 42L,
            urlRecoveryMethod = BrowserUrlRecoveryMethod.CLICK
        )

        assertThat(
            BrowserCompatibilityStore.hasStrongBrowserEvidenceForVersion(record, 42L)
        ).isTrue()
    }

    @Test
    fun `confirmed navigation on exact version is strong evidence`() {
        val record = BrowserCompatibilityRecord(
            packageName = "example.browser",
            status = BrowserCompatibilityStatus.SUPPORTED,
            packageVersionCode = 42L
        )

        assertThat(
            BrowserCompatibilityStore.hasStrongBrowserEvidenceForVersion(record, 42L)
        ).isTrue()
    }

    @Test
    fun `evidence from older browser version never promotes current version`() {
        val record = BrowserCompatibilityRecord(
            packageName = "example.browser",
            status = BrowserCompatibilityStatus.SUPPORTED,
            packageVersionCode = 41L,
            preferredUrlEntryName = "url_bar",
            preferredUrlMethod = BrowserIdentificationMethod.STRONG_RESOURCE_ID
        )

        assertThat(
            BrowserCompatibilityStore.hasStrongBrowserEvidenceForVersion(record, 42L)
        ).isFalse()
    }

    @Test
    fun `legacy record without version cannot promote an inconclusive package query`() {
        val record = BrowserCompatibilityRecord(
            packageName = "example.browser",
            status = BrowserCompatibilityStatus.SUPPORTED,
            preferredUrlEntryName = "url_bar",
            preferredUrlMethod = BrowserIdentificationMethod.STRONG_RESOURCE_ID
        )

        assertThat(
            BrowserCompatibilityStore.hasStrongBrowserEvidenceForVersion(record, 42L)
        ).isFalse()
    }

    @Test
    fun `submit preference is learned only after confirmed navigation`() {
        val packageName = "example.deferred.submit.browser"
        BrowserCompatibilityStore.recordWriteSuccess(
            packageName,
            "$packageName:id/url_bar",
            BrowserWriteMethod.SET_TEXT,
            "https://www.google.com"
        )
        BrowserCompatibilityStore.recordSubmitAccepted(
            packageName,
            "$packageName:id/url_bar",
            BrowserSubmitMethod.IME_ENTER
        )

        assertThat(BrowserCompatibilityStore.preferredSubmitMethod(packageName)).isNull()
        BrowserCompatibilityStore.recordNavigationConfirmed(packageName)
        assertThat(BrowserCompatibilityStore.preferredSubmitMethod(packageName))
            .isEqualTo(BrowserSubmitMethod.IME_ENTER)
        BrowserCompatibilityStore.finishRedirection(packageName)
    }

    @Test
    fun `accepted submit without navigation is skipped on retry for same target`() {
        val packageName = "example.submit.retry.browser"
        BrowserCompatibilityStore.recordWriteSuccess(
            packageName,
            "$packageName:id/url_bar",
            BrowserWriteMethod.SET_TEXT,
            "https://www.google.com"
        )
        assertThat(
            BrowserCompatibilityStore.mayAttemptSubmitMethod(
                packageName,
                BrowserSubmitMethod.IME_ENTER
            )
        ).isTrue()

        BrowserCompatibilityStore.recordSubmitAccepted(
            packageName,
            "$packageName:id/url_bar",
            BrowserSubmitMethod.IME_ENTER
        )
        assertThat(
            BrowserCompatibilityStore.mayAttemptSubmitMethod(
                packageName,
                BrowserSubmitMethod.IME_ENTER
            )
        ).isFalse()
        assertThat(
            BrowserCompatibilityStore.mayAttemptSubmitMethod(
                packageName,
                BrowserSubmitMethod.ANNOUNCED_EDITOR_ACTION
            )
        ).isTrue()

        // The retry writes the same certified target again. That must not erase the
        // fact that IME_ENTER already accepted without producing navigation.
        BrowserCompatibilityStore.recordWriteSuccess(
            packageName,
            "$packageName:id/url_bar",
            BrowserWriteMethod.SET_TEXT,
            "https://www.google.com"
        )
        assertThat(
            BrowserCompatibilityStore.mayAttemptSubmitMethod(
                packageName,
                BrowserSubmitMethod.IME_ENTER
            )
        ).isFalse()
        BrowserCompatibilityStore.finishRedirection(packageName)
    }

    @Test
    fun `submit attempt history resets for a different redirect target`() {
        val packageName = "example.submit.target.browser"
        BrowserCompatibilityStore.recordWriteSuccess(
            packageName,
            "$packageName:id/url_bar",
            BrowserWriteMethod.SET_TEXT,
            "https://www.google.com"
        )
        BrowserCompatibilityStore.recordSubmitAccepted(
            packageName,
            "$packageName:id/url_bar",
            BrowserSubmitMethod.IME_ENTER
        )
        assertThat(
            BrowserCompatibilityStore.mayAttemptSubmitMethod(
                packageName,
                BrowserSubmitMethod.IME_ENTER
            )
        ).isFalse()

        BrowserCompatibilityStore.recordWriteSuccess(
            packageName,
            "$packageName:id/url_bar",
            BrowserWriteMethod.SET_TEXT,
            "https://google.com/search?q=focus"
        )
        assertThat(
            BrowserCompatibilityStore.mayAttemptSubmitMethod(
                packageName,
                BrowserSubmitMethod.IME_ENTER
            )
        ).isTrue()
        BrowserCompatibilityStore.finishRedirection(packageName)
    }

    @Test
    fun `weak activation evidence alone never promotes browser`() {
        val record = BrowserCompatibilityRecord(
            packageName = "example.browser",
            packageVersionCode = 42L,
            preferredAddressBarEntryName = "url_bar",
            activationMethod = BrowserActivationMethod.CLICK
        )

        assertThat(
            BrowserCompatibilityStore.hasStrongBrowserEvidenceForVersion(record, 42L)
        ).isFalse()
    }
}
