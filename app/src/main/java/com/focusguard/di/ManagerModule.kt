package com.focusguard.di

import android.content.Context
import com.focusguard.admin.DeviceOwnerManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Módulo Hilt para managers legados que ainda usam `getInstance(context)`.
 *
 * P3-2: `BlockingSessionManager` agora usa `@Inject constructor` direto —
 * não precisa mais de @Provides aqui. Mantemos o módulo para `DeviceOwnerManager`
 * que ainda usa pattern getInstance legado (P3-3 não cobre DeviceOwnerManager,
 * será migrado em PR futura).
 *
 * Quando todos os managers forem migrados para `@Inject constructor`, este
 * módulo pode ser removido completamente.
 */
@Module
@InstallIn(SingletonComponent::class)
object ManagerModule {

    @Provides
    @Singleton
    fun provideDeviceOwnerManager(@ApplicationContext context: Context): DeviceOwnerManager {
        return DeviceOwnerManager.getInstance(context)
    }
}
