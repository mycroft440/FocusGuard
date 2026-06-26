package com.focusguard.di

import android.content.Context
import com.focusguard.security.AuthManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Módulo Hilt que fornece [AuthManager] como dependência injetável.
 *
 * Solução de transição: mantém o construtor `AuthManager(context)` legado
 * para os 37 callers existentes, mas permite que ViewModels e Repositories
 * injetem a instância via Hilt.
 */
@Module
@InstallIn(SingletonComponent::class)
object AuthModule {

    @Provides
    @Singleton
    fun provideAuthManager(@ApplicationContext context: Context): AuthManager {
        return AuthManager(context)
    }
}
