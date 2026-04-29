package com.focusguard.manager

import android.content.Context
import com.focusguard.database.AppDatabase
import com.focusguard.database.AppUsageLimit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UsageLimitManager private constructor(context: Context) {
    private val database = AppDatabase.getDatabase(context)
    private val limitDao = database.appUsageLimitDao()

    companion object {
        @Volatile
        private var instance: UsageLimitManager? = null

        fun getInstance(context: Context): UsageLimitManager {
            return instance ?: synchronized(this) {
                instance ?: UsageLimitManager(context.applicationContext).also { instance = it }
            }
        }
    }

    suspend fun setLimit(packageName: String, appName: String, limitMinutes: Int) {
        val limit = AppUsageLimit(
            packageName = packageName,
            appName = appName,
            dailyLimitMinutes = limitMinutes,
            isEnabled = true,
            createdAt = System.currentTimeMillis(),
            lastResetDate = System.currentTimeMillis()
        )
        limitDao.insert(limit)
    }

    suspend fun getLimit(packageName: String): AppUsageLimit? {
        return limitDao.getLimitForPackage(packageName)
    }

    suspend fun removeLimit(packageName: String) {
        limitDao.deleteLimitByPackage(packageName)
    }

    suspend fun getAllActiveLimits(): List<AppUsageLimit> {
        return limitDao.getAllActiveLimitsStatic()
    }
}
