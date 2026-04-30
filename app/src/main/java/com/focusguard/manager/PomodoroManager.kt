package com.focusguard.manager

import com.focusguard.utils.FocusGuardLogger

import android.content.Context
import com.focusguard.database.AppDatabase
import com.focusguard.database.PomodoroSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PomodoroManager private constructor(private val context: Context) {

    private val database = AppDatabase.getDatabase(context)
    private val dao = database.pomodoroSessionDao()
    private val sessionManager = BlockingSessionManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _currentSession = MutableStateFlow<PomodoroSession?>(null)
    val currentSession: StateFlow<PomodoroSession?> = _currentSession.asStateFlow()

    private val _timeLeftMillis = MutableStateFlow(0L)
    val timeLeftMillis: StateFlow<Long> = _timeLeftMillis.asStateFlow()

    companion object {
        @Volatile
        private var instance: PomodoroManager? = null

        fun getInstance(context: Context): PomodoroManager {
            return instance ?: synchronized(this) {
                instance ?: PomodoroManager(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        FocusGuardLogger.log("PomodoroManager", "Inicializando PomodoroManager...")
        loadSession()
        startTicker()
    }

    private fun loadSession() {
        scope.launch {
            try {
                val session = dao.getPomodoroSessionSync()
                if (session != null) {
                    FocusGuardLogger.log("PomodoroManager", "Sessão carregada do banco: Ativa=${session.isActive}, Restante=${(session.endTime - System.currentTimeMillis())/1000}s")
                } else {
                    FocusGuardLogger.log("PomodoroManager", "Nenhuma sessão de Pomodoro encontrada no banco.")
                }
                _currentSession.value = session
                updateTimeLeft()
            } catch (e: Exception) {
                FocusGuardLogger.logError("PomodoroManager", "Falha ao carregar sessão", e)
            }
        }
    }

    private fun startTicker() {
        scope.launch {
            FocusGuardLogger.log("PomodoroManager", "Iniciando Ticker do Pomodoro (1s)")
            while (true) {
                updateTimeLeft()
                delay(1000)
            }
        }
    }

    private fun updateTimeLeft() {
        val session = _currentSession.value
        if (session != null && session.isActive) {
            val now = System.currentTimeMillis()
            val remaining = session.endTime - now
            if (remaining <= 0) {
                FocusGuardLogger.log("PomodoroManager", "Tempo esgotado! Iniciando finalização automática.")
                _timeLeftMillis.value = 0
                scope.launch { stopSession() }
            } else {
                _timeLeftMillis.value = remaining
                // Log a cada 30 segundos para não poluir muito, ou log profundo se quiser tudo
                if ((remaining / 1000) % 30 == 0L) {
                    FocusGuardLogger.log("PomodoroManager", "Timer rodando: ${remaining/1000}s restantes")
                }
            }
        } else {
            if (_timeLeftMillis.value != 0L) {
                _timeLeftMillis.value = 0
                FocusGuardLogger.log("PomodoroManager", "Timer zerado (sessão inativa ou nula)")
            }
        }
    }

    suspend fun startSession(durationMinutes: Int, isBreak: Boolean = false) {
        try {
            val durationMillis = durationMinutes * 60 * 1000L
            val endTime = System.currentTimeMillis() + durationMillis
            val session = PomodoroSession(
                id = 1,
                endTime = endTime,
                durationMillis = durationMillis,
                isActive = true,
                isBreak = isBreak
            )
            FocusGuardLogger.log("PomodoroManager", "Iniciando nova sessão: ${durationMinutes}min, Break=$isBreak")
            dao.insertOrUpdate(session)
            _currentSession.value = session
            
            FocusGuardLogger.log("PomodoroManager", "Notificando BlockingSessionManager para bloqueio de apps.")
            sessionManager.startPomodoroSession(durationMillis)
            FocusGuardLogger.log("PomodoroManager", "Sessão de Pomodoro iniciada com sucesso.")
        } catch (e: Exception) {
            FocusGuardLogger.logError("PomodoroManager", "Erro ao iniciar sessão", e)
        }
    }

    suspend fun stopSession() {
        try {
            FocusGuardLogger.log("PomodoroManager", "Encerrando sessão de Pomodoro...")
            dao.deleteSession()
            _currentSession.value = null
            _timeLeftMillis.value = 0
            
            FocusGuardLogger.log("PomodoroManager", "Sessão deletada do banco. Atualizando BlockingSessionManager.")
            sessionManager.endPomodoroSession()
            FocusGuardLogger.log("PomodoroManager", "Pomodoro encerrado com sucesso. App desbloqueado.")
        } catch (e: Exception) {
            FocusGuardLogger.logError("PomodoroManager", "Erro ao parar sessão", e)
        }
    }

    fun isPomodoroActive(): Boolean {
        return _currentSession.value?.isActive == true
    }
}
