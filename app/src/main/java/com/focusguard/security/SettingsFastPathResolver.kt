package com.focusguard.security

/**
 * Enforces the architectural rule that remote Accessibility context is lazy.
 * Tests can inject a counting supplier and prove conclusive L0 decisions never
 * touch event.source/root/findByText.
 */
object SettingsFastPathResolver {
    fun resolve(
        input: SettingsLocalFastPathPolicy.Input,
        remoteContext: () -> SettingsLocalFastPathPolicy.Decision
    ): SettingsLocalFastPathPolicy.Decision {
        val local = SettingsLocalFastPathPolicy.decide(input)
        return if (local.action == SettingsLocalFastPathPolicy.Action.NEED_REMOTE) {
            remoteContext()
        } else {
            local
        }
    }
}
