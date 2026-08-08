package com.focusguard.security

import com.focusguard.utils.WebsiteBlocker

/**
 * What each kind of protection is allowed to target.
 *
 * The three blocks do not cover the same ground, and the difference is a product
 * decision rather than a limitation:
 *
 *  - **Password block**: apps only. Its exit is a password typed on the lock
 *    screen the app puts in front of the blocked target, which is something it
 *    can only do for an app it detects in the foreground. A site or a word would
 *    promise a prompt the block cannot reliably show.
 *  - **Daily limit**: apps and sites. A limit counts time against a target, and
 *    both apps and sites are measurable. A word is not a target you can spend
 *    two hours on, so keywords are out.
 *  - **Dopamine fast (time block)**: apps, sites and words. It is the strictest
 *    block and the one people arm against a whole habit, so it takes the widest
 *    net — including a word that matches any domain containing it.
 *
 * Decided here, in one place, so the wizard, the limits screen and
 * [com.focusguard.manager.BlockingSessionManager] cannot drift apart: the UI
 * hides what a block cannot take, and the manager drops it again on the way in.
 */
object BlockTargetPolicy {

    const val SESSION_TYPE_PASSWORD = "PASSWORD"
    const val SESSION_TYPE_TIME = "TIME"
    const val SESSION_TYPE_POMODORO = "POMODORO"

    /**
     * @param apps installed or preventive app packages.
     * @param websites domain rules, including the pornography category shortcut.
     * @param keywords `keyword:` rules that match any domain containing the word.
     */
    data class Kinds(
        val apps: Boolean,
        val websites: Boolean,
        val keywords: Boolean
    ) {
        /** True when the picker has more than one kind to switch between. */
        val needsTabs: Boolean
            get() = listOf(apps, websites, keywords).count { it } > 1
    }

    val APPS_ONLY = Kinds(apps = true, websites = false, keywords = false)

    /** Usage limits measure time spent, so they take targets but never words. */
    val DAILY_LIMIT = Kinds(apps = true, websites = true, keywords = false)

    fun forSessionType(sessionType: String): Kinds = when (sessionType.uppercase()) {
        SESSION_TYPE_TIME, SESSION_TYPE_POMODORO ->
            Kinds(apps = true, websites = true, keywords = true)

        else -> APPS_ONLY
    }

    /**
     * Normalizes website rules and drops the ones [kinds] does not accept.
     *
     * Returns normalized rules so callers persist the same shape the matcher
     * reads back — a rule that survives the filter is ready to store.
     */
    fun acceptedRules(kinds: Kinds, rules: Collection<String>): Set<String> {
        if (!kinds.websites && !kinds.keywords) return emptySet()
        return WebsiteBlocker.normalizeRules(rules).filterTo(linkedSetOf()) { rule ->
            if (WebsiteBlocker.isKeywordRule(rule)) kinds.keywords else kinds.websites
        }
    }

    /** Convenience for callers that only have the session type at hand. */
    fun acceptedRulesForSessionType(
        sessionType: String,
        rules: Collection<String>
    ): Set<String> = acceptedRules(forSessionType(sessionType), rules)
}
