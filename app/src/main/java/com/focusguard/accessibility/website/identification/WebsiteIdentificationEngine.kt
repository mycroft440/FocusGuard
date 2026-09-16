package com.focusguard.accessibility.website.identification

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.focusguard.utils.BrowserSurfaceInspector
import com.focusguard.utils.BrowserUiCapabilityPolicy
import com.focusguard.utils.WebsiteBlocker

/**
 * Layered website identification for browser accessibility surfaces.
 *
 * Order of evidence:
 * 1. event as a reinspection trigger;
 * 2. browser package/window ownership;
 * 3. known strong address-bar ids;
 * 4. current address-bar text/URL;
 * 5. semantic address-field fallback;
 * 6. fresh reidentification after an interaction.
 *
 * The engine never keeps AccessibilityNodeInfo references between phases.
 */
internal object WebsiteIdentificationEngine {

    fun identifyFromEvent(
        event: AccessibilityEvent,
        browserPackageName: String,
        httpsHandlerRecognized: Boolean
    ): WebsiteIdentificationResult {
        if (browserPackageName.isBlank() || event.windowId < 0) {
            return WebsiteIdentificationResult(
                status = WebsiteIdentificationStatus.REJECTED_CONTEXT
            )
        }
        if (!WebsiteIdentificationEventPolicy.shouldReinspect(event.eventType)) {
            return WebsiteIdentificationResult(
                status = WebsiteIdentificationStatus.UNOBSERVABLE,
                browserPackageName = browserPackageName,
                windowId = event.windowId
            )
        }

        val evidence = linkedSetOf(
            WebsiteIdentificationLayer.ACCESSIBILITY_EVENT,
            WebsiteIdentificationLayer.BROWSER_PACKAGE_AND_WINDOW
        )
        if (eventSourceHasStrongAddressBarId(event, browserPackageName)) {
            evidence += WebsiteIdentificationLayer.STRONG_ADDRESS_BAR_ID
        }

        val rawText = WebsiteBlocker.extractAddressBarTextFromEvent(
            event = event,
            browserPackageName = browserPackageName,
            httpsHandlerRecognized = httpsHandlerRecognized
        )
        val url = rawText?.let(WebsiteBlocker::extractUrlCandidate)
            ?: WebsiteBlocker.extractUrlFromEvent(
                event = event,
                browserPackageName = browserPackageName,
                httpsHandlerRecognized = httpsHandlerRecognized
            )

        if (!rawText.isNullOrBlank()) evidence += WebsiteIdentificationLayer.ADDRESS_BAR_TEXT
        if (rawText != null || url != null) {
            if (WebsiteIdentificationLayer.STRONG_ADDRESS_BAR_ID !in evidence) {
                evidence += WebsiteIdentificationLayer.FIELD_SEMANTICS
            }
            return WebsiteIdentificationResult(
                status = classifyStatus(
                    urlCandidate = url,
                    addressBarObservable = rawText != null || url != null
                ),
                rawAddressText = rawText,
                urlCandidate = url,
                browserPackageName = browserPackageName,
                windowId = event.windowId,
                evidence = evidence
            )
        }

        return WebsiteIdentificationResult(
            status = WebsiteIdentificationStatus.UNOBSERVABLE,
            browserPackageName = browserPackageName,
            windowId = event.windowId,
            evidence = evidence
        )
    }

