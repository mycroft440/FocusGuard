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
import android.os.Bundle
import android.os.UserManager
import android.util.Log
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manager for Device Owner Mode functionality.
 * Handles app blocking and device policy enforcement.
 */
class DeviceOwnerManager(private val context: Context) {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val componentName = FocusGuardDeviceAdminReceiver.getComponentName(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        private val sotaAttempts = AtomicInteger(0)
        private const val MAX_SOTA_DOMAINS = 500
        private val SACRED_WHITELIST = setOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.server.telecom",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.google.android.gms",
            "com.android.vending" // Play Store (essencial para correções)
        )
    }

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
                val myPkg = context.packageName
                val pm = context.packageManager
                
                val filtered = packageNames.filter { pkg ->
                    // 1. Never block self
                    if (pkg == myPkg || pkg == "com.focusguard") return@filter false
                    
                    // 2. Never block sacred system apps
                    if (SACRED_WHITELIST.contains(pkg)) return@filter false
                    
                    // 3. Prevent blocking critical system apps via FLAG_SYSTEM
                    try {
                        val appInfo = pm.getApplicationInfo(pkg, 0)
                        if ((appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) {
                            // Extra check: allow blocking known common bloatware/distractions even if system
                            // But for absolute safety, we follow the technical review: FLAG_SYSTEM = No Block
                            return@filter false
                        }
                    } catch (_: Exception) {}
                    
                    true
                }
                
                if (filtered.isNotEmpty()) {
                    dpm.setPackagesSuspended(componentName, filtered.toTypedArray(), true)
                    Log.d("FocusGuardAdmin", "Apps suspensos: ${filtered.size}")
                }
            } catch (e: Exception) {
                Log.e("FocusGuardAdmin", "Falha ao suspender apps", e)
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
            } catch (e: Exception) {
                com.focusguard.utils.FocusGuardLogger.log("Admin", "Erro ao desbloquear apps: ${e.message}")
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
        } catch (e: Exception) {
            com.focusguard.utils.FocusGuardLogger.log("Admin", "Erro ao travar dispositivo: ${e.message}")
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
            val restrictions = mutableListOf(
                UserManager.DISALLOW_FACTORY_RESET,
                UserManager.DISALLOW_SAFE_BOOT,
                UserManager.DISALLOW_ADD_USER,
                UserManager.DISALLOW_REMOVE_USER,
                UserManager.DISALLOW_CONFIG_DATE_TIME
            )
            
            // Add these only if SDK version supports them to avoid potential crashes
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                restrictions.add(UserManager.DISALLOW_APPS_CONTROL)
                restrictions.add(UserManager.DISALLOW_UNINSTALL_APPS)
            }
            restrictions.forEach { dpm.addUserRestriction(componentName, it) }
            Log.d("FocusGuardAdmin", "Políticas de restrição aplicadas")
        } catch (e: Exception) {
            Log.e("FocusGuardAdmin", "Falha ao aplicar políticas", e)
        }
    }

    /**
     * Clear strict device policies when block session ends.
     */
    fun clearBlockingPolicies() {
        if (!isDeviceOwnerActive()) return
        try {
            val restrictions = mutableListOf(
                UserManager.DISALLOW_FACTORY_RESET,
                UserManager.DISALLOW_SAFE_BOOT,
                UserManager.DISALLOW_ADD_USER,
                UserManager.DISALLOW_REMOVE_USER,
                UserManager.DISALLOW_CONFIG_DATE_TIME
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                restrictions.add(UserManager.DISALLOW_APPS_CONTROL)
                restrictions.add(UserManager.DISALLOW_UNINSTALL_APPS)
            }
            restrictions.forEach { dpm.clearUserRestriction(componentName, it) }
            Log.d("FocusGuardAdmin", "Políticas de restrição removidas")
        } catch (e: Exception) {
            Log.e("FocusGuardAdmin", "Falha ao remover políticas", e)
        }
    }

    /**
     * Enforce SOTA Website Blocking via Managed Configurations (URLBlocklist)
     * Applies to Chrome and Edge natively.
     */
    fun enforceWebsiteRestrictions(domains: List<String>) {
        if (!isDeviceOwnerActive()) return
        
        scope.launch {
            try {
                val limitedDomains = if (domains.size > MAX_SOTA_DOMAINS) {
                    Log.w("FocusGuardNuclear", "Aviso: Lista de domínios excedeu o limite Binder ($MAX_SOTA_DOMAINS). Truncando.")
                    domains.take(MAX_SOTA_DOMAINS)
                } else {
                    domains
                }

                val restrictions = Bundle()
                val jsonArray = JSONArray(limitedDomains).toString()
                restrictions.putString("URLBlocklist", jsonArray)

                val attempt = sotaAttempts.incrementAndGet()
                if (attempt <= 5) {
                    Log.d("FocusGuardNuclear", "Tentativa SOTA (Managed Config) $attempt: $jsonArray")
                }

                // Apply to Google Chrome
                dpm.setApplicationRestrictions(componentName, "com.android.chrome", restrictions)
                // Apply to Microsoft Edge
                dpm.setApplicationRestrictions(componentName, "com.microsoft.emmx", restrictions)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Clear SOTA Website Blocking via Managed Configurations
     */
    fun clearWebsiteRestrictions() {
        if (!isDeviceOwnerActive()) return
        
        scope.launch {
            try {
                val emptyRestrictions = Bundle()
                // Passing an empty bundle clears the restrictions
                dpm.setApplicationRestrictions(componentName, "com.android.chrome", emptyRestrictions)
                dpm.setApplicationRestrictions(componentName, "com.microsoft.emmx", emptyRestrictions)
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
