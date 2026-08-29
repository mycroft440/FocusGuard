package com.focusguard.monetization

import android.content.Context

object MonetizationStateStore {
    private const val PREFS = "focusguard_monetization"
    private const val LEGACY_KEY_POMODORO_COMPLETION_PENDING = "pomodoro_completion_ad_pending"
    private const val KEY_POMODORO_COMPLETION_PENDING_COUNT =
        "pomodoro_completion_ad_pending_count"
    private const val MAX_PENDING_POMODORO_ADS = 5

    @Synchronized
    fun markPomodoroCompletionAdPending(context: Context) {
        val preferences = prefs(context)
        val next = (pendingCount(preferences) + 1).coerceAtMost(MAX_PENDING_POMODORO_ADS)
        preferences.edit()
            .remove(LEGACY_KEY_POMODORO_COMPLETION_PENDING)
            .putInt(KEY_POMODORO_COMPLETION_PENDING_COUNT, next)
            .commit()
    }

    @Synchronized
    fun hasPomodoroCompletionAdPending(context: Context): Boolean =
        pendingCount(prefs(context)) > 0

    @Synchronized
    fun pendingPomodoroCompletionAds(context: Context): Int =
        pendingCount(prefs(context))

    /** Reserva um encerramento para uma tentativa real de exibição. */
    @Synchronized
    fun consumePomodoroCompletionAdPending(context: Context): Boolean {
        val preferences = prefs(context)
        val current = pendingCount(preferences)
        if (current <= 0) return false
        preferences.edit()
            .remove(LEGACY_KEY_POMODORO_COMPLETION_PENDING)
            .putInt(KEY_POMODORO_COMPLETION_PENDING_COUNT, current - 1)
            .commit()
        return true
    }

    /** Devolve à fila uma reserva cujo anúncio falhou antes de ser apresentado. */
    fun restorePomodoroCompletionAdPending(context: Context) {
        markPomodoroCompletionAdPending(context)
    }

    private fun pendingCount(preferences: android.content.SharedPreferences): Int {
        val count = preferences.getInt(KEY_POMODORO_COMPLETION_PENDING_COUNT, 0)
        if (count > 0) return count
        return if (preferences.getBoolean(LEGACY_KEY_POMODORO_COMPLETION_PENDING, false)) 1 else 0
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
