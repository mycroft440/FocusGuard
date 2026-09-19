package com.focusguard.accessibility.website.identification

import android.view.accessibility.AccessibilityNodeInfo
import com.focusguard.accessibility.website.compatibility.BrowserProfileRegistry
import com.focusguard.utils.BrowserInspectionSessionStore
import com.focusguard.utils.BrowserSurfaceInspector
import com.focusguard.utils.BrowserUiCapabilityPolicy
import com.focusguard.utils.StrongAddressBarFallbackReader
import com.focusguard.utils.WebsiteBlocker

/**
 * Layered website identification for browser accessibility surfaces.
 *
 * Order of evidence:
 * 1. browser package/window ownership;
 * 2. known strong address-bar ids;
 * 3. current address-bar text/URL;
 * 4. semantic address-field fallback;
 * 5. fresh reidentification after an interaction.
 *
 * The engine never keeps AccessibilityNodeInfo references between phases.
 */
internal object WebsiteIdentificationEngine {

    /**
     * Standard normal-inspection entry point. Surface, URL, raw address text and
     * address-bar observability all resolve against the same root, so
     * BrowserInspectionSessionStore can reuse their budget and intermediate data.
     */
    fun identifyFromRoot(
        root: AccessibilityNodeInfo?,
        browserPackageName: String,
        expectedWindowId: Int,
        httpsHandlerRecognized: Boolean,
        isCurrent: () -> Boolean
    ): WebsiteIdentificationResult {
        if (!isCurrent()) return WebsiteIdentificationResult(WebsiteIdentificationStatus.REJECTED_CONTEXT)
        if (root == null || browserPackageName.isBlank() || expectedWindowId < 0) {
            return WebsiteIdentificationResult(
                status = WebsiteIdentificationStatus.UNOBSERVABLE,
                browserPackageName = browserPackageName.takeIf(String::isNotBlank),
                windowId = expectedWindowId.takeIf { it >= 0 }
            )
        }

        return BrowserInspectionSessionStore.withInspection(root, browserPackageName, isCurrent) {
            identifyCurrentRoot(root, browserPackageName, expectedWindowId, httpsHandlerRecognized, isCurrent)
        }
    }

