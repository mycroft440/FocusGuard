#!/usr/bin/env python3
from pathlib import Path
from textwrap import dedent

ROOT = Path(__file__).resolve().parents[2]

def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")

def write(rel, text):
    path = ROOT / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")

def replace_once(rel, old, new):
    text = read(rel)
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{rel}: expected exactly one match, got {count}")
    write(rel, text.replace(old, new, 1))

# FG-01 + FG-02: explicit targets only, plus live foreground tail.
rel = "app/src/main/java/com/focusguard/manager/BlockingSessionManager.kt"
replace_once(rel, "import android.os.Build\n", "import android.os.Build\nimport android.os.PowerManager\n")
replace_once(rel, "import com.focusguard.utils.AppUsageLimitActivationUsage\n", "import com.focusguard.utils.AppUsageForegroundResolver\nimport com.focusguard.utils.AppUsageLimitActivationUsage\n")

replace_once(rel, '''                val appFamilySites = WebsiteBlocker.domainRulesForAppPackages(
                    sessionApps + limitApps
                )
''', "")
replace_once(rel, '''                val strongerWebsiteApps = WebsiteBlocker.appPackageDomainsFor(
                    strongerWebsiteRules
                ).keys.filter(::isPackageInstalled)
                val sitesToBlock = (sessionSites + limitSites + appFamilySites + adultFilterRules)
''', '''                val sitesToBlock = (sessionSites + limitSites + adultFilterRules)
''')
replace_once(rel, '''                val websiteAppsToBlock = WebsiteBlocker.appPackageDomainsFor(sitesToBlock)
                    .keys
                    .filter(::isPackageInstalled)
                // A Focus Mode allowlist is an explicit temporary override:
''', '''                // A Focus Mode allowlist is an explicit temporary override:
''')
replace_once(rel, '''                    configuredBlockedPackages = sessionApps + limitApps + websiteAppsToBlock,
''', '''                    configuredBlockedPackages = sessionApps + limitApps,
''')
replace_once(rel, '''                val strongerAppPackages = (
                    strongerSessionApps + limitApps + strongerWebsiteApps + focusModeApps
                ).filter { packageName -> packageName in appsToBlock }.toSet()
''', '''                val strongerAppPackages = (
                    strongerSessionApps + limitApps + focusModeApps
                ).filter { packageName -> packageName in appsToBlock }.toSet()
''')
replace_once(rel, '''                val allSessionSites = getSitesForSessions(activeSessions.map { it.id })
                val allKnownWebsiteApps = WebsiteBlocker.appPackageDomainsFor(
                    allSessionSites + activeWebsiteLimits.map { it.domain }
                ).keys.filter(::isPackageInstalled)
                val allKnownApps = (
                    allSessionApps +
                        activeAppLimits.map { it.packageName } +
                        allKnownWebsiteApps +
                        focusModeApps
                ).distinct()
''', '''                val allKnownApps = (
                    allSessionApps +
                        activeAppLimits.map { it.packageName } +
                        focusModeApps
                ).distinct()
''')

replace_once(rel, '''        val usage = usageStatsManager.queryAndAggregateUsageStats(startOfDay, now)

        return limits.filter { limit ->
            val totalDayUsageMillis =
                usage[limit.packageName]?.totalTimeInForeground ?: 0L
''', '''        val usage = usageStatsManager.queryAndAggregateUsageStats(startOfDay, now)
        val isInteractive =
            (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive == true
        val currentForegroundPackage = if (isInteractive) {
            AppUsageForegroundResolver.currentForegroundPackage(
                usageStatsManager = usageStatsManager,
                startMillis = (startOfDay - 24L * 60L * 60L * 1_000L).coerceAtLeast(0L),
                endMillis = now
            )
        } else {
            null
        }

        return limits.filter { limit ->
            val stat = usage[limit.packageName]
            val totalDayUsageMillis = UsageLimitForegroundPolicy.includeOpenForegroundInterval(
                aggregatedForegroundMillis = stat?.totalTimeInForeground ?: 0L,
                lastUsageEventMillis = stat?.lastTimeUsed ?: 0L,
                nowMillis = now,
                isCurrentForeground = currentForegroundPackage == limit.packageName,
                isDeviceInteractive = isInteractive
            )
''')

