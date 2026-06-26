package com.focusguard.di

import android.content.Context
import com.focusguard.manager.BlockingSessionManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Módulo Hilt que fornece os managers legados (que ainda usam o pattern
 * `getInstance(context)`) como dependências injetáveis.
 *
 * Esta é uma solução de transição: mantém o `getInstance` para os 32 callers
 * legados que ainda fazem `BlockingSessionManager.getInstance(context)`,
 * mas permite que ViewModels e Repositories injetem a instância via Hilt.
 *
 * Quando todos os callers forem migrados para Hilt, `getInstance` poderá ser
 * removido e o `BlockingSessionManager` convertido para `@Inject constructor`.
 */
@Module
@InstallIn(SingletonComponent::class)
object ManagerModule {

    @Provides
    @Singleton
    fun provideBlockingSessionManager(@ApplicationContext context: Context): BlockingSessionManager {
        return BlockingSessionManager.getInstance(context)
    }
}
