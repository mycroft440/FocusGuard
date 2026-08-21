package com.focusguard.repository

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import com.focusguard.database.AppUsageLimit
import com.focusguard.database.AppUsageLimitDao
import com.focusguard.database.DailyUsageStatDao
import com.focusguard.database.WebsiteUsageLimit
import com.focusguard.database.WebsiteUsageLimitDao
import com.focusguard.utils.WebsiteBlocker
import com.focusguard.utils.WebsiteUsageLimitPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

data class InstalledAppLimit(
    val packageName: String,
    val appName: String,
    val dailyLimitMinutes: Int?,
    val isEnabled: Boolean,
    val usageMillis: Long,
    val lockMode: String,
    val lockUntilTimestamp: Long?
)

data class ConfiguredWebsiteLimit(
    val domain: String,
    val dailyLimitMinutes: Int,
    val isEnabled: Boolean,
    val usageMillis: Long,
    val lockMode: String,
    val lockUntilTimestamp: Long?
)

data class AppLimitChange(
    val packageName: String,
    val appName: String,
    val dailyLimitMinutes: Int?,
    val isEnabled: Boolean,
    val lockMode: String,
    val lockUntilTimestamp: Long?
)

data class WebsiteLimitChange(
    val previousDomain: String?,
    val domain: String,
    val dailyLimitMinutes: Int,
    val isEnabled: Boolean,
    val lockMode: String,
    val lockUntilTimestamp: Long?
)

/**
 * Single persistence boundary for the active usage-limits feature.
 *
 * Composables never receive Room or a DAO. Room invalidation drives these flows,
 * while [refreshPlatformSnapshot] refreshes data Android does not expose as a
 * Flow (installed launchers and UsageStats).
 */
@Singleton
class UsageLimitsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appUsageLimitDao: AppUsageLimitDao,
    private val websiteUsageLimitDao: WebsiteUsageLimitDao,
    private val dailyUsageStatDao: DailyUsageStatDao
) {
    private val platformRevision = MutableStateFlow(0L)

    fun observeInstalledApps(): Flow<List<InstalledAppLimit>> = combine(
        appUsageLimitDao.getAll(),
        platformRevision
    ) { limits, _ -> limits }.mapLatest(::loadInstalledApps)

    fun observeWebsiteLimits(): Flow<List<ConfiguredWebsiteLimit>> =
        platformRevision.flatMapLatest {
            val today = currentDate()
            combine(
                websiteUsageLimitDao.getAll(),
                dailyUsageStatDao.getStatsForDate(today)
            ) { limits, dailyStats ->
                val usageByRule = WebsiteUsageLimitPolicy.aggregateUsageByRule(
                    usageByIdentifier = dailyStats.map { stat ->
                        stat.identifier to stat.timeSpentMs
                    },
                    configuredRules = limits.map(WebsiteUsageLimit::domain)
                )
                limits.map { limit ->
                    ConfiguredWebsiteLimit(
                        domain = WebsiteBlocker.normalizeRule(limit.domain),
                        dailyLimitMinutes = limit.dailyLimitMinutes,
                        isEnabled = limit.isEnabled,
                        usageMillis = usageByRule[
                            WebsiteBlocker.normalizeRule(limit.domain)
                        ] ?: 0L,
                        lockMode = limit.lockMode,
                        lockUntilTimestamp = limit.lockUntilTimestamp
                    )
                }.sortedBy(ConfiguredWebsiteLimit::domain)
            }
        }

    fun refreshPlatformSnapshot() {
        platformRevision.update { it + 1L }
    }

    suspend fun saveAppLimit(change: AppLimitChange) = withContext(Dispatchers.IO) {
        val minutes = change.dailyLimitMinutes
        if (minutes == null || minutes <= 0) {
            appUsageLimitDao.deleteLimitByPackage(change.packageName)
            return@withContext
        }
        appUsageLimitDao.insert(
            AppUsageLimit(
                packageName = change.packageName,
                appName = change.appName,
                dailyLimitMinutes = minutes,
                isEnabled = change.isEnabled,
                lockMode = change.lockMode,
                lockPasswordHash = null,
                lockUntilTimestamp = change.lockUntilTimestamp,
                preventOpeningAfterLimit = true,
                unlockWithPassword = change.lockMode.equals("PASSWORD", ignoreCase = true)
            )
        )
    }

    suspend fun saveWebsiteLimit(change: WebsiteLimitChange) = withContext(Dispatchers.IO) {
        val normalized = WebsiteBlocker.normalizeRule(change.domain)
        require(WebsiteBlocker.isValidRule(normalized)) { "Regra de site inválida" }

        change.previousDomain
            ?.takeIf { WebsiteBlocker.normalizeRule(it) != normalized }
            ?.let { websiteUsageLimitDao.deleteByDomain(it) }

        websiteUsageLimitDao.getAllStatic()
            .asSequence()
            .filter { existing ->
                existing.domain != normalized &&
                    WebsiteBlocker.normalizeRule(existing.domain) == normalized
            }
            .forEach { duplicate -> websiteUsageLimitDao.deleteByDomain(duplicate.domain) }

        websiteUsageLimitDao.insert(
            WebsiteUsageLimit(
                domain = normalized,
                dailyLimitMinutes = change.dailyLimitMinutes.coerceAtLeast(1),
                isEnabled = change.isEnabled,
                lockMode = change.lockMode,
                lockPasswordHash = null,
                lockUntilTimestamp = change.lockUntilTimestamp
            )
        )
    }

    suspend fun deleteWebsiteLimit(domain: String) = withContext(Dispatchers.IO) {
        val normalized = WebsiteBlocker.normalizeRule(domain)
        websiteUsageLimitDao.getAllStatic()
            .filter { WebsiteBlocker.normalizeRule(it.domain) == normalized }
            .forEach { websiteUsageLimitDao.deleteByDomain(it.domain) }
    }

    private suspend fun loadInstalledApps(
        limits: List<AppUsageLimit>
    ): List<InstalledAppLimit> = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val existingByPackage = limits.associateBy(AppUsageLimit::packageName)
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val usageStats = context.getSystemService(UsageStatsManager::class.java)
            ?.queryAndAggregateUsageStats(startOfDay, System.currentTimeMillis())
            .orEmpty()

        packageManager.queryIntentActivities(launcherIntent, 0)
            .asSequence()
            .distinctBy { it.activityInfo.packageName }
            .mapNotNull { info ->
                val packageName = info.activityInfo.packageName
                if (packageName == context.packageName) return@mapNotNull null
                val limit = existingByPackage[packageName]
                InstalledAppLimit(
                    packageName = packageName,
                    appName = info.loadLabel(packageManager).toString(),
                    dailyLimitMinutes = limit?.dailyLimitMinutes,
                    isEnabled = limit?.isEnabled ?: false,
                    usageMillis = usageStats[packageName]?.totalTimeInForeground ?: 0L,
                    lockMode = limit?.lockMode ?: "NONE",
                    lockUntilTimestamp = limit?.lockUntilTimestamp
                )
            }
            .sortedBy(InstalledAppLimit::appName)
            .toList()
    }

    private fun currentDate(): String = SimpleDateFormat(
        "yyyy-MM-dd",
        Locale.US
    ).format(Date())
}
