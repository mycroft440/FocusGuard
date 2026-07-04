package com.focusguard.manager

import android.content.Context
import android.os.Build
import com.focusguard.utils.FocusGuardLogger

object StrictPomodoroLock {
    private const val PREFS_NAME = "focusguard_strict_pomodoro_lock"
    private const val KEY_ACTIVE = "active"
    private const val KEY_END_TIME = "end_time"
    private const val KEY_DURATION = "duration"

    fun save(context: Context, endTime: Long, durationMillis: Long) {
        writeTo(context, endTime, durationMillis)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                val directBootContext = context.createDeviceProtectedStorageContext()
                writeTo(directBootContext, endTime, durationMillis)
            }.onFailure { error ->
                FocusGuardLogger.logError("StrictPomodoroLock", "Falha ao salvar estado Direct Boot", error)
            }
        }
    }

    fun clear(context: Context) {
        clearFrom(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { clearFrom(context.createDeviceProtectedStorageContext()) }
        }
    }

    fun getEndTime(context: Context): Long {
        val normal = readEndTime(context)
        if (normal > 0L) return normal
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return runCatching { readEndTime(context.createDeviceProtectedStorageContext()) }.getOrDefault(0L)
        }
        return 0L
    }

    fun isActive(context: Context): Boolean {
        val endTime = getEndTime(context)
        val active = endTime > System.currentTimeMillis()
        // ANTIGO: this.clear() era chamado aqui se endTime expirou.
        // PROBLEMA: isActive() é chamado por múltiplas threads (PomodoroManager,
        // BlockingAccessibilityService, PomodoroForegroundService, receivers).
        // Se duas threads chamam isActive() ao mesmo tempo quando a sessão
        // expirou, ambas disparam clear() — race condition. Pior: se uma
        // thread está no meio de iniciar uma NOVA sessão (save()) enquanto
        // outra chama isActive() com endTime antigo, clear() pode apagar
        // o estado recém-criado.
        //
        // SOLUÇÃO: isActive() é uma função de LEITURA pura. A limpeza do
        // estado expirado deve ser responsabilidade do PomodoroManager
        // (que é singleton e coordena stop/clear).
        return active
    }

    fun remainingMillis(context: Context): Long {
        return (getEndTime(context) - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    private fun writeTo(context: Context, endTime: Long, durationMillis: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVE, true)
            .putLong(KEY_END_TIME, endTime)
            .putLong(KEY_DURATION, durationMillis)
            .apply()
    }

    private fun clearFrom(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private fun readEndTime(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return 0L
        return prefs.getLong(KEY_END_TIME, 0L)
    }
}