# Accessibility: keep native apps independent from website-only selections; live app limit tail.
rel = "app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt"
replace_once(rel, '''                        val blockedWebsiteApps = WebsiteBlocker.appPackageDomainsFor(
                            sessionSites + exceededWebsiteDomains
                        ).filterKeys { it !in focusAllowedApps }
                        val limitedWebsiteApps = WebsiteBlocker.appPackageDomainsFor(
                            configuredWebsiteDomains
                        ).filterKeys { it !in focusAllowedApps }
''', '''                        // Native apps are enforced only when their package was
                        // explicitly persisted. A website rule must not silently
                        // acquire the companion app after the user declined it.
                        val blockedWebsiteApps = emptyMap<String, String>()
                        val limitedWebsiteApps = emptyMap<String, String>()
''')
replace_once(rel, '''        return limits.filter { limit ->
            val totalDayUsageMillis =
                usage[limit.packageName]?.totalTimeInForeground ?: 0L
''', '''        return limits.filter { limit ->
            val stat = usage[limit.packageName]
            val totalDayUsageMillis = UsageLimitForegroundPolicy.includeOpenForegroundInterval(
                aggregatedForegroundMillis = stat?.totalTimeInForeground ?: 0L,
                lastUsageEventMillis = stat?.lastTimeUsed ?: 0L,
                nowMillis = now,
                isCurrentForeground = foregroundPackageName == limit.packageName,
                isDeviceInteractive = powerManager?.isInteractive == true
            )
''')

write("app/src/main/java/com/focusguard/utils/AppUsageForegroundResolver.kt", dedent('''
    package com.focusguard.utils

    import android.app.usage.UsageEvents
    import android.app.usage.UsageStatsManager

    /** Resolves the currently resumed package from exact UsageEvents. */
    object AppUsageForegroundResolver {
        private const val EVENT_ACTIVITY_RESUMED = 1
        private const val EVENT_ACTIVITY_PAUSED = 2
        private const val EVENT_ACTIVITY_STOPPED = 23

        fun currentForegroundPackage(
            usageStatsManager: UsageStatsManager,
            startMillis: Long,
            endMillis: Long
        ): String? {
            if (endMillis <= startMillis) return null
            return runCatching {
                val events = usageStatsManager.queryEvents(startMillis, endMillis)
                val event = UsageEvents.Event()
                var foregroundPackage: String? = null
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    val packageName = event.packageName?.takeIf(String::isNotBlank) ?: continue
                    when (event.eventType) {
                        EVENT_ACTIVITY_RESUMED -> foregroundPackage = packageName
                        EVENT_ACTIVITY_PAUSED,
                        EVENT_ACTIVITY_STOPPED -> {
                            if (foregroundPackage == packageName) {
                                foregroundPackage = null
                            }
                        }
                    }
                }
                foregroundPackage
            }.getOrNull()
        }
    }
''').lstrip())

write("app/src/main/java/com/focusguard/utils/UsageLimitForegroundPolicy.kt", dedent('''
    package com.focusguard.utils

    /** Shared rules that keep daily limits restricted to active foreground use. */
    object UsageLimitForegroundPolicy {

        fun usedMinutes(totalTimeInForegroundMillis: Long): Long =
            totalTimeInForegroundMillis.coerceAtLeast(0L) / 60_000L

        /**
         * Adds only the open foreground segment that UsageStats has not yet committed.
         *
         * [lastUsageEventMillis] is the latest event reflected by the aggregate. Using
         * it as the anchor avoids counting the same interval twice when Android updates
         * the aggregate while the Activity remains resumed.
         */
        fun includeOpenForegroundInterval(
            aggregatedForegroundMillis: Long,
            lastUsageEventMillis: Long,
            nowMillis: Long,
            isCurrentForeground: Boolean,
            isDeviceInteractive: Boolean
        ): Long {
            val aggregated = aggregatedForegroundMillis.coerceAtLeast(0L)
            if (!isCurrentForeground || !isDeviceInteractive) return aggregated
            if (lastUsageEventMillis <= 0L || lastUsageEventMillis >= nowMillis) return aggregated
            return aggregated + (nowMillis - lastUsageEventMillis)
        }

        fun shouldCountWebsiteUsage(
            trackedPackageName: String?,
            foregroundPackageName: String?,
            isDeviceInteractive: Boolean
        ): Boolean =
            isDeviceInteractive &&
                !trackedPackageName.isNullOrBlank() &&
                trackedPackageName == foregroundPackageName

        /**
         * Cheap guard for the one-second app-limit pulse.
         *
         * This must run before Room/UsageStats work. FocusGuard itself, the launcher,
         * a blank foreground package, a screen-off device, or an app with no active
         * limit cannot possibly need app-limit measurement on that pulse.
         */
        fun shouldMeasureCurrentApp(
            foregroundPackageName: String?,
            activeLimitPackages: Set<String>,
            focusGuardPackageName: String,
            launcherPackageName: String?,
            isDeviceInteractive: Boolean
        ): Boolean =
            isDeviceInteractive &&
                !foregroundPackageName.isNullOrBlank() &&
                foregroundPackageName != focusGuardPackageName &&
                foregroundPackageName != launcherPackageName &&
                foregroundPackageName in activeLimitPackages

        fun shouldEnforceCurrentApp(
            foregroundPackageName: String?,
            exceededPackages: Set<String>,
            focusGuardPackageName: String,
            launcherPackageName: String?,
            isDeviceInteractive: Boolean
        ): Boolean =
            shouldMeasureCurrentApp(
                foregroundPackageName = foregroundPackageName,
                activeLimitPackages = exceededPackages,
                focusGuardPackageName = focusGuardPackageName,
                launcherPackageName = launcherPackageName,
                isDeviceInteractive = isDeviceInteractive
            )
    }
''').lstrip())

