from pathlib import Path


def replace_required(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"{label} not found")
    return text.replace(old, new, 1)


service_path = Path("app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt")
text = service_path.read_text()

text = replace_required(
    text,
    """        fun onFailureOrTimeout(): WebsiteTransitionAction {
            check(state != State.FINISHED)
            state = State.FINISHED
            // Website blocking must never close the browser or evict it to HOME.
            // If same-tab sanitization cannot be confirmed, release only the curtain;
            // the still-blocked URL will be intercepted again on the next browser event.
            return WebsiteTransitionAction.HIDE_CURTAIN
        }
""",
    """        fun onFailureOrTimeout(): WebsiteTransitionAction {
            check(state != State.FINISHED)
            state = State.FINISHED
            // Failure is terminal for this transition, but the caller must not reveal
            // an unsafe browser surface. It routes to FocusGuard's generic fail-closed
            // notice whenever same-tab sanitization or destination confirmation fails.
            return WebsiteTransitionAction.HIDE_CURTAIN
        }
""",
    "state-machine failure block",
)

text = replace_required(
    text,
    """            val root = rootInActiveWindow ?: return
            val windowId = root.windowId
""",
    """            val root = rootInActiveWindow
            if (root == null) {
                handleBrowserObservability(currentPackage, addressBarObservable = false)
                return
            }
            val windowId = root.windowId
""",
    "foreground root block",
)

text = replace_required(
    text,
    """            val blockedCandidate = candidate?.takeIf(String::isNotBlank) ?: return
            if (WebsiteBlocker.findMatchingRule(
""",
    """            val blockedCandidate = candidate?.takeIf(String::isNotBlank)
            if (blockedCandidate == null) {
                handleBrowserObservability(currentPackage, addressBarObservable = false)
                return
            }
            if (WebsiteBlocker.findMatchingRule(
""",
    "foreground candidate block",
)

text = replace_required(
    text,
    """                if (!redirectRequested) {
                    FocusGuardLogger.log(
                        \"A11y\",
                        \"Navegador preservado: redirecionamento na mesma aba não pôde ser certificado \" +
                            \"para $browserPackageName (API ${Build.VERSION.SDK_INT})\"
                    )
                    stateMachine.onFailureOrTimeout()
                    releaseWebsiteCurtainAfterMinimumNotice(
                        curtainGeneration = curtainGeneration,
                        curtainShownAtUptimeMillis = curtainShownAtUptimeMillis
                    )
                    return@launch
                }
""",
    """                if (!redirectRequested) {
                    FocusGuardLogger.log(
                        \"A11y\",
                        \"Redirecionamento na mesma aba não pôde ser certificado para \" +
                            \"$browserPackageName (API ${Build.VERSION.SDK_INT}); bloqueando fail-closed\"
                    )
                    stateMachine.onFailureOrTimeout()
                    launchOpaqueBrowserFailClosedNotice(
                        browserPackageName = browserPackageName,
                        eventUptimeMillis = SystemClock.uptimeMillis()
                    )
                    return@launch
                }
""",
    "redirect failure block",
)

text = replace_required(
    text,
    """                if (!googleConfirmed) {
                    stateMachine.onFailureOrTimeout()
                    releaseWebsiteCurtainAfterMinimumNotice(
                        curtainGeneration = curtainGeneration,
                        curtainShownAtUptimeMillis = curtainShownAtUptimeMillis
                    )
                    return@launch
                }
""",
    """                if (!googleConfirmed) {
                    stateMachine.onFailureOrTimeout()
                    launchOpaqueBrowserFailClosedNotice(
                        browserPackageName = browserPackageName,
                        eventUptimeMillis = SystemClock.uptimeMillis()
                    )
                    return@launch
                }
""",
    "google confirmation failure block",
)

