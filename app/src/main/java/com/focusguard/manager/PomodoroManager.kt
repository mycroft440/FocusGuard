package com.focusguard.manager

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
        loadSession()
        startTicker()
    }

    private fun loadSession() {
        scope.launch {
            val session = dao.getPomodoroSessionSync()
            _currentSession.value = session
            updateTimeLeft()
        }
    }

    private fun startTicker() {
        scope.launch {
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
                _timeLeftMillis.value = 0
                scope.launch { stopSession() }
            } else {
                _timeLeftMillis.value = remaining
            }
        } else {
            _timeLeftMillis.value = 0
        }
    }

    suspend fun startSession(durationMinutes: Int, isBreak: Boolean = false) {
        val durationMillis = durationMinutes * 60 * 1000L
        val endTime = System.currentTimeMillis() + durationMillis
        val session = PomodoroSession(
            id = 1,
            endTime = endTime,
            durationMillis = durationMillis,
            isActive = true,
            isBreak = isBreak
        )
        dao.insertOrUpdate(session)
        _currentSession.value = session
        
        // Ativa o bloqueio de apps no sistema
        sessionManager.startPomodoroSession(durationMillis)
    }

    suspend fun stopSession() {
        dao.deleteSession()
        _currentSession.value = null
        _timeLeftMillis.value = 0
        
        // O BlockingSessionManager encerrará a sessão quando o tempo acabar no checkAndEnforce dele
        // ou podemos forçar um checkAndEnforce
        sessionManager.checkAndEnforce()
    }

    fun isPomodoroActive(): Boolean {
        return _currentSession.value?.isActive == true
    }
}
