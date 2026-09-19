package com.focusguard.accessibility.website.compatibility

/**
 * Browser compatibility metadata shared by URL identification and same-tab redirection.
 *
 * Exact products are only overrides. The main compatibility unit is the rendering/browser
 * family so a new Chromium or Gecko browser can reuse the same safe Accessibility strategies
 * before FocusGuard has ever seen its package. A package that is not known up-front can still
 * acquire a family from a successfully observed address-bar resource and then keep using the
 * existing [BrowserCompatibilityStore] as its package/version-specific learned profile.
 */
internal enum class BrowserFamily {
    CHROMIUM,
    GECKO,
    WEBVIEW_BASED,
    GENERIC
}

internal enum class BrowserProduct {
    CHROME,
    BRAVE,
    EDGE,
    VIVALDI,
    OPERA,
    KIWI,
    ECOSIA,
    SAMSUNG_INTERNET,
    YANDEX,
    FIREFOX,
    DUCKDUCKGO,
    VIA,
    OTHER
}

internal data class BrowserProfile(
    val product: BrowserProduct,
    val family: BrowserFamily,
    val preferredAddressBarEntryNames: List<String>,
    val activationOverride: BrowserActivationMethod? = null,
    val prioritizeUrlInspection: Boolean = family == BrowserFamily.CHROMIUM ||
        family == BrowserFamily.GECKO
)

internal object BrowserProfileRegistry {
    private val chromiumEntryNames = listOf(
        "url_bar",
        "url_bar_edit_text",
        "url_text",
        "location_bar_edit_text",
        "location_bar",
        "url_field",
        "url_edit_text",
        "omnibarTextInput",
        "omnibox_text",
        "address_bar"
    )

    private val geckoEntryNames = listOf(
        "mozac_browser_toolbar_url_view",
        "mozac_browser_toolbar_edit_url_view",
        "mozac_browser_toolbar_url",
        "mozac_browser_toolbar_edit_url",
        "mozac_browser_toolbar_address_view",
        "browser_toolbar_url_view",
        "browser_toolbar_edit_url_view",
        "browser_toolbar_address_view",
        "toolbar_url",
        "toolbar_url_view",
        "ADDRESSBAR_URL_BOX",
        "ADDRESSBAR_SEARCH_BOX"
    )

    private val webViewEntryNames = listOf(
        "address_bar",
        "bro_omnibox_address_title",
        "bro_omnibox_address_bar",
        "url_field",
        "url_edit_text"
    )

    val allKnownAddressBarEntryNames: Set<String> = linkedSetOf<String>().apply {
        addAll(chromiumEntryNames)
        addAll(geckoEntryNames)
        addAll(webViewEntryNames)
    }

    private fun chromium(
        product: BrowserProduct,
        activationOverride: BrowserActivationMethod? = null
    ) = BrowserProfile(
        product = product,
        family = BrowserFamily.CHROMIUM,
        preferredAddressBarEntryNames = chromiumEntryNames,
        activationOverride = activationOverride
    )

    private fun gecko(product: BrowserProduct) = BrowserProfile(
        product = product,
        family = BrowserFamily.GECKO,
        preferredAddressBarEntryNames = geckoEntryNames,
        // Fenix/Firefox commonly enters edit mode by clicking the display address first.
        activationOverride = BrowserActivationMethod.CLICK
    )

    private fun webView(
        product: BrowserProduct,
        activationOverride: BrowserActivationMethod? = null
    ) = BrowserProfile(
        product = product,
        family = BrowserFamily.WEBVIEW_BASED,
        preferredAddressBarEntryNames = webViewEntryNames,
        activationOverride = activationOverride,
        prioritizeUrlInspection = false
    )

    private val exactProfiles: Map<String, BrowserProfile> = buildMap {
        listOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary"
        ).forEach { put(it, chromium(BrowserProduct.CHROME, BrowserActivationMethod.FOCUS)) }

        listOf(
            "com.brave.browser",
            "com.brave.browser_beta",
            "com.brave.browser_nightly"
        ).forEach { put(it, chromium(BrowserProduct.BRAVE)) }

        listOf(
            "com.microsoft.emmx",
            "com.microsoft.emmx.beta",
            "com.microsoft.emmx.dev",
            "com.microsoft.emmx.canary"
        ).forEach { put(it, chromium(BrowserProduct.EDGE)) }

        listOf(
            "com.vivaldi.browser",
            "com.vivaldi.browser.snapshot"
        ).forEach { put(it, chromium(BrowserProduct.VIVALDI)) }

        listOf(
            "com.opera.browser",
            "com.opera.browser.beta",
            "com.opera.gx"
        ).forEach { put(it, chromium(BrowserProduct.OPERA)) }
        put("com.opera.mini.native", webView(BrowserProduct.OPERA, BrowserActivationMethod.CLICK))

        listOf(
            "com.kiwibrowser.browser",
            "com.kiwibrowser.browser.dev"
        ).forEach { put(it, chromium(BrowserProduct.KIWI)) }

        put("com.ecosia.android", chromium(BrowserProduct.ECOSIA))