# FG-03: DevicePolicyManager DNS connectivity check must not run on main.
rel = "app/src/main/java/com/focusguard/ui/compose/screens/LimitsSecurityScreen.kt"
replace_once(rel, "import kotlinx.coroutines.launch\n", "import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.launch\nimport kotlinx.coroutines.withContext\n")
replace_once(rel, '''                            } else {
                                authManager.setAdultFilterEnabled(true)
                                val success = deviceOwnerManager.enforceAdultDns()
                                if (success) {
                                    adultFilterEnabled = true
                                    policyScope.launch {
                                        blockingSessionManager.checkAndEnforce()
                                    }
                                } else {
                                    authManager.setAdultFilterEnabled(false)
                                    adultFilterEnabled = false
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.limits_adult_filter_failed),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                            context.sendBroadcast(
                                com.focusguard.service.BlockingAccessibilityService
                                    .createRefreshBlockingIntent(context)
                            )
''', '''                            } else {
                                authManager.setAdultFilterEnabled(true)
                                policyScope.launch {
                                    val success = withContext(Dispatchers.IO) {
                                        deviceOwnerManager.enforceAdultDns()
                                    }
                                    if (success) {
                                        adultFilterEnabled = true
                                        withContext(Dispatchers.IO) {
                                            blockingSessionManager.checkAndEnforce()
                                        }
                                    } else {
                                        authManager.setAdultFilterEnabled(false)
                                        adultFilterEnabled = false
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.limits_adult_filter_failed),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                    context.sendBroadcast(
                                        com.focusguard.service.BlockingAccessibilityService
                                            .createRefreshBlockingIntent(context)
                                    )
                                }
                                return@Switch
                            }
                            context.sendBroadcast(
                                com.focusguard.service.BlockingAccessibilityService
                                    .createRefreshBlockingIntent(context)
                            )
''')

rel = "app/src/main/java/com/focusguard/FocusGuardApplication.kt"
replace_once(rel, '''            deviceOwnerManager.applyNuclearShield()
            AccessibilityStateMonitor.start(this)
''', '''            applicationScope.launch {
                deviceOwnerManager.applyNuclearShield()
            }
            AccessibilityStateMonitor.start(this)
''')

write("app/src/main/java/com/focusguard/receiver/DeviceOwnerMaintenanceExpiryReceiver.kt", dedent('''
    package com.focusguard.receiver

    import android.content.BroadcastReceiver
    import android.content.Context
    import android.content.Intent
    import com.focusguard.admin.DeviceOwnerManager
    import com.focusguard.security.DeviceOwnerMaintenanceGate
    import com.focusguard.utils.FocusGuardLogger
    import kotlinx.coroutines.CoroutineScope
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.SupervisorJob
    import kotlinx.coroutines.launch

    class DeviceOwnerMaintenanceExpiryReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != DeviceOwnerMaintenanceGate.ACTION_EXPIRE_MAINTENANCE) return

            val pendingResult = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    DeviceOwnerMaintenanceGate.revoke(context)
                    DeviceOwnerManager.getInstance(context.applicationContext).applyNuclearShield()
                } catch (error: Exception) {
                    FocusGuardLogger.logError(
                        "DeviceOwnerMaintenance",
                        "Falha ao reaplicar políticas após expirar manutenção",
                        error
                    )
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
''').lstrip())

