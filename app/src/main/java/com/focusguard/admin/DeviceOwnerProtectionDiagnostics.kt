package com.focusguard.admin

/**
 * Snapshot of the Device Owner policies that matter to FocusGuard self-protection.
 *
 * Nullable values mean the platform API is unavailable on the current Android version
 * or the OEM did not allow the state to be queried. They are shown as unsupported,
 * never silently treated as protected.
 */
data class DeviceOwnerProtectionDiagnostics(
    val deviceAdminActive: Boolean,
    val deviceOwnerActive: Boolean,
    val maintenanceActive: Boolean,
    val uninstallBlocked: Boolean?,
    val userControlDisabled: Boolean?,
    val factoryResetBlocked: Boolean?,
    val safeBootBlocked: Boolean?,
    val dateTimeChangesBlocked: Boolean?,
    val grantAdminBlocked: Boolean?,
    val automaticTimeEnabled: Boolean?,
    val automaticTimeZoneEnabled: Boolean?
) {
    val isFullyProtected: Boolean
        get() = deviceOwnerActive &&
            !maintenanceActive &&
            uninstallBlocked == true &&
            userControlDisabled != false &&
            factoryResetBlocked == true &&
            safeBootBlocked == true &&
            dateTimeChangesBlocked == true &&
            grantAdminBlocked != false &&
            automaticTimeEnabled != false &&
            automaticTimeZoneEnabled != false

    val failedChecks: Int
        get() = listOf(
            deviceOwnerActive,
            uninstallBlocked,
            userControlDisabled,
            factoryResetBlocked,
            safeBootBlocked,
            dateTimeChangesBlocked,
            grantAdminBlocked,
            automaticTimeEnabled,
            automaticTimeZoneEnabled
        ).count { it == false }
}
