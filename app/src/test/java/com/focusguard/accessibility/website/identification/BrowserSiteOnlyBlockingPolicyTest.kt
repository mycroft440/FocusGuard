package com.focusguard.accessibility.website.identification

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrowserSiteOnlyBlockingPolicyTest {
    @Test
    fun `supported browsers stay accessible when URL recovery ends unobservable`() {
        val opaqueWebSurface = WebsiteIdentificationResult(
            status = WebsiteIdentificationStatus.UNOBSERVABLE,
            webContentObserved = true
        )

        listOf(
            "com.android.chrome",
            "mark.via.gp",
            "com.yandex.browser",
            "com.brave.browser"
        ).forEach { packageName ->
            val adjusted = BrowserSiteOnlyBlockingPolicy.applyAfterRecovery(
                packageName = packageName,
                result = opaqueWebSurface
            )

            assertThat(adjusted.status).isEqualTo(WebsiteIdentificationStatus.UNOBSERVABLE)
            assertThat(adjusted.webContentObserved).isFalse()
            assertThat(adjusted.bestCandidate).isNull()
        }
    }

    @Test
    fun `unknown browser keeps opaque fail closed evidence`() {
        val opaqueWebSurface = WebsiteIdentificationResult(
            status = WebsiteIdentificationStatus.UNOBSERVABLE,
            webContentObserved = true
        )

        val adjusted = BrowserSiteOnlyBlockingPolicy.applyAfterRecovery(
            packageName = "org.example.opaque.browser",
            result = opaqueWebSurface
        )

        assertThat(adjusted).isEqualTo(opaqueWebSurface)
        assertThat(adjusted.webContentObserved).isTrue()
    }

    @Test
    fun `positive blocked candidate evidence is never downgraded`() {
        val identified = WebsiteIdentificationResult(
            status = WebsiteIdentificationStatus.IDENTIFIED,
            rawAddressText = "https://facebook.com/feed",
            urlCandidate = "https://facebook.com/feed",
            webContentObserved = true
        )

        val adjusted = BrowserSiteOnlyBlockingPolicy.applyAfterRecovery(
            packageName = "com.android.chrome",
            result = identified
        )

        assertThat(adjusted).isEqualTo(identified)
        assertThat(adjusted.bestCandidate).isEqualTo("https://facebook.com/feed")
        assertThat(adjusted.webContentObserved).isTrue()
    }
}
