package com.focusguard.security

import com.focusguard.service.BlockingAccessibilityService
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

/** A liberação de um site por senha vale para uma visita num navegador, sem prazo. */
class PasswordWebsiteVisitTest {
    private val chrome = "com.android.chrome"
    private val firefox = "org.mozilla.firefox"

    @After
    fun tearDown() = PasswordTargetAccessGrant.clear()

    @Test
    fun `the visit stays open while the same browser is in front`() {
        PasswordTargetAccessGrant.publishWebsiteGrant("youtube.com", chrome)

        PasswordTargetAccessGrant.onWebsiteBrowserObserved(chrome)
        PasswordTargetAccessGrant.endWebsiteVisitsOnForeground(chrome, foregroundIsBrowser = true)

        assertThat(PasswordTargetAccessGrant.isWebsiteRuleGranted("youtube.com")).isTrue()
    }

    @Test
    fun `another browser showing a page ends the visit`() {
        PasswordTargetAccessGrant.publishWebsiteGrant("youtube.com", chrome)

        PasswordTargetAccessGrant.onWebsiteBrowserObserved(firefox)

        assertThat(PasswordTargetAccessGrant.isWebsiteRuleGranted("youtube.com")).isFalse()
    }

    @Test
    fun `leaving the browser for the home screen or another app ends the visit`() {
        PasswordTargetAccessGrant.publishWebsiteGrant("youtube.com", chrome)

        PasswordTargetAccessGrant.endWebsiteVisitsOnForeground(
            "com.google.android.apps.nexuslauncher",
            foregroundIsBrowser = false
        )

        assertThat(PasswordTargetAccessGrant.isWebsiteRuleGranted("youtube.com")).isFalse()
    }

    @Test
    fun `a grant without a known browser binds to the first browser seen`() {
        PasswordTargetAccessGrant.publishWebsiteGrant("youtube.com", null)

        PasswordTargetAccessGrant.onWebsiteBrowserObserved(chrome)
        assertThat(PasswordTargetAccessGrant.isWebsiteRuleGranted("youtube.com")).isTrue()

        PasswordTargetAccessGrant.onWebsiteBrowserObserved(firefox)
        assertThat(PasswordTargetAccessGrant.isWebsiteRuleGranted("youtube.com")).isFalse()
    }

    @Test
    fun `notification shade, share sheet and own screens do not end the visit`() {
        val own = "com.focusguard"
        listOf(
            "com.android.systemui",
            "android",
            "com.android.intentresolver",
            "com.google.android.gms",
            own
        ).forEach { pkg ->
            assertThat(BlockingAccessibilityService.shouldEndWebsiteVisitsFor(pkg, own)).isFalse()
        }
        assertThat(
            BlockingAccessibilityService.shouldEndWebsiteVisitsFor("com.whatsapp", own)
        ).isTrue()
    }

    @Test
    fun `toasts and popups never count as leaving the browser`() {
        assertThat(BlockingAccessibilityService.isTransientWindowClass("android.widget.Toast\$TN"))
            .isTrue()
        assertThat(BlockingAccessibilityService.isTransientWindowClass("android.widget.PopupWindow"))
            .isTrue()
        assertThat(BlockingAccessibilityService.isTransientWindowClass("com.whatsapp.HomeActivity"))
            .isFalse()
    }
}
