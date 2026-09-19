package com.focusguard.accessibility.website.blocking

/**
 * Pure hand-off contract between website identification and the presentation/redirection layers.
 *
 * Accessibility tree inspection must finish before this policy is called. From that point there is
 * exactly one next step: hard protection owns the candidate and the blocked tab must be sanitized,
 * password protection owns it and must show its authentication surface, or no blocking owner exists.
 * This keeps URL matching out of the redirection code and keeps navigation actions out of detection.
 */
internal object WebsiteProtectionFlowPolicy {
    enum class NextStep {
        REDIRECT_BLOCKED_TAB,
        SHOW_PASSWORD_BLOCK,
        ALLOW
    }

    data class Decision(
        val owner: WebsiteBlockDecisionPolicy.Owner,
        val matchedRule: String?,
        val nextStep: NextStep
    )

    fun afterIdentification(
        identifiedCandidate: String,
        passwordRules: Collection<String>,
        strongerRules: Collection<String>
    ): Decision {
        if (identifiedCandidate.isBlank()) {
            return Decision(
                owner = WebsiteBlockDecisionPolicy.Owner.NONE,
                matchedRule = null,
                nextStep = NextStep.ALLOW
            )
        }

        val resolution = WebsiteBlockDecisionPolicy.resolve(
            candidate = identifiedCandidate,
            passwordRules = passwordRules,
            strongerRules = strongerRules
        )
        val nextStep = when (resolution.owner) {
            WebsiteBlockDecisionPolicy.Owner.HARD -> NextStep.REDIRECT_BLOCKED_TAB
            WebsiteBlockDecisionPolicy.Owner.PASSWORD -> NextStep.SHOW_PASSWORD_BLOCK
            WebsiteBlockDecisionPolicy.Owner.NONE -> NextStep.ALLOW
        }
        return Decision(
            owner = resolution.owner,
            matchedRule = resolution.matchedRule,
            nextStep = nextStep
        )
    }
}
