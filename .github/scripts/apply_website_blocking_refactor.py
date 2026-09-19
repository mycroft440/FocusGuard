from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
SERVICE = ROOT / "app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt"

service = SERVICE.read_text()

pattern = re.compile(
    r"        scope\.launch\(Dispatchers\.Main\.immediate\) \{\n"
    r"            try \{.*?"
    r"            \} finally \{\n"
    r"                finishWebsiteTransition\(transition\)\n"
    r"            \}\n"
    r"        \}\n"
    r"    \}\n\n\n"
    r"    /\*\*\n"
    r"     \* Normal website block presentation\.",
    re.S,
)

replacement = '''        scope.launch(Dispatchers.Main.immediate) {
            try {
                WebsiteRedirectionCoordinator.execute(
                    session = stateMachine,
                    adapter = object : WebsiteRedirectionCoordinator.Adapter {
                        override suspend fun awaitPresentationFrame(): Boolean {
                            // Let the already-warm overlay commit one display frame
                            // before touching browser UI, avoiding a blocked-page flash.
                            awaitNextWebsiteRedirectFrame()
                            return transitionOwnsCurtain(transition)
                        }

                        override fun ownsProtection(): Boolean =
                            transitionOwnsCurtain(transition)

                        override suspend fun requestSameTabRedirect(
                            attemptNumber: Int
                        ): Boolean {
                            if (!curtainReadyForTransition(transition)) return false
                            return requestSafeRedirectInCurrentTab(
                                browserPackageName = browserPackageName,
                                expectedWindowId = expectedWindowId,
                                policy = WebsiteTabNeutralizationPolicy(
                                    browserPackageName = browserPackageName,
                                    expectedWindowId = expectedWindowId
                                ),
                                transition = transition
                            )
                        }

                        override suspend fun restoreBlockedSurfaceForRetry(): Boolean =
                            restoreBlockedSurfaceAfterAddressEdit(transition)

                        override suspend fun beforeRetry(nextAttemptNumber: Int) {
                            FocusGuardLogger.log(
                                "A11y",
                                "Repetindo redirecionamento seguro na mesma aba de " +
                                    "$browserPackageName (tentativa $nextAttemptNumber)"
                            )
                            delay(WEBSITE_ADDRESS_BAR_ACTION_RETRY_MILLIS)
                        }

                        override suspend fun requestExternalFallback(): Boolean {
                            if (!transitionOwnsCurtain(transition) ||
                                !supportsCapabilityBasedIntentRedirectFallback(
                                    knownBrowser = browserPackageName in knownBrowserPackages,
                                    verifiedHttpsHandler = isVerifiedHttpsHandler(browserPackageName)
                                )
                            ) return false
                            FocusGuardLogger.log(
                                "A11y",
                                "Usando fallback por intent para destino seguro em $browserPackageName"
                            )
                            return requestSafeRedirectThroughBrowserIntent(transition)
                        }

                        override suspend fun awaitRedirectConfirmation(): Boolean =
                            withTimeoutOrNull(WEBSITE_DESTINATION_CONFIRM_TIMEOUT_MILLIS) {
                                transition.safeRedirectConfirmed.await()
                                true
                            } == true

                        override suspend fun completeStrictDestination(): Boolean =
                            completeStrictWebsiteDestination(
                                transition = transition,
                                curtainGeneration = curtainGeneration
                            )

                        override suspend fun releasePresentation() {
                            releaseWebsiteCurtainAfterMinimumNotice(
                                curtainGeneration = curtainGeneration,
                                curtainShownAtUptimeMillis = curtainShownAtUptimeMillis
                            )
                        }

                        override fun failClosed() {
                            FocusGuardLogger.log(
                                "A11y",
                                "Redirecionamento seguro não pôde ser certificado para " +
                                    "$browserPackageName (API ${Build.VERSION.SDK_INT}); " +
                                    "bloqueando fail-closed"
                            )
                            failClosedWebsiteTransition(transition)
                        }
                    }
                )
            } finally {
                finishWebsiteTransition(transition)
            }
        }
    }


    /**
     * Normal website block presentation.'''

service, count = pattern.subn(replacement, service, count=1)
if count != 1:
    raise RuntimeError(f"redirect orchestration block: expected one match, found {count}")

service = service.replace("AWAIT_GOOGLE_CONFIRMATION", "AWAIT_REDIRECT_CONFIRMATION")
service = service.replace(
    "REQUEST_SAFE_GOOGLE_AFTER_CONFIRMED_CLOSE",
    "REQUEST_SAFE_REDIRECT_AFTER_CONFIRMED_CLOSE",
)
service = service.replace("explicit package-scoped Google intent", "configured package-scoped redirect intent")

SERVICE.write_text(service)

# Keep service tests aligned with generic redirect terminology.
for path in (ROOT / "app/src/test").rglob("*.kt"):
    text = path.read_text()
    updated = text.replace("AWAIT_GOOGLE_CONFIRMATION", "AWAIT_REDIRECT_CONFIRMATION")
    updated = updated.replace(
        "REQUEST_SAFE_GOOGLE_AFTER_CONFIRMED_CLOSE",
        "REQUEST_SAFE_REDIRECT_AFTER_CONFIRMED_CLOSE",
    )
    if updated != text:
        path.write_text(updated)

print("Redirect coordinator extraction applied successfully")
