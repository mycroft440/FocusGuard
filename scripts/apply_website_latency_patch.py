from pathlib import Path

SERVICE = Path("app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt")
MARKER = "browserWindowIdValidated"


def replace_once(text: str, old: str, new: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly one match, got {count}: {old[:80]!r}")
    return text.replace(old, new, 1)


text = SERVICE.read_text()
if MARKER in text:
    print("Scoped service patch already applied.")
    raise SystemExit(0)

text = replace_once(
    text,
    """            routeWebsiteBlockByHierarchy(
                browserPackageName = currentPackage,
                browserWindowId = windowId,
                blockedCandidate = blockedCandidate,
                detectionEventUptimeMillis = SystemClock.uptimeMillis()
            )
""",
    """            routeWebsiteBlockByHierarchy(
                browserPackageName = currentPackage,
                browserWindowId = windowId,
                blockedCandidate = blockedCandidate,
                detectionEventUptimeMillis = SystemClock.uptimeMillis(),
                browserWindowIdValidated = true
            )
""",
)

text = replace_once(
    text,
    """        routeWebsiteBlockByHierarchy(
            browserPackageName = packageName,
            browserWindowId = event.windowId,
            blockedCandidate = url ?: addressText ?: blockedCandidate,
            detectionEventUptimeMillis = event.eventTime
        )
        return true
""",
    """        routeWebsiteBlockByHierarchy(
            browserPackageName = packageName,
            browserWindowId = event.windowId,
            blockedCandidate = url ?: addressText ?: blockedCandidate,
            detectionEventUptimeMillis = event.eventTime,
            browserWindowIdValidated = true
        )
        return true
""",
)

text = replace_once(
    text,
    """        val root = if (fastUrl == null) rootInActiveWindow ?: event.source else null
        val url = fastUrl ?: WebsiteBlocker.extractUrlFromRoot(
            root,
            packageName,
            isVerifiedHttpsHandler(packageName)
        )
        val addressText = fastAddressText
            ?: WebsiteBlocker.extractAddressBarTextFromRoot(
                root,
                packageName,
                isVerifiedHttpsHandler(packageName)
            )
        val addressBarObservable = fastAddressText != null ||
            url != null || WebsiteBlocker.hasAddressBarNode(
                root,
                packageName,
                isVerifiedHttpsHandler(packageName)
            )

        // Even on the root-fallback path, the block decision outranks tracking,
        // policy refreshes and observability bookkeeping. Once the URL is known,
        // cover the page immediately just like blockApp() covers an app window.
        val blockedCandidate = immediateWebsiteBlockTarget(
                addressText = addressText,
                url = url,
                blockedRules = blockedWebsitesDomainSet
            )
        if (blockedCandidate != null) {
            routeWebsiteBlockByHierarchy(
                browserPackageName = packageName,
                browserWindowId = event.windowId,
                blockedCandidate = url ?: addressText ?: blockedCandidate,
                detectionEventUptimeMillis = event.eventTime
            )
            recycleSafely(root)
            return
        }
""",
    """        // Direct omnibox evidence can be classified before any active-root read.
        val fastBlockedCandidate = immediateWebsiteBlockTarget(
            addressText = fastAddressText,
            url = fastUrl,
            blockedRules = blockedWebsitesDomainSet
        )
        if (fastBlockedCandidate != null) {
            routeWebsiteBlockByHierarchy(
                browserPackageName = packageName,
                browserWindowId = event.windowId,
                blockedCandidate = fastUrl ?: fastAddressText ?: fastBlockedCandidate,
                detectionEventUptimeMillis = event.eventTime,
                browserWindowIdValidated = true
            )
            return
        }

        val root = if (fastUrl == null) rootInActiveWindow ?: event.source else null
        val url = fastUrl ?: WebsiteBlocker.extractUrlFromRoot(
            root,
            packageName,
            isVerifiedHttpsHandler(packageName)
        )
        val detectedWindowId = if (fastUrl != null) {
            event.windowId
        } else {
            root?.windowId ?: event.windowId
        }

        // Normal URL/domain rules do not need a second tree traversal for raw text.
        val urlBlockedCandidate = immediateWebsiteBlockTarget(
            addressText = null,
            url = url,
            blockedRules = blockedWebsitesDomainSet
        )
        if (urlBlockedCandidate != null) {
            routeWebsiteBlockByHierarchy(
                browserPackageName = packageName,
                browserWindowId = detectedWindowId,
                blockedCandidate = url ?: urlBlockedCandidate,
                detectionEventUptimeMillis = event.eventTime,
                browserWindowIdValidated = true
            )
            recycleSafely(root)
            return
        }

        val addressText = fastAddressText
            ?: WebsiteBlocker.extractAddressBarTextFromRoot(
                root,
                packageName,
                isVerifiedHttpsHandler(packageName)
            )
        val addressBarObservable = addressText != null ||
            url != null || WebsiteBlocker.hasAddressBarNode(
                root,
                packageName,
                isVerifiedHttpsHandler(packageName)
            )

        // Raw text remains necessary for category/search rules that are not URLs.
        val blockedCandidate = immediateWebsiteBlockTarget(
            addressText = addressText,
            url = url,
            blockedRules = blockedWebsitesDomainSet
        )
        if (blockedCandidate != null) {
            routeWebsiteBlockByHierarchy(
                browserPackageName = packageName,
                browserWindowId = detectedWindowId,
                blockedCandidate = url ?: addressText ?: blockedCandidate,
                detectionEventUptimeMillis = event.eventTime,
                browserWindowIdValidated = true
            )
            recycleSafely(root)
            return
        }
""",
)

text = replace_once(
    text,
    """                blockWebsite(
                    browserPackageName = packageName,
                    browserWindowId = event.windowId,
                    blockedCandidate = url,
                    detectionEventUptimeMillis = event.eventTime
                )
""",
    """                blockWebsite(
                    browserPackageName = packageName,
                    browserWindowId = detectedWindowId,
                    blockedCandidate = url,
                    detectionEventUptimeMillis = event.eventTime,
                    browserWindowIdValidated = true
                )
""",
)

text = replace_once(
    text,
    """    private fun routeWebsiteBlockByHierarchy(
        browserPackageName: String,
        browserWindowId: Int,
        blockedCandidate: String,
        detectionEventUptimeMillis: Long
    ) {
""",
    """    private fun routeWebsiteBlockByHierarchy(
        browserPackageName: String,
        browserWindowId: Int,
        blockedCandidate: String,
        detectionEventUptimeMillis: Long,
        browserWindowIdValidated: Boolean = false
    ) {
""",
)

text = replace_once(
    text,
    """        blockWebsite(
            browserPackageName = browserPackageName,
            browserWindowId = browserWindowId,
            blockedCandidate = blockedCandidate,
            detectionEventUptimeMillis = detectionEventUptimeMillis
        )
""",
    """        blockWebsite(
            browserPackageName = browserPackageName,
            browserWindowId = browserWindowId,
            blockedCandidate = blockedCandidate,
            detectionEventUptimeMillis = detectionEventUptimeMillis,
            browserWindowIdValidated = browserWindowIdValidated
        )
""",
)

text = replace_once(
    text,
    """    private fun blockWebsite(
        browserPackageName: String,
        browserWindowId: Int = INVALID_BROWSER_WINDOW_ID,
        blockedCandidate: String? = null,
        detectionEventUptimeMillis: Long = 0L
    ) {
        val now = System.currentTimeMillis()
        stopWebsiteTracking(now)
        startWebsiteBlockTransition(
            browserPackageName,
            browserWindowId,
            blockedCandidate,
            detectionEventUptimeMillis
        )
    }
""",
    """    private fun blockWebsite(
        browserPackageName: String,
        browserWindowId: Int = INVALID_BROWSER_WINDOW_ID,
        blockedCandidate: String? = null,
        detectionEventUptimeMillis: Long = 0L,
        browserWindowIdValidated: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        stopWebsiteTracking(now)
        startWebsiteBlockTransition(
            browserPackageName = browserPackageName,
            browserWindowId = browserWindowId,
            blockedCandidate = blockedCandidate,
            detectionEventUptimeMillis = detectionEventUptimeMillis,
            browserWindowIdValidated = browserWindowIdValidated
        )
    }
""",
)

text = replace_once(
    text,
    """    private fun startWebsiteBlockTransition(
        browserPackageName: String,
        browserWindowId: Int = INVALID_BROWSER_WINDOW_ID,
        blockedCandidate: String? = null,
        detectionEventUptimeMillis: Long = 0L
    ) {
        val expectedWindowId = resolveBrowserWindowId(browserPackageName, browserWindowId)
""",
    """    private fun startWebsiteBlockTransition(
        browserPackageName: String,
        browserWindowId: Int = INVALID_BROWSER_WINDOW_ID,
        blockedCandidate: String? = null,
        detectionEventUptimeMillis: Long = 0L,
        browserWindowIdValidated: Boolean = false
    ) {
        val expectedWindowId = if (browserWindowIdValidated && browserWindowId >= 0) {
            browserWindowId
        } else {
            resolveBrowserWindowId(browserPackageName, browserWindowId)
        }
""",
)

SERVICE.write_text(text)
print("Scoped service patch applied.")
