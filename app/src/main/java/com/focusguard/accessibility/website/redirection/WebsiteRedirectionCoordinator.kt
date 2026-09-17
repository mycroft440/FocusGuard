package com.focusguard.accessibility.website.redirection

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.focusguard.accessibility.website.identification.WebsiteIdentificationEngine
import com.focusguard.accessibility.website.identification.WebsiteIdentificationStatus

/**
 * Multi-phase same-tab redirect. Nodes never survive an asynchronous phase:
 * activate -> discard -> wait -> reacquire -> edit -> discard -> wait ->
 * reacquire -> submit -> wait -> verify.
 *
 * [isCurrent] binds every side effect to the browser window generation that
 * requested the redirect. A window/package change aborts before the next action.
 */
internal class WebsiteRedirectionCoordinator(
    private val context: Context,
    private val browserPackageName: String,
    private val expectedWindowId: Int,
    private val httpsHandlerRecognized: Boolean,
    private val redirectUrl: String,
    private val rootProvider: () -> AccessibilityNodeInfo?,
    private val waitForUiMutation: suspend () -> Unit,
    private val performBack: () -> Boolean,
    private val isRedirectAddress: (String?) -> Boolean,
    private val verifyDestination: suspend () -> Boolean,
    private val isCurrent: () -> Boolean = { true },
    private val onPhase: (WebsiteRedirectionPhase) -> Unit = {}
) {
    enum class SubmissionMethod {
        IME_ENTER,
        ANNOUNCED_EDITOR_ACTION,
        CERTIFIED_GO_BUTTON
    }

    sealed class Outcome {
        data class Redirected(
            val attempts: Int,
            val submissionMethod: SubmissionMethod
        ) : Outcome()

        data class FailClosed(val attempts: Int) : Outcome()
    }

    suspend fun redirect(): Outcome {
        var attempt = 1
        while (attempt <= WebsiteRedirectionPlan.MAX_SAME_TAB_ATTEMPTS && isCurrent()) {
            val submissionMethod = runAttempt()
            if (!isCurrent()) return Outcome.FailClosed(attempt)
            if (submissionMethod != null) {
                onPhase(WebsiteRedirectionPhase.VERIFY_DESTINATION)
                if (isCurrent() && verifyDestination() && isCurrent()) {
                    onPhase(WebsiteRedirectionPhase.REDIRECT_CONFIRMED)
                    return Outcome.Redirected(attempt, submissionMethod)
                }
            }

            if (!WebsiteRedirectionPlan.canRetry(attempt) || !isCurrent()) break
            onPhase(WebsiteRedirectionPhase.BACK_AND_RETRY)
            if (!isCurrent() || !performBack()) break
            waitForUiMutation()
            if (!isCurrent()) break
            attempt += 1
        }

        onPhase(WebsiteRedirectionPhase.FAIL_CLOSED)
        return Outcome.FailClosed(attempt.coerceAtMost(WebsiteRedirectionPlan.MAX_SAME_TAB_ATTEMPTS))
    }

    private suspend fun runAttempt(): SubmissionMethod? {
        if (!isCurrent()) return null
        onPhase(WebsiteRedirectionPhase.ACTIVATE_ADDRESS_BAR)
        val activated = withFreshRoot { root ->
            AddressBarRedirectionActions.activateAddressBar(
                root = root,
                browserPackageName = browserPackageName,
                expectedWindowId = expectedWindowId,
                httpsHandlerRecognized = httpsHandlerRecognized
            )
        } ?: return null
        if (!activated.accepted || !isCurrent()) return null

        waitForUiMutation()
        if (!isCurrent()) return null
        onPhase(WebsiteRedirectionPhase.REIDENTIFY_EDITOR)
        val editorIdentification = WebsiteIdentificationEngine.reidentifyAfterInteraction(
            rootProvider = { if (isCurrent()) rootProvider() else null },
            browserPackageName = browserPackageName,
            expectedWindowId = expectedWindowId,
            httpsHandlerRecognized = httpsHandlerRecognized
        )
        if (!isCurrent() ||
            editorIdentification.status == WebsiteIdentificationStatus.REJECTED_CONTEXT ||
            !editorIdentification.addressBarObservable
        ) return null

        onPhase(WebsiteRedirectionPhase.SELECT_ALL)
        val selection = withFreshRoot { root ->
            AddressBarRedirectionActions.selectAll(
                root = root,
                browserPackageName = browserPackageName,
                expectedWindowId = expectedWindowId,
                httpsHandlerRecognized = httpsHandlerRecognized
            )
        }
        if (!isCurrent() || selection?.status == AddressBarRedirectionActions.Status.AMBIGUOUS) return null
        if (selection?.accepted == true) {
            waitForUiMutation()
            if (!isCurrent()) return null
        }

        onPhase(WebsiteRedirectionPhase.SET_TEXT)
        var written = withFreshRoot { root ->
            AddressBarRedirectionActions.setText(
                root = root,
                browserPackageName = browserPackageName,
                expectedWindowId = expectedWindowId,
                text = redirectUrl,
                httpsHandlerRecognized = httpsHandlerRecognized
            )
        }
        if (!isCurrent() || written?.status == AddressBarRedirectionActions.Status.AMBIGUOUS) return null

        if (written?.accepted != true) {
            if (!isCurrent()) return null
            onPhase(WebsiteRedirectionPhase.PASTE_FALLBACK)
            written = withFreshRoot { root ->
                if (!isCurrent()) return@withFreshRoot AddressBarRedirectionActions.Result(
                    AddressBarRedirectionActions.Status.NOT_FOUND
                )
                ClipboardPasteFallback.pasteSafely(
                    context = context,
                    text = redirectUrl
                ) {
                    if (!isCurrent()) {
                        AddressBarRedirectionActions.Result(
                            AddressBarRedirectionActions.Status.NOT_FOUND
                        )
                    } else {
                        AddressBarRedirectionActions.paste(
                            root = root,
                            browserPackageName = browserPackageName,
                            expectedWindowId = expectedWindowId,
                            httpsHandlerRecognized = httpsHandlerRecognized
                        )
                    }
                }
            }
        }
        if (!isCurrent() || written?.accepted != true) return null

        waitForUiMutation()
        if (!isCurrent()) return null
        onPhase(WebsiteRedirectionPhase.REIDENTIFY_SUBMITTER)
        val submitIdentification = WebsiteIdentificationEngine.reidentifyAfterInteraction(
            rootProvider = { if (isCurrent()) rootProvider() else null },
            browserPackageName = browserPackageName,
            expectedWindowId = expectedWindowId,
            httpsHandlerRecognized = httpsHandlerRecognized
        )
        if (!isCurrent() ||
            submitIdentification.status == WebsiteIdentificationStatus.REJECTED_CONTEXT ||
            !submitIdentification.addressBarObservable ||
            !isRedirectAddress(submitIdentification.bestCandidate)
        ) return null

        onPhase(WebsiteRedirectionPhase.IME_ENTER)
        val ime = withFreshRoot { root ->
            AddressBarRedirectionActions.submitImeEnter(
                root = root,
                browserPackageName = browserPackageName,
                expectedWindowId = expectedWindowId,
                textPredicate = isRedirectAddress,
                httpsHandlerRecognized = httpsHandlerRecognized
            )
        }
        if (!isCurrent()) return null
        if (ime?.accepted == true) return SubmissionMethod.IME_ENTER
        if (ime?.status == AddressBarRedirectionActions.Status.AMBIGUOUS) return null

        onPhase(WebsiteRedirectionPhase.ANNOUNCED_EDITOR_ACTION)
        val announced = withFreshRoot { root ->
            AddressBarRedirectionActions.submitAnnouncedEditorAction(
                root = root,
                browserPackageName = browserPackageName,
                expectedWindowId = expectedWindowId,
                textPredicate = isRedirectAddress,
                httpsHandlerRecognized = httpsHandlerRecognized
            )
        }
        if (!isCurrent()) return null
        if (announced?.accepted == true) return SubmissionMethod.ANNOUNCED_EDITOR_ACTION
        if (announced?.status == AddressBarRedirectionActions.Status.AMBIGUOUS) return null

        onPhase(WebsiteRedirectionPhase.CERTIFIED_GO_BUTTON)
        val go = withFreshRoot { root ->
            AddressBarRedirectionActions.clickCertifiedGoButton(
                root = root,
                browserPackageName = browserPackageName,
                expectedWindowId = expectedWindowId
            )
        }
        return if (isCurrent() && go?.accepted == true) SubmissionMethod.CERTIFIED_GO_BUTTON else null
    }

    private inline fun <T> withFreshRoot(block: (AccessibilityNodeInfo) -> T): T? {
        if (!isCurrent()) return null
        val root = rootProvider() ?: return null
        return try {
            if (!isCurrent()) return null
            val matches = runCatching {
                root.packageName?.toString() == browserPackageName &&
                    root.windowId == expectedWindowId
            }.getOrDefault(false)
            if (!matches || !isCurrent()) null else block(root)
        } finally {
            recycleSafely(root)
        }
    }

    private fun recycleSafely(node: AccessibilityNodeInfo?) {
        if (node == null) return
        @Suppress("DEPRECATION")
        runCatching { node.recycle() }
    }
}
