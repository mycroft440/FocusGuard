from pathlib import Path


def read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    Path(path).write_text(text, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}: {old[:80]!r}")
    write(path, text.replace(old, new, 1))


def insert_before(path: str, marker: str, addition: str) -> None:
    text = read(path)
    count = text.count(marker)
    if count != 1:
        raise SystemExit(f"{path}: expected one marker, found {count}: {marker[:80]!r}")
    write(path, text.replace(marker, addition + marker, 1))


def replace_method(path: str, start: str, next_marker: str, new_block: str) -> None:
    text = read(path)
    if text.count(start) != 1:
        raise SystemExit(f"{path}: start marker count={text.count(start)}: {start!r}")
    start_i = text.index(start)
    end_i = text.find(next_marker, start_i + len(start))
    if end_i < 0:
        raise SystemExit(f"{path}: next marker not found: {next_marker!r}")
    write(path, text[:start_i] + new_block + text[end_i:])


def replace_in_region(path: str, region_start: str, region_end: str, old: str, new: str) -> None:
    text = read(path)
    start_i = text.index(region_start)
    end_i = text.index(region_end, start_i + len(region_start))
    region = text[start_i:end_i]
    if region.count(old) != 1:
        raise SystemExit(
            f"{path}: region replacement expected once, found {region.count(old)}: {old[:80]!r}"
        )
    region = region.replace(old, new, 1)
    write(path, text[:start_i] + region + text[end_i:])


policy = "app/src/main/java/com/focusguard/utils/BrowserUiCapabilityPolicy.kt"
insert_before(
    policy,
    "    fun isStrongAddressBarResource(\n",
    """    /**
     * Firefox/Fenix can expose a stable display URL node while the edit-only
     * SEARCH_BOX is nested below it after a tap. Continue the bounded walk only
     * below stable Firefox address-display nodes; all editors and other browser
     * families remain terminal strong nodes.
     */
    internal fun shouldSearchAddressBarDescendants(
        packageName: String,
        viewIdResourceName: String?
    ): Boolean {
        if (!isFirefoxPackage(packageName)) return false
        return isStableUrlEntryName(browserOwnedEntryName(packageName, viewIdResourceName))
    }

""",
)
replace_once(
    policy,
    """        if (isChromePackage(expectedBrowserPackage)) return false

        return when (BrowserCompatibilityStore.preferredActivationMethod(expectedBrowserPackage)) {
""",
    """        if (isChromePackage(expectedBrowserPackage)) return false
        // Fenix enters edit mode through a click from ADDRESSBAR_URL_BOX to the
        // edit-only ADDRESSBAR_SEARCH_BOX. An old cached FOCUS result must not
        // override that transition; FOCUS remains the secondary fallback.
        if (isFirefoxPackage(expectedBrowserPackage)) return true

        return when (BrowserCompatibilityStore.preferredActivationMethod(expectedBrowserPackage)) {
""",
)
replace_once(
    policy,
    """            null -> expectedBrowserPackage == DUCKDUCKGO_PACKAGE ||
                isFirefoxPackage(expectedBrowserPackage) ||
                expectedBrowserPackage in setOf(
""",
    """            null -> expectedBrowserPackage == DUCKDUCKGO_PACKAGE ||
                expectedBrowserPackage in setOf(
""",
)

actions = "app/src/main/java/com/focusguard/accessibility/website/redirection/AddressBarRedirectionActions.kt"
replace_in_region(
    actions,
    "    private fun collectStrongNodes(\n",
    "    private fun collectSemanticNodes(\n",
    """            return
        }

        for (index in 0 until node.childCount) {
""",
    """            if (!BrowserUiCapabilityPolicy.shouldSearchAddressBarDescendants(
                    browserPackageName,
                    viewId
                )
            ) return
        }

        for (index in 0 until node.childCount) {
""",
)

blocker = "app/src/main/java/com/focusguard/utils/WebsiteBlocker.kt"
replace_in_region(
    blocker,
    "    private fun collectStrongActionAddressBarNodes(\n",
    "    private fun collectSemanticActionAddressBarNodes(\n",
    """        val belongsToBrowser = node.packageName?.toString() == browserPackageName
        if (belongsToBrowser && BrowserUiCapabilityPolicy.isStrongAddressBarResource(
                node.viewIdResourceName.orEmpty(),
                browserPackageName
            )
        ) {
            if (output.none { existing -> existing == node }) {
                @Suppress("DEPRECATION")
                output += AccessibilityNodeInfo.obtain(node)
            }
            return
        }
""",
    """        val belongsToBrowser = node.packageName?.toString() == browserPackageName
        val viewId = node.viewIdResourceName.orEmpty()
        if (belongsToBrowser && BrowserUiCapabilityPolicy.isStrongAddressBarResource(
                viewId,
                browserPackageName
            )
        ) {
            if (output.none { existing -> existing == node }) {
                @Suppress("DEPRECATION")
                output += AccessibilityNodeInfo.obtain(node)
            }
            if (!BrowserUiCapabilityPolicy.shouldSearchAddressBarDescendants(
                    browserPackageName,
                    viewId
                )
            ) return
        }
""",
)

