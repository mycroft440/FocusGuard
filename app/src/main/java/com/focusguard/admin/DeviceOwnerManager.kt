package com.focusguard.admin

import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.UserManager
import android.util.Log

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
     * Block applications using Device Policy Manager (setPackagesSuspended)
     */
    fun blockApps(packageNames: List<String>) {
        if (!isDeviceOwnerActive()) {
            return
        }

        scope.launch {
            try {
                // Suspend packages (makes them grayed out and unlaunchable)
                dpm.setPackagesSuspended(componentName, packageNames.toTypedArray(), true)
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    /**
     * Unblock applications
     */
    fun unblockApps(packageNames: List<String>) {
        if (!isDeviceOwnerActive()) return

        scope.launch {
            try {
                dpm.setPackagesSuspended(componentName, packageNames.toTypedArray(), false)
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    /**
     * Lock the device
     */
    fun lockDevice() {
        if (!isDeviceAdminActive()) return
        try {
            dpm.lockNow()
        } catch (e: Exception) {}
    }

    /**
     * Set device owner instructions
     */
    fun setAsDeviceOwner() {
        val adbCommand = "adb shell dpm set-device-owner com.focusguard/.admin.FocusGuardDeviceAdminReceiver"
        
        AlertDialog.Builder(context)
            .setTitle("Set Device Owner Mode")
            .setMessage("To enable advanced blocking, run this ADB command on your computer:\n\n$adbCommand")
            .setPositiveButton("Copy Command") { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("ADB Command", adbCommand)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Command copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
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
        }
    }

    /**
     * Enforce strict device policies during an active block session
     */
    fun enforceBlockingPolicies() {
        if (!isDeviceOwnerActive()) return
        Log.e("NUCLEAR_DEBUG", "Enforcing strict Device Owner policies: Factory Reset Disabled, Debugging Disabled, Safe Boot Disabled, Add User Disabled")
        try {
            dpm.addUserRestriction(componentName, UserManager.DISALLOW_FACTORY_RESET)
            dpm.addUserRestriction(componentName, UserManager.DISALLOW_DEBUGGING_FEATURES)
            dpm.addUserRestriction(componentName, UserManager.DISALLOW_SAFE_BOOT)
            dpm.addUserRestriction(componentName, UserManager.DISALLOW_ADD_USER)
            // Optional but recommended for productivity tools
            dpm.addUserRestriction(componentName, UserManager.DISALLOW_REMOVE_USER)
        } catch (e: Exception) {
            Log.e("NUCLEAR_DEBUG", "Error enforcing policies", e)
        }
    }

    /**
     * Clear strict device policies when block session ends
     */
    fun clearBlockingPolicies() {
        if (!isDeviceOwnerActive()) return
        Log.e("NUCLEAR_DEBUG", "Clearing strict Device Owner policies")
        try {
            dpm.clearUserRestriction(componentName, UserManager.DISALLOW_FACTORY_RESET)
            dpm.clearUserRestriction(componentName, UserManager.DISALLOW_DEBUGGING_FEATURES)
            dpm.clearUserRestriction(componentName, UserManager.DISALLOW_SAFE_BOOT)
            dpm.clearUserRestriction(componentName, UserManager.DISALLOW_ADD_USER)
            dpm.clearUserRestriction(componentName, UserManager.DISALLOW_REMOVE_USER)
        } catch (e: Exception) {
            Log.e("NUCLEAR_DEBUG", "Error clearing policies", e)
        }
    }

    /**
     * Renounce Device Owner privileges natively
     */
    fun renounceDeviceOwner() {
        if (!isDeviceOwnerActive()) return
        Log.e("NUCLEAR_DEBUG", "Renouncing Device Owner Privileges")
        try {
            clearBlockingPolicies() // Always try to clear restrictions first
            dpm.clearDeviceOwnerApp(context.packageName)
            Toast.makeText(context, "Device Owner Access Revoked", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("NUCLEAR_DEBUG", "Error renouncing Device Owner", e)
            Toast.makeText(context, "Failed to revoke Device Owner Access: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
