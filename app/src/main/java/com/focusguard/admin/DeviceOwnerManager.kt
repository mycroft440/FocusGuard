package com.focusguard.admin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            Toast.makeText(
                context,
                "Device Owner Mode is required to block apps via DPM",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        scope.launch {
            try {
                // Suspend packages (makes them grayed out and unlaunchable)
                val failedPackages = dpm.setPackagesSuspended(componentName, packageNames.toTypedArray(), true)
                
                withContext(Dispatchers.Main) {
                    if (failedPackages.isEmpty()) {
                        Toast.makeText(context, "${packageNames.size} apps blocked", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Blocked ${packageNames.size - failedPackages.size} apps, ${failedPackages.size} failed", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to block apps: ${e.message}", Toast.LENGTH_SHORT).show()
                }
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
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Apps unblocked", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to unblock apps: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
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
        }
    }
}
