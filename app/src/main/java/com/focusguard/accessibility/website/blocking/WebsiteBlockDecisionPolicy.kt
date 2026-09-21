package com.focusguard.accessibility.website.blocking

import com.focusguard.utils.WebsiteBlocker

/**
 * Pure ownership decision for one identified browser target.
 *
 * Identification has already finished when this policy runs. Stronger protections
 * always win over PASSWORD, and only an effective PASSWORD rule can own the target
 * after temporary authenticated grants are applied.
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
        val identifiedCandidate = candidate.trim()
        if (identifiedCandidate.isEmpty()) return Resolution(Owner.NONE)

        // Stronger protections are ownership facts, not PASSWORD visit state. They
        // must remain visible even if the same rule currently has a temporary
        // PASSWORD grant, otherwise an exhausted limit/time block could be bypassed.
        val strongerMatch = WebsiteBlocker.findMatchingRulesIgnoringGrants(
            urlOrDomain = identifiedCandidate,
            configuredRules = strongerRules
        ).firstOrNull()
        if (strongerMatch != null) {
            return Resolution(
                owner = Owner.HARD,
                matchedRule = strongerMatch
            )
        }

        // PASSWORD is interactive. Its matcher deliberately observes/revokes the
        // visit grant lifecycle and omits a rule while that authenticated visit is
        // still valid.
        val normalizedPasswordRules = WebsiteBlocker.normalizeRules(passwordRules)
        val passwordMatch = WebsiteBlocker.findMatchingRule(
            urlOrDomain = identifiedCandidate,
            normalizedBlockedDomains = normalizedPasswordRules
        )
        if (passwordMatch != null) {
            return Resolution(
                owner = Owner.PASSWORD,
                matchedRule = passwordMatch
            )
        }

        return Resolution(Owner.NONE)
    }
}
