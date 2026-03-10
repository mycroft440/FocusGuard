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
    val createdAt: Long = System.currentTimeMillis()
)
