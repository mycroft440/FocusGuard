package com.focusguard.di

import android.content.Context
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
 * Antes da Fase 3, cada tela chamava `AppDatabase.getDatabase(context)` diretamente
 * (15 sites de chamada UI→DB). Agora os DAOs são injetados via construtor de
 * Repository/ViewModel — eliminando o boilerplate e facilitando testes.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        // CRÍTICO: delega para o singleton do AppDatabase — garante que Hilt e
        // callers legados (AppDatabase.getDatabase(context)) usem a MESMA
        // instância Room. Antes desta correção, duas instâncias paralelas eram
        // criadas (uma aqui, outra no companion do AppDatabase), causando
        // race conditions e dados divergentes entre UI e Accessibility Service.
        return AppDatabase.getDatabase(context)
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
