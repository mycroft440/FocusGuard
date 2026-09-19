package com.focusguard.accessibility.website.compatibility

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrowserProfileRegistryTest {
    @Test
    fun `known chromium products share chromium family`() {
        val packages = listOf(
            "com.android.chrome",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.vivaldi.browser",
            "com.opera.browser",
            "com.kiwibrowser.browser",
            "com.ecosia.android",
            "com.sec.android.app.sbrowser",
            "com.yandex.browser"
        )

        packages.forEach { packageName ->
            assertThat(BrowserProfileRegistry.familyFor(packageName))
                .isEqualTo(BrowserFamily.CHROMIUM)
            assertThat(BrowserProfileRegistry.shouldPrioritizeUrlInspection(packageName)).isTrue()
        }
    }

    @Test
    fun `known firefox products share gecko family`() {
        listOf(
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "org.mozilla.fenix",
            "org.mozilla.focus"
        ).forEach { packageName ->
            assertThat(BrowserProfileRegistry.familyFor(packageName))
                .isEqualTo(BrowserFamily.GECKO)
            assertThat(BrowserProfileRegistry.shouldPrioritizeUrlInspection(packageName)).isTrue()
        }
    }

    @Test
    fun `webview style browsers retain explicit click behavior`() {
        assertThat(BrowserProfileRegistry.familyFor("mark.via"))
            .isEqualTo(BrowserFamily.WEBVIEW_BASED)
        assertThat(BrowserProfileRegistry.preferredActivationMethod("mark.via", null))
            .isEqualTo(BrowserActivationMethod.CLICK)
        assertThat(
            BrowserProfileRegistry.preferredActivationMethod(
                "com.duckduckgo.mobile.android",
                null
            )
        ).isEqualTo(BrowserActivationMethod.CLICK)
    }

    @Test
    fun `chromium family provides focus first without blocking learned package fallback`() {
        assertThat(BrowserProfileRegistry.preferredActivationMethod("com.brave.browser", null))
            .isEqualTo(BrowserActivationMethod.FOCUS)
        assertThat(
            BrowserProfileRegistry.preferredActivationMethod(
                "com.brave.browser",
                BrowserActivationMethod.CLICK
            )
        ).isEqualTo(BrowserActivationMethod.CLICK)
    }

    @Test
    fun `exact transition overrides remain authoritative`() {
        assertThat(
            BrowserProfileRegistry.preferredActivationMethod(
                "com.android.chrome",
                BrowserActivationMethod.CLICK
            )
        ).isEqualTo(BrowserActivationMethod.FOCUS)
        assertThat(
            BrowserProfileRegistry.preferredActivationMethod(
                "org.mozilla.firefox",
                BrowserActivationMethod.FOCUS
            )
        ).isEqualTo(BrowserActivationMethod.CLICK)
        assertThat(
            BrowserProfileRegistry.preferredActivationMethod(
                "com.sec.android.app.sbrowser",
                BrowserActivationMethod.FOCUS
            )
        ).isEqualTo(BrowserActivationMethod.CLICK)
    }

    @Test
    fun `unknown package stays generic before evidence`() {
        assertThat(BrowserProfileRegistry.familyFor("com.example.newbrowser"))
            .isEqualTo(BrowserFamily.GENERIC)
        assertThat(BrowserProfileRegistry.shouldPrioritizeUrlInspection("com.example.newbrowser"))
            .isFalse()
    }

    @Test
    fun `address bar evidence can infer family for learned browsers`() {
        assertThat(BrowserProfileRegistry.inferFamilyFromAddressBarEntryName("url_bar"))
            .isEqualTo(BrowserFamily.CHROMIUM)
        assertThat(
            BrowserProfileRegistry.inferFamilyFromAddressBarEntryName(
                "mozac_browser_toolbar_edit_url_view"
            )
        ).isEqualTo(BrowserFamily.GECKO)
        assertThat(BrowserProfileRegistry.inferFamilyFromAddressBarEntryName("custom_url_input"))
            .isEqualTo(BrowserFamily.WEBVIEW_BASED)
        assertThat(BrowserProfileRegistry.inferFamilyFromAddressBarEntryName("message_field"))
            .isEqualTo(BrowserFamily.GENERIC)
    }

    @Test
    fun `family entries are tried before generic defaults`() {
        val firefoxEntries = BrowserProfileRegistry.prioritizeAddressBarEntryNames(
            packageName = "org.mozilla.firefox",
            defaults = listOf("url_bar", "custom_url")
        )

        assertThat(firefoxEntries.first()).isEqualTo("mozac_browser_toolbar_url_view")
        assertThat(firefoxEntries).contains("url_bar")
        assertThat(firefoxEntries).contains("custom_url")
    }
}
