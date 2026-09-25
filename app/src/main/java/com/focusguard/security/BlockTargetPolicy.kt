package com.focusguard.security

import com.focusguard.utils.WebsiteBlocker

/**
 * What each kind of protection is allowed to target.
 *
 *  - **Password block**, **daily limit** and **daily periods**: apps and sites.
 *  - **Block without password by time** (continuous TIME block): apps, sites and words.
 *    It is the only block that takes keyword rules.
 *
 * Sites and words are enforced by the site blocker (com.focusguard.sitesblocker).
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
    val APPS_WEBSITES_AND_KEYWORDS = Kinds(apps = true, websites = true, keywords = true)

    /** Usage limits measure time spent, so they take targets but never words. */
    val DAILY_LIMIT = APPS_AND_WEBSITES

    /**
     * @param continuousTime for TIME: true for "block without password by time" (the only
     *   block with words), false for daily periods.
     */
    fun forSessionType(sessionType: String, continuousTime: Boolean = true): Kinds =
        when (sessionType.uppercase()) {
            SESSION_TYPE_PASSWORD -> APPS_AND_WEBSITES
            SESSION_TYPE_TIME ->
                if (continuousTime) APPS_WEBSITES_AND_KEYWORDS else APPS_AND_WEBSITES
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
        rules: Collection<String>,
        continuousTime: Boolean = true
    ): Set<String> = acceptedRules(forSessionType(sessionType, continuousTime), rules)
}
