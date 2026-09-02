package com.focusguard.security

import com.focusguard.utils.WebsiteBlocker

/**
 * What each kind of protection is allowed to target.
 *
 * The three blocks deliberately cover different surfaces:
 *
 *  - **Password block**: apps and sites. Each target uses the credential configured
 *    for that PASSWORD block; the master credential is never involved. Keywords
 *    stay out because a substring rule is too broad for a personal access lock.
 *  - **Daily limit**: apps and sites. A limit counts time against a target, and
 *    both apps and sites are measurable. A word is not a target you can spend
 *    two hours on, so keywords are out.
 *  - **Dopamine fast (time block)**: apps, sites and words. It is the strictest
 *    block and can cover a whole habit, including a keyword rule.
 *
 * Decided here so the wizard, limits UI and BlockingSessionManager cannot drift
 * apart: the UI hides unsupported target kinds and the manager filters again on
 * persistence.
 */
object BlockTargetPolicy {

    const val SESSION_TYPE_PASSWORD = "PASSWORD"
    const val SESSION_TYPE_TIME = "TIME"
    const val SESSION_TYPE_POMODORO = "POMODORO"

    data class Kinds(
        val apps: Boolean,
        val websites: Boolean,
        val keywords: Boolean
    ) {
        val needsTabs: Boolean
            get() = listOf(apps, websites, keywords).count { it } > 1
    }

    val APPS_ONLY = Kinds(apps = true, websites = false, keywords = false)
    val APPS_AND_WEBSITES = Kinds(apps = true, websites = true, keywords = false)

    /** Usage limits measure time spent, so they take targets but never words. */
    val DAILY_LIMIT = APPS_AND_WEBSITES

    fun forSessionType(sessionType: String): Kinds = when (sessionType.uppercase()) {
        SESSION_TYPE_PASSWORD -> APPS_AND_WEBSITES
        SESSION_TYPE_TIME, SESSION_TYPE_POMODORO ->
            Kinds(apps = true, websites = true, keywords = true)
        else -> APPS_ONLY
    }

    fun acceptedRules(kinds: Kinds, rules: Collection<String>): Set<String> {
        if (!kinds.websites && !kinds.keywords) return emptySet()
        return WebsiteBlocker.normalizeRules(rules).filterTo(linkedSetOf()) { rule ->
            if (WebsiteBlocker.isKeywordRule(rule)) kinds.keywords else kinds.websites
        }
    }

    fun acceptedRulesForSessionType(
        sessionType: String,
        rules: Collection<String>
    ): Set<String> = acceptedRules(forSessionType(sessionType), rules)
}