service = "app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt"
insert_before(
    service,
    "    internal enum class WebsiteCloseFollowUp {\n",
    """    internal enum class WebsiteRestoreDecision {
        BACK_TO_BLOCKED_SURFACE,
        RETRY_CURRENT_BLOCKED_SURFACE,
        KEEP_CURRENT_SURFACE
    }

""",
)
insert_before(
    service,
    "        @Synchronized\n        fun transitionForConfirmation(\n",
    """        /**
         * A fresh, stable browser root is itself navigation evidence when the exact
         * safe Google root URL is visible as WEB_CONTENT and no address editor is
         * focused. This covers Fenix when submission succeeds but its mutation event
         * is delayed or lost.
         */
        @Synchronized
        fun confirmGoogleFromStableCurrentSurface(
            browserPackageName: String,
            windowId: Int,
            observedAtUptimeMillis: Long
        ): Boolean {
            val transition = activeTransitions[browserPackageName] ?: return false
            if (transition.externalRedirectRequested ||
                !transition.sanitizationRequested ||
                transition.expectedWindowId != windowId ||
                observedAtUptimeMillis < transition.sanitizationRequestedAtUptimeMillis
            ) return false
            transition.latestObservedEventUptimeMillis = maxOf(
                transition.latestObservedEventUptimeMillis,
                observedAtUptimeMillis
            )
            return transition.safeGoogleConfirmed.complete(Unit) ||
                transition.safeGoogleConfirmed.isCompleted
        }

""",
)

replace_method(
    service,
    "    private suspend fun restoreBlockedSurfaceAfterAddressEdit(\n",
    "    private suspend fun restoreBlockedSurfaceForSafeIntentFallback(\n",
    """    private suspend fun restoreBlockedSurfaceAfterAddressEdit(
        transition: WebsiteBlockTransitionHandle
    ): Boolean {
        if (!curtainReadyForTransition(transition)) return false
        if (transition.activatedAddressViewId == null && transition.editorAddressViewId == null) return true

        // BACK is allowed only while the exact safe URL is still being edited.
        // If submission already navigated away from the blocked page, going BACK
        // would resurrect that blocked page from Firefox history.
        val https = isVerifiedHttpsHandler(transition.browserPackageName)
        val editorRoot = activeBrowserRoot(
            transition.browserPackageName,
            transition.expectedWindowId
        ) ?: return false
        val safeRedirectStillEditing = try {
            AddressBarRedirectionActions.hasFocusedAddressEditor(
                editorRoot,
                transition.browserPackageName,
                transition.expectedWindowId,
                https,
                ::isSafeGoogleRedirectSurface
            )
        } finally { recycleSafely(editorRoot) }
        if (!curtainReadyForTransition(transition)) return false

        val blockedSurfaceStillCurrent = if (safeRedirectStillEditing) {
            false
        } else {
            websiteTreeWorker.run { currentBrowserSurfaceMatchesBlockedTransition(transition) }
        }
        if (!curtainReadyForTransition(transition)) return false

        when (websiteRestoreDecision(
            safeRedirectStillEditing = safeRedirectStillEditing,
            blockedSurfaceStillCurrent = blockedSurfaceStillCurrent
        )) {
            WebsiteRestoreDecision.RETRY_CURRENT_BLOCKED_SURFACE -> {
                transition.activatedAddressViewId = null
                transition.editorAddressViewId = null
                return true
            }
            WebsiteRestoreDecision.KEEP_CURRENT_SURFACE -> {
                transition.activatedAddressViewId = null
                transition.editorAddressViewId = null
                return false
            }
            WebsiteRestoreDecision.BACK_TO_BLOCKED_SURFACE -> Unit
        }

        if (!performTransitionBack(transition)) return false
        delay(WEBSITE_ADDRESS_BAR_FOCUS_SETTLE_MILLIS)
        if (!curtainReadyForTransition(transition)) return false
        val restored = websiteTreeWorker.run {
            currentBrowserSurfaceMatchesBlockedTransition(transition)
        }
        if (!curtainReadyForTransition(transition)) return false
        transition.activatedAddressViewId = null
        transition.editorAddressViewId = null
        return restored
    }

""",
)

