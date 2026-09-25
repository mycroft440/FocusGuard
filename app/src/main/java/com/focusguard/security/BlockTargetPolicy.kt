package com.focusguard.security

import com.focusguard.utils.WebsiteBlocker

/**
 * What each kind of protection is allowed to target.
 *
 * Password blocks, daily limits and time blocks target apps only. Website blocking
 * lives entirely in the site blocker (com.focusguard.sitesblocker), with its own list,
 * so no block persists or enforces website or keyword rules.
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

    val DAILY_LIMIT = APPS_ONLY

    @Suppress("UNUSED_PARAMETER")
    fun forSessionType(sessionType: String): Kinds = APPS_ONLY

    @Suppress("UNUSED_PARAMETER")
    fun acceptedRules(kinds: Kinds, rules: Collection<String>): Set<String> = emptySet()

    fun acceptedRulesForSessionType(
        sessionType: String,
        rules: Collection<String>
    ): Set<String> = acceptedRules(forSessionType(sessionType), rules)
}