    fun identifyFromRoot(
        root: AccessibilityNodeInfo?,
        browserPackageName: String,
        expectedWindowId: Int,
        httpsHandlerRecognized: Boolean
    ): WebsiteIdentificationResult {
        if (root == null || browserPackageName.isBlank() || expectedWindowId < 0) {
            return WebsiteIdentificationResult(
                status = WebsiteIdentificationStatus.UNOBSERVABLE,
                browserPackageName = browserPackageName.takeIf(String::isNotBlank),
                windowId = expectedWindowId.takeIf { it >= 0 }
            )
        }

        val rootMatchesBrowser = runCatching {
            root.packageName?.toString() == browserPackageName &&
                root.windowId == expectedWindowId
        }.getOrDefault(false)
        if (!rootMatchesBrowser) {
            return WebsiteIdentificationResult(
                status = WebsiteIdentificationStatus.REJECTED_CONTEXT,
                browserPackageName = browserPackageName,
                windowId = expectedWindowId
            )
        }

        val inspection = BrowserWindowInspection.inspect(
            root = root,
            browserPackageName = browserPackageName,
            expectedWindowId = expectedWindowId,
            httpsHandlerRecognized = httpsHandlerRecognized
        )
        if (inspection.surface == BrowserSurfaceInspector.Surface.NATIVE_PANEL) {
            return WebsiteIdentificationResult(
                status = WebsiteIdentificationStatus.NATIVE_BROWSER_UI,
                browserPackageName = browserPackageName,
                windowId = expectedWindowId,
                evidence = setOf(WebsiteIdentificationLayer.BROWSER_PACKAGE_AND_WINDOW)
            )
        }

        val evidence = linkedSetOf(WebsiteIdentificationLayer.BROWSER_PACKAGE_AND_WINDOW)
        if (inspection.strongAddressBarObserved) {
            evidence += WebsiteIdentificationLayer.STRONG_ADDRESS_BAR_ID
        }
        if (!inspection.rawAddressText.isNullOrBlank()) {
            evidence += WebsiteIdentificationLayer.ADDRESS_BAR_TEXT
        }
        if (inspection.addressBarObservable) {
            evidence += WebsiteIdentificationLayer.FIELD_SEMANTICS
        }

        return WebsiteIdentificationResult(
            status = classifyStatus(
                urlCandidate = inspection.urlCandidate,
                addressBarObservable = inspection.addressBarObservable,
                nativeBrowserUiObserved = inspection.surface.isNativeUi
            ),
            rawAddressText = inspection.rawAddressText,
            urlCandidate = inspection.urlCandidate,
            browserPackageName = browserPackageName,
            windowId = expectedWindowId,
            evidence = evidence,
            webContentObserved = inspection.surface == BrowserSurfaceInspector.Surface.WEB_CONTENT
        )
    }

    internal fun classifyStatus(
        urlCandidate: String?,
        addressBarObservable: Boolean,
        nativeBrowserUiObserved: Boolean = false
    ): WebsiteIdentificationStatus = when {
        !urlCandidate.isNullOrBlank() -> WebsiteIdentificationStatus.IDENTIFIED
        addressBarObservable -> WebsiteIdentificationStatus.ADDRESS_BAR_OBSERVABLE
        nativeBrowserUiObserved -> WebsiteIdentificationStatus.NATIVE_BROWSER_UI
        else -> WebsiteIdentificationStatus.UNOBSERVABLE
    }

    /**
     * Reacquires the tree after click/focus and marks that fresh observation as a
     * separate layer. The provider must return a new node handle each time.
     */
    fun reidentifyAfterInteraction(
        rootProvider: () -> AccessibilityNodeInfo?,
        browserPackageName: String,
        expectedWindowId: Int,
        httpsHandlerRecognized: Boolean
    ): WebsiteIdentificationResult {
        val freshRoot = rootProvider() ?: return WebsiteIdentificationResult(
            status = WebsiteIdentificationStatus.UNOBSERVABLE,
            browserPackageName = browserPackageName,
            windowId = expectedWindowId,
            evidence = setOf(WebsiteIdentificationLayer.POST_INTERACTION_REIDENTIFICATION)
        )
        return try {
            val result = identifyFromRoot(
                root = freshRoot,
                browserPackageName = browserPackageName,
                expectedWindowId = expectedWindowId,
                httpsHandlerRecognized = httpsHandlerRecognized
            )
            result.copy(
                evidence = result.evidence +
                    WebsiteIdentificationLayer.POST_INTERACTION_REIDENTIFICATION
            )
        } finally {
            recycleSafely(freshRoot)
        }
    }

    private fun eventSourceHasStrongAddressBarId(
        event: AccessibilityEvent,
        browserPackageName: String
    ): Boolean {
        val source = runCatching { event.source }.getOrNull() ?: return false
        return try {
            BrowserSurfaceInspector.isNativeNode(source) &&
                source.packageName?.toString() == browserPackageName &&
                source.windowId == event.windowId &&
                BrowserUiCapabilityPolicy.isStrongAddressBarResource(
                    source.viewIdResourceName.orEmpty(),
                    browserPackageName
                )
        } finally {
            recycleSafely(source)
        }
    }

    private fun recycleSafely(node: AccessibilityNodeInfo?) {
        if (node == null) return
        @Suppress("DEPRECATION")
        runCatching { node.recycle() }
    }
}
