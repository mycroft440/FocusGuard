from pathlib import Path

policy_path = Path("app/src/main/java/com/focusguard/utils/BrowserUiCapabilityPolicy.kt")
policy = policy_path.read_text()
old = """    fun canUseImeEnter(apiLevel: Int): Boolean = apiLevel >= IME_ENTER_MIN_API

    fun mayRewriteBlockedTabAfterCloseAttempt(
"""
new = """    fun canUseImeEnter(apiLevel: Int): Boolean = apiLevel >= IME_ENTER_MIN_API

    fun prefersClickAddressBarActivation(expectedBrowserPackage: String): Boolean =
        expectedBrowserPackage == DUCKDUCKGO_PACKAGE

    fun mayRewriteBlockedTabAfterCloseAttempt(
"""
if old not in policy:
    raise SystemExit("policy insertion point not found")
policy_path.write_text(policy.replace(old, new, 1))

service_path = Path("app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt")
service = service_path.read_text()
old = """        val activationRequestedAt = SystemClock.uptimeMillis()
        val focusResult = WebsiteBlocker.performUniqueAddressBarAction(
            root = root,
            browserPackageName = browserPackageName,
            expectedWindowId = expectedWindowId,
            requiredAction = BrowserUiCapabilityPolicy.NodeAction.FOCUS,
            httpsHandlerRecognized = isVerifiedHttpsHandler(browserPackageName)
        )
        val activationResult = if (
            !focusResult.accepted &&
            focusResult.status != WebsiteBlocker.AddressBarActionStatus.AMBIGUOUS
        ) {
            WebsiteBlocker.performUniqueAddressBarAction(
                root = root,
                browserPackageName = browserPackageName,
                expectedWindowId = expectedWindowId,
                requiredAction = BrowserUiCapabilityPolicy.NodeAction.CLICK,
                httpsHandlerRecognized = isVerifiedHttpsHandler(browserPackageName)
            )
        } else {
            focusResult
        }
"""
new = """        val activationRequestedAt = SystemClock.uptimeMillis()
        val preferClick = BrowserUiCapabilityPolicy.prefersClickAddressBarActivation(
            browserPackageName
        )
        val primaryAction = if (preferClick) {
            BrowserUiCapabilityPolicy.NodeAction.CLICK
        } else {
            BrowserUiCapabilityPolicy.NodeAction.FOCUS
        }
        val secondaryAction = if (preferClick) {
            BrowserUiCapabilityPolicy.NodeAction.FOCUS
        } else {
            BrowserUiCapabilityPolicy.NodeAction.CLICK
        }
        val primaryResult = WebsiteBlocker.performUniqueAddressBarAction(
            root = root,
            browserPackageName = browserPackageName,
            expectedWindowId = expectedWindowId,
            requiredAction = primaryAction,
            httpsHandlerRecognized = isVerifiedHttpsHandler(browserPackageName)
        )
        val activationResult = if (
            !primaryResult.accepted &&
            primaryResult.status != WebsiteBlocker.AddressBarActionStatus.AMBIGUOUS
        ) {
            WebsiteBlocker.performUniqueAddressBarAction(
                root = root,
                browserPackageName = browserPackageName,
                expectedWindowId = expectedWindowId,
                requiredAction = secondaryAction,
                httpsHandlerRecognized = isVerifiedHttpsHandler(browserPackageName)
            )
        } else {
            primaryResult
        }
"""
if old not in service:
    raise SystemExit("address-bar activation block not found")
service_path.write_text(service.replace(old, new, 1))

test_path = Path("app/src/test/java/com/focusguard/utils/BrowserUiCapabilityPolicyTest.kt")
test = test_path.read_text()
marker = """    @Test
    fun `API below 30 has no certifiable IME submit`() {
"""
addition = """    @Test
    fun `DuckDuckGo prefers click activation while other browsers prefer focus`() {
        assertThat(
            BrowserUiCapabilityPolicy.prefersClickAddressBarActivation(
                \"com.duckduckgo.mobile.android\"
            )
        ).isTrue()
        assertThat(
            BrowserUiCapabilityPolicy.prefersClickAddressBarActivation(BROWSER_PACKAGE)
        ).isFalse()
    }

"""
if marker not in test:
    raise SystemExit("click-first test insertion point not found")
test_path.write_text(test.replace(marker, addition + marker, 1))