# FG-04: rebuild Focus Mode inventory during every reconciliation and package change.
rel = "app/src/main/java/com/focusguard/focusmode/FocusModeManager.kt"
replace_once(rel, '''        return@withLock try {
            val nativeFocusLockdownActive = FocusModePolicy.usesNativeFocusLockdown(
                deviceOwnerActive = deviceOwnerManager.isDeviceOwnerActive(),
                systemLockdownSupported =
                    deviceOwnerManager.isFocusModeSystemLockdownSupported()
            )
            if (nativeFocusLockdownActive) {
                check(deviceOwnerManager.prepareFocusModeLockTaskPackages(stored.allowedPackages))
            }
            check(FocusModeHomeController.reconcile(context))
            check(FocusModeKioskController.reconcileSystemRestrictions(context))
            blockingSessionManager.checkAndEnforceStrict()
            if (nativeFocusLockdownActive) {
                check(FocusModeHomeController.isNativeHomeConfigured(context))
            }
            FocusModeForegroundService.start(context)
            FocusModeReceiver.scheduleExpiration(context, stored.endTimeMillis)
            FocusModeNotificationService.requestRefresh(context)
            _session.value = stored
            true
''', '''        return@withLock try {
            val launchablePackages = withContext(Dispatchers.IO) {
                FocusModeAppCatalog.loadLaunchableApps(context)
                    .mapTo(mutableSetOf()) { it.packageName }
            }
            val refreshedBlockedPackages = FocusModePolicy.packagesToBlock(
                launchablePackages = launchablePackages,
                allowedPackages = stored.allowedPackages
            )
            val refreshedSession = if (refreshedBlockedPackages != stored.blockedPackages) {
                stored.copy(
                    blockedPackages = refreshedBlockedPackages,
                    nonSuspendablePackages =
                        stored.nonSuspendablePackages.intersect(refreshedBlockedPackages)
                ).also { refreshed ->
                    check(FocusModeStore.saveSession(context, refreshed)) {
                        "Não foi possível persistir o inventário atualizado do Modo Foco"
                    }
                }
            } else {
                stored
            }

            val nativeFocusLockdownActive = FocusModePolicy.usesNativeFocusLockdown(
                deviceOwnerActive = deviceOwnerManager.isDeviceOwnerActive(),
                systemLockdownSupported =
                    deviceOwnerManager.isFocusModeSystemLockdownSupported()
            )
            if (nativeFocusLockdownActive) {
                check(
                    deviceOwnerManager.prepareFocusModeLockTaskPackages(
                        refreshedSession.allowedPackages
                    )
                )
            }
            check(FocusModeHomeController.reconcile(context))
            check(FocusModeKioskController.reconcileSystemRestrictions(context))
            blockingSessionManager.checkAndEnforceStrict()
            if (nativeFocusLockdownActive) {
                check(FocusModeHomeController.isNativeHomeConfigured(context))
            }
            val nonSuspendable = if (nativeFocusLockdownActive) {
                refreshedSession.blockedPackages.filterNotTo(mutableSetOf()) {
                    deviceOwnerManager.isPackageSuspendedByFocusMode(it)
                }
            } else {
                emptySet()
            }
            val verifiedSession = FocusModeStore.updateNonSuspendablePackages(
                context,
                nonSuspendable
            ) ?: refreshedSession.copy(nonSuspendablePackages = nonSuspendable)
            FocusModeForegroundService.start(context)
            FocusModeReceiver.scheduleExpiration(context, verifiedSession.endTimeMillis)
            FocusModeNotificationService.requestRefresh(context)
            _session.value = verifiedSession
            true
''')

write("app/src/main/java/com/focusguard/receiver/PackageChangeReceiver.kt", dedent('''
    package com.focusguard.receiver

    import android.content.BroadcastReceiver
    import android.content.Context
    import android.content.Intent
    import com.focusguard.focusmode.FocusModeManager
    import com.focusguard.focusmode.FocusModeStore
    import com.focusguard.manager.BlockingSessionManager
    import com.focusguard.utils.FocusGuardLogger
    import kotlinx.coroutines.CoroutineScope
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.SupervisorJob
    import kotlinx.coroutines.launch

    class PackageChangeReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_PACKAGE_ADDED &&
                intent.action != Intent.ACTION_PACKAGE_REPLACED &&
                intent.action != Intent.ACTION_PACKAGE_CHANGED
            ) return

            val changedPackage = intent.data?.schemeSpecificPart.orEmpty()
            if (changedPackage.isBlank() || changedPackage == context.packageName) return

            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    val appContext = context.applicationContext
                    if (FocusModeStore.readSession(appContext) != null) {
                        FocusModeManager.getInstance(appContext).ensureEnforced()
                    } else {
                        BlockingSessionManager.getInstance(appContext).checkAndEnforce()
                    }
                } catch (error: Exception) {
                    FocusGuardLogger.logError(
                        "PackageChangeReceiver",
                        "Falha ao reaplicar bloqueio após mudança de pacote: $changedPackage",
                        error
                    )
                } finally {
                    pending.finish()
                }
            }
        }
    }
''').lstrip())

