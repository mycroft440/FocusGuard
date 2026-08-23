package com.focusguard.security

/** Pure policy for the package-scoped uninstall guard used by timed blocks. */
object TimedBlockProtectionPolicy {

    fun requiresUninstallProtection(
        sessionType: String,
        isActive: Boolean,
        endTimeMillis: Long?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (!isActive) return false
        if (!sessionType.equals("TIME", ignoreCase = true)) return false
        return endTimeMillis == null || endTimeMillis > nowMillis
    }
}
