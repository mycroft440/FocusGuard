package com.focusguard.accessibility.website.blocking

import com.focusguard.utils.WebsiteBlocker

/**
 * Resolves ownership for a configured website rule without presenting UI or
 * performing navigation. Matching remains centralized in [WebsiteBlocker].
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
        val passwordRule = WebsiteBlocker
            .findMatchingRulesIgnoringGrants(candidate, passwordRules)
            .firstOrNull()
        if (passwordRule != null) {
            return Resolution(Owner.PASSWORD, passwordRule)
        }

        val strongerRule = WebsiteBlocker
            .findMatchingRulesIgnoringGrants(candidate, strongerRules)
            .firstOrNull()
        if (strongerRule != null) {
            return Resolution(Owner.HARD, strongerRule)
        }

        return Resolution(Owner.NONE)
    }
}
