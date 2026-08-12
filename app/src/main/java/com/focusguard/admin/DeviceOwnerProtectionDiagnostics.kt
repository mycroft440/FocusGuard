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
    val protectionArmed: Boolean,
    val blockingProtectionArmed: Boolean,
    val adultContentProtectionArmed: Boolean,
    val otherAppsControlAvailable: Boolean,
    val uninstallBlocked: Boolean?,
    val userControlDisabled: Boolean?,
    val factoryResetBlocked: Boolean?,
    val safeBootBlocked: Boolean?,
    val addUserBlocked: Boolean?,
    val removeUserBlocked: Boolean?,
    val userSwitchBlocked: Boolean?,
    val privateProfileCreationBlocked: Boolean?,
    val dateTimeChangesBlocked: Boolean?,
    val notificationPermissionLocked: Boolean?,
    val accessibilityServiceEnabled: Boolean,
    val usageAccessEnabled: Boolean,
    val batteryOptimizationExempt: Boolean,
    val automaticTimeEnabled: Boolean?,
    val automaticTimeZoneEnabled: Boolean?,
    val adultFilterEnabled: Boolean,
    val adultDnsEnforced: Boolean?,
    val privateDnsChangesBlocked: Boolean?,
    val vpnConfigurationBlocked: Boolean?
) {
    val isFullyProtected: Boolean
        get() = deviceOwnerActive && otherAppsControlAvailable && when {
            maintenanceActive -> false
            !protectionArmed -> essentialPermissionsVerified
            else -> uninstallBlocked == true &&
                userControlDisabled != false &&
                factoryResetBlocked == true &&
                safeBootBlocked == true &&
                userIsolationVerified &&
                dateTimeChangesBlocked == true &&
                notificationPermissionLocked != false &&
                essentialPermissionsVerified &&
                automaticTimeEnabled != false &&
                automaticTimeZoneEnabled != false &&
                adultContentProtectionVerified
        }

    private val essentialPermissionsVerified: Boolean
        get() = accessibilityServiceEnabled &&
            usageAccessEnabled &&
            batteryOptimizationExempt

    private val adultContentProtectionVerified: Boolean
        get() = !adultContentProtectionArmed ||
            (adultDnsEnforced == true &&
                privateDnsChangesBlocked == true &&
                vpnConfigurationBlocked == true)

    private val userIsolationVerified: Boolean
        get() = addUserBlocked == true &&
            removeUserBlocked == true &&
            userSwitchBlocked != false &&
            privateProfileCreationBlocked != false

    val failedChecks: Int
        get() = buildList<Boolean?> {
            add(deviceOwnerActive)
            add(otherAppsControlAvailable)
            add(accessibilityServiceEnabled)
            add(usageAccessEnabled)
            add(batteryOptimizationExempt)
            if (protectionArmed) {
                add(uninstallBlocked)
                add(userControlDisabled)
                add(factoryResetBlocked)
                add(safeBootBlocked)
                add(addUserBlocked)
                add(removeUserBlocked)
                add(userSwitchBlocked)
                add(privateProfileCreationBlocked)
                add(dateTimeChangesBlocked)
                add(notificationPermissionLocked)
                add(automaticTimeEnabled)
                add(automaticTimeZoneEnabled)
                if (adultContentProtectionArmed) {
                    add(adultDnsEnforced ?: false)
                    add(privateDnsChangesBlocked ?: false)
                    add(vpnConfigurationBlocked ?: false)
                }
            }
        }.count { it == false }
}
