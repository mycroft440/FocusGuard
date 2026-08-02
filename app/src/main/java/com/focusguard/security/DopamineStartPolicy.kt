package com.focusguard.security

/** Selects the strongest available Jejum de Dopamina mode without making it mandatory. */
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
     * Accessibility is the only hard requirement for real-time app/site interception.
     * Usage access and unrestricted battery remain recommended, while Device Owner upgrades
     * the same session to native anti-removal policies when it is already provisioned.
     */
    fun protectionLevel(capabilities: Capabilities): ProtectionLevel = when {
        !capabilities.accessibilityEnabled -> ProtectionLevel.UNAVAILABLE
        capabilities.deviceOwnerActive -> ProtectionLevel.ARMORED
        else -> ProtectionLevel.SIMPLE
    }
}
