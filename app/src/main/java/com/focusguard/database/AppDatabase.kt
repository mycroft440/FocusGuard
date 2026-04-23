package com.focusguard.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BlockedApp::class, BlockedWebsite::class, BlockSession::class, SessionAppCrossRef::class, SessionWebsiteCrossRef::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun blockedAppDao(): BlockedAppDao
    abstract fun blockedWebsiteDao(): BlockedWebsiteDao
    abstract fun blockSessionDao(): BlockSessionDao
    abstract fun sessionAppCrossRefDao(): SessionAppCrossRefDao
    abstract fun sessionWebsiteCrossRefDao(): SessionWebsiteCrossRefDao

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

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "focusguard_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
