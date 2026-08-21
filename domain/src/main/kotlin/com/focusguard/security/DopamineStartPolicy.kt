package com.focusguard.security

/**
 * Classifies an already-authorized Jejum de Dopamina protection level.
 * ProtectionPermissionGate decides whether configuration is allowed first.
 */
object DopamineStartPolicy {

    data class Capabilities(
        val accessibilityEnabled: Boolean,
        val usageAccessEnabled: Boolean,
        val batteryOptimizationExempt: Boolean,
        val deviceOwnerActive: Boolean
    )

    enum class ProtectionLevel {
        UNAVAILABLE,
        SIMPLE,
        ARMORED
    }

    /**
     * This classifier intentionally describes platform capability, not product
     * readiness. FocusGuard requires every protection permission before calling it.
     * Device Owner upgrades the same session to native anti-removal policies.
     */
    fun protectionLevel(capabilities: Capabilities): ProtectionLevel = when {
        !capabilities.accessibilityEnabled -> ProtectionLevel.UNAVAILABLE
        capabilities.deviceOwnerActive -> ProtectionLevel.ARMORED
        else -> ProtectionLevel.SIMPLE
    }
}

