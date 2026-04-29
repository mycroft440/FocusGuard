package com.focusguard.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BlockedApp::class, BlockedWebsite::class, BlockSession::class, 
        SessionAppCrossRef::class, SessionWebsiteCrossRef::class, 
        AppUsageLimit::class, WebsiteUsageLimit::class, UsageLimitsLock::class, DailyUsageStat::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun blockedAppDao(): BlockedAppDao
    abstract fun blockedWebsiteDao(): BlockedWebsiteDao
    abstract fun blockSessionDao(): BlockSessionDao
    abstract fun sessionAppCrossRefDao(): SessionAppCrossRefDao
    abstract fun sessionWebsiteCrossRefDao(): SessionWebsiteCrossRefDao
    abstract fun appUsageLimitDao(): AppUsageLimitDao
    abstract fun websiteUsageLimitDao(): WebsiteUsageLimitDao
    abstract fun usageLimitsLockDao(): UsageLimitsLockDao
    abstract fun dailyUsageStatDao(): DailyUsageStatDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE block_sessions ADD COLUMN isRecurring INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE block_sessions ADD COLUMN recurringStartHour INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE block_sessions ADD COLUMN recurringStartMinute INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE block_sessions ADD COLUMN recurringEndHour INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE block_sessions ADD COLUMN recurringEndMinute INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE block_sessions ADD COLUMN recurringDaysOfWeek TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE block_sessions ADD COLUMN recurringDurationMonths INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `session_app_cross_ref` (`sessionId` INTEGER NOT NULL, `packageName` TEXT NOT NULL, PRIMARY KEY(`sessionId`, `packageName`))")
                database.execSQL("CREATE TABLE IF NOT EXISTS `session_website_cross_ref` (`sessionId` INTEGER NOT NULL, `domain` TEXT NOT NULL, PRIMARY KEY(`sessionId`, `domain`))")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE block_sessions ADD COLUMN sessionType TEXT NOT NULL DEFAULT 'PASSWORD'")
                database.execSQL("ALTER TABLE block_sessions ADD COLUMN isFixed24h INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `app_usage_limits` (`packageName` TEXT NOT NULL, `appName` TEXT NOT NULL, `limitMinutesPerDay` INTEGER NOT NULL, `isActive` INTEGER NOT NULL DEFAULT 1, `securityMode` INTEGER NOT NULL DEFAULT 0, `lockAfterLimit` INTEGER NOT NULL DEFAULT 1, `durationDays` INTEGER NOT NULL DEFAULT 1, `createdAt` INTEGER NOT NULL, `lastResetDate` INTEGER NOT NULL, PRIMARY KEY(`packageName`))")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Drop and Recreate app_usage_limits due to structure change
                database.execSQL("DROP TABLE IF EXISTS `app_usage_limits`")
                database.execSQL("CREATE TABLE IF NOT EXISTS `app_usage_limits` (`packageName` TEXT NOT NULL, `appName` TEXT NOT NULL, `dailyLimitMinutes` INTEGER NOT NULL, `isEnabled` INTEGER NOT NULL DEFAULT 1, `lockMode` TEXT NOT NULL DEFAULT 'NONE', `lockPasswordHash` TEXT, `lockUntilTimestamp` INTEGER, `createdAt` INTEGER NOT NULL, `lastResetDate` INTEGER NOT NULL, PRIMARY KEY(`packageName`))")
                
                // Create new tables
                database.execSQL("CREATE TABLE IF NOT EXISTS `website_usage_limits` (`domain` TEXT NOT NULL, `dailyLimitMinutes` INTEGER NOT NULL, `isEnabled` INTEGER NOT NULL DEFAULT 1, `lockMode` TEXT NOT NULL DEFAULT 'NONE', `lockPasswordHash` TEXT, `lockUntilTimestamp` INTEGER, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`domain`))")
                database.execSQL("CREATE TABLE IF NOT EXISTS `usage_limits_locks` (`id` INTEGER NOT NULL, `lockedUntilTimestamp` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                database.execSQL("CREATE TABLE IF NOT EXISTS `daily_usage_stats` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `identifier` TEXT NOT NULL, `date` TEXT NOT NULL, `timeSpentMs` INTEGER NOT NULL)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "focusguard_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
