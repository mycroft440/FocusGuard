package com.focusguard.accessibility.website.blocking

/**
 * Runtime seam for website protection.
 *
 * Website selection, normalization and persistence intentionally remain available,
 * but enforcement is reset: no configured website currently owns a browser visit.
 * This keeps the configuration UI intact while the blocking implementation can be
 * rebuilt from zero without leaving an old redirect path active.
 */
internal object WebsiteBlockDecisionPolicy {
    enum class Owner { HARD, PASSWORD, NONE }

    data class Resolution(
        val owner: Owner,
        val matchedRule: String? = null
    )

    @Suppress("UNUSED_PARAMETER")
    fun resolve(
        candidate: String,
        passwordRules: Collection<String>,
        strongerRules: Collection<String>
    ): Resolution = Resolution(Owner.NONE)
}
