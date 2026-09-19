package com.focusguard.service

import com.focusguard.accessibility.website.blocking.WebsiteProtectionFlowPolicy

/**
 * Compatibility facade for service callers.
 *
 * Identification finishes before callers enter this facade. The blocking layer then decides
 * ownership and an explicit next step; the service remains responsible only for presentation
 * and execution of that step.
 *
 * [NextStep] is the source of truth here. [Owner] is derived from it only to preserve the existing
 * service contract while callers migrate toward the explicit flow decision.
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
        val nextStep = when (decision.nextStep) {
            WebsiteProtectionFlowPolicy.NextStep.REDIRECT_BLOCKED_TAB ->
                NextStep.REDIRECT_BLOCKED_TAB
            WebsiteProtectionFlowPolicy.NextStep.SHOW_PASSWORD_BLOCK ->
                NextStep.SHOW_PASSWORD_BLOCK
            WebsiteProtectionFlowPolicy.NextStep.ALLOW -> NextStep.ALLOW
        }
        val owner = when (nextStep) {
            NextStep.REDIRECT_BLOCKED_TAB -> Owner.HARD
            NextStep.SHOW_PASSWORD_BLOCK -> Owner.PASSWORD
            NextStep.ALLOW -> Owner.NONE
        }
        return Resolution(
            owner = owner,
            matchedRule = decision.matchedRule,
            nextStep = nextStep
        )
    }
}
