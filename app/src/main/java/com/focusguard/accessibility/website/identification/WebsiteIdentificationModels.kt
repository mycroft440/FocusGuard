package com.focusguard.accessibility.website.identification

/**
 * Evidence layers used to identify the browser's current website without trusting
 * arbitrary text from the rendered page.
 */
internal enum class WebsiteIdentificationLayer {
    ACCESSIBILITY_EVENT,
    BROWSER_PACKAGE_AND_WINDOW,
    STRONG_ADDRESS_BAR_ID,
    ADDRESS_BAR_TEXT,
    FIELD_SEMANTICS,
    POST_INTERACTION_REIDENTIFICATION,
    FAIL_CLOSED
}

internal enum class WebsiteIdentificationStatus {
    IDENTIFIED,
    ADDRESS_BAR_OBSERVABLE,
    UNOBSERVABLE,
    REJECTED_CONTEXT
}

/**
 * A result never means "allowed". It only describes what Accessibility can prove
 * about the current browser surface; blocking policy is evaluated afterwards.
 */
internal data class WebsiteIdentificationResult(
    val status: WebsiteIdentificationStatus,
    val rawAddressText: String? = null,
    val urlCandidate: String? = null,
    val browserPackageName: String? = null,
    val windowId: Int? = null,
    val evidence: Set<WebsiteIdentificationLayer> = emptySet()
) {
    val addressBarObservable: Boolean
        get() = status == WebsiteIdentificationStatus.IDENTIFIED ||
            status == WebsiteIdentificationStatus.ADDRESS_BAR_OBSERVABLE

    val bestCandidate: String?
        get() = urlCandidate?.takeIf(String::isNotBlank)
            ?: rawAddressText?.takeIf(String::isNotBlank)
}
