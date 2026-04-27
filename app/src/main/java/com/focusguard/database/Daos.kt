package com.focusguard.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface BlockedAppDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockedApp(app: BlockedApp)

    @Update
    suspend fun updateBlockedApp(app: BlockedApp)

    @Delete
    suspend fun deleteBlockedApp(app: BlockedApp)

    @Query("SELECT * FROM blocked_apps WHERE isBlocked = 1 ORDER BY createdAt DESC")
    suspend fun getAllBlockedApps(): List<BlockedApp>

    @Query("SELECT * FROM blocked_apps WHERE packageName = :packageName LIMIT 1")
    suspend fun getBlockedAppByPackage(packageName: String): BlockedApp?

    @Query("DELETE FROM blocked_apps WHERE packageName = :packageName")
    suspend fun deleteBlockedAppByPackage(packageName: String)

    @Query("DELETE FROM blocked_apps")
    suspend fun deleteAllBlockedApps()
}

@Dao
interface BlockedWebsiteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockedWebsite(website: BlockedWebsite)

    @Update
    suspend fun updateBlockedWebsite(website: BlockedWebsite)

    @Delete
    suspend fun deleteBlockedWebsite(website: BlockedWebsite)

    @Query("SELECT * FROM blocked_websites WHERE isBlocked = 1 ORDER BY createdAt DESC")
    suspend fun getAllBlockedWebsites(): List<BlockedWebsite>

    @Query("SELECT * FROM blocked_websites WHERE domain = :domain LIMIT 1")
    suspend fun getBlockedWebsiteByDomain(domain: String): BlockedWebsite?

    @Query("DELETE FROM blocked_websites WHERE domain = :domain")
    suspend fun deleteBlockedWebsiteByDomain(domain: String)

    @Query("DELETE FROM blocked_websites")
    suspend fun deleteAllBlockedWebsites()
}

@Dao
interface BlockSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockSession(session: BlockSession): Long

    @Update
    suspend fun updateBlockSession(session: BlockSession)

    @Query("SELECT * FROM block_sessions WHERE isActive = 1")
    suspend fun getAllActiveSessions(): List<BlockSession>

    @Query("SELECT * FROM block_sessions WHERE isActive = 1 ORDER BY startTime DESC LIMIT 1")
    suspend fun getActiveSession(): BlockSession?

    @Query("DELETE FROM block_sessions WHERE isActive = 0 AND endTime < :threshold")
    suspend fun deleteOldInactiveSessions(threshold: Long)

    @Query("DELETE FROM session_app_cross_ref WHERE sessionId NOT IN (SELECT id FROM block_sessions)")
    suspend fun cleanOrphanApps()

    @Query("DELETE FROM session_website_cross_ref WHERE sessionId NOT IN (SELECT id FROM block_sessions)")
    suspend fun cleanOrphanWebsites()

    @Transaction
    suspend fun insertNewSession(session: BlockSession): Long {
        // Limpa lixo do DB que já expirou há mais de 30 dias (Trash Cleanup)
        deleteOldInactiveSessions(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
        cleanOrphanApps()
        cleanOrphanWebsites()
        return insertBlockSession(session)
    }

    @Query("SELECT * FROM block_sessions ORDER BY startTime DESC")
    suspend fun getAllSessions(): List<BlockSession>
}

@Dao
interface SessionAppCrossRefDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(crossRef: SessionAppCrossRef)

    @Query("SELECT packageName FROM session_app_cross_ref WHERE sessionId IN (:sessionIds)")
    suspend fun getAppsForSessions(sessionIds: List<Int>): List<String>

    @Query("DELETE FROM session_app_cross_ref WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Int)
}

@Dao
interface SessionWebsiteCrossRefDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(crossRef: SessionWebsiteCrossRef)

    @Query("SELECT domain FROM session_website_cross_ref WHERE sessionId IN (:sessionIds)")
    suspend fun getWebsitesForSessions(sessionIds: List<Int>): List<String>

    @Query("DELETE FROM session_website_cross_ref WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Int)
}

@Dao
interface AppUsageLimitDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(limit: AppUsageLimit)

    @Delete
    suspend fun delete(limit: AppUsageLimit)

    @Query("SELECT * FROM app_usage_limits")
    suspend fun getAll(): List<AppUsageLimit>

    @Query("SELECT * FROM app_usage_limits WHERE isEnabled = 1")
    suspend fun getAllEnabled(): List<AppUsageLimit>

    @Query("SELECT * FROM app_usage_limits WHERE packageName = :packageName LIMIT 1")
    suspend fun getByPackageName(packageName: String): AppUsageLimit?
}

@Dao
interface WebsiteUsageLimitDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(limit: WebsiteUsageLimit)

    @Delete
    suspend fun delete(limit: WebsiteUsageLimit)

    @Query("SELECT * FROM website_usage_limits")
    suspend fun getAll(): List<WebsiteUsageLimit>

    @Query("SELECT * FROM website_usage_limits WHERE isEnabled = 1")
    suspend fun getAllEnabled(): List<WebsiteUsageLimit>

    @Query("SELECT * FROM website_usage_limits WHERE domain = :domain LIMIT 1")
    suspend fun getByDomain(domain: String): WebsiteUsageLimit?
}

@Dao
interface DailyUsageStatDao {
    @Query("SELECT * FROM daily_usage_stats WHERE identifier = :identifier AND date = :date")
    suspend fun getStat(identifier: String, date: String): DailyUsageStat?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stat: DailyUsageStat)

    @Query("SELECT * FROM daily_usage_stats WHERE date = :date")
    suspend fun getStatsForDate(date: String): List<DailyUsageStat>

    @Query("DELETE FROM daily_usage_stats WHERE date < :date")
    suspend fun deleteOldStats(date: String)
}

@Dao
interface UsageLimitsLockDao {
    @Query("SELECT * FROM usage_limits_lock WHERE id = 1")
    suspend fun getLock(): UsageLimitsLock?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(lock: UsageLimitsLock)

    @Query("DELETE FROM usage_limits_lock WHERE id = 1")
    suspend fun delete()
}

