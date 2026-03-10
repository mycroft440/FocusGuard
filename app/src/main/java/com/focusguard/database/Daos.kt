package com.focusguard.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface BlockedAppDao {
    @Insert
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
}

@Dao
interface BlockedWebsiteDao {
    @Insert
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
}

@Dao
interface BlockSessionDao {
    @Insert
    suspend fun insertBlockSession(session: BlockSession)

    @Update
    suspend fun updateBlockSession(session: BlockSession)

    @Query("SELECT * FROM block_sessions WHERE isActive = 1 ORDER BY startTime DESC LIMIT 1")
    suspend fun getActiveSession(): BlockSession?

    @Query("SELECT * FROM block_sessions ORDER BY startTime DESC")
    suspend fun getAllSessions(): List<BlockSession>
}
