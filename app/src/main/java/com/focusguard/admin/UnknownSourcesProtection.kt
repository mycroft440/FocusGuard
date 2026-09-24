package com.focusguard.admin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.UserManager

/** Opt-in policy. Android persists it across restarts; no UI preference can claim enforcement. */
class UnknownSourcesProtection internal constructor(
    private val backend: Backend,
    private val sdkInt: Int
) {
    constructor(context: Context) : this(AndroidBackend(context.applicationContext), Build.VERSION.SDK_INT)

    enum class Scope { NONE, CURRENT_USER, ALL_USERS }
    enum class Result { APPLIED, OWNER_REQUIRED, FAILED }
    data class Status(val ownerActive: Boolean, val scope: Scope, val verified: Boolean) {
        val enabled: Boolean get() = verified && scope != Scope.NONE
    }

    internal interface Backend {
        fun isDeviceOwner(): Boolean
        fun restrictions(): Set<String>
        fun add(restriction: String)
        fun clear(restriction: String)
    }

    fun inspect(): Status = try {
        if (!backend.isDeviceOwner()) {
            Status(false, Scope.NONE, true)
        } else {
            val restrictions = backend.restrictions()
            val scope = when {
                sdkInt >= Build.VERSION_CODES.Q &&
                    UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY in restrictions -> Scope.ALL_USERS
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES in restrictions -> Scope.CURRENT_USER
                else -> Scope.NONE
            }
            Status(true, scope, true)
        }
    } catch (_: Exception) {
        Status(false, Scope.NONE, false)
    }

    fun setEnabled(enabled: Boolean): Result = try {
        if (!backend.isDeviceOwner()) {
            Result.OWNER_REQUIRED
        } else {
            if (enabled) {
                backend.add(if (sdkInt >= Build.VERSION_CODES.Q) {
                    UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY
                } else {
                    UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES
                })
            } else {
                // Also clear a per-user policy retained after an Android upgrade.
                restrictionsForCleanup(sdkInt).forEach(backend::clear)
            }
            val status = inspect()
            val expectedScope = if (!enabled) Scope.NONE else if (sdkInt >= Build.VERSION_CODES.Q) {
                Scope.ALL_USERS
            } else {
                Scope.CURRENT_USER
            }
            if (status.ownerActive && status.verified && status.scope == expectedScope) {
                Result.APPLIED
            } else {
                Result.FAILED
            }
        }
    } catch (_: Exception) {
        Result.FAILED
    }

    companion object {
        internal fun restrictionsForCleanup(sdkInt: Int): List<String> = buildList {
            add(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            if (sdkInt >= Build.VERSION_CODES.Q) add(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
        }
    }

    private class AndroidBackend(context: Context) : Backend {
        private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        private val admin = FocusGuardDeviceAdminReceiver.getComponentName(context)
        private val packageName = context.packageName

        override fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(packageName)
        override fun restrictions(): Set<String> {
            val bundle = dpm.getUserRestrictions(admin)
            return bundle.keySet().filterTo(mutableSetOf()) { bundle.getBoolean(it, false) }
        }
        override fun add(restriction: String) = dpm.addUserRestriction(admin, restriction)
        override fun clear(restriction: String) = dpm.clearUserRestriction(admin, restriction)
    }
}
