package com.focusguard.admin

/**
 * Snapshot of the Device Owner policies that matter to FocusGuard self-protection.
 *
 * Nullable values mean the platform API is unavailable on the current Android version.
 * Query failures on supported APIs are normalized to `false` by the auditor, so an OEM
 * read error can never produce a false "fully protected" result.
 */
data class DeviceOwnerProtectionDiagnostics(
    val deviceAdminActive: Boolean,
    val deviceOwnerActive: Boolean,
    val maintenanceActive: Boolean,
    val blockingProtectionArmed: Boolean,
    val uninstallBlocked: Boolean?,
    val appsControlBlocked: Boolean?,
    val userControlDisabled: Boolean?,
    val factoryResetBlocked: Boolean?,
    val safeBootBlocked: Boolean?,
    val debuggingBlocked: Boolean?,
    val dateTimeChangesBlocked: Boolean?,
    val grantAdminBlocked: Boolean?,
    val automaticTimeEnabled: Boolean?,
    val automaticTimeZoneEnabled: Boolean?,
    val adultFilterEnabled: Boolean,
    val adultDnsEnforced: Boolean?,
    val privateDnsChangesBlocked: Boolean?,
    val vpnConfigurationBlocked: Boolean?
) {
    val isFullyProtected: Boolean
        get() = deviceOwnerActive &&
            !maintenanceActive &&
            uninstallBlocked == true &&
            appsControlBlocked == true &&
            userControlDisabled != false &&
            factoryResetBlocked == true &&
            safeBootBlocked == true &&
            blockingProtectionVerified &&
            dateTimeChangesBlocked == true &&
            grantAdminBlocked != false &&
            automaticTimeEnabled != false &&
            automaticTimeZoneEnabled != false &&
            adultContentProtectionVerified

    private val adultContentProtectionVerified: Boolean
        get() = !adultFilterEnabled ||
            (adultDnsEnforced == true &&
                privateDnsChangesBlocked == true &&
                vpnConfigurationBlocked == true)

    private val blockingProtectionVerified: Boolean
        get() = !blockingProtectionArmed || debuggingBlocked == true

    val failedChecks: Int
        get() = buildList<Boolean?> {
            add(deviceOwnerActive)
            add(uninstallBlocked)
            add(appsControlBlocked)
            add(userControlDisabled)
            add(factoryResetBlocked)
            add(safeBootBlocked)
            if (blockingProtectionArmed) add(debuggingBlocked ?: false)
            add(dateTimeChangesBlocked)
            add(grantAdminBlocked)
            add(automaticTimeEnabled)
            add(automaticTimeZoneEnabled)
            if (adultFilterEnabled) {
                add(adultDnsEnforced ?: false)
                add(privateDnsChangesBlocked ?: false)
                add(vpnConfigurationBlocked ?: false)
            }
        }.count { it == false }
}
