package com.focusguard.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.focusguard.R
import com.focusguard.security.DeviceAdminActivationWindow

/**
 * Device Admin Receiver for FocusGuard.
 * Handles device admin policies and enables Device Owner Mode functionality.
 */
class FocusGuardDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        DeviceAdminActivationWindow.close(context)
        Toast.makeText(context, context.getString(R.string.focusguard_device_admin_habilitado), Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(context, context.getString(R.string.focusguard_device_admin_desabilitado), Toast.LENGTH_SHORT).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // O administrador legado não pode vetar a desativação. O bloqueio imediato
        // acrescenta fricção e o texto retornado informa com precisão o impacto.
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.lockNow()
            android.util.Log.w("FocusGuardAdmin", "Desativação solicitada: Dispositivo bloqueado preventivamente.")
        } catch (e: Exception) {
            android.util.Log.e("FocusGuardAdmin", "Falha ao bloquear dispositivo no onDisableRequested", e)
        }
        
        return context.getString(R.string.device_admin_disable_warning)
    }

    companion object {
        /**
         * Get the component name for this device admin receiver.
         */
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context, FocusGuardDeviceAdminReceiver::class.java)
        }

        /**
         * Check if FocusGuard is a device admin.
         */
        fun isDeviceAdminActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isAdminActive(getComponentName(context))
        }

        /**
         * Check if FocusGuard is the device owner.
         */
        fun isDeviceOwner(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isDeviceOwnerApp(context.packageName)
        }

    }
}
