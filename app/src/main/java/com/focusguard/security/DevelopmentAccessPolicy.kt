package com.focusguard.security

/** Pure policy for the debug-only development escape hatch. */
object DevelopmentAccessPolicy {

    fun isAvailable(isDebugBuild: Boolean, configuredPassword: String): Boolean =
        isDebugBuild && configuredPassword.isNotEmpty()

    fun acceptsPassword(
        input: String,
        isDebugBuild: Boolean,
        configuredPassword: String
    ): Boolean = isAvailable(isDebugBuild, configuredPassword) &&
        input == configuredPassword
}
