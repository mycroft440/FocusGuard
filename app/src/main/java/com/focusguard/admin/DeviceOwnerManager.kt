package com.focusguard.admin

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.UserManager
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manager for Device Owner Mode functionality.
 * Handles app blocking and device policy enforcement.
 */
class DeviceOwnerManager private constructor(private val context: Context) {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val componentName = FocusGuardDeviceAdminReceiver.getComponentName(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        @Volatile
        private var instance: DeviceOwnerManager? = null

        fun getInstance(context: Context): DeviceOwnerManager {
            return instance ?: synchronized(this) {
                instance ?: DeviceOwnerManager(context.applicationContext).also { instance = it }
            }
        }

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
            "com.android.vending"
        )

        private val PHONE_LOCK_TASK_PACKAGES = listOf(
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

        private val GLOBAL_SHIELD_RESTRICTIONS = listOf(
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_UNINSTALL_APPS,
            UserManager.DISALLOW_APPS_CONTROL
        )

        private val SESSION_RESTRICTIONS = listOf(
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_REMOVE_USER,
            UserManager.DISALLOW_CONFIG_DATE_TIME
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
     * Update the suspended apps list to match exactly the provided list.
     * Apps in the list will be suspended; apps NOT in the list will be unsuspended.
     * This prevents apps from being "stuck" suspended when a session ends.
     */
    fun syncSuspendedApps(allAppsInSessions: List<String>, appsToBlockNow: List<String>) {
        if (!isDeviceOwnerActive()) return

        scope.launch {
            try {
                val myPkg = context.packageName
                val pm = context.packageManager

                val appsToUnblock = allAppsInSessions.filter { !appsToBlockNow.contains(it) }
                if (appsToUnblock.isNotEmpty()) {
                    dpm.setPackagesSuspended(componentName, appsToUnblock.toTypedArray(), false)
                    Log.d("FocusGuardAdmin", "Apps desbloqueados diferencialmente: ${appsToUnblock.size}")
                }

                val filteredToBlock = appsToBlockNow.filter { pkg ->
                    if (pkg == myPkg || pkg == "com.focusguard") return@filter false
                    if (SACRED_WHITELIST.contains(pkg)) return@filter false
                    try {
                        val appInfo = pm.getApplicationInfo(pkg, 0)
                        if ((appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) return@filter false
                    } catch (_: Exception) {}
                    true
                }

                if (filteredToBlock.isNotEmpty()) {
                    dpm.setPackagesSuspended(componentName, filteredToBlock.toTypedArray(), true)
                    Log.d("FocusGuardAdmin", "Apps suspensos: ${filteredToBlock.size}")
                }
            } catch (e: Exception) {
                Log.e("FocusGuardAdmin", "Falha na sincronização diferencial de apps", e)
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
     * Configura o Lock Task para o Pomodoro rigoroso.
     * Em Device Owner, isso impede sair do FocusGuard e permite somente o discador/telefone.
     */
    fun prepareStrictPomodoroLockTaskPackages() {
        if (!isDeviceOwnerActive()) return
        try {
            val pm = context.packageManager
            val allowedPackages = (listOf(context.packageName) + PHONE_LOCK_TASK_PACKAGES)
                .distinct()
                .filter { packageName ->
                    packageName == context.packageName || runCatching {
                        pm.getPackageInfo(packageName, 0)
                        true
                    }.getOrDefault(false)
                }

            dpm.setLockTaskPackages(componentName, allowedPackages.toTypedArray())
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                dpm.setLockTaskFeatures(componentName, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            }
            Log.d("FocusGuardAdmin", "Lock Task rigoroso preparado: $allowedPackages")
        } catch (e: Exception) {
            com.focusguard.utils.FocusGuardLogger.logError("DeviceOwner", "Falha ao preparar Lock Task rigoroso", e)
        }
    }

    /**
     * Mantém somente o FocusGuard no allowlist após o fim do Pomodoro rigoroso.
     */
    fun clearStrictPomodoroLockTaskPackages() {
        if (!isDeviceOwnerActive()) return
        try {
            dpm.setLockTaskPackages(componentName, arrayOf(context.packageName))
            Log.d("FocusGuardAdmin", "Lock Task rigoroso limpo")
        } catch (e: Exception) {
            com.focusguard.utils.FocusGuardLogger.logError("DeviceOwner", "Falha ao limpar Lock Task rigoroso", e)
        }
    }

    /**
     * Show Device Owner setup instructions dialog.
     * The singleton stores applicationContext, so callers should pass the foreground Activity.
     */
    fun setAsDeviceOwner(activity: Activity? = null) {
        val hostActivity = activity ?: context as? Activity ?: return
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

        AlertDialog.Builder(hostActivity)
            .setTitle("Tutorial: Proteção Nuclear (ADB)")
            .setMessage(tutorialMessage)
            .setPositiveButton("Copiar Comando ADB") { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("ADB Command", adbCommand)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(hostActivity, "Comando ADB copiado!", Toast.LENGTH_LONG).show()
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
            // Primeiro aplica o Shield Global (Persistente)
            applyNuclearShield()
            
            // Depois aplica as restrições temporárias da sessão
            SESSION_RESTRICTIONS.forEach { dpm.addUserRestriction(componentName, it) }
            Log.d("FocusGuardAdmin", "Políticas de sessão aplicadas")
        } catch (e: Exception) {
            com.focusguard.utils.FocusGuardLogger.logError("DeviceOwner", "Falha ao aplicar políticas de sessão", e)
        }
    }

    /**
     * Clear strict device policies when block session ends.
     * Note: Does NOT clear the Nuclear Shield if it's meant to be persistent.
     */
    fun clearBlockingPolicies() {
        if (!isDeviceOwnerActive()) return
        try {
            SESSION_RESTRICTIONS.forEach { dpm.clearUserRestriction(componentName, it) }
            Log.d("FocusGuardAdmin", "Políticas de sessão removidas")
        } catch (e: Exception) {
            com.focusguard.utils.FocusGuardLogger.logError("DeviceOwner", "Falha ao remover políticas de sessão", e)
        }
    }

    /**
     * Aplica a Proteção Nuclear Permanente (Anti-Desinstalação e Anti-SafeBoot).
     * Deve ser chamada sempre que o app estiver em execução e for Device Owner.
     */
    fun applyNuclearShield() {
        if (!isDeviceOwnerActive()) return
        try {
            GLOBAL_SHIELD_RESTRICTIONS.forEach { dpm.addUserRestriction(componentName, it) }
            Log.d("FocusGuardAdmin", "Nuclear Shield (Permanente) aplicado")
        } catch (e: Exception) {
            com.focusguard.utils.FocusGuardLogger.logError("DeviceOwner", "Falha ao aplicar Nuclear Shield", e)
        }
    }

    /**
     * Revoga a Proteção Nuclear. Usado apenas quando o usuário deseja desinstalar o app legitimamente.
     */
    fun revokeNuclearShield() {
        if (!isDeviceOwnerActive()) return
        try {
            GLOBAL_SHIELD_RESTRICTIONS.forEach { dpm.clearUserRestriction(componentName, it) }
            Log.d("FocusGuardAdmin", "Nuclear Shield revogado")
        } catch (e: Exception) {
            com.focusguard.utils.FocusGuardLogger.logError("DeviceOwner", "Falha ao revogar Nuclear Shield", e)
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
                    Log.w("FocusGuardNuclear", "Aviso: Lista de dominios excedeu o limite Binder ($MAX_SOTA_DOMAINS). Truncando.")
                    domains.take(MAX_SOTA_DOMAINS)
                } else {
                    domains
                }

                val restrictions = Bundle()
                restrictions.putStringArray("URLBlocklist", limitedDomains.toTypedArray())

                val attempt = sotaAttempts.incrementAndGet()
                if (attempt <= 5) {
                    Log.d("FocusGuardNuclear", "Tentativa SOTA (Managed Config) $attempt: ${limitedDomains.size} dominios")
                }

                dpm.setApplicationRestrictions(componentName, "com.android.chrome", restrictions)
                dpm.setApplicationRestrictions(componentName, "com.microsoft.emmx", restrictions)
            } catch (e: android.os.TransactionTooLargeException) {
                com.focusguard.utils.FocusGuardLogger.logError("DeviceOwner", "Erro: Limite de Binder excedido na restricao de URLs", e)
            } catch (e: Exception) {
                com.focusguard.utils.FocusGuardLogger.logError("DeviceOwner", "Falha na operacao de Device Owner", e)
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
                dpm.setApplicationRestrictions(componentName, "com.android.chrome", emptyRestrictions)
                dpm.setApplicationRestrictions(componentName, "com.microsoft.emmx", emptyRestrictions)
            } catch (e: Exception) {
                com.focusguard.utils.FocusGuardLogger.logError("DeviceOwner", "Erro na operação de Device Owner", e)
            }
        }
    }

    /**
     * Enforce Global Private DNS using CleanBrowsing Adult Filter
     */
    fun enforceAdultDns(): Boolean {
        if (!isDeviceOwnerActive()) return false
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                val result = dpm.setGlobalPrivateDnsModeSpecifiedHost(componentName, "adult-filter-dns.cleanbrowsing.org")
                if (result == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                    Log.d("FocusGuardAdmin", "DNS Adulto aplicado com sucesso via DPM")
                    return true
                }
            } catch (e: Exception) {
                com.focusguard.utils.FocusGuardLogger.logError("DeviceOwner", "Erro ao aplicar DNS", e)
            }
        } else {
            com.focusguard.utils.FocusGuardLogger.log("DeviceOwner", "DNS Global não suportado nesta versão do Android")
        }
        return false
    }

    /**
     * Clear Global Private DNS to Opportunistic Mode
     */
    fun clearAdultDns() {
        if (!isDeviceOwnerActive()) return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                dpm.setGlobalPrivateDnsModeOpportunistic(componentName)
                Log.d("FocusGuardAdmin", "DNS Global removido via DPM")
            } catch (e: Exception) {
                com.focusguard.utils.FocusGuardLogger.logError("DeviceOwner", "Erro ao remover DNS", e)
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
            revokeNuclearShield()
            dpm.clearDeviceOwnerApp(context.packageName)
            Toast.makeText(context, "Acesso Device Owner revogado", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Falha ao revogar Device Owner: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
