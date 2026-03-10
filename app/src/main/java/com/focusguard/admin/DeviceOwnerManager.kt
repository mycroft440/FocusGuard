package com.focusguard.admin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Manager for Device Owner Mode functionality
 * Handles app blocking and device policy enforcement
 */
class DeviceOwnerManager(private val context: Context) {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val componentName = FocusGuardDeviceAdminReceiver.getComponentName(context)
    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * Check if Device Owner Mode is active
     */
    fun isDeviceOwnerActive(): Boolean {
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    /**
     * Check if Device Admin is active
     */
    fun isDeviceAdminActive(): Boolean {
        return dpm.isAdminActive(componentName)
    }

    /**
     * Request Device Admin activation
     */
    fun requestDeviceAdmin() {
        FocusGuardDeviceAdminReceiver.requestDeviceAdmin(context)
    }

    /**
     * Block an application using Device Policy Manager
     */
    fun blockApp(packageName: String) {
        if (!isDeviceOwnerActive()) {
            Toast.makeText(
                context,
                "Device Owner Mode is required to block apps",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        scope.launch {
            try {
                // Set app restrictions (if Device Owner)
                val restrictions = android.os.Bundle()
                restrictions.putBoolean("no_uninstall", true)
                
                dpm.setApplicationRestrictions(componentName, packageName, restrictions)
                
                Toast.makeText(
                    context,
                    "App blocked: $packageName",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "Failed to block app: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Unblock an application
     */
    fun unblockApp(packageName: String) {
        if (!isDeviceOwnerActive()) return

        scope.launch {
            try {
                dpm.setApplicationRestrictions(componentName, packageName, android.os.Bundle())
                
                Toast.makeText(
                    context,
                    "App unblocked: $packageName",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "Failed to unblock app: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Get list of blocked apps
     */
    fun getBlockedApps(): List<String> {
        if (!isDeviceOwnerActive()) return emptyList()

        return try {
            val pm = context.packageManager
            val installedApps = pm.getInstalledApplications(0)
            installedApps.mapNotNull { app ->
                val restrictions = dpm.getApplicationRestrictions(componentName, app.packageName)
                if (restrictions.getBoolean("no_uninstall", false)) {
                    app.packageName
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Lock the device
     */
    fun lockDevice() {
        if (!isDeviceAdminActive()) {
            Toast.makeText(
                context,
                "Device Admin is required",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        try {
            dpm.lockNow()
        } catch (e: Exception) {
            Toast.makeText(
                context,
                "Failed to lock device: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Wipe device data (requires Device Owner)
     */
    fun wipeDeviceData() {
        if (!isDeviceOwnerActive()) {
            Toast.makeText(
                context,
                "Device Owner Mode is required",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        try {
            dpm.wipeData(0)
        } catch (e: Exception) {
            Toast.makeText(
                context,
                "Failed to wipe device: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Set device owner (requires ADB or EMM)
     */
    fun setAsDeviceOwner() {
        Toast.makeText(
            context,
            "Use ADB command to set Device Owner:\nadb shell dpm set-device-owner com.focusguard/.admin.FocusGuardDeviceAdminReceiver",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Get Device Owner status information
     */
    fun getStatusInfo(): String {
        val isAdmin = isDeviceAdminActive()
        val isOwner = isDeviceOwnerActive()
        
        return buildString {
            appendLine("Device Admin Active: $isAdmin")
            appendLine("Device Owner Active: $isOwner")
            if (isOwner) {
                appendLine("Blocked Apps: ${getBlockedApps().size}")
            }
        }
    }
}
