package com.focusguard.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_apps")
data class BlockedApp(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val packageName: String,
    val appName: String,
    val isBlocked: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "blocked_websites")
data class BlockedWebsite(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val domain: String,
    val url: String,
    val isBlocked: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "block_sessions")
data class BlockSession(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,
    val durationDays: Int = 0,
    val isActive: Boolean = true,
    val blockedAppsCount: Int = 0,
    val blockedWebsitesCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    // Novas Colunas para Recorrência Diária
    val isRecurring: Boolean = false,
    val recurringStartHour: Int = 0,
    val recurringStartMinute: Int = 0,
    val recurringEndHour: Int = 0,
    val recurringEndMinute: Int = 0,
    val recurringDaysOfWeek: String = "", // e.g., "1,2,3,4,5" for Mon-Fri
    val recurringDurationMonths: Int = 1,
    val sessionType: String = "PASSWORD", // "PASSWORD" or "TIME"
    val isFixed24h: Boolean = true
)

@Entity(tableName = "session_app_cross_ref", primaryKeys = ["sessionId", "packageName"])
data class SessionAppCrossRef(
    val sessionId: Int,
    val packageName: String
)

@Entity(tableName = "session_website_cross_ref", primaryKeys = ["sessionId", "domain"])
data class SessionWebsiteCrossRef(
    val sessionId: Int,
    val domain: String
)

@Entity(tableName = "app_usage_limits")
data class AppUsageLimit(
    @PrimaryKey
    val packageName: String,
    val appName: String,
    val dailyLimitMinutes: Int,
    val isEnabled: Boolean = true,
    val lockMode: String = "NONE", // "NONE", "PASSWORD", "TIME"
    val lockPasswordHash: String? = null,
    val lockUntilTimestamp: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastResetDate: Long = 0,
    val preventOpeningAfterLimit: Boolean = true,
    val durationDays: Int = 1,
    val unlockWithPassword: Boolean = false
)

@Entity(tableName = "website_usage_limits")
data class WebsiteUsageLimit(
    @PrimaryKey
    val domain: String,
    val dailyLimitMinutes: Int,
    val isEnabled: Boolean = true,
    val lockMode: String = "NONE",
    val lockPasswordHash: String? = null,
    val lockUntilTimestamp: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "usage_limits_locks")
data class UsageLimitsLock(
    @PrimaryKey
    val id: Int = 0,
    val lockedUntilTimestamp: Long
)

@Entity(tableName = "daily_usage_stats")
data class DailyUsageStat(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val identifier: String, // packageName or domain
    val date: String, // yyyy-MM-dd
    val timeSpentMs: Long
)
