package com.focusguard.service

import com.focusguard.utils.WebsiteBlocker

/** Resolves only the PASSWORD-vs-stronger ownership of a website attempt. */
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
