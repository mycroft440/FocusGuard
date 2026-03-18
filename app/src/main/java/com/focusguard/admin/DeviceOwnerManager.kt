package com.focusguard.admin

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.os.UserManager

/**
 * Manager for Device Owner Mode functionality.
 * Handles app blocking and device policy enforcement.
 */
class DeviceOwnerManager(private val context: Context) {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val componentName = FocusGuardDeviceAdminReceiver.getComponentName(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Check if Device Owner Mode is active.
     */
    fun isDeviceOwnerActive(): Boolean {
        return try {
            dpm.isDeviceOwnerApp(context.packageName)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Check if Device Admin is active.
     */
    fun isDeviceAdminActive(): Boolean {
        return try {
            dpm.isAdminActive(componentName)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Request Device Admin activation.
     * Adds FLAG_ACTIVITY_NEW_TASK for non-Activity contexts.
     */
    fun requestDeviceAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "FocusGuard precisa de permissão de administrador para bloquear apps e sites"
            )
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(intent)
    }

    /**
     * Block applications using Device Policy Manager (setPackagesSuspended).
     */
    fun blockApps(packageNames: List<String>) {
        if (!isDeviceOwnerActive() || packageNames.isEmpty()) return

        scope.launch {
            try {
                dpm.setPackagesSuspended(componentName, packageNames.toTypedArray(), true)
            } catch (_: Exception) {
                // Handle error
            }
        }
    }

    /**
     * Unblock applications.
     */
    fun unblockApps(packageNames: List<String>) {
        if (!isDeviceOwnerActive() || packageNames.isEmpty()) return

        scope.launch {
            try {
                dpm.setPackagesSuspended(componentName, packageNames.toTypedArray(), false)
            } catch (_: Exception) {
                // Handle error
            }
        }
    }

    /**
     * Lock the device.
     */
    fun lockDevice() {
        if (!isDeviceAdminActive()) return
        try {
            dpm.lockNow()
        } catch (_: Exception) {
            // Handle error
        }
    }

    /**
     * Show Device Owner setup instructions dialog.
     * Requires an Activity context to display the AlertDialog.
     */
    fun setAsDeviceOwner() {
        if (context !is Activity) return

        val adbCommand = "adb shell dpm set-device-owner ${context.packageName}/com.focusguard.admin.FocusGuardDeviceAdminReceiver"

        val tutorialMessage = "Proteção Nuclear (Device Owner) impede a desinstalação burlando os bloqueios.\n\n" +
                "Siga os passos abaixo no seu computador:\n\n" +
                "1. Ative a 'Depuração USB' nas Opções de Desenvolvedor do Android.\n" +
                "2. Conecte o celular via cabo USB ao PC.\n" +
                "3. IMPORTANTE: Remova temporariamente TODAS as suas contas Google/Samsung logadas (Ajustes > Contas). Sem isso, o Android recusa o comando.\n" +
                "4. Baixe ou abra o ADB (Terminal/CMD) no PC.\n" +
                "5. Cole e rode o comando ADB copiado no botão abaixo.\n" +
                "6. Se houver sucesso ('Success'), você pode logar nas suas contas novamente.\n\n" +
                adbCommand

        AlertDialog.Builder(context)
            .setTitle("Tutorial: Proteção Nuclear (ADB)")
            .setMessage(tutorialMessage)
            .setPositiveButton("Copiar Comando ADB") { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("ADB Command", adbCommand)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Comando ADB copiado!", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    /**
     * Get Device Owner status information.
     */
    fun getStatusInfo(): String {
        val isAdmin = isDeviceAdminActive()
        val isOwner = isDeviceOwnerActive()

        return buildString {
            appendLine("Device Admin Ativo: $isAdmin")
            appendLine("Device Owner Ativo: $isOwner")
        }
    }

    /**
     * Enforce strict device policies during an active block session.
     */
    fun enforceBlockingPolicies() {
        if (!isDeviceOwnerActive()) return
        try {
            dpm.addUserRestriction(componentName, UserManager.DISALLOW_FACTORY_RESET)
            dpm.addUserRestriction(componentName, UserManager.DISALLOW_SAFE_BOOT)
            dpm.addUserRestriction(componentName, UserManager.DISALLOW_ADD_USER)
            dpm.addUserRestriction(componentName, UserManager.DISALLOW_REMOVE_USER)
            dpm.addUserRestriction(componentName, UserManager.DISALLOW_CONFIG_DATE_TIME)
        } catch (_: Exception) {
            // Handle error
        }
    }

    /**
     * Clear strict device policies when block session ends.
     */
    fun clearBlockingPolicies() {
        if (!isDeviceOwnerActive()) return
        try {
            dpm.clearUserRestriction(componentName, UserManager.DISALLOW_FACTORY_RESET)
            dpm.clearUserRestriction(componentName, UserManager.DISALLOW_SAFE_BOOT)
            dpm.clearUserRestriction(componentName, UserManager.DISALLOW_ADD_USER)
            dpm.clearUserRestriction(componentName, UserManager.DISALLOW_REMOVE_USER)
            dpm.clearUserRestriction(componentName, UserManager.DISALLOW_CONFIG_DATE_TIME)
        } catch (_: Exception) {
            // Handle error
        }
    }

    /**
     * Renounce Device Owner privileges natively.
     */
    fun renounceDeviceOwner() {
        if (!isDeviceOwnerActive()) return
        try {
            clearBlockingPolicies()
            dpm.clearDeviceOwnerApp(context.packageName)
            Toast.makeText(context, "Acesso Device Owner revogado", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Falha ao revogar Device Owner: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