# FG-05: persist exact activation baseline immediately; reconstruct old/missing baseline from exact events.
write("app/src/main/java/com/focusguard/utils/AppUsageLimitActivationUsage.kt", dedent('''
    package com.focusguard.utils

    import android.app.usage.UsageEvents
    import android.app.usage.UsageStatsManager
    import android.content.Context
    import android.os.Build
    import com.focusguard.database.AppUsageLimit

    /**
     * Converts Android's day-wide UsageStats counter into usage after a limit activation.
     *
     * New limits persist the day aggregate at the instant they are created. If an old
     * row or an interrupted write has no baseline, the fallback reconstructs only the
     * pre-activation interval from UsageEvents instead of asking UsageStats for an
     * arbitrary historical aggregate whose interval can be expanded by Android.
     */
    object AppUsageLimitActivationUsage {
        private const val PREFS_NAME = "app_usage_limit_activation_usage"
        private const val SUFFIX_ACTIVATED_AT = ".activated_at"
        private const val SUFFIX_DAY_START = ".day_start"
        private const val SUFFIX_BASELINE_MS = ".baseline_ms"
        private const val EVENT_ACTIVITY_RESUMED = 1
        private const val EVENT_ACTIVITY_PAUSED = 2
        private const val EVENT_ACTIVITY_STOPPED = 23
        private const val EVENT_LOOKBACK_MILLIS = 24L * 60L * 60L * 1_000L

        internal data class ForegroundTransition(
            val atMillis: Long,
            val enteredForeground: Boolean,
            val instanceId: Int? = null
        )

        private data class BaselineKey(
            val packageName: String,
            val activatedAtMillis: Long,
            val dayStartMillis: Long
        )

        private val memoryBaselines = mutableMapOf<BaselineKey, Long>()

        fun effectiveUsageMillis(
            context: Context,
            usageStatsManager: UsageStatsManager,
            limit: AppUsageLimit,
            currentDayUsageMillis: Long,
            dayStartMillis: Long,
            nowMillis: Long
        ): Long {
            val currentUsage = currentDayUsageMillis.coerceAtLeast(0L)
            val activatedAt = limit.createdAt
            if (activatedAt <= dayStartMillis) return currentUsage
            if (activatedAt > nowMillis) return 0L

            val baseline = readOrCreateBaseline(
                context = context,
                usageStatsManager = usageStatsManager,
                packageName = limit.packageName,
                activatedAtMillis = activatedAt,
                dayStartMillis = dayStartMillis,
                nowMillis = nowMillis
            ) ?: return 0L

            return usageSinceActivationMillis(
                currentDayUsageMillis = currentUsage,
                activationBaselineMillis = baseline,
                activatedAtMillis = activatedAt,
                dayStartMillis = dayStartMillis
            )
        }

        fun captureActivationBaseline(
            context: Context,
            usageStatsManager: UsageStatsManager,
            packageName: String,
            activatedAtMillis: Long,
            dayStartMillis: Long
        ): Boolean {
            if (packageName.isBlank() || activatedAtMillis <= dayStartMillis) return true
            if (!PermissionUtils.isUsageAccessEnabled(context)) return false

            val baseline = try {
                usageStatsManager
                    .queryAndAggregateUsageStats(dayStartMillis, activatedAtMillis)
                    .get(packageName)
                    ?.totalTimeInForeground
                    ?.coerceAtLeast(0L)
                    ?: 0L
            } catch (_: RuntimeException) {
                return false
            }
            persistBaseline(
                context = context,
                packageName = packageName,
                activatedAtMillis = activatedAtMillis,
                dayStartMillis = dayStartMillis,
                baselineMillis = baseline
            )
            return true
        }

        /** Pure calculation kept public for deterministic unit coverage. */
        fun usageSinceActivationMillis(
            currentDayUsageMillis: Long,
            activationBaselineMillis: Long,
            activatedAtMillis: Long,
            dayStartMillis: Long
        ): Long {
            val currentUsage = currentDayUsageMillis.coerceAtLeast(0L)
            if (activatedAtMillis <= dayStartMillis) return currentUsage
            return (currentUsage - activationBaselineMillis.coerceAtLeast(0L))
                .coerceAtLeast(0L)
        }

        internal fun foregroundUsageMillis(
            transitions: List<ForegroundTransition>,
            startMillis: Long,
            endMillis: Long
        ): Long {
            if (endMillis <= startMillis) return 0L
            var legacyActive = false
            val activeInstances = mutableSetOf<Int>()
            var segmentStart: Long? = null
            var total = 0L

            transitions.sortedBy(ForegroundTransition::atMillis).forEach { transition ->
                val before = legacyActive || activeInstances.isNotEmpty()
                val instanceId = transition.instanceId
                if (instanceId == null) {
                    legacyActive = transition.enteredForeground
                } else if (transition.enteredForeground) {
                    activeInstances += instanceId
                } else {
                    activeInstances -= instanceId
                }
                val after = legacyActive || activeInstances.isNotEmpty()
                when {
                    !before && after -> {
                        segmentStart = transition.atMillis.coerceAtLeast(startMillis)
                    }
                    before && !after -> {
                        val from = segmentStart ?: startMillis
                        val until = transition.atMillis.coerceAtMost(endMillis)
                        if (until > from) total += until - from
                        segmentStart = null
                    }
                }
            }

            if ((legacyActive || activeInstances.isNotEmpty()) && segmentStart != null) {
                val from = segmentStart!!.coerceAtLeast(startMillis)
                if (endMillis > from) total += endMillis - from
            }
            return total.coerceAtLeast(0L)
        }

        @Synchronized
        private fun readOrCreateBaseline(
            context: Context,
            usageStatsManager: UsageStatsManager,
            packageName: String,
            activatedAtMillis: Long,
            dayStartMillis: Long,
            nowMillis: Long
        ): Long? {
            if (packageName.isBlank()) return null
            val cacheKey = BaselineKey(packageName, activatedAtMillis, dayStartMillis)
            memoryBaselines[cacheKey]?.let { return it }

            val prefs = context.applicationContext.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            val keyPrefix = packageName
            val storedActivation = prefs.getLong(keyPrefix + SUFFIX_ACTIVATED_AT, Long.MIN_VALUE)
            val storedDayStart = prefs.getLong(keyPrefix + SUFFIX_DAY_START, Long.MIN_VALUE)
            if (storedActivation == activatedAtMillis && storedDayStart == dayStartMillis) {
                val persisted = prefs.getLong(keyPrefix + SUFFIX_BASELINE_MS, 0L)
                    .coerceAtLeast(0L)
                memoryBaselines[cacheKey] = persisted
                return persisted
            }

            if (!PermissionUtils.isUsageAccessEnabled(context)) return null
            val baselineEnd = activatedAtMillis.coerceAtMost(nowMillis)
            val baseline = try {
                queryExactForegroundUsage(
                    usageStatsManager = usageStatsManager,
                    packageName = packageName,
                    dayStartMillis = dayStartMillis,
                    endMillis = baselineEnd
                )
            } catch (_: RuntimeException) {
                return null
            }

            persistBaseline(
                context = context,
                packageName = packageName,
                activatedAtMillis = activatedAtMillis,
                dayStartMillis = dayStartMillis,
                baselineMillis = baseline
            )
            return baseline
        }

        private fun queryExactForegroundUsage(
            usageStatsManager: UsageStatsManager,
            packageName: String,
            dayStartMillis: Long,
            endMillis: Long
        ): Long {
            if (endMillis <= dayStartMillis) return 0L
            val events = usageStatsManager.queryEvents(
                (dayStartMillis - EVENT_LOOKBACK_MILLIS).coerceAtLeast(0L),
                endMillis
            )
            val event = UsageEvents.Event()
            val transitions = mutableListOf<ForegroundTransition>()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.packageName != packageName) continue
                val entered = when (event.eventType) {
                    EVENT_ACTIVITY_RESUMED -> true
                    EVENT_ACTIVITY_PAUSED,
                    EVENT_ACTIVITY_STOPPED -> false
                    else -> continue
                }
                val instanceId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    event.instanceId
                } else {
                    null
                }
                transitions += ForegroundTransition(
                    atMillis = event.timeStamp,
                    enteredForeground = entered,
                    instanceId = instanceId
                )
            }
            return foregroundUsageMillis(
                transitions = transitions,
                startMillis = dayStartMillis,
                endMillis = endMillis
            )
        }

        @Synchronized
        private fun persistBaseline(
            context: Context,
            packageName: String,
            activatedAtMillis: Long,
            dayStartMillis: Long,
            baselineMillis: Long
        ) {
            val cacheKey = BaselineKey(packageName, activatedAtMillis, dayStartMillis)
            val baseline = baselineMillis.coerceAtLeast(0L)
            memoryBaselines.keys.removeAll { it.packageName == packageName && it != cacheKey }
            memoryBaselines[cacheKey] = baseline
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(packageName + SUFFIX_ACTIVATED_AT, activatedAtMillis)
                .putLong(packageName + SUFFIX_DAY_START, dayStartMillis)
                .putLong(packageName + SUFFIX_BASELINE_MS, baseline)
                .commit()
        }
    }
''').lstrip())

