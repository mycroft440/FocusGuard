package com.focusguard.admin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.TransactionTooLargeException
import android.os.UserManager
import android.util.Log
import com.focusguard.data.PredefinedWebsites
import com.focusguard.security.AuthManager
import com.focusguard.security.DeviceOwnerMaintenanceGate
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.WebsiteBlocker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Managed-browser URL policy and adult-content DNS enforcement. */
@Singleton
class DeviceOwnerWebPolicyController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val access: DeviceOwnerPolicyAccess
) {
    private val websitePolicyMutex = Mutex()
    private var lastWebsitePolicySignature: String? = null

    suspend fun enforceWebsiteRestrictions(domains: List<String>) {
        if (!access.isDeviceOwnerActive()) {
            invalidateWebsitePolicyCache()
            return
        }
        val configuredRules = if (AuthManager.isAdultFilterConfigured(context)) {
            domains + PredefinedWebsites.PORNOGRAPHY_RULE
        } else {
            domains
        }
        val normalizedRules = WebsiteBlocker.normalizeRules(configuredRules).sorted()
        val allManagedFilters = WebsiteBlocker.managedBrowserFiltersFor(normalizedRules).toList()
        val managedFilters = allManagedFilters.take(DeviceOwnerPolicyCatalog.MAX_MANAGED_URLS)
        if (managedFilters.size < allManagedFilters.size) {
            Log.w(
                "FocusGuardNuclear",
                "URLBlocklist limitada aos primeiros " +
                    "${DeviceOwnerPolicyCatalog.MAX_MANAGED_URLS} filtros"
            )
        }
        applyWebsiteRestrictions(
            domains = managedFilters,
            requireSystemDns = isAdultDnsProtectionRequired()
        )
    }

    suspend fun clearWebsiteRestrictions() {
        if (!access.isDeviceOwnerActive()) {
            invalidateWebsitePolicyCache()
            return
        }
        applyWebsiteRestrictions(
            domains = emptyList(),
            requireSystemDns = isAdultDnsProtectionRequired()
        )
    }

    suspend fun invalidateWebsitePolicyCache() {
        websitePolicyMutex.withLock { lastWebsitePolicySignature = null }
    }

    fun enforceAdultDns(): Boolean {
        if (!access.isDeviceOwnerActive()) return false
        access.setAdultContentProtectionArmed(true)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            FocusGuardLogger.log(
                "DeviceOwner",
                "DNS Global não suportado nesta versão do Android"
            )
            return false
        }

        return try {
            val alreadyConfigured =
                access.dpm.getGlobalPrivateDnsMode(access.componentName) ==
                    DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME &&
                    access.dpm.getGlobalPrivateDnsHost(access.componentName) ==
                    DeviceOwnerPolicyCatalog.ADULT_DNS_HOST
            if (!alreadyConfigured) {
                val result = access.dpm.setGlobalPrivateDnsModeSpecifiedHost(
                    access.componentName,
                    DeviceOwnerPolicyCatalog.ADULT_DNS_HOST
                )
                if (result != DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                    FocusGuardLogger.log(
                        "DeviceOwner",
                        "Android recusou o DNS adulto (código=$result)"
                    )
                    return false
                }
            }

            access.dpm.addUserRestriction(
                access.componentName,
                UserManager.DISALLOW_CONFIG_PRIVATE_DNS
            )
            access.dpm.addUserRestriction(
                access.componentName,
                UserManager.DISALLOW_CONFIG_VPN
            )
            val restrictions = access.dpm.getUserRestrictions(access.componentName)
            val verified =
                access.dpm.getGlobalPrivateDnsMode(access.componentName) ==
                    DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME &&
                    access.dpm.getGlobalPrivateDnsHost(access.componentName) ==
                    DeviceOwnerPolicyCatalog.ADULT_DNS_HOST &&
                    restrictions.getBoolean(
                        UserManager.DISALLOW_CONFIG_PRIVATE_DNS,
                        false
                    ) &&
                    restrictions.getBoolean(UserManager.DISALLOW_CONFIG_VPN, false)
            if (verified) {
                Log.d(
                    "FocusGuardAdmin",
                    "DNS adulto aplicado; alterações de DNS e VPN bloqueadas"
                )
            } else {
                FocusGuardLogger.log(
                    "DeviceOwner",
                    "Android não confirmou o DNS adulto ou as proteções anti-bypass"
                )
            }
            verified
        } catch (error: Exception) {
            FocusGuardLogger.logError("DeviceOwner", "Erro ao aplicar DNS", error)
            false
        }
    }

    fun setPornographyCategoryActive(active: Boolean) {
        val wasActive = access.isPornographyCategoryActive()
        if (active) {
            capturePrivateDnsBeforeCategoryIfNeeded()
            access.setPornographyCategoryActive(true)
            access.setAdultContentProtectionArmed(true)
            if (access.isDeviceOwnerActive() && !isMaintenanceActive()) {
                enforceAdultDns()
            }
            return
        }

        access.setPornographyCategoryActive(false)
        if (wasActive && access.isDeviceOwnerActive() &&
            !AuthManager.isAdultFilterConfigured(context)
        ) {
            access.setAdultContentProtectionArmed(false)
            restorePrivateDnsAfterCategory()
        } else {
            access.setAdultContentProtectionArmed(
                AuthManager.isAdultFilterConfigured(context)
            )
            access.clearCapturedPrivateDns()
        }
    }

    fun clearAdultDns() {
        if (!access.isDeviceOwnerActive()) return
        if (access.isPornographyCategoryActive()) {
            enforceAdultDns()
            return
        }
        access.setAdultContentProtectionArmed(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                clearAdultContentRestrictions()
                access.dpm.setGlobalPrivateDnsModeOpportunistic(access.componentName)
                Log.d("FocusGuardAdmin", "DNS Global removido via DPM")
            } catch (error: Exception) {
                FocusGuardLogger.logError("DeviceOwner", "Erro ao remover DNS", error)
            }
        }
    }

    internal fun isAdultDnsProtectionRequired(): Boolean =
        DeviceOwnerManager.requiresAdultDns(
            globalAdultFilterEnabled = AuthManager.isAdultFilterConfigured(context),
            pornographyCategoryActive = access.isPornographyCategoryActive()
        )

    internal fun clearAdultContentRestrictions() {
        DeviceOwnerPolicyCatalog.adultContentRestrictionsForSdk(Build.VERSION.SDK_INT)
            .forEach { restriction ->
                access.applyPolicySafely("clear:$restriction") {
                    access.dpm.clearUserRestriction(access.componentName, restriction)
                }
            }
    }

    internal fun clearManagedBrowserRestrictionsForRemoval() {
        (DeviceOwnerPolicyCatalog.chromeManagedPackages +
            DeviceOwnerPolicyCatalog.edgeManagedPackages)
            .filter(::isPackageInstalled)
            .forEach { packageName ->
                access.applyPolicySafely("clear_browser_restrictions:$packageName") {
                    access.dpm.setApplicationRestrictions(
                        access.componentName,
                        packageName,
                        Bundle.EMPTY
                    )
                }
            }
    }

    internal fun restorePrivateDnsAfterCategory() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            clearAdultContentRestrictions()
            access.clearCapturedPrivateDns()
            return
        }

        runCatching {
            clearAdultContentRestrictions()
            val captured = access.hasCapturedPrivateDns()
            val previousMode = access.capturedPrivateDnsMode(
                DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC
            )
            val previousHost = access.capturedPrivateDnsHost()
            if (captured &&
                previousMode == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME &&
                previousHost.isNotBlank()
            ) {
                val result = access.dpm.setGlobalPrivateDnsModeSpecifiedHost(
                    access.componentName,
                    previousHost
                )
                if (result != DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                    access.dpm.setGlobalPrivateDnsModeOpportunistic(access.componentName)
                }
            } else {
                access.dpm.setGlobalPrivateDnsModeOpportunistic(access.componentName)
            }
            access.clearCapturedPrivateDns()
            Log.d(
                "FocusGuardAdmin",
                "DNS anterior restaurado após a categoria Pornografia"
            )
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "DeviceOwner",
                "Falha ao restaurar DNS após a categoria Pornografia",
                error
            )
        }
    }

    private suspend fun applyWebsiteRestrictions(
        domains: List<String>,
        requireSystemDns: Boolean
    ) {
        websitePolicyMutex.withLock {
            withContext(Dispatchers.IO) {
                val chromePackages = DeviceOwnerPolicyCatalog.chromeManagedPackages
                    .filter(::isPackageInstalled)
                val edgePackages = DeviceOwnerPolicyCatalog.edgeManagedPackages
                    .filter(::isPackageInstalled)
                val targets = (chromePackages + edgePackages).sorted()
                val managedFilters = domains.map { domain ->
                    if (':' in domain) "[$domain]" else domain
                }
                val signature = managedFilters.joinToString("\u0000") +
                    "|" + targets.joinToString("\u0000") +
                    "|systemDns=$requireSystemDns"
                if (signature == lastWebsitePolicySignature) return@withContext

                var allWritesSucceeded = true
                targets.forEach { packageName ->
                    val privateModePolicy =
                        if (packageName in DeviceOwnerPolicyCatalog.edgeManagedPackages) {
                            "InPrivateModeAvailability"
                        } else {
                            "IncognitoModeAvailability"
                        }

                    try {
                        val restrictions = DeviceOwnerManager.buildManagedBrowserRestrictions(
                            existing = access.dpm.getApplicationRestrictions(
                                access.componentName,
                                packageName
                            ),
                            managedFilters = managedFilters,
                            privateModePolicy = privateModePolicy,
                            requireSystemDns = requireSystemDns
                        )
                        access.dpm.setApplicationRestrictions(
                            access.componentName,
                            packageName,
                            restrictions
                        )
                        val stored = access.dpm.getApplicationRestrictions(
                            access.componentName,
                            packageName
                        )
                        val storedDomains = stored.getStringArray("URLBlocklist")
                            ?.toList()
                            .orEmpty()
                        val dnsPolicyVerified = if (requireSystemDns) {
                            stored.getString("DnsOverHttpsMode") == "off" &&
                                !stored.containsKey("DnsOverHttpsTemplates")
                        } else {
                            !stored.containsKey("DnsOverHttpsMode") &&
                                !stored.containsKey("DnsOverHttpsTemplates")
                        }
                        val verified = if (managedFilters.isEmpty()) {
                            !stored.containsKey("URLBlocklist") &&
                                !stored.containsKey("IncognitoModeAvailability") &&
                                !stored.containsKey("InPrivateModeAvailability") &&
                                dnsPolicyVerified
                        } else {
                            storedDomains == managedFilters &&
                                stored.getInt(privateModePolicy, -1) == 1 &&
                                dnsPolicyVerified
                        }
                        if (!verified) {
                            allWritesSucceeded = false
                            FocusGuardLogger.log(
                                "DeviceOwner",
                                "O navegador $packageName não confirmou a política de URLs"
                            )
                        }
                    } catch (error: TransactionTooLargeException) {
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

    private fun capturePrivateDnsBeforeCategoryIfNeeded() {
        if (!access.isDeviceOwnerActive() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            AuthManager.isAdultFilterConfigured(context) || access.hasCapturedPrivateDns()
        ) {
            return
        }

        runCatching {
            val previousMode = access.dpm.getGlobalPrivateDnsMode(access.componentName)
            val previousHost = if (
                previousMode == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME
            ) {
                access.dpm.getGlobalPrivateDnsHost(access.componentName).orEmpty()
            } else {
                ""
            }
            access.capturePrivateDns(previousMode, previousHost)
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "DeviceOwner",
                "Falha ao guardar DNS anterior à categoria Pornografia",
                error
            )
        }
    }

    private fun isMaintenanceActive(): Boolean = access.isDeviceOwnerActive() &&
        DeviceOwnerMaintenanceGate.isTemporarilyUnlocked(context)

    @Suppress("DEPRECATION")
    private fun isPackageInstalled(packageName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0L)
            )
        } else {
            context.packageManager.getPackageInfo(packageName, 0)
        }
    }.isSuccess
}
