package com.focusguard.service

import com.focusguard.accessibility.website.blocking.WebsiteBlockDecisionPolicy
import com.focusguard.accessibility.website.blocking.WebsiteProtectionFlowPolicy

/**
 * Compatibility facade for service callers.
 *
 * Identification finishes before callers enter this facade. The blocking layer then decides
 * ownership and an explicit next step; the service remains responsible only for presentation
 * and execution of that step.
 */
internal object WebsiteProtectionHierarchyPolicy {
    enum class Owner { HARD, PASSWORD, NONE }

    enum class NextStep {
        REDIRECT_BLOCKED_TAB,
        SHOW_PASSWORD_BLOCK,
        ALLOW
    }

    data class Resolution(
        val owner: Owner,
        val matchedRule: String? = null,
        val nextStep: NextStep = NextStep.ALLOW
    )

    fun resolve(
        candidate: String,
        passwordRules: Collection<String>,
        strongerRules: Collection<String>
    ): Resolution {
        val decision = WebsiteProtectionFlowPolicy.afterIdentification(
            identifiedCandidate = candidate,
            passwordRules = passwordRules,
            strongerRules = strongerRules
        )
        return Resolution(
            owner = when (decision.owner) {
                WebsiteBlockDecisionPolicy.Owner.HARD -> Owner.HARD
                WebsiteBlockDecisionPolicy.Owner.PASSWORD -> Owner.PASSWORD
                WebsiteBlockDecisionPolicy.Owner.NONE -> Owner.NONE
            },
            matchedRule = decision.matchedRule,
            nextStep = when (decision.nextStep) {
                WebsiteProtectionFlowPolicy.NextStep.REDIRECT_BLOCKED_TAB ->
                    NextStep.REDIRECT_BLOCKED_TAB
                WebsiteProtectionFlowPolicy.NextStep.SHOW_PASSWORD_BLOCK ->
                    NextStep.SHOW_PASSWORD_BLOCK
                WebsiteProtectionFlowPolicy.NextStep.ALLOW -> NextStep.ALLOW
            }
        )
    }
}