rel = "app/src/main/java/com/focusguard/ui/compose/screens/UsageLimitsScreen.kt"
replace_once(rel, '''                        val updated = if (minutes != null && minutes > 0) {
                            limitDao.insert(
                                AppUsageLimit(
                                    packageName = appToSave.packageName,
''', '''                        val updated = if (minutes != null && minutes > 0) {
                            val activationTime = System.currentTimeMillis()
                            val activationDayStart = java.util.Calendar.getInstance().apply {
                                timeInMillis = activationTime
                                set(java.util.Calendar.HOUR_OF_DAY, 0)
                                set(java.util.Calendar.MINUTE, 0)
                                set(java.util.Calendar.SECOND, 0)
                                set(java.util.Calendar.MILLISECOND, 0)
                            }.timeInMillis
                            val usageStatsManager = context.getSystemService(
                                Context.USAGE_STATS_SERVICE
                            ) as android.app.usage.UsageStatsManager
                            AppUsageLimitActivationUsage.captureActivationBaseline(
                                context = context,
                                usageStatsManager = usageStatsManager,
                                packageName = appToSave.packageName,
                                activatedAtMillis = activationTime,
                                dayStartMillis = activationDayStart
                            )
                            limitDao.insert(
                                AppUsageLimit(
                                    packageName = appToSave.packageName,
''')
replace_once(rel, '''                                    lockUntilTimestamp = lockUntil,
                                    preventOpeningAfterLimit = true,
''', '''                                    lockUntilTimestamp = lockUntil,
                                    createdAt = activationTime,
                                    preventOpeningAfterLimit = true,
''')

