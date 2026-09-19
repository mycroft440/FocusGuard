package com.focusguard.accessibility.website.blocking

import com.focusguard.utils.WebsiteBlocker

/**
 * Pure website-rule ownership decision.
 *
 * This layer receives an already identified candidate and decides only which
 * configured protection owns it. It never reads Accessibility nodes, renders UI
 * or performs browser navigation.
 */
internal object WebsiteBlockDecisionPolicy {
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
        WebsiteBlocker.findMatchingRulesIgnoringGrants(
            candidate,
            strongerRules
        ).firstOrNull()?.let { return Resolution(Owner.HARD, it) }

        WebsiteBlocker.findMatchingRulesIgnoringGrants(
            candidate,
            passwordRules
        ).firstOrNull()?.let { return Resolution(Owner.PASSWORD, it) }

        return Resolution(Owner.NONE)
    }
}
