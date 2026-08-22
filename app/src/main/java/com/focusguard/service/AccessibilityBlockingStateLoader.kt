package com.focusguard.service

import android.app.usage.UsageStatsManager
import android.content.Context
import com.focusguard.data.PredefinedWebsites
import com.focusguard.database.AppDatabase
import com.focusguard.database.AppUsageLimit
import com.focusguard.database.WebsiteUsageLimit
import com.focusguard.domain.model.BlockSessionType
import com.focusguard.focusmode.FocusModePolicy
import com.focusguard.state.FocusModeStore
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AuthManager
import com.focusguard.security.SelfProtectionStateStore
import com.focusguard.security.UsageAccessPausePolicy
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.PermissionUtils
import com.focusguard.utils.UsageLimitForegroundPolicy
import com.focusguard.utils.WebsiteBlocker
import com.focusguard.utils.WebsiteUsageLimitPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class AccessibilityBlockingState(
    val strictPomodoroActive: Boolean,
    val focusModeSessionActive: Boolean,
    val focusModeFallbackActive: Boolean,
    val focusModeBlockedApps: Set<String>,
    val focusModeAllowedApps: Set<String>,
    val blockedApps: Set<String>,
    val blockedWebsiteDomains: Set<String>,
    val blockedWebsiteAppDomains: Map<String, String>,
    val limitedWebsiteDomains: Set<String>,
    val limitedWebsiteAppDomains: Map<String, String>,
    val hasActiveAppLimits: Boolean,
    val blockingSessionActive: Boolean,
    val enforcementFingerprint: String,
    val shouldReconcilePolicies: Boolean
)

