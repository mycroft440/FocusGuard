package com.focusguard.manager

import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.data.PredefinedWebsites
import com.focusguard.database.AppDatabase
import com.focusguard.database.AppUsageLimit
import com.focusguard.database.BlockSession
import com.focusguard.domain.model.BlockSessionType
import com.focusguard.domain.model.UsageLimitLockMode
import com.focusguard.domain.port.BlockingRuntimePort
import com.focusguard.domain.port.BlockingSnapshot
import com.focusguard.focusmode.FocusModePolicy
import com.focusguard.focusmode.FocusModeStore
import com.focusguard.scheduling.BlockingScheduleCalculator
import com.focusguard.security.AuthManager
import com.focusguard.security.MasterCredentialPolicy
import com.focusguard.security.SelfProtectionStateStore
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.UsageLimitForegroundPolicy
import com.focusguard.utils.WebsiteBlocker
import com.focusguard.utils.WebsiteUsageLimitPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes and applies the effective blocking policy across Android surfaces. */
@Singleton
class BlockingPolicyReconciler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val deviceOwnerManager: DeviceOwnerManager,
    private val blockingRuntime: BlockingRuntimePort
) {
    private val enforcementMutex = Mutex()
    private val statePreferences = context.getSharedPreferences(
        STATE_PREFERENCES,
        Context.MODE_PRIVATE
    )

    suspend fun reconcile() {
        enforcementMutex.withLock {
            val now = System.currentTimeMillis()
            val focusModeSession = FocusModeStore.readSession(context)
                ?.takeIf { it.isActive(now) }
            val focusModeApps = focusModeSession?.blockedPackages.orEmpty()
            val beforeExpiration = database.blockSessionDao().getAllActiveSessionsStatic()
            val expiredPomodoro = beforeExpiration.any { session ->
                session.sessionType == BlockSessionType.POMODORO &&
                    session.endTime?.let { it <= now } == true
            }
            database.blockSessionDao().deactivateExpiredSessions(now)
            if (expiredPomodoro) {
                StrictPomodoroLock.clear(context)
                blockingRuntime.stopPomodoroForeground()
            }

            val activeSessions = database.blockSessionDao().getAllActiveSessionsStatic()
            val enforcingSessions = activeSessions.filter {
                BlockingSessionManager.participatesInBlocking(it) &&
                    isCurrentlyInBlockingWindow(it)
            }
            val enforcingIds = enforcingSessions.map { it.id }
            val strictPomodoro = enforcingSessions.any {
                it.sessionType == BlockSessionType.POMODORO && it.isBlockingEnabled
            }

            setDoNotDisturbMode(strictPomodoro)

            val sessionApps = if (strictPomodoro) {
                getInstalledUserAppsExceptPhone()
            } else {
                getAppsForSessions(enforcingIds)
            }
            val sessionSites = getSitesForSessions(enforcingIds)

            val activeAppLimits = database.appUsageLimitDao().getAllActiveLimitsStatic()
            val limitApps = getExceededAppLimits(activeAppLimits, now)

            val activeWebsiteLimits = database.websiteUsageLimitDao().getAllStatic()
                .filter { it.isEnabled }
            val adultFilterEnabled = AuthManager.isAdultFilterConfigured(context)
            val policyExpirations = (
                activeAppLimits.mapNotNull { limit ->
                    if (limit.lockMode == UsageLimitLockMode.TIME) {
                        limit.lockUntilTimestamp?.takeIf { it > now }
                    } else {
                        null
                    }
                } + activeWebsiteLimits.mapNotNull { limit ->
                    if (limit.lockMode == UsageLimitLockMode.TIME) {
                        limit.lockUntilTimestamp?.takeIf { it > now }
                    } else {
                        null
                    }
                }
                )
            val nextDailyReset = if (
                activeAppLimits.isNotEmpty() || activeWebsiteLimits.isNotEmpty()
            ) {
                BlockingScheduleCalculator.nextLocalMidnight(now)
            } else {
                null
            }
            val nextReconciliation = if (
                activeSessions.isNotEmpty() ||
                activeAppLimits.isNotEmpty() ||
                activeWebsiteLimits.isNotEmpty() ||
                adultFilterEnabled ||
                focusModeSession != null
            ) {
                now + POLICY_RECONCILIATION_INTERVAL_MILLIS
            } else {
                null
            }
            val nextBoundary = BlockingScheduleCalculator.nextBoundary(
                sessions = activeSessions,
                additionalBoundaries = policyExpirations +
                    listOfNotNull(
                        nextDailyReset,
                        nextReconciliation,
                        focusModeSession?.endTimeMillis
                    ),
                nowMillis = now
            )
            blockingRuntime.scheduleReconciliation(nextBoundary)

            val activeWebsiteDomains = WebsiteBlocker.normalizeRules(
                activeWebsiteLimits.map { it.domain }
            )
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
            val usageByWebsite = WebsiteUsageLimitPolicy.aggregateUsageByRule(
                usageByIdentifier = database.dailyUsageStatDao()
                    .getStatsForDateStatic(today)
                    .map { it.identifier to it.timeSpentMs },
                configuredRules = activeWebsiteDomains
            )
            val limitSites = activeWebsiteLimits.filter { limit ->
                val normalizedDomain = WebsiteBlocker.normalizeRule(limit.domain)
                WebsiteUsageLimitPolicy.shouldBlock(
                    usedMillis = usageByWebsite[normalizedDomain] ?: 0L,
                    dailyLimitMinutes = limit.dailyLimitMinutes,
                    lockMode = limit.lockMode,
                    lockUntilTimestamp = limit.lockUntilTimestamp,
                    nowMillis = now
                )
            }.map { WebsiteBlocker.normalizeRule(it.domain) }

            val appFamilySites = WebsiteBlocker.domainRulesForAppPackages(
                sessionApps + limitApps
            )
            val adultFilterRules = if (adultFilterEnabled) {
                listOf(PredefinedWebsites.PORNOGRAPHY_RULE)
            } else {
                emptyList()
            }
            val sitesToBlock = (sessionSites + limitSites + appFamilySites + adultFilterRules)
                .map(WebsiteBlocker::normalizeRule)
                .filter { it.isNotBlank() }
                .distinct()
            deviceOwnerManager.setPornographyCategoryActive(
                WebsiteBlocker.containsPornographyRule(sitesToBlock)
            )
            val websiteAppsToBlock = WebsiteBlocker.appPackageDomainsFor(sitesToBlock)
                .keys
                .filter(::isPackageInstalled)

            val appsToBlock = FocusModePolicy.packagesToEnforce(
                configuredBlockedPackages = sessionApps + limitApps + websiteAppsToBlock,
                focusModeBlockedPackages = focusModeApps,
                focusModeAllowedPackages = focusModeSession?.allowedPackages.orEmpty()
            ).toList()
            val nativeFocusLockdownActive = focusModeSession != null &&
                FocusModePolicy.usesNativeFocusLockdown(
                    deviceOwnerActive = deviceOwnerManager.isDeviceOwnerActive(),
                    systemLockdownSupported =
                        deviceOwnerManager.isFocusModeSystemLockdownSupported()
                )
            val accessibilityAppsToBlock = FocusModePolicy.packagesForAccessibility(
                enforcedPackages = appsToBlock,
                focusModeBlockedPackages = focusModeApps,
                nativeFocusLockdownActive = nativeFocusLockdownActive
            ).toList()

            val allSessionApps = getAppsForSessions(activeSessions.map { it.id })
            val allSessionSites = getSitesForSessions(activeSessions.map { it.id })
            val allKnownWebsiteApps = WebsiteBlocker.appPackageDomainsFor(
                allSessionSites + activeWebsiteLimits.map { it.domain }
            ).keys.filter(::isPackageInstalled)
            val allKnownApps = (
                allSessionApps +
                    activeAppLimits.map { it.packageName } +
                    allKnownWebsiteApps +
                    focusModeApps
                ).distinct()

            deviceOwnerManager.syncSuspendedApps(
                allAppsInSessions = allKnownApps,
                appsToBlockNow = appsToBlock,
                allowedSystemApps = appsToBlock.toSet()
            )

            if (sitesToBlock.isEmpty() && !adultFilterEnabled) {
                deviceOwnerManager.clearWebsiteRestrictions()
            } else {
                deviceOwnerManager.enforceWebsiteRestrictions(sitesToBlock)
            }

            val selfProtectionRequired = BlockingSessionManager.shouldArmSelfProtection(
                hasEnforcingSessions = enforcingSessions.isNotEmpty(),
                hasBlockedApps = appsToBlock.isNotEmpty(),
                hasBlockedSites = sitesToBlock.isNotEmpty(),
                adultFilterEnabled = adultFilterEnabled,
                focusModeActive = focusModeSession != null
            )
            if (selfProtectionRequired) {
                check(SelfProtectionStateStore.setArmed(context, true)) {
                    "Não foi possível persistir a autoproteção da primeira tentativa"
                }
                val nativeProtectionConfirmed = deviceOwnerManager.enforceBlockingPolicies()
                check(
                    !deviceOwnerManager.isDeviceOwnerActive() || nativeProtectionConfirmed
                ) {
                    "O Android não confirmou a autoproteção nativa do FocusGuard"
                }
            } else {
                deviceOwnerManager.clearBlockingPolicies()
                check(SelfProtectionStateStore.setArmed(context, false)) {
                    "Não foi possível persistir o fim da autoproteção"
                }
            }
            deviceOwnerManager.applyNuclearShield()

            blockingRuntime.publishSnapshot(
                BlockingSnapshot(
                    blockedApps = accessibilityAppsToBlock.toSet(),
                    blockedSites = sitesToBlock.toSet(),
                    blockingActive = selfProtectionRequired,
                    strictPomodoro = strictPomodoro
                )
            )
        }
    }

    fun isCurrentlyInBlockingWindow(session: BlockSession?): Boolean {
        if (session == null || !session.isActive) return false
        val now = Calendar.getInstance()
        val endTime = session.endTime
        if (endTime != null && now.timeInMillis >= endTime) return false
        if (session.isFixed24h) return true

        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes = session.recurringStartHour * 60 + session.recurringStartMinute
        val endMinutes = session.recurringEndHour * 60 + session.recurringEndMinute
        val overnight = startMinutes > endMinutes
        val afterMidnight = overnight && currentMinutes < endMinutes
        val logicalDay = now.clone() as Calendar
        if (afterMidnight) logicalDay.add(Calendar.DAY_OF_YEAR, -1)

        if (session.recurringDaysOfWeek.isNotEmpty() &&
            logicalDay.get(Calendar.DAY_OF_WEEK) !in session.recurringDaysOfWeek
        ) {
            return false
        }

        return if (startMinutes <= endMinutes) {
            currentMinutes in startMinutes until endMinutes
        } else {
            currentMinutes >= startMinutes || currentMinutes < endMinutes
        }
    }

    fun getExceededAppLimits(limits: List<AppUsageLimit>, now: Long): List<String> {
        if (limits.isEmpty()) return emptyList()
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as?
            UsageStatsManager ?: return emptyList()
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val usage = usageStatsManager.queryAndAggregateUsageStats(startOfDay, now)

        return limits.filter { limit ->
            val usedMinutes = UsageLimitForegroundPolicy.usedMinutes(
                usage[limit.packageName]?.totalTimeInForeground ?: 0L
            )
            usedMinutes >= limit.dailyLimitMinutes &&
                limit.preventOpeningAfterLimit &&
                WebsiteUsageLimitPolicy.isBlockingModeActive(
                    limit.lockMode,
                    limit.lockUntilTimestamp,
                    now
                )
        }.map { it.packageName }
    }

    private fun getInstalledUserAppsExceptPhone(): List<String> {
        val phoneWhitelist = setOf(
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.phone",
            "com.android.server.telecom",
            "com.samsung.android.dialer",
            "com.samsung.android.incallui"
        )
        return try {
            context.packageManager
                .getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { app ->
                    app.packageName != context.packageName &&
                        app.packageName !in phoneWhitelist &&
                        app.flags and ApplicationInfo.FLAG_SYSTEM == 0
                }
                .map { it.packageName }
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(
                "BlockingPolicyReconciler",
                "Falha ao listar aplicativos instalados",
                error
            )
            emptyList()
        }
    }

    @Suppress("DEPRECATION")
    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0L)
                )
            } else {
                context.packageManager.getApplicationInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(
                "BlockingPolicyReconciler",
                "Falha ao verificar pacote associado a site",
                error
            )
            false
        }
    }

    private suspend fun getAppsForSessions(ids: List<Int>): List<String> =
        if (ids.isEmpty()) emptyList()
        else database.sessionAppCrossRefDao().getAppsForSessions(ids)

    private suspend fun getSitesForSessions(ids: List<Int>): List<String> =
        if (ids.isEmpty()) emptyList()
        else database.sessionWebsiteCrossRefDao().getWebsitesForSessions(ids)

    private fun setDoNotDisturbMode(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            if (manager.isNotificationPolicyAccessGranted) {
                if (enabled) {
                    if (!statePreferences.contains(PREVIOUS_DND_FILTER_KEY)) {
                        statePreferences.edit()
                            .putInt(PREVIOUS_DND_FILTER_KEY, manager.currentInterruptionFilter)
                            .apply()
                    }
                    manager.setInterruptionFilter(
                        NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    )
                } else if (statePreferences.contains(PREVIOUS_DND_FILTER_KEY)) {
                    val savedFilter = statePreferences.getInt(
                        PREVIOUS_DND_FILTER_KEY,
                        NotificationManager.INTERRUPTION_FILTER_ALL
                    )
                    val previousFilter = savedFilter.takeIf {
                        it == NotificationManager.INTERRUPTION_FILTER_ALL ||
                            it == NotificationManager.INTERRUPTION_FILTER_PRIORITY ||
                            it == NotificationManager.INTERRUPTION_FILTER_NONE ||
                            it == NotificationManager.INTERRUPTION_FILTER_ALARMS
                    } ?: NotificationManager.INTERRUPTION_FILTER_ALL
                    manager.setInterruptionFilter(previousFilter)
                    statePreferences.edit().remove(PREVIOUS_DND_FILTER_KEY).apply()
                }
            }
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(
                "BlockingPolicyReconciler",
                "Erro ao alterar Não Perturbe",
                error
            )
        }
    }

    private companion object {
        const val STATE_PREFERENCES = "blocking_session_manager_state"
        const val PREVIOUS_DND_FILTER_KEY = "previous_dnd_filter"
        const val POLICY_RECONCILIATION_INTERVAL_MILLIS = 15L * 60L * 1_000L
    }
}
