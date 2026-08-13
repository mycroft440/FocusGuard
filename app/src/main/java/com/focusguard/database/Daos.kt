package com.focusguard.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedAppDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockedApp(app: BlockedApp)

    @Update
    suspend fun updateBlockedApp(app: BlockedApp)

    @Delete
    suspend fun deleteBlockedApp(app: BlockedApp)

    @Query("SELECT * FROM blocked_apps WHERE isBlocked = 1 ORDER BY createdAt DESC")
    fun getAllBlockedApps(): Flow<List<BlockedApp>>

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
    fun getAllBlockedWebsites(): Flow<List<BlockedWebsite>>

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
    fun getAllActiveSessions(): Flow<List<BlockSession>>

    @Query("SELECT * FROM block_sessions WHERE isActive = 1")
    suspend fun getAllActiveSessionsStatic(): List<BlockSession>

    @Query("SELECT * FROM block_sessions WHERE id = :sessionId AND isActive = 1 LIMIT 1")
    suspend fun getActiveSessionById(sessionId: Int): BlockSession?

    @Query("SELECT * FROM block_sessions WHERE isActive = 1 ORDER BY startTime DESC LIMIT 1")
    fun getActiveSession(): Flow<BlockSession?>

    @Query("UPDATE block_sessions SET isActive = 0 WHERE id = :sessionId AND isActive = 1")
    suspend fun deactivateSession(sessionId: Int): Int

    @Query("UPDATE block_sessions SET isActive = 0 WHERE isActive = 1 AND sessionType = :sessionType")
    suspend fun deactivateActiveSessionsByType(sessionType: String): Int

    @Query("UPDATE block_sessions SET isActive = 0 WHERE isActive = 1")
    suspend fun deactivateAllActiveSessions(): Int

    @Query("UPDATE block_sessions SET isActive = 0 WHERE isActive = 1 AND endTime IS NOT NULL AND endTime <= :now")
    suspend fun deactivateExpiredSessions(now: Long): Int

    @Query("DELETE FROM block_sessions WHERE isActive = 0 AND endTime < :threshold")
    suspend fun deleteOldInactiveSessions(threshold: Long)

    @Query("DELETE FROM session_app_cross_ref WHERE sessionId NOT IN (SELECT id FROM block_sessions)")
    suspend fun cleanOrphanApps()

    @Query("DELETE FROM session_website_cross_ref WHERE sessionId NOT IN (SELECT id FROM block_sessions)")
    suspend fun cleanOrphanWebsites()

    @Transaction
    suspend fun insertNewSession(session: BlockSession): Long {
        deleteOldInactiveSessions(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
        cleanOrphanApps()
        cleanOrphanWebsites()
        return insertBlockSession(session)
    }

    @Query("SELECT * FROM block_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<BlockSession>>
}

@Dao
interface SessionAppCrossRefDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(crossRef: SessionAppCrossRef)

    @Query("SELECT packageName FROM session_app_cross_ref WHERE sessionId IN (:sessionIds)")
    suspend fun getAppsForSessions(sessionIds: List<Int>): List<String>

    @Query("SELECT sessionId FROM session_app_cross_ref WHERE packageName = :packageName")
    suspend fun getSessionIdsForApp(packageName: String): List<Int>

    @Query("DELETE FROM session_app_cross_ref WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Int)

    @Query("DELETE FROM session_app_cross_ref WHERE sessionId = :sessionId AND packageName = :packageName")
    suspend fun deleteSpecificApp(sessionId: Int, packageName: String)
}

@Dao
interface SessionWebsiteCrossRefDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(crossRef: SessionWebsiteCrossRef)

    @Query("SELECT domain FROM session_website_cross_ref WHERE sessionId IN (:sessionIds)")
    suspend fun getWebsitesForSessions(sessionIds: List<Int>): List<String>

    @Query("SELECT sessionId FROM session_website_cross_ref WHERE domain = :domain")
    suspend fun getSessionIdsForWebsite(domain: String): List<Int>

    @Query("DELETE FROM session_website_cross_ref WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Int)

    @Query("DELETE FROM session_website_cross_ref WHERE sessionId = :sessionId AND domain = :domain")
    suspend fun deleteSpecificWebsite(sessionId: Int, domain: String)
}

@Dao
interface AppUsageLimitDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(limit: AppUsageLimit)

    @Update
    suspend fun update(limit: AppUsageLimit)

    @Delete
    suspend fun delete(limit: AppUsageLimit)

    @Query("SELECT * FROM app_usage_limits")
    fun getAll(): Flow<List<AppUsageLimit>>

    @Query("SELECT * FROM app_usage_limits WHERE packageName = :packageName LIMIT 1")
    suspend fun getLimitForPackage(packageName: String): AppUsageLimit?

    @Query("SELECT * FROM app_usage_limits WHERE isEnabled = 1")
    fun getAllActiveLimits(): Flow<List<AppUsageLimit>>

    @Query("SELECT * FROM app_usage_limits WHERE isEnabled = 1")
    suspend fun getAllActiveLimitsStatic(): List<AppUsageLimit>

    @Query("SELECT * FROM app_usage_limits")
    suspend fun getAllStatic(): List<AppUsageLimit>

    @Query("DELETE FROM app_usage_limits WHERE packageName = :packageName")
    suspend fun deleteLimitByPackage(packageName: String)

    @Query("DELETE FROM app_usage_limits")
    suspend fun deleteAll()
}

