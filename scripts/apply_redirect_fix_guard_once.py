from pathlib import Path

path = Path("app/src/main/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectionCoordinator.kt")
text = path.read_text()

old = """            if (prepared) {
                var submitAlternativeNumber = 1
                while (submitAlternativeNumber <= WebsiteRedirectionPlan.MAX_SUBMIT_ALTERNATIVES) {
                    val submitted = adapter.submitSameTabRedirect(attemptNumber)
                    if (!adapter.ownsProtection()) return Outcome.ABORTED

                    if (submitted && adapter.awaitRedirectConfirmation()) {
"""
new = """            var submittedAtLeastOnce = false
            if (prepared) {
                var submitAlternativeNumber = 1
                while (submitAlternativeNumber <= WebsiteRedirectionPlan.MAX_SUBMIT_ALTERNATIVES) {
                    val submitted = adapter.submitSameTabRedirect(attemptNumber)
                    submittedAtLeastOnce = submittedAtLeastOnce || submitted
                    if (!adapter.ownsProtection()) return Outcome.ABORTED

                    if (submitted && adapter.awaitRedirectConfirmation()) {
"""
if text.count(old) != 1:
    raise SystemExit(f"Expected coordinator submit block once, found {text.count(old)}")
text = text.replace(old, new, 1)

old = """                if (adapter.awaitRedirectConfirmation()) {
                    if (!adapter.ownsProtection()) return Outcome.ABORTED
                    return completeConfirmedRedirect(session, adapter)
                }
"""
new = """                if (submittedAtLeastOnce && adapter.awaitRedirectConfirmation()) {
                    if (!adapter.ownsProtection()) return Outcome.ABORTED
                    return completeConfirmedRedirect(session, adapter)
                }
"""
if text.count(old) != 1:
    raise SystemExit(f"Expected delayed confirmation block once, found {text.count(old)}")
path.write_text(text.replace(old, new, 1))
