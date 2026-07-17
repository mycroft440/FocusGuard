package com.focusguard.admin

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.focusguard.R
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.WebsiteBlocker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Manager for Device Owner Mode functionality.
 * Handles app blocking and device policy enforcement.
 */
class DeviceOwnerManager private constructor(private val context: Context) {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val componentName = FocusGuardDeviceAdminReceiver.getComponentName(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val websitePolicyMutex = Mutex()
    private val suspendedAppsMutex = Mutex()
    private val suspendedAppsPreferences = context.getSharedPreferences(
        SUSPENDED_APPS_PREFERENCES,
        Context.MODE_PRIVATE
    )
    private var lastWebsitePolicySignature: String? = null

    companion object {
        @Volatile
        private var instance: DeviceOwnerManager? = null

        fun getInstance(context: Context): DeviceOwnerManager {
            return instance ?: synchronized(this) {
                instance ?: DeviceOwnerManager(context.applicationContext).also { instance = it }
            }
        }

        private const val MAX_MANAGED_URLS = 1_000
        private const val ACTION_DEVICE_ADMIN_SETTINGS = "android.settings.DEVICE_ADMIN_SETTINGS"
        private const val SUSPENDED_APPS_PREFERENCES = "focusguard_suspended_apps"
        private const val MANAGED_SUSPENDED_APPS_KEY = "managed_packages"
        private val CHROME_MANAGED_PACKAGES = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary"
        )
        private val EDGE_MANAGED_PACKAGES = setOf(
            "com.microsoft.emmx",
            "com.microsoft.emmx.beta",
            "com.microsoft.emmx.dev",
            "com.microsoft.emmx.canary"
        )
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
     * Builds the system-owned confirmation screen for legacy Device Admin.
     * The foreground Activity must launch this intent so its result and lifecycle
     * stay tied to the permission screen instead of opening in a detached task.
     */
    fun createDeviceAdminActivationIntent(): Intent {
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                context.getString(R.string.permission_device_admin_desc)
            )
        }
    }

    /**
     * Opens the manufacturer's Device Admin list when the direct confirmation
     * activity is unavailable or returns without showing a usable screen.
     */
    fun openDeviceAdminSettings(hostContext: Context): Boolean {
        val candidates = listOf(
            Intent(ACTION_DEVICE_ADMIN_SETTINGS),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            },
            Intent(Settings.ACTION_SETTINGS)
        )

        candidates.forEach { candidate ->
            val launched = runCatching {
                if (hostContext !is Activity) {
                    candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                hostContext.startActivity(candidate)
                true
            }.onFailure { error ->
                FocusGuardLogger.logError(
                    "DeviceOwner",
                    "Falha ao abrir ${candidate.action}",
                    error
                )
            }.getOrDefault(false)

            if (launched) return true
        }

        return false
    }

    /**
     * Update the suspended apps list to match exactly the provided list.
     * Apps in the list will be suspended; apps NOT in the list will be unsuspended.
     * This prevents apps from being "stuck" suspended when a session ends.
     */
    fun syncSuspendedApps(
        allAppsInSessions: List<String>,
        appsToBlockNow: List<String>,
        allowedSystemApps: Set<String> = emptySet()
    ) {
        if (!isDeviceOwnerActive()) return

        scope.launch {
            suspendedAppsMutex.withLock {
                try {
                    val myPkg = context.packageName
                    val pm = context.packageManager
                    val previouslyManaged = managedSuspendedApps()
                    val filteredToBlock = appsToBlockNow.distinct().filter { pkg ->
                        if (pkg == myPkg || pkg == "com.focusguard") return@filter false
                        if (SACRED_WHITELIST.contains(pkg)) return@filter false
                        try {
                            val appInfo = pm.getApplicationInfo(pkg, 0)
                            val isSystemApp =
                                (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                            !isSystemApp || pkg in allowedSystemApps
                        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                            false
                        }
                    }
                    val desired = filteredToBlock.toSet()
                    val managedAfterSync = previouslyManaged.toMutableSet()
                    val appsToUnblock = (allAppsInSessions + previouslyManaged)
                        .distinct()
                        .filter { it !in desired }

                    if (appsToUnblock.isNotEmpty()) {
                        val failed = dpm.setPackagesSuspended(
                            componentName,
                            appsToUnblock.toTypedArray(),
                            false
                        ).toSet()
                        managedAfterSync.removeAll(appsToUnblock.toSet() - failed)
                        Log.d(
                            "FocusGuardAdmin",
                            "Apps desbloqueados diferencialmente: " +
                                "${appsToUnblock.size - failed.size}"
                        )
                    }

                    if (filteredToBlock.isNotEmpty()) {
                        val failed = dpm.setPackagesSuspended(
                            componentName,
                            filteredToBlock.toTypedArray(),
                            true
                        ).toSet()
                        managedAfterSync.addAll(filteredToBlock.toSet() - failed)
                        Log.d(
                            "FocusGuardAdmin",
                            "Apps suspensos: ${filteredToBlock.size - failed.size}"
                        )
                    }
                    saveManagedSuspendedApps(managedAfterSync)
                } catch (error: Exception) {
                    Log.e("FocusGuardAdmin", "Falha na sincronização diferencial de apps", error)
                }
            }
        }
    }

    /**
     * Unblock applications.
     */
    fun unblockApps(packageNames: List<String>) {
        if (!isDeviceOwnerActive() || packageNames.isEmpty()) return

        scope.launch {
            suspendedAppsMutex.withLock {
                try {
                    val failed = dpm.setPackagesSuspended(
                        componentName,
                        packageNames.toTypedArray(),
                        false
                    ).toSet()
                    val remaining = managedSuspendedApps().toMutableSet()
                    remaining.removeAll(packageNames.toSet() - failed)
                    saveManagedSuspendedApps(remaining)
                } catch (error: Exception) {
                    FocusGuardLogger.logError("Admin", "Erro ao desbloquear apps", error)
                }
            }
        }
    }

    private fun managedSuspendedApps(): Set<String> {
        return suspendedAppsPreferences
            .getStringSet(MANAGED_SUSPENDED_APPS_KEY, emptySet())
            .orEmpty()
            .toSet()
    }

    private fun saveManagedSuspendedApps(packageNames: Set<String>) {
        suspendedAppsPreferences.edit()
            .putStringSet(MANAGED_SUSPENDED_APPS_KEY, packageNames)
            .apply()
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
                Toast.makeText(hostActivity, context.getString(R.string.comando_adb_copiado), Toast.LENGTH_LONG).show()
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
     * Aplica bloqueio preventivo no renderer dos navegadores gerenciáveis.
     *
     * `URLBlocklist` é a camada mais forte disponível sem VPN para Chrome e
     * Edge em um aparelho Device Owner. A navegação privada também é suspensa
     * enquanto há regras ativas, evitando uma superfície de bypass. Os demais
     * navegadores continuam protegidos pelo AccessibilityService.
     */
    suspend fun enforceWebsiteRestrictions(domains: List<String>) {
        if (!isDeviceOwnerActive()) {
            invalidateWebsitePolicyCache()
            return
        }
        val normalizedDomains = WebsiteBlocker.normalizeDomains(domains).sorted()
        val allManagedDomains = WebsiteBlocker.expandDomainAliases(normalizedDomains).toList()
        val managedDomains = allManagedDomains.take(MAX_MANAGED_URLS)
        if (managedDomains.size < allManagedDomains.size) {
            Log.w(
                "FocusGuardNuclear",
                "URLBlocklist limitada aos primeiros $MAX_MANAGED_URLS domínios"
            )
        }
        applyWebsiteRestrictions(managedDomains)
    }

    /** Remove somente as políticas de navegador controladas pelo FocusGuard. */
    suspend fun clearWebsiteRestrictions() {
        if (!isDeviceOwnerActive()) {
            invalidateWebsitePolicyCache()
            return
        }
        applyWebsiteRestrictions(emptyList())
    }

    /** Força nova sincronização após instalação, remoção ou atualização do navegador. */
    suspend fun invalidateWebsitePolicyCache() {
        websitePolicyMutex.withLock {
            lastWebsitePolicySignature = null
        }
    }

    private suspend fun applyWebsiteRestrictions(domains: List<String>) {
        websitePolicyMutex.withLock {
            withContext(Dispatchers.IO) {
                val chromePackages = CHROME_MANAGED_PACKAGES.filter(::isPackageInstalled)
                val edgePackages = EDGE_MANAGED_PACKAGES.filter(::isPackageInstalled)
                val targets = (chromePackages + edgePackages).sorted()
                val managedFilters = domains.map { domain ->
                    if (':' in domain) "[$domain]" else domain
                }
                val signature = managedFilters.joinToString("\u0000") +
                    "|" + targets.joinToString("\u0000")
                if (signature == lastWebsitePolicySignature) return@withContext

                var allWritesSucceeded = true
                targets.forEach { packageName ->
                    val privateModePolicy = if (packageName in EDGE_MANAGED_PACKAGES) {
                        "InPrivateModeAvailability"
                    } else {
                        "IncognitoModeAvailability"
                    }

                    try {
                        val restrictions = Bundle(
                            dpm.getApplicationRestrictions(componentName, packageName)
                        ).apply {
                            remove("URLBlocklist")
                            remove("IncognitoModeAvailability")
                            remove("InPrivateModeAvailability")
                            // Limpa chaves gravadas por versões antigas. O
                            // bloqueio de sites não precisa alterar o DNS do navegador.
                            remove("DnsOverHttpsMode")
                            remove("DnsOverHttpsTemplates")
                            if (managedFilters.isNotEmpty()) {
                                putStringArray("URLBlocklist", managedFilters.toTypedArray())
                                putInt(privateModePolicy, 1)
                            }
                        }
                        dpm.setApplicationRestrictions(componentName, packageName, restrictions)
                        val stored = dpm.getApplicationRestrictions(componentName, packageName)
                        val storedDomains = stored.getStringArray("URLBlocklist")?.toList().orEmpty()
                        val legacyDnsPolicyCleared =
                            !stored.containsKey("DnsOverHttpsMode") &&
                                !stored.containsKey("DnsOverHttpsTemplates")
                        val verified = if (managedFilters.isEmpty()) {
                            !stored.containsKey("URLBlocklist") &&
                                !stored.containsKey("IncognitoModeAvailability") &&
                                !stored.containsKey("InPrivateModeAvailability") &&
                                legacyDnsPolicyCleared
                        } else {
                            storedDomains == managedFilters &&
                                stored.getInt(privateModePolicy, -1) == 1 &&
                                legacyDnsPolicyCleared
                        }
                        if (!verified) {
                            allWritesSucceeded = false
                            FocusGuardLogger.log(
                                "DeviceOwner",
                                "O navegador $packageName não confirmou a política de URLs"
                            )
                        }
                    } catch (error: android.os.TransactionTooLargeException) {
                        allWritesSucceeded = false
                        FocusGuardLogger.logError(
                            "DeviceOwner",
                            "URLBlocklist excedeu o limite Binder em $packageName",
                            error
                        )
                    } catch (error: Exception) {
                        allWritesSucceeded = false
                        FocusGuardLogger.logError(
                            "DeviceOwner",
                            "Falha ao aplicar URLBlocklist em $packageName",
                            error
                        )
                    }
                }

                if (allWritesSucceeded) {
                    lastWebsitePolicySignature = signature
                    Log.d(
                        "FocusGuardNuclear",
                        "URLBlocklist sincronizada: ${managedFilters.size} filtros em " +
                            "${targets.size} navegadores"
                    )
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun isPackageInstalled(packageName: String): Boolean {
        return runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                context.packageManager.getPackageInfo(packageName, 0)
            }
        }.isSuccess
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
            Toast.makeText(context, context.getString(R.string.acesso_device_owner_revogado), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.falha_ao_revogar_device_owner_e_message), Toast.LENGTH_LONG).show()
        }
    }
}
