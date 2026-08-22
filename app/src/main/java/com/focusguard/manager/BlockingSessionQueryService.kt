package com.focusguard.manager

import com.focusguard.database.AppDatabase
import com.focusguard.domain.model.BlockSessionType
import com.focusguard.security.MasterCredentialPolicy
import com.focusguard.utils.WebsiteBlocker
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only blocking projections kept separate from session mutation and enforcement. */
@Singleton
class BlockingSessionQueryService @Inject constructor(
    private val database: AppDatabase
) {
    suspend fun getBlockOverview(): BlockingSessionManager.BlockOverview =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val activeSessions = database.blockSessionDao().getAllActiveSessionsStatic()
                .filter { session -> session.endTime?.let { it > now } ?: true }

            val passwordIds = activeSessions
                .filter { it.sessionType == BlockSessionType.PASSWORD }
                .map { it.id }
            val fastSessions = activeSessions.filter {
                MasterCredentialPolicy.isIrreversibleSessionType(it.sessionType)
            }

            val passwordEntries = buildEntries(
                appPackages = getAppsForSessions(passwordIds),
                websiteRules = getSitesForSessions(passwordIds)
            )
            val fastEntries = fastSessions.flatMap { session ->
                buildEntries(
                    appPackages = getAppsForSessions(listOf(session.id)),
                    websiteRules = getSitesForSessions(listOf(session.id)),
                    unlockAtMillis = session.endTime
                )
            }.distinctBy { it.identifier }

            val appLimits = database.appUsageLimitDao().getAllActiveLimitsStatic()
            val websiteLimits = database.websiteUsageLimitDao().getAllStatic()
                .filter { it.isEnabled }
            val limitEntries = appLimits.map { limit ->
                BlockingSessionManager.BlockOverview.Entry(
                    identifier = limit.packageName,
                    isWebsite = false,
                    dailyLimitMinutes = limit.dailyLimitMinutes
                )
            } + websiteLimits.map { limit ->
                BlockingSessionManager.BlockOverview.Entry(
                    identifier = WebsiteBlocker.normalizeRule(limit.domain),
                    isWebsite = true,
                    dailyLimitMinutes = limit.dailyLimitMinutes
                )
            }

            BlockingSessionManager.BlockOverview(
                passwordEntries = passwordEntries.sortedBy { it.identifier },
                dailyLimitEntries = limitEntries.sortedBy { it.identifier },
                dopamineFastEntries = fastEntries.sortedBy { it.identifier }
            )
        }

    suspend fun getConfiguredBlockedTargets(): BlockingSessionManager.ConfiguredBlockedTargets =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val configuredSessions = database.blockSessionDao()
                .getAllActiveSessionsStatic()
                .filter { session -> session.endTime?.let { it > now } ?: true }
            val passwordSessionIds = configuredSessions
                .filter { it.sessionType == BlockSessionType.PASSWORD }
                .map { it.id }
            val exclusiveSessionIds = configuredSessions
                .filter { it.sessionType != BlockSessionType.PASSWORD }
                .map { it.id }

            BlockingSessionManager.combineConfiguredBlockedTargets(
                passwordSessionAppPackages = getAppsForSessions(passwordSessionIds),
                passwordSessionWebsiteRules = getSitesForSessions(passwordSessionIds),
                exclusiveSessionAppPackages = getAppsForSessions(exclusiveSessionIds),
                exclusiveSessionWebsiteRules = getSitesForSessions(exclusiveSessionIds),
                limitedAppPackages = database.appUsageLimitDao()
                    .getAllActiveLimitsStatic()
                    .map { it.packageName },
                limitedWebsiteRules = database.websiteUsageLimitDao()
                    .getAllStatic()
                    .filter { it.isEnabled }
                    .map { it.domain }
            )
        }

    private fun buildEntries(
        appPackages: List<String>,
        websiteRules: List<String>,
        unlockAtMillis: Long? = null
    ): List<BlockingSessionManager.BlockOverview.Entry> {
        val apps = appPackages.distinct().map { packageName ->
            BlockingSessionManager.BlockOverview.Entry(
                identifier = packageName,
                isWebsite = false,
                unlockAtMillis = unlockAtMillis
            )
        }
        val sites = WebsiteBlocker.normalizeRules(websiteRules).map { rule ->
            BlockingSessionManager.BlockOverview.Entry(
                identifier = rule,
                isWebsite = true,
                unlockAtMillis = unlockAtMillis
            )
        }
        return apps + sites
    }

    private suspend fun getAppsForSessions(ids: List<Int>): List<String> =
        if (ids.isEmpty()) emptyList()
        else database.sessionAppCrossRefDao().getAppsForSessions(ids)

    private suspend fun getSitesForSessions(ids: List<Int>): List<String> =
        if (ids.isEmpty()) emptyList()
        else database.sessionWebsiteCrossRefDao().getWebsitesForSessions(ids)
}
