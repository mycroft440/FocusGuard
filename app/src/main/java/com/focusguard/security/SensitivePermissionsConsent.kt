package com.focusguard.security

import android.content.Context

/**
 * Stores the user's explicit acknowledgement of the sensitive-permissions
 * disclosure. Increment [CURRENT_VERSION] whenever the disclosure or data use
 * changes materially so the app asks again.
 */
object SensitivePermissionsConsent {

    internal const val CURRENT_VERSION = 1

    private const val PREFERENCES_NAME = "sensitive_permissions_consent"
    private const val ACCEPTED_VERSION_KEY = "accepted_version"

    fun hasAccepted(context: Context): Boolean {
        val storedVersion = preferences(context).getInt(ACCEPTED_VERSION_KEY, 0)
        return isAcceptedVersion(storedVersion)
    }

    fun accept(context: Context) {
        preferences(context).edit()
            .putInt(ACCEPTED_VERSION_KEY, CURRENT_VERSION)
            .apply()
    }

    internal fun isAcceptedVersion(storedVersion: Int): Boolean =
        storedVersion >= CURRENT_VERSION

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
