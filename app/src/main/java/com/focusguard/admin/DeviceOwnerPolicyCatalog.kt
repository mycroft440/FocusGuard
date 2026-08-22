package com.focusguard.admin

import android.app.admin.DevicePolicyManager
import android.os.Build
import android.os.Bundle
import android.os.UserManager

/** Immutable Android policy catalogue shared by Device Owner collaborators. */
internal object DeviceOwnerPolicyCatalog {
    const val MAX_MANAGED_URLS = 1_000
    const val ADULT_DNS_HOST = "family-filter-dns.cleanbrowsing.org"

    val chromeManagedPackages = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary"
    )
    val edgeManagedPackages = setOf(
        "com.microsoft.emmx",
        "com.microsoft.emmx.beta",
        "com.microsoft.emmx.dev",
        "com.microsoft.emmx.canary"
    )
    val sacredWhitelist = setOf(
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.server.telecom",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.google.android.gms",
        "com.android.vending"
    )
    val phoneLockTaskPackages = listOf(
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.phone",
        "com.android.server.telecom",
        "com.samsung.android.dialer",
        "com.samsung.android.incallui",
        "com.miui.dialer",
        "com.coloros.dialer",
        "com.oplus.dialer"
    )
    val alwaysOnRestrictions = listOf(
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_CONFIG_DATE_TIME
    )

    fun activeBlockRestrictionsForSdk(sdkInt: Int): List<String> = buildList {
        add(UserManager.DISALLOW_ADD_USER)
        add(UserManager.DISALLOW_REMOVE_USER)
        if (sdkInt >= Build.VERSION_CODES.P) add(UserManager.DISALLOW_USER_SWITCH)
        if (sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            add(UserManager.DISALLOW_ADD_PRIVATE_PROFILE)
        }
    }

    fun legacyGlobalAppControlRestrictionsForSdk(sdkInt: Int): List<String> = buildList {
        add(UserManager.DISALLOW_UNINSTALL_APPS)
        add(UserManager.DISALLOW_APPS_CONTROL)
        if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(UserManager.DISALLOW_GRANT_ADMIN)
        }
    }

    fun adultContentRestrictionsForSdk(sdkInt: Int): List<String> = buildList {
        add(UserManager.DISALLOW_CONFIG_VPN)
        if (sdkInt >= Build.VERSION_CODES.Q) {
            add(UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
        }
    }

    fun allShieldRestrictionsForSdk(sdkInt: Int): List<String> =
        alwaysOnRestrictions +
            adultContentRestrictionsForSdk(sdkInt) +
            activeBlockRestrictionsForSdk(sdkInt)

    fun allRestrictionsForCleanupForSdk(sdkInt: Int): List<String> =
        allShieldRestrictionsForSdk(sdkInt) +
            legacyGlobalAppControlRestrictionsForSdk(sdkInt)

    fun buildManagedBrowserRestrictions(
        existing: Bundle,
        managedFilters: List<String>,
        privateModePolicy: String,
        requireSystemDns: Boolean
    ): Bundle = Bundle(existing).apply {
        remove("URLBlocklist")
        remove("IncognitoModeAvailability")
        remove("InPrivateModeAvailability")
        remove("DnsOverHttpsMode")
        remove("DnsOverHttpsTemplates")
        if (requireSystemDns) putString("DnsOverHttpsMode", "off")
        if (managedFilters.isNotEmpty()) {
            putStringArray("URLBlocklist", managedFilters.toTypedArray())
            putInt(privateModePolicy, 1)
        }
    }

    fun supportsStrictFocusModeLockdown(sdkInt: Int): Boolean =
        sdkInt >= Build.VERSION_CODES.P

    fun lockTaskFeaturesKeepOnlyGlobalActions(features: Int): Boolean =
        features == DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
}