text = replace_required(
    text,
    """                        } else {
                            stateMachine.onFailureOrTimeout()
                            releaseWebsiteCurtainAfterMinimumNotice(
                                curtainGeneration = curtainGeneration,
                                curtainShownAtUptimeMillis = curtainShownAtUptimeMillis
                            )
                        }
                    }

                    else -> {
                        stateMachine.onFailureOrTimeout()
                        releaseWebsiteCurtainAfterMinimumNotice(
                            curtainGeneration = curtainGeneration,
                            curtainShownAtUptimeMillis = curtainShownAtUptimeMillis
                        )
                    }
""",
    """                        } else {
                            stateMachine.onFailureOrTimeout()
                            launchOpaqueBrowserFailClosedNotice(
                                browserPackageName = browserPackageName,
                                eventUptimeMillis = SystemClock.uptimeMillis()
                            )
                        }
                    }

                    else -> {
                        stateMachine.onFailureOrTimeout()
                        launchOpaqueBrowserFailClosedNotice(
                            browserPackageName = browserPackageName,
                            eventUptimeMillis = SystemClock.uptimeMillis()
                        )
                    }
""",
    "strict destination failure block",
)

text = replace_required(
    text,
    """    private fun blockOpaqueBrowser(packageName: String) {
        opaqueBrowserFirstSeenElapsed.remove(packageName)
        opaqueBrowserVerificationScheduled.remove(packageName)
        stopWebsiteTracking()
        FocusGuardLogger.log(
            \"A11y\",
            \"Não foi possível observar a URL em $packageName; o navegador não será bloqueado \" +
                \"por inteiro\"
        )
    }
""",
    """    private fun blockOpaqueBrowser(packageName: String) {
        opaqueBrowserFirstSeenElapsed.remove(packageName)
        opaqueBrowserVerificationScheduled.remove(packageName)
        stopWebsiteTracking()
        if (!websiteObservationRequired() || foregroundPackageName != packageName) return
        FocusGuardLogger.log(
            \"A11y\",
            \"URL não observável em $packageName; bloqueando o navegador fail-closed \" +
                \"enquanto a proteção de sites exige observação\"
        )
        launchOpaqueBrowserFailClosedNotice(
            browserPackageName = packageName,
            eventUptimeMillis = SystemClock.uptimeMillis()
        )
    }

    private fun launchOpaqueBrowserFailClosedNotice(
        browserPackageName: String,
        eventUptimeMillis: Long
    ) {
        FocusGuardLogger.log(
            \"A11y\",
            \"Bloqueio fail-closed do navegador $browserPackageName: \" +
                \"uma superfície segura não pôde ser certificada\"
        )
        launchBlockNotice(
            blockedPackage = null,
            blockedDomain = null,
            redirectBrowserPackage = null,
            eventUptimeMillis = eventUptimeMillis
        )
    }
""",
    "opaque browser block",
)

service_path.write_text(text)

policy_path = Path("app/src/main/java/com/focusguard/utils/WebsiteObservabilityPolicy.kt")
policy_path.write_text("""package com.focusguard.utils

/**
 * Fail-closed policy for browsers that hide their current URL from Accessibility.
 *
 * Website rules cannot be enforced safely when FocusGuard cannot establish which
 * URL is open. A short grace period allows transient toolbar animations to settle;
 * after that, an opaque foreground browser must be blocked while website protection
 * still requires trustworthy URL observation.
 */
object WebsiteObservabilityPolicy {
    const val OPAQUE_BROWSER_GRACE_MILLIS = 200L

    fun shouldBlockOpaqueBrowser(
        websiteProtectionRequiresObservation: Boolean,
        browserStillForeground: Boolean,
        addressBarObservable: Boolean,
        firstUnobservableElapsed: Long?,
        nowElapsed: Long,
        graceMillis: Long = OPAQUE_BROWSER_GRACE_MILLIS
    ): Boolean {
        if (!websiteProtectionRequiresObservation || !browserStillForeground) return false
        if (addressBarObservable) return false
        val firstSeen = firstUnobservableElapsed ?: return false
        return nowElapsed - firstSeen >= graceMillis.coerceAtLeast(0L)
    }

    /** Compatibility alias retained for older callers/tests. */
    fun shouldStopObservingOpaqueBrowser(
        websiteProtectionRequiresObservation: Boolean,
        browserStillForeground: Boolean,
        addressBarObservable: Boolean,
        firstUnobservableElapsed: Long?,
        nowElapsed: Long,
        graceMillis: Long = OPAQUE_BROWSER_GRACE_MILLIS
    ): Boolean = shouldBlockOpaqueBrowser(
        websiteProtectionRequiresObservation = websiteProtectionRequiresObservation,
        browserStillForeground = browserStillForeground,
        addressBarObservable = addressBarObservable,
        firstUnobservableElapsed = firstUnobservableElapsed,
        nowElapsed = nowElapsed,
        graceMillis = graceMillis
    )
}
""")

