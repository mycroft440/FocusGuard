package com.focusguard.service

import com.focusguard.accessibility.website.blocking.WebsiteBlockDecisionPolicy

/**
 * Compatibility facade for service callers.
 *
 * The rule decision itself lives in `accessibility.website.blocking`; the service
 * package keeps this facade only so the accessibility orchestrator does not own
 * matching policy.
 */
internal object WebsiteProtectionHierarchyPolicy {
    enum class Owner { HARD, PASSWORD, NONE }

    data class Resolution(
        val owner: Owner,
        val matchedRule: String? = null
    )

    fun resolve(
        candidate: String,
        passwordRules: Collection<String>,
        strongerRules: Collection<String>
    ): Resolution {
        val resolution = WebsiteBlockDecisionPolicy.resolve(
            candidate = candidate,
            passwordRules = passwordRules,
            strongerRules = strongerRules
        )
        return Resolution(
            owner = when (resolution.owner) {
                WebsiteBlockDecisionPolicy.Owner.HARD -> Owner.HARD
                WebsiteBlockDecisionPolicy.Owner.PASSWORD -> Owner.PASSWORD
                WebsiteBlockDecisionPolicy.Owner.NONE -> Owner.NONE
            },
            matchedRule = resolution.matchedRule
        )
    }
}
