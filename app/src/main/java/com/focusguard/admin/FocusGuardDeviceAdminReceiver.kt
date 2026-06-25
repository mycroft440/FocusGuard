package com.focusguard.admin

import com.focusguard.R
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Device Admin Receiver for FocusGuard.
 * Handles device admin policies and enables Device Owner Mode functionality.
 */
class FocusGuardDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, context.getString(R.string.focusguard_device_admin_habilitado), Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(context, context.getString(R.string.focusguard_device_admin_desabilitado), Toast.LENGTH_SHORT).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // Ação defensiva: Bloqueia o dispositivo imediatamente para impedir o clique em 'Desativar'
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.lockNow()
            android.util.Log.w("FocusGuardAdmin", "Desativação solicitada: Dispositivo bloqueado preventivamente.")
        } catch (e: Exception) {
            android.util.Log.e("FocusGuardAdmin", "Falha ao bloquear dispositivo no onDisableRequested", e)
        }
        
        return "Proteção Nuclear Ativa: O dispositivo foi bloqueado para impedir a desativação da proteção. Sua disciplina é nossa prioridade!"
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

        /**
         * Request device admin activation.
         */
        fun requestDeviceAdmin(context: Context) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, getComponentName(context))
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "FocusGuard precisa de permissão de administrador para bloquear apps e sites"
                )
            }
            if (context !is android.app.Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