replace_once(
    service,
    "            val deadline = SystemClock.uptimeMillis() + WEBSITE_ADDRESS_BAR_ACTION_TIMEOUT_MILLIS\n",
    """            val deadline = SystemClock.uptimeMillis() +
                websiteAddressBarActionTimeoutMillis(browserPackageName)
""",
)
replace_once(
    service,
    "                val writeDeadline = SystemClock.uptimeMillis() + WEBSITE_ADDRESS_BAR_ACTION_TIMEOUT_MILLIS\n",
    """                val writeDeadline = SystemClock.uptimeMillis() +
                    websiteAddressBarActionTimeoutMillis(browserPackageName)
""",
)
replace_in_region(
    service,
    "    private suspend fun submitSafeAddressBar(\n",
    "    private fun transitionWindowIsCurrent(transition: WebsiteBlockTransitionHandle): Boolean =\n",
    """            val fresh = activeBrowserRoot(browserPackageName, expectedWindowId) ?: return 0L
""",
    """            if (confirmSafeGoogleFromFreshBrowserSurface(transition)) return submittedAt
            val fresh = activeBrowserRoot(browserPackageName, expectedWindowId) ?: return 0L
""",
)

insert_before(
    service,
    "    private fun transitionWindowIsCurrent(transition: WebsiteBlockTransitionHandle): Boolean =\n",
    """    private suspend fun confirmSafeGoogleFromFreshBrowserSurface(
        transition: WebsiteBlockTransitionHandle
    ): Boolean {
        if (!curtainReadyForTransition(transition) ||
            !transition.sanitizationRequested ||
            transition.externalRedirectRequested
        ) return false

        repeat(2) { pass ->
            val root = activeBrowserRoot(
                transition.browserPackageName,
                transition.expectedWindowId
            ) ?: return false
            val stableSafeSurface = try {
                val identification = WebsiteIdentificationEngine.identifyFromRoot(
                    root,
                    transition.browserPackageName,
                    transition.expectedWindowId,
                    isVerifiedHttpsHandler(transition.browserPackageName),
                    isCurrent = { transitionWindowIsCurrent(transition) }
                )
                val session = BrowserInspectionSessionStore.sessionFor(
                    root,
                    transition.browserPackageName
                )
                isSafeGoogleRedirectSurface(identification.bestCandidate) &&
                    (session.surface ?: BrowserSurfaceInspector.inspect(
                        root,
                        transition.browserPackageName
                    )) == BrowserSurfaceInspector.Surface.WEB_CONTENT &&
                    !session.focusedAddressEditor
            } finally { recycleSafely(root) }
            if (!stableSafeSurface || !curtainReadyForTransition(transition)) return false
            if (pass == 0) {
                delay(WEBSITE_GOOGLE_SURFACE_SETTLE_MILLIS)
                if (!curtainReadyForTransition(transition)) return false
            }
        }

        val confirmed = websiteBlockTransitionGuard.confirmGoogleFromStableCurrentSurface(
            browserPackageName = transition.browserPackageName,
            windowId = transition.expectedWindowId,
            observedAtUptimeMillis = SystemClock.uptimeMillis()
        )
        if (confirmed) {
            BrowserCompatibilityStore.recordNavigationConfirmed(transition.browserPackageName)
        }
        return confirmed
    }

""",
)

replace_once(
    service,
    "        private const val WEBSITE_ADDRESS_BAR_ACTION_TIMEOUT_MILLIS = 360L\n",
    """        private const val WEBSITE_ADDRESS_BAR_ACTION_TIMEOUT_MILLIS = 360L
        private const val WEBSITE_FIREFOX_ADDRESS_BAR_ACTION_TIMEOUT_MILLIS = 800L
""",
)
insert_before(
    service,
    "        internal fun settingsTransitionGuardMillisForTest(): Long =\n",
    """        internal fun websiteAddressBarActionTimeoutMillis(
            browserPackageName: String
        ): Long = if (BrowserUiCapabilityPolicy.isFirefoxPackage(browserPackageName)) {
            WEBSITE_FIREFOX_ADDRESS_BAR_ACTION_TIMEOUT_MILLIS
        } else {
            WEBSITE_ADDRESS_BAR_ACTION_TIMEOUT_MILLIS
        }

        internal fun websiteRestoreDecision(
            safeRedirectStillEditing: Boolean,
            blockedSurfaceStillCurrent: Boolean
        ): WebsiteRestoreDecision = when {
            safeRedirectStillEditing -> WebsiteRestoreDecision.BACK_TO_BLOCKED_SURFACE
            blockedSurfaceStillCurrent -> WebsiteRestoreDecision.RETRY_CURRENT_BLOCKED_SURFACE
            else -> WebsiteRestoreDecision.KEEP_CURRENT_SURFACE
        }

""",
)

# Remove temporary patch machinery from the resulting application commit.
Path(".github/workflows/fix-firefox-redirect.yml").unlink(missing_ok=True)
Path(".github/scripts/apply_firefox_redirect_fix.py").unlink(missing_ok=True)