@Dao
interface WebsiteUsageLimitDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(limit: WebsiteUsageLimit)

    @Query("SELECT * FROM website_usage_limits")
    fun getAll(): Flow<List<WebsiteUsageLimit>>

    @Query("SELECT * FROM website_usage_limits")
    suspend fun getAllStatic(): List<WebsiteUsageLimit>

    @Delete
    suspend fun delete(limit: WebsiteUsageLimit)

    @Query("DELETE FROM website_usage_limits")
    suspend fun deleteAll()
}

@Dao
interface UsageLimitsLockDao {
    @Query("SELECT * FROM usage_limits_locks WHERE id = 0 LIMIT 1")
    suspend fun getLock(): UsageLimitsLock?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(lock: UsageLimitsLock)

    @Query("DELETE FROM usage_limits_locks")
    suspend fun deleteAll()
}

@Dao
interface DailyUsageStatDao {
    @Query("SELECT * FROM daily_usage_stats WHERE date = :date")
    fun getStatsForDate(date: String): Flow<List<DailyUsageStat>>

    @Query("SELECT * FROM daily_usage_stats WHERE date = :date")
    suspend fun getStatsForDateStatic(date: String): List<DailyUsageStat>

    @Query("SELECT COALESCE(SUM(timeSpentMs), 0) FROM daily_usage_stats WHERE date = :date AND identifier = :identifier")
    suspend fun getUsageMillis(identifier: String, date: String): Long

    @Query("UPDATE daily_usage_stats SET timeSpentMs = timeSpentMs + :deltaMs WHERE date = :date AND identifier = :identifier")
    suspend fun incrementExisting(identifier: String, date: String, deltaMs: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stat: DailyUsageStat)

    @Transaction
    suspend fun addUsage(identifier: String, date: String, deltaMs: Long) {
        if (identifier.isBlank() || deltaMs <= 0L) return
        if (incrementExisting(identifier, date, deltaMs) == 0) {
            insert(DailyUsageStat(identifier = identifier, date = date, timeSpentMs = deltaMs))
        }
    }

    @Query("DELETE FROM daily_usage_stats WHERE date < :oldestDate")
    suspend fun deleteOlderThan(oldestDate: String)
}

@Dao
interface AppPasswordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(password: AppPassword)

    @Update
    suspend fun update(password: AppPassword)

    @Delete
    suspend fun delete(password: AppPassword)

    @Query("SELECT * FROM app_passwords ORDER BY id ASC")
    fun getAll(): Flow<List<AppPassword>>

    @Query("SELECT * FROM app_passwords ORDER BY id ASC")
    suspend fun getAllStatic(): List<AppPassword>

    @Query("DELETE FROM app_passwords")
    suspend fun deleteAll()
}
