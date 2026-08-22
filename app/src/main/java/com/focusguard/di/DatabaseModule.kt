package com.focusguard.di

import android.content.Context
import androidx.room.Room
import com.focusguard.database.AppDatabase
import com.focusguard.database.BlockedAppDao
import com.focusguard.database.BlockedWebsiteDao
import com.focusguard.database.BlockSessionDao
import com.focusguard.database.SessionAppCrossRefDao
import com.focusguard.database.SessionWebsiteCrossRefDao
import com.focusguard.database.AppUsageLimitDao
import com.focusguard.database.WebsiteUsageLimitDao
import com.focusguard.database.UsageLimitsLockDao
import com.focusguard.database.DailyUsageStatDao
import com.focusguard.database.AppPasswordDao
import com.focusguard.database.PomodoroSessionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Módulo Hilt que fornece a instância única do Room database e seus DAOs.
 *
 * Hilt owns the only Room instance; consumers receive the database or a DAO.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "focusguard_database"
        ).addMigrations(*AppDatabase.ALL_MIGRATIONS).build()
    }

    @Provides fun provideBlockedAppDao(db: AppDatabase): BlockedAppDao = db.blockedAppDao()
    @Provides fun provideBlockedWebsiteDao(db: AppDatabase): BlockedWebsiteDao = db.blockedWebsiteDao()
    @Provides fun provideBlockSessionDao(db: AppDatabase): BlockSessionDao = db.blockSessionDao()
    @Provides fun provideSessionAppCrossRefDao(db: AppDatabase): SessionAppCrossRefDao = db.sessionAppCrossRefDao()
    @Provides fun provideSessionWebsiteCrossRefDao(db: AppDatabase): SessionWebsiteCrossRefDao = db.sessionWebsiteCrossRefDao()
    @Provides fun provideAppUsageLimitDao(db: AppDatabase): AppUsageLimitDao = db.appUsageLimitDao()
    @Provides fun provideWebsiteUsageLimitDao(db: AppDatabase): WebsiteUsageLimitDao = db.websiteUsageLimitDao()
    @Provides fun provideUsageLimitsLockDao(db: AppDatabase): UsageLimitsLockDao = db.usageLimitsLockDao()
    @Provides fun provideDailyUsageStatDao(db: AppDatabase): DailyUsageStatDao = db.dailyUsageStatDao()
    @Provides fun provideAppPasswordDao(db: AppDatabase): AppPasswordDao = db.appPasswordDao()
    @Provides fun providePomodoroSessionDao(db: AppDatabase): PomodoroSessionDao = db.pomodoroSessionDao()
}
