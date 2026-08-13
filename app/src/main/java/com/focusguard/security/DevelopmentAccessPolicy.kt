package com.focusguard.security

/** Pure policy for the password-protected technical exit. */
object DevelopmentAccessPolicy {

    fun isAvailable(configuredPassword: String): Boolean = configuredPassword.isNotEmpty()

    fun acceptsPassword(
        input: String,
        configuredPassword: String
    ): Boolean = isAvailable(configuredPassword) &&
        input == configuredPassword
}