test_path = Path("app/src/test/java/com/focusguard/utils/WebsiteObservabilityPolicyTest.kt")
test_path.write_text("""package com.focusguard.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebsiteObservabilityPolicyTest {
    @Test
    fun `opaque browser fails closed after grace while website protection is active`() {
        assertThat(
            WebsiteObservabilityPolicy.shouldBlockOpaqueBrowser(
                websiteProtectionRequiresObservation = true,
                browserStillForeground = true,
                addressBarObservable = false,
                firstUnobservableElapsed = 1_000L,
                nowElapsed = 1_800L,
                graceMillis = 800L
            )
        ).isTrue()
    }

    @Test
    fun `observable browser never enters opaque fail closed fallback`() {
        assertThat(
            WebsiteObservabilityPolicy.shouldBlockOpaqueBrowser(
                websiteProtectionRequiresObservation = true,
                browserStillForeground = true,
                addressBarObservable = true,
                firstUnobservableElapsed = 1_000L,
                nowElapsed = 9_000L
            )
        ).isFalse()
    }

    @Test
    fun `inactive protection or background browser never enters opaque fallback`() {
        assertThat(
            WebsiteObservabilityPolicy.shouldBlockOpaqueBrowser(
                websiteProtectionRequiresObservation = false,
                browserStillForeground = true,
                addressBarObservable = false,
                firstUnobservableElapsed = 1_000L,
                nowElapsed = 9_000L
            )
        ).isFalse()
        assertThat(
            WebsiteObservabilityPolicy.shouldBlockOpaqueBrowser(
                websiteProtectionRequiresObservation = true,
                browserStillForeground = false,
                addressBarObservable = false,
                firstUnobservableElapsed = 1_000L,
                nowElapsed = 9_000L
            )
        ).isFalse()
    }

    @Test
    fun `opaque browser receives grace before fail closed fallback`() {
        assertThat(
            WebsiteObservabilityPolicy.shouldBlockOpaqueBrowser(
                websiteProtectionRequiresObservation = true,
                browserStillForeground = true,
                addressBarObservable = false,
                firstUnobservableElapsed = 1_000L,
                nowElapsed = 1_799L,
                graceMillis = 800L
            )
        ).isFalse()
    }

    @Test
    fun `legacy observation alias keeps the same fail closed threshold`() {
        assertThat(
            WebsiteObservabilityPolicy.shouldStopObservingOpaqueBrowser(
                websiteProtectionRequiresObservation = true,
                browserStillForeground = true,
                addressBarObservable = false,
                firstUnobservableElapsed = 1_000L,
                nowElapsed = 1_800L,
                graceMillis = 800L
            )
        ).isEqualTo(
            WebsiteObservabilityPolicy.shouldBlockOpaqueBrowser(
                websiteProtectionRequiresObservation = true,
                browserStillForeground = true,
                addressBarObservable = false,
                firstUnobservableElapsed = 1_000L,
                nowElapsed = 1_800L,
                graceMillis = 800L
            )
        )
    }
}
""")

docs_path = Path("docs/WEBSITE_BLOCKING.md")
docs = docs_path.read_text()
marker = "## Navegadores sem URL observável (fail-closed)"
if marker not in docs:
    docs += """

## Navegadores sem URL observável (fail-closed)

Quando uma proteção de site ou um limite rígido exige conhecer a URL atual, o FocusGuard concede apenas uma janela curta para a barra de endereço aparecer na árvore de acessibilidade. Se o navegador continuar opaco, ele é bloqueado por uma superfície genérica do FocusGuard em vez de continuar utilizável sem fiscalização. Isso fecha o bypass de navegadores que ocultam a URL, inclusive o Via quando sua interface não expõe um endereço confiável.

O mesmo princípio vale para a neutralização de uma página já identificada como bloqueada: se a reescrita na mesma aba ou a confirmação do destino seguro não puder ser certificada, o FocusGuard mantém o fluxo fail-closed e mostra a superfície de bloqueio; ele não devolve a página bloqueada ao usuário.
"""
    docs_path.write_text(docs)
