package com.focusguard.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object CreatorInstagramPromptPolicy {
    const val MIN_INSTALL_AGE_MILLIS = 60L * 60L * 1_000L
    const val ATTENTION_VISIBLE_MILLIS = 12_000L
    const val ATTENTION_FADE_OUT_MILLIS = 900L

    fun shouldShowFeedbackButton(
        homeCardPresented: Boolean,
        @Suppress("UNUSED_PARAMETER") homeCardVisible: Boolean
    ): Boolean = homeCardPresented

    fun remainingDelayMillis(
        firstInstallTimeMillis: Long,
        nowMillis: Long
    ): Long {
        val installedForMillis = if (nowMillis > firstInstallTimeMillis) {
            nowMillis - firstInstallTimeMillis
        } else {
            0L
        }
        return (MIN_INSTALL_AGE_MILLIS - installedForMillis).coerceAtLeast(0L)
    }
}

class CreatorInstagramPromptStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun wasHomeCardPresented(): Boolean = preferences.getBoolean(
        HOME_CARD_PRESENTED_KEY,
        false
    )

    fun markHomeCardPresented() {
        preferences.edit()
            .putBoolean(HOME_CARD_PRESENTED_KEY, true)
            .apply()
    }

    fun remainingDelayMillis(nowMillis: Long = System.currentTimeMillis()): Long {
        return CreatorInstagramPromptPolicy.remainingDelayMillis(
            firstInstallTimeMillis = resolveFirstInstallTimeMillis(nowMillis),
            nowMillis = nowMillis
        )
    }

    private fun resolveFirstInstallTimeMillis(nowMillis: Long): Long {
        val packageInstallTime = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.getPackageInfo(
                    appContext.packageName,
                    PackageManager.PackageInfoFlags.of(0L)
                ).firstInstallTime
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getPackageInfo(
                    appContext.packageName,
                    0
                ).firstInstallTime
            }
        }.getOrNull()?.takeIf { it > 0L }

        if (packageInstallTime != null) return packageInstallTime

        val storedFallback = preferences.getLong(FALLBACK_INSTALL_TIME_KEY, 0L)
        if (storedFallback > 0L) return storedFallback

        preferences.edit()
            .putLong(FALLBACK_INSTALL_TIME_KEY, nowMillis)
            .apply()
        return nowMillis
    }

    internal companion object {
        const val PREFERENCES_NAME = "focusguard_creator_instagram"
        private const val HOME_CARD_PRESENTED_KEY = "home_card_presented"
        private const val FALLBACK_INSTALL_TIME_KEY = "fallback_install_time"
    }
}