# FG-06: every still-active persisted pause must re-arm its alarm.
rel = "app/src/main/java/com/focusguard/utils/UsageLimitBehaviorPolicy.kt"
replace_once(rel, '''        return PauseEvaluation(
            shouldBlock = nowMillis < previous.blockedUntilMillis,
            state = previous,
            startedPause = false
        )
    }
}
''', '''        return PauseEvaluation(
            shouldBlock = nowMillis < previous.blockedUntilMillis,
            state = previous,
            startedPause = false
        )
    }

    internal fun pauseEndDeadline(
        evaluation: PauseEvaluation,
        nowMillis: Long
    ): Long? = evaluation.state
        ?.blockedUntilMillis
        ?.takeIf { evaluation.shouldBlock && it > nowMillis }
}
''')
replace_once(rel, '''        if (evaluation.startedPause) {
            notifyUser(R.string.limits_pause_notice)
            evaluation.state?.blockedUntilMillis?.let { blockedUntil ->
                storageContext?.let { context ->
                    BlockingScheduleReceiver.scheduleUsageLimitPauseEnd(
                        context = context,
                        identifier = identifier,
                        atMillis = blockedUntil
                    )
                }
            }
        }
        return evaluation.shouldBlock
''', '''        if (evaluation.startedPause) {
            notifyUser(R.string.limits_pause_notice)
        }
        UsageLimitBehaviorPolicy.pauseEndDeadline(evaluation, nowMillis)?.let { blockedUntil ->
            storageContext?.let { context ->
                BlockingScheduleReceiver.scheduleUsageLimitPauseEnd(
                    context = context,
                    identifier = identifier,
                    atMillis = blockedUntil
                )
            }
        }
        return evaluation.shouldBlock
''')

# Focused unit coverage.
rel = "app/src/test/java/com/focusguard/utils/UsageLimitForegroundPolicyTest.kt"
replace_once(rel, "\n}\n", dedent('''

        @Test
        fun `open foreground interval advances an otherwise stale aggregate`() {
            val now = 10 * 60_000L
            val reported = 4 * 60_000L + 30_000L
            val lastUsageEvent = now - 30_000L

            assertThat(
                UsageLimitForegroundPolicy.includeOpenForegroundInterval(
                    aggregatedForegroundMillis = reported,
                    lastUsageEventMillis = lastUsageEvent,
                    nowMillis = now,
                    isCurrentForeground = true,
                    isDeviceInteractive = true
                )
            ).isEqualTo(5 * 60_000L)
        }

        @Test
        fun `open foreground interval is not counted for background or screen off`() {
            val now = 600_000L
            val reported = 300_000L

            assertThat(
                UsageLimitForegroundPolicy.includeOpenForegroundInterval(
                    aggregatedForegroundMillis = reported,
                    lastUsageEventMillis = now - 60_000L,
                    nowMillis = now,
                    isCurrentForeground = false,
                    isDeviceInteractive = true
                )
            ).isEqualTo(reported)
            assertThat(
                UsageLimitForegroundPolicy.includeOpenForegroundInterval(
                    aggregatedForegroundMillis = reported,
                    lastUsageEventMillis = now - 60_000L,
                    nowMillis = now,
                    isCurrentForeground = true,
                    isDeviceInteractive = false
                )
            ).isEqualTo(reported)
        }
    }
'''))

