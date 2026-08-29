package com.focusguard.monetization

import android.content.Context

object MonetizationStateStore {
    private const val PREFS = "focusguard_monetization"
    private const val KEY_POMODORO_COMPLETION_PENDING = "pomodoro_completion_ad_pending"

    fun markPomodoroCompletionAdPending(context: Context) {
        prefs(context).edit().putBoolean(KEY_POMODORO_COMPLETION_PENDING, true).apply()
    }

    fun hasPomodoroCompletionAdPending(context: Context): Boolean =
        prefs(context).getBoolean(KEY_POMODORO_COMPLETION_PENDING, false)

    fun consumePomodoroCompletionAdPending(context: Context): Boolean {
        val preferences = prefs(context)
        if (!preferences.getBoolean(KEY_POMODORO_COMPLETION_PENDING, false)) return false
        preferences.edit().putBoolean(KEY_POMODORO_COMPLETION_PENDING, false).apply()
        return true
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