        listOf(
            "com.sec.android.app.sbrowser",
            "com.sec.android.app.sbrowser.beta"
        ).forEach {
            put(it, chromium(BrowserProduct.SAMSUNG_INTERNET, BrowserActivationMethod.CLICK))
        }

        listOf(
            "com.yandex.browser",
            "com.yandex.browser.beta",
            "com.yandex.browser.alpha",
            "com.yandex.browser.lite"
        ).forEach { put(it, chromium(BrowserProduct.YANDEX, BrowserActivationMethod.CLICK)) }

        listOf(
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "org.mozilla.fenix",
            "org.mozilla.fenix.nightly",
            "org.mozilla.fennec_aurora",
            "org.mozilla.focus",
            "org.mozilla.klar"
        ).forEach { put(it, gecko(BrowserProduct.FIREFOX)) }

        put(
            "com.duckduckgo.mobile.android",
            webView(BrowserProduct.DUCKDUCKGO, BrowserActivationMethod.CLICK)
        )
        listOf("mark.via", "mark.via.gp").forEach {
            put(it, webView(BrowserProduct.VIA, BrowserActivationMethod.CLICK))
        }
    }

    private val genericProfile = BrowserProfile(
        product = BrowserProduct.OTHER,
        family = BrowserFamily.GENERIC,
        preferredAddressBarEntryNames = emptyList(),
        activationOverride = null,
        prioritizeUrlInspection = false
    )

    fun profileFor(packageName: String): BrowserProfile {
        exactProfiles[packageName]?.let { return it }

        // Existing package/version learning becomes the dynamic profile layer for packages
        // FocusGuard has never shipped explicit metadata for.
        val learnedEntry = BrowserCompatibilityStore.preferredAddressBarEntryName(packageName)
        return when (inferFamilyFromAddressBarEntryName(learnedEntry)) {
            BrowserFamily.CHROMIUM -> chromium(BrowserProduct.OTHER)
            BrowserFamily.GECKO -> gecko(BrowserProduct.OTHER).copy(activationOverride = null)
            BrowserFamily.WEBVIEW_BASED -> webView(BrowserProduct.OTHER)
            BrowserFamily.GENERIC -> genericProfile
        }
    }

    fun familyFor(packageName: String): BrowserFamily = profileFor(packageName).family

    fun isKnownBrowserPackage(packageName: String): Boolean = packageName in exactProfiles

    fun isKnownChromePackage(packageName: String): Boolean =
        exactProfiles[packageName]?.product == BrowserProduct.CHROME

    fun isKnownFirefoxPackage(packageName: String): Boolean =
        exactProfiles[packageName]?.product == BrowserProduct.FIREFOX

    fun shouldPrioritizeUrlInspection(packageName: String): Boolean =
        profileFor(packageName).prioritizeUrlInspection

    /**
     * Package/version learning wins unless the exact browser profile has an intentional UI
     * transition override (Chrome focus, Fenix click, Samsung/Via/Yandex click).
     */
    fun preferredActivationMethod(
        packageName: String,
        learnedMethod: BrowserActivationMethod?
    ): BrowserActivationMethod? {
        val profile = profileFor(packageName)
        profile.activationOverride?.let { return it }
        learnedMethod?.let { return it }
        return when (profile.family) {
            BrowserFamily.CHROMIUM -> BrowserActivationMethod.FOCUS
            BrowserFamily.GECKO -> BrowserActivationMethod.CLICK
            BrowserFamily.WEBVIEW_BASED,
            BrowserFamily.GENERIC -> null
        }
    }

    fun prioritizeAddressBarEntryNames(
        packageName: String,
        defaults: Iterable<String>
    ): List<String> {
        val profile = profileFor(packageName)
        return buildList {
            profile.preferredAddressBarEntryNames.forEach { entry ->
                if (entry.isNotBlank() && entry !in this) add(entry)
            }
            defaults.forEach { entry ->
                if (entry.isNotBlank() && entry !in this) add(entry)
            }
        }
    }

    internal fun inferFamilyFromAddressBarEntryName(entryName: String?): BrowserFamily {
        val value = entryName?.substringAfterLast("/")?.trim().orEmpty()
        if (value.isEmpty()) return BrowserFamily.GENERIC
        return when {
            value in geckoEntryNames -> BrowserFamily.GECKO
            value in chromiumEntryNames -> BrowserFamily.CHROMIUM
            value in webViewEntryNames -> BrowserFamily.WEBVIEW_BASED
            value.contains("omnibox", ignoreCase = true) ||
                value.contains("location_bar", ignoreCase = true) -> BrowserFamily.CHROMIUM
            value.contains("mozac", ignoreCase = true) ||
                value.startsWith("ADDRESSBAR_", ignoreCase = true) -> BrowserFamily.GECKO
            value.contains("address", ignoreCase = true) ||
                value.contains("url", ignoreCase = true) ||
                value.contains("uri", ignoreCase = true) -> BrowserFamily.WEBVIEW_BASED
            else -> BrowserFamily.GENERIC
        }
    }
}
