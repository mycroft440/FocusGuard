package com.focusguard.security

import android.content.Context

/**
 * Records the user's explicit, versioned agreement to self-imposed protection.
 * This is intentionally separate from the sensitive-data disclosure: agreeing
 * to a commitment is not consent to Accessibility or Usage Access processing.
 */
object SelfProtectionConsent {

    internal const val CURRENT_VERSION = 1

    private const val PREFERENCES_NAME = "self_protection_consent"
    private const val ACCEPTED_VERSION_KEY = "accepted_version"
    private const val ACCEPTED_AT_KEY = "accepted_at"

    fun hasAccepted(context: Context): Boolean = isAcceptedVersion(
        preferences(context).getInt(ACCEPTED_VERSION_KEY, 0)
    )

    fun accept(context: Context, acceptedAtMillis: Long = System.currentTimeMillis()) {
        preferences(context).edit()
            .putInt(ACCEPTED_VERSION_KEY, CURRENT_VERSION)
            .putLong(ACCEPTED_AT_KEY, acceptedAtMillis)
            .apply()
    }

    internal fun isAcceptedVersion(storedVersion: Int): Boolean =
        storedVersion >= CURRENT_VERSION

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
