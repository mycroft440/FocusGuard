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
        AppUsageLimit::class, WebsiteUsageLimit::class, UsageLimitsLock::class,
        DailyUsageStat::class, AppPassword::class, PomodoroSession::class
    ],
    version = 10,
    // ANTIGO: exportSchema = false — impossível auditar schema em produção.
    // NOVO: true — Room exporta o schema JSON em app/schemas/ a cada build.
    // Esses JSONs podem (e devem) ser commitados no repo para permitir:
    //   1. Auditoria de mudanças de schema em code review
    //   2. Validação automática de migrações em CI
    //   3. Rollback seguro para versões anteriores
    // O diretório de output é configurado no KSP args do app/build.gradle.kts.
    exportSchema = true
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
    abstract fun appPasswordDao(): AppPasswordDao
    abstract fun pomodoroSessionDao(): PomodoroSessionDao

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
                database.execSQL("CREATE TABLE IF NOT EXISTS `app_usage_limits_new` (`packageName` TEXT NOT NULL, `appName` TEXT NOT NULL, `dailyLimitMinutes` INTEGER NOT NULL, `isEnabled` INTEGER NOT NULL DEFAULT 1, `lockMode` TEXT NOT NULL DEFAULT 'NONE', `lockPasswordHash` TEXT, `lockUntilTimestamp` INTEGER, `createdAt` INTEGER NOT NULL, `lastResetDate` INTEGER NOT NULL, `preventOpeningAfterLimit` INTEGER NOT NULL DEFAULT 1, `durationDays` INTEGER NOT NULL DEFAULT 1, `unlockWithPassword` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`packageName`))")
                database.execSQL("INSERT INTO app_usage_limits_new (packageName, appName, dailyLimitMinutes, isEnabled, createdAt, lastResetDate, lockMode, preventOpeningAfterLimit, durationDays) SELECT packageName, appName, limitMinutesPerDay, isActive, createdAt, lastResetDate, 'NONE', lockAfterLimit, durationDays FROM app_usage_limits")
                database.execSQL("DROP TABLE `app_usage_limits`")
                database.execSQL("ALTER TABLE `app_usage_limits_new` RENAME TO `app_usage_limits`")
                database.execSQL("CREATE TABLE IF NOT EXISTS `website_usage_limits` (`domain` TEXT NOT NULL, `dailyLimitMinutes` INTEGER NOT NULL, `isEnabled` INTEGER NOT NULL DEFAULT 1, `lockMode` TEXT NOT NULL DEFAULT 'NONE', `lockPasswordHash` TEXT, `lockUntilTimestamp` INTEGER, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`domain`))")
                database.execSQL("CREATE TABLE IF NOT EXISTS `usage_limits_locks` (`id` INTEGER NOT NULL, `lockedUntilTimestamp` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                database.execSQL("CREATE TABLE IF NOT EXISTS `daily_usage_stats` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `identifier` TEXT NOT NULL, `date` TEXT NOT NULL, `timeSpentMs` INTEGER NOT NULL)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `app_passwords` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `label` TEXT NOT NULL, `passwordHash` TEXT NOT NULL, `salt` TEXT, `createdAt` INTEGER NOT NULL DEFAULT 0)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `pomodoro_sessions` (`id` INTEGER NOT NULL, `endTime` INTEGER NOT NULL, `durationMillis` INTEGER NOT NULL, `isActive` INTEGER NOT NULL, `isBreak` INTEGER NOT NULL, `lastTickTime` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE block_sessions ADD COLUMN isBlockingEnabled INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE pomodoro_sessions ADD COLUMN isBlockingEnabled INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * Singleton accessor legado — DELEGADO ao Hilt para garantir uma única
         * instância de AppDatabase em todo o app.
         *
         * Antes desta correção, existiam DUAS instâncias paralelas:
         *   1. AppDatabase.INSTANCE (este companion) — usado por callers legados
         *   2. DatabaseModule.provideAppDatabase() — usado por Hilt (Repositories/ViewModels)
         *
         * Isso causava race conditions: dados escritos via uma instância podiam não
         * ser visíveis imediatamente na outra (caches separados), migrações podiam
         * rodar em paralelo, e o estado de bloqueio podia divergir entre UI e
         * Accessibility Service.
         *
         * Agora ambos os caminhos apontam para a mesma instância — ou via Hilt
         * (preferencial) ou via este singleton (legado).
         */
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "focusguard_database"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10
                    )
                    // fallbackToDestructiveMigration só em debug builds — em produção
                    // se uma migração faltar, preferimos crashar a perder dados de
                    // bloqueio do usuário.
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