rel = "app/src/test/java/com/focusguard/utils/AppUsageLimitActivationUsageTest.kt"
replace_once(rel, "\n}\n", dedent('''

        @Test
        fun `event fallback cuts the baseline exactly at activation`() {
            val start = 1_000L
            val activation = 11_000L
            val transitions = listOf(
                AppUsageLimitActivationUsage.ForegroundTransition(2_000L, true, 7),
                AppUsageLimitActivationUsage.ForegroundTransition(6_000L, false, 7),
                AppUsageLimitActivationUsage.ForegroundTransition(8_000L, true, 8)
            )

            assertThat(
                AppUsageLimitActivationUsage.foregroundUsageMillis(
                    transitions = transitions,
                    startMillis = start,
                    endMillis = activation
                )
            ).isEqualTo(7_000L)
        }

        @Test
        fun `event fallback does not double count overlapping activities`() {
            val transitions = listOf(
                AppUsageLimitActivationUsage.ForegroundTransition(1_000L, true, 1),
                AppUsageLimitActivationUsage.ForegroundTransition(2_000L, true, 2),
                AppUsageLimitActivationUsage.ForegroundTransition(3_000L, false, 1),
                AppUsageLimitActivationUsage.ForegroundTransition(5_000L, false, 2)
            )

            assertThat(
                AppUsageLimitActivationUsage.foregroundUsageMillis(
                    transitions = transitions,
                    startMillis = 0L,
                    endMillis = 6_000L
                )
            ).isEqualTo(4_000L)
        }
    }
'''))

rel = "app/src/test/java/com/focusguard/utils/UsageLimitBehaviorPolicyTest.kt"
replace_once(rel, "\n}\n", dedent('''

        @Test
        fun `restored active pause still exposes its alarm deadline`() {
            val now = 7_000_000L
            val deadline = now + 10L * 60L * 1_000L
            val evaluation = UsageLimitBehaviorPolicy.PauseEvaluation(
                shouldBlock = true,
                state = UsageLimitBehaviorPolicy.PauseState(
                    ruleEndMillis = deadline + 60_000L,
                    dayKey = 2026240L,
                    blockedUntilMillis = deadline
                ),
                startedPause = false
            )

            assertEquals(
                deadline,
                UsageLimitBehaviorPolicy.pauseEndDeadline(evaluation, now)
            )
        }
    }
'''))

rel = "app/src/test/java/com/focusguard/focusmode/FocusModePolicyTest.kt"
replace_once(rel, "\n}\n", dedent('''

        @Test
        fun `newly installed launchable app is blocked when it is not allowlisted`() {
            val allowed = setOf("com.focusguard", "com.example.allowed")
            val beforeInstall = FocusModePolicy.packagesToBlock(
                launchablePackages = listOf("com.focusguard", "com.example.allowed"),
                allowedPackages = allowed
            )
            val afterInstall = FocusModePolicy.packagesToBlock(
                launchablePackages = listOf(
                    "com.focusguard",
                    "com.example.allowed",
                    "com.example.new"
                ),
                allowedPackages = allowed
            )

            assertThat(beforeInstall).isEmpty()
            assertThat(afterInstall).containsExactly("com.example.new")
        }
    }
'''))

# Static regression guards for FG-01.
manager = read("app/src/main/java/com/focusguard/manager/BlockingSessionManager.kt")
service = read("app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt")
for forbidden in (
    "domainRulesForAppPackages(\\n                    sessionApps + limitApps",
    "configuredBlockedPackages = sessionApps + limitApps + websiteAppsToBlock",
):
    if forbidden in manager:
        raise RuntimeError(f"FG-01 regression remains in BlockingSessionManager: {forbidden}")
if "val blockedWebsiteApps = WebsiteBlocker.appPackageDomainsFor(" in service:
    raise RuntimeError("FG-01 regression remains in accessibility blockedWebsiteApps")

print("Applied FG-01 through FG-06 fixes.")