/** Reads Room and Android usage state into one immutable Accessibility snapshot. */
@Singleton
class AccessibilityBlockingStateLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authManager: AuthManager,
    private val database: AppDatabase,
    private val sessionManager: BlockingSessionManager,
    private val deviceOwnerManager: com.focusguard.admin.DeviceOwnerManager
) {
    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    private val dateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }

    suspend fun load(previousFingerprint: String?): AccessibilityBlockingState {
        val adultFilterEnabled = authManager.isAdultFilterEnabled()
        val adultRules = if (adultFilterEnabled) {
            setOf(PredefinedWebsites.PORNOGRAPHY_RULE)
        } else {
            emptySet()
        }
        val focusModeSession = FocusModeStore.readSession(context)?.takeIf { it.isActive() }
        val nativeFocusLockdownActive = focusModeSession != null &&
            FocusModePolicy.usesNativeFocusLockdown(
                deviceOwnerActive = deviceOwnerManager.isDeviceOwnerActive(),
                systemLockdownSupported =
                    deviceOwnerManager.isFocusModeSystemLockdownSupported()
            )
        val focusFallbackActive = focusModeSession != null && !nativeFocusLockdownActive
        val focusFallbackApps = if (focusFallbackActive) {
            focusModeSession?.blockedPackages.orEmpty()
        } else {
            emptySet()
        }
        val focusAllowedApps = focusModeSession?.allowedPackages.orEmpty()

        val activeSessions = database.blockSessionDao().getAllActiveSessionsStatic()
        val enforcingSessions = activeSessions.filter {
            BlockingSessionManager.participatesInBlocking(it) &&
                sessionManager.isCurrentlyInBlockingWindow(it)
        }
        val enforcingIds = enforcingSessions.map { it.id }
        val sessionApps = getAppsForSessions(enforcingIds).toSet()
        val sessionSites = WebsiteBlocker.normalizeRules(getSitesForSessions(enforcingIds))

        val activeAppLimits = database.appUsageLimitDao().getAllActiveLimitsStatic()
        val limitApps = calculateExceededAppLimits(activeAppLimits)
        val websiteLimits = database.websiteUsageLimitDao().getAllStatic()
            .filter { it.isEnabled }
        val configuredWebsiteDomains = WebsiteBlocker.normalizeRules(
            websiteLimits.map { it.domain }
        )
        val exceededWebsiteDomains = calculateExceededWebsiteLimits(websiteLimits)
        val blockedWebsiteDomains = WebsiteBlocker.normalizeRules(
            sessionSites + exceededWebsiteDomains + adultRules
        )
        val blockedWebsiteApps = WebsiteBlocker.appPackageDomainsFor(
            sessionSites + exceededWebsiteDomains
        ).filterKeys { it !in focusAllowedApps }
        val limitedWebsiteApps = WebsiteBlocker.appPackageDomainsFor(
            configuredWebsiteDomains
        ).filterKeys { it !in focusAllowedApps }
        val enforcedApps = FocusModePolicy.packagesToEnforce(
            configuredBlockedPackages = sessionApps + limitApps,
            focusModeBlockedPackages = focusModeSession?.blockedPackages.orEmpty(),
            focusModeAllowedPackages = focusAllowedApps
        )
        val accessibilityApps = FocusModePolicy.packagesForAccessibility(
            enforcedPackages = enforcedApps,
            focusModeBlockedPackages = focusModeSession?.blockedPackages.orEmpty(),
            nativeFocusLockdownActive = nativeFocusLockdownActive
        )
        val fingerprint = listOf(
            enforcingIds.sorted().joinToString(","),
            sessionApps.sorted().joinToString(","),
            sessionSites.sorted().joinToString(","),
            limitApps.sorted().joinToString(","),
            exceededWebsiteDomains.sorted().joinToString(","),
            adultFilterEnabled.toString(),
            focusModeSession?.startedAtMillis?.toString().orEmpty()
        ).joinToString("|")

        val liveBlocking = enforcingSessions.isNotEmpty() ||
            limitApps.isNotEmpty() ||
            exceededWebsiteDomains.isNotEmpty() ||
            adultFilterEnabled
        val blockingSessionActive = BlockingAccessibilityService.isSelfProtectionEngaged(
            cachedActive = liveBlocking,
            persistedActive = SelfProtectionStateStore.isArmed(context),
            focusModeActive = FocusModeStore.isActive(context),
            armoredDeviceOwnerActive = deviceOwnerManager.isDeviceOwnerActive() &&
                deviceOwnerManager.isArmoredProtectionArmed()
        )

        return AccessibilityBlockingState(
            strictPomodoroActive = enforcingSessions.any {
                it.sessionType == BlockSessionType.POMODORO && it.isBlockingEnabled
            },
            focusModeSessionActive = focusModeSession != null,
            focusModeFallbackActive = focusFallbackActive,
            focusModeBlockedApps = focusFallbackApps,
            focusModeAllowedApps = focusAllowedApps,
            blockedApps = accessibilityApps,
            blockedWebsiteDomains = blockedWebsiteDomains,
            blockedWebsiteAppDomains = blockedWebsiteApps,
            limitedWebsiteDomains = configuredWebsiteDomains,
            limitedWebsiteAppDomains = limitedWebsiteApps,
            hasActiveAppLimits = activeAppLimits.isNotEmpty(),
            blockingSessionActive = blockingSessionActive,
            enforcementFingerprint = fingerprint,
            shouldReconcilePolicies = previousFingerprint?.let { it != fingerprint } == true
        )
    }

    suspend fun calculateExceededAppLimits(): Set<String> =
        calculateExceededAppLimits(database.appUsageLimitDao().getAllActiveLimitsStatic())

    private fun calculateExceededAppLimits(limits: List<AppUsageLimit>): Set<String> {
        val manager = usageStatsManager ?: return emptySet()
        if (limits.isEmpty()) return emptySet()
        if (UsageAccessPausePolicy.measurementIsUnavailable(
                usageAccessGranted = PermissionUtils.isUsageAccessEnabled(context),
                enabledAppLimitCount = limits.size
            )
        ) {
            FocusGuardLogger.log(
                "A11y",
                "Acesso de uso revogado: ${limits.size} limite(s) de app sem medicao"
            )
            return emptySet()
        }

        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val now = System.currentTimeMillis()
        val usage = manager.queryAndAggregateUsageStats(startOfDay, now)
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
        }.mapTo(mutableSetOf()) { it.packageName }
    }

    private suspend fun calculateExceededWebsiteLimits(
        limits: List<WebsiteUsageLimit>
    ): Set<String> {
        if (limits.isEmpty()) return emptySet()
        val today = dateFormat.get()!!.format(Date())
        val usage = WebsiteUsageLimitPolicy.aggregateUsageByRule(
            usageByIdentifier = database.dailyUsageStatDao()
                .getStatsForDateStatic(today)
                .map { it.identifier to it.timeSpentMs },
            configuredRules = limits.map { it.domain }
        )
        val now = System.currentTimeMillis()
        return limits.filter { limit ->
            val domain = WebsiteBlocker.normalizeRule(limit.domain)
            WebsiteUsageLimitPolicy.shouldBlock(
                usedMillis = usage[domain] ?: 0L,
                dailyLimitMinutes = limit.dailyLimitMinutes,
                lockMode = limit.lockMode,
                lockUntilTimestamp = limit.lockUntilTimestamp,
                nowMillis = now
            )
        }.mapTo(mutableSetOf()) { WebsiteBlocker.normalizeRule(it.domain) }
    }

    private suspend fun getAppsForSessions(ids: List<Int>): List<String> =
        if (ids.isEmpty()) emptyList()
        else database.sessionAppCrossRefDao().getAppsForSessions(ids)

    private suspend fun getSitesForSessions(ids: List<Int>): List<String> =
        if (ids.isEmpty()) emptyList()
        else database.sessionWebsiteCrossRefDao().getWebsitesForSessions(ids)
}