    private fun identifyCurrentRoot(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        httpsHandlerRecognized: Boolean,
        isCurrent: () -> Boolean
    ): WebsiteIdentificationResult {
        val rootMatchesBrowser = runCatching {
            root.packageName?.toString() == browserPackageName &&
                root.windowId == expectedWindowId
        }.getOrDefault(false)
        if (!isCurrent() || !rootMatchesBrowser) {
            return WebsiteIdentificationResult(
                status = WebsiteIdentificationStatus.REJECTED_CONTEXT,
                browserPackageName = browserPackageName,
                windowId = expectedWindowId
            )
        }

        // Chromium and Gecko families expose their strongest address evidence through native
        // browser chrome, but a full WebView/ContentView/GeckoView surface walk can consume the
        // bounded shared budget first on complex pages. Give the family URL reader first use of
        // that budget. Exact package overrides still live in BrowserProfileRegistry, while an
        // unknown package can acquire the same optimization from learned address-bar evidence.
        if (BrowserProfileRegistry.shouldPrioritizeUrlInspection(browserPackageName)) {
            WebsiteBlocker.extractUrlFromRoot(
                root = root,
                browserPackageName = browserPackageName,
                httpsHandlerRecognized = httpsHandlerRecognized
            )
        }
        if (!isCurrent()) {
            return WebsiteIdentificationResult(WebsiteIdentificationStatus.REJECTED_CONTEXT)
        }

        val surface = BrowserSurfaceInspector.inspect(root, browserPackageName)
        if (surface == BrowserSurfaceInspector.Surface.NATIVE_PANEL) {
            return WebsiteIdentificationResult(
                status = WebsiteIdentificationStatus.NATIVE_BROWSER_UI,
                browserPackageName = browserPackageName,
                windowId = expectedWindowId,
                evidence = setOf(WebsiteIdentificationLayer.BROWSER_PACKAGE_AND_WINDOW)
            )
        }

        val evidence = linkedSetOf(WebsiteIdentificationLayer.BROWSER_PACKAGE_AND_WINDOW)
        // These accessors all resolve through the same BrowserInspectionSession for
        // this root. Calling each accessor does not start an independent tree walk.
        var url = WebsiteBlocker.extractUrlFromRoot(
            root = root,
            browserPackageName = browserPackageName,
            httpsHandlerRecognized = httpsHandlerRecognized
        )
        var rawText = WebsiteBlocker.extractAddressBarTextFromRoot(
            root = root,
            browserPackageName = browserPackageName,
            httpsHandlerRecognized = httpsHandlerRecognized
        )
        var observable = url != null || rawText != null || WebsiteBlocker.hasAddressBarNode(
            root = root,
            browserPackageName = browserPackageName,
            httpsHandlerRecognized = httpsHandlerRecognized
        )

        // A direct ID lookup can fail even though an exact browser-owned native bar is
        // nested below a WebView-like container. The normal semantic walker correctly
        // refuses to cross that boundary. If no usable URL was obtained, perform one
        // bounded strong-resource-only traversal. Arbitrary HTML text remains ineligible.
        if (url == null && isCurrent()) {
            StrongAddressBarFallbackReader.read(
                root = root,
                browserPackageName = browserPackageName,
                expectedWindowId = expectedWindowId,
                isCurrent = isCurrent
            )?.let { fallback ->
                val session = BrowserInspectionSessionStore.sessionFor(root, browserPackageName)
                url = fallback.url ?: url
                if (rawText.isNullOrBlank() || WebsiteBlocker.extractUrlCandidate(rawText!!) == null) {
                    rawText = fallback.text ?: rawText
                }
                observable = true
                session.addressBarObservable = true
                session.strongAddressBarObserved = true
                session.focusedAddressEditor = session.focusedAddressEditor ||
                    fallback.focusedAddressEditor
                if (session.url == null) session.url = fallback.url
                if (session.addressText.isNullOrBlank() ||
                    WebsiteBlocker.extractUrlCandidate(session.addressText!!) == null
                ) {
                    session.addressText = fallback.text ?: session.addressText
                }
            }
        }

        if (!isCurrent()) return WebsiteIdentificationResult(WebsiteIdentificationStatus.REJECTED_CONTEXT)
        if (BrowserInspectionSessionStore.sessionFor(root, browserPackageName).strongAddressBarObserved) {
            evidence += WebsiteIdentificationLayer.STRONG_ADDRESS_BAR_ID
        }
        if (!rawText.isNullOrBlank()) evidence += WebsiteIdentificationLayer.ADDRESS_BAR_TEXT
        if (observable) evidence += WebsiteIdentificationLayer.FIELD_SEMANTICS

        return WebsiteIdentificationResult(
            status = classifyStatus(
                urlCandidate = url,
                addressBarObservable = observable,
                nativeBrowserUiObserved = surface.isNativeUi
            ),
            rawAddressText = rawText,
            urlCandidate = url,
            browserPackageName = browserPackageName,
            windowId = expectedWindowId,
            evidence = evidence.toSet(),
            webContentObserved = surface == BrowserSurfaceInspector.Surface.WEB_CONTENT
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
        httpsHandlerRecognized: Boolean,
        isCurrent: () -> Boolean
    ): WebsiteIdentificationResult {
        if (!isCurrent()) return WebsiteIdentificationResult(WebsiteIdentificationStatus.REJECTED_CONTEXT)
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
                httpsHandlerRecognized = httpsHandlerRecognized,
                isCurrent = isCurrent
            )
            if (!isCurrent()) return WebsiteIdentificationResult(WebsiteIdentificationStatus.REJECTED_CONTEXT)
            result.copy(
                evidence = result.evidence +
                    WebsiteIdentificationLayer.POST_INTERACTION_REIDENTIFICATION
            )
        } finally {
            recycleSafely(freshRoot)
        }
    }

    private fun recycleSafely(node: AccessibilityNodeInfo?) {
        if (node == null) return
        @Suppress("DEPRECATION")
        runCatching { node.recycle() }
    }
}
