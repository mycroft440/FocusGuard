package com.focusguard.manager

import android.content.Context
import android.content.Intent
import com.focusguard.database.AppDatabase
import com.focusguard.database.PomodoroSession
import com.focusguard.service.PomodoroForegroundService
import com.focusguard.ui.PomodoroLockActivity
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.service.BlockingAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager do Pomodoro (timer rigoroso com bloqueio).
 *
 * P3-3: Agora anotado com `@Singleton` + `@Inject constructor` para que Hilt
 * forneça a instância automaticamente. O `companion.getInstance(context)`
 * permanece para callers legados (BootReceiver, etc.) — internamente delega
 * para a mesma instância via `EntryPointAccessors.fromApplication`.
 */
@Singleton
class PomodoroManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val database = AppDatabase.getDatabase(context)
    private val dao = database.pomodoroSessionDao()
    private val sessionManager = BlockingSessionManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _currentSession = MutableStateFlow<PomodoroSession?>(null)
    val currentSession: StateFlow<PomodoroSession?> = _currentSession.asStateFlow()

    private val _timeLeftMillis = MutableStateFlow(0L)
    val timeLeftMillis: StateFlow<Long> = _timeLeftMillis.asStateFlow()

    private val _onSessionFinished = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(replay = 0)
    val onSessionFinished = _onSessionFinished.asSharedFlow()

    private var tickerJob: Job? = null

    companion object {
        @Volatile
        private var legacyInstance: PomodoroManager? = null

        /**
         * Entry point legado para callers que não são Hilt-injectable
         * (BroadcastReceivers como BootReceiver, etc.).
         *
         * Tenta primeiro obter a instância via Hilt EntryPointAccessors
         * (garantindo mesma instância que @Inject callers). Se Hilt não
         * estiver inicializado, cai para singleton legacy.
         */
        fun getInstance(context: Context): PomodoroManager {
            return try {
                val appContext = context.applicationContext
                val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
                    appContext,
                    PomodoroManagerEntryPoint::class.java
                )
                entryPoint.pomodoroManager()
            } catch (_: Throwable) {
                synchronized(this) {
                    legacyInstance ?: PomodoroManager(context.applicationContext).also { legacyInstance = it }
                }
            }
        }
    }

    /**
     * Hilt EntryPoint para que callers não-Hilt (BootReceiver, etc.) possam
     * acessar a instância singleton via `getInstance()`.
     */
    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface PomodoroManagerEntryPoint {
        fun pomodoroManager(): PomodoroManager
    }

    init {
        FocusGuardLogger.log("PomodoroManager", "Inicializando PomodoroManager...")
        loadSession()
    }

    private fun loadSession() {
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                val session = dao.getPomodoroSessionSync()
                if (session != null) {
                    FocusGuardLogger.log("PomodoroManager", "Sessão carregada do banco: Ativa=${session.isActive}, Restante=${(session.endTime - now) / 1000}s")
                    if (session.isActive && session.endTime > now) {
                        _currentSession.value = session
                        if (session.isBlockingEnabled) {
                            StrictPomodoroLock.save(context, session.endTime, session.durationMillis)
                            launchStrictLockActivity()
                        }
                        startTicker()
                    } else {
                        FocusGuardLogger.log("PomodoroManager", "Sessão expirada encontrada. Limpando.")
                        dao.deleteSession()
                        StrictPomodoroLock.clear(context)
                        _currentSession.value = null
                    }
                } else {
                    val strictEndTime = StrictPomodoroLock.getEndTime(context)
                    if (strictEndTime > now) {
                        val remaining = strictEndTime - now
                        val restoredSession = PomodoroSession(
                            id = 1,
                            endTime = strictEndTime,
                            durationMillis = remaining,
                            isActive = true,
                            isBreak = false,
                            isBlockingEnabled = true
                        )
                        dao.insertOrUpdate(restoredSession)
                        _currentSession.value = restoredSession
                        sessionManager.startPomodoroSession(remaining, true)
                        launchStrictLockActivity()
                        startTicker()
                        FocusGuardLogger.log("PomodoroManager", "Pomodoro rigoroso restaurado por estado persistido: ${remaining / 1000}s restantes")
                    } else {
                        StrictPomodoroLock.clear(context)
                        FocusGuardLogger.log("PomodoroManager", "Nenhuma sessão de Pomodoro encontrada no banco.")
                    }
                }
                updateTimeLeft()
            } catch (e: Exception) {
                FocusGuardLogger.logError("PomodoroManager", "Falha ao carregar sessão", e)
            }
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            FocusGuardLogger.log("PomodoroManager", "Ticker iniciado (sessão ativa)")
            while (true) {
                updateTimeLeft()
                delay(1000)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
        FocusGuardLogger.log("PomodoroManager", "Ticker parado (sem sessão ativa)")
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
                if (session.isBlockingEnabled) {
                    StrictPomodoroLock.save(context, session.endTime, session.durationMillis)
                }
                if ((remaining / 1000) % 30 == 0L) {
                    FocusGuardLogger.log("PomodoroManager", "Timer rodando: ${remaining / 1000}s restantes")
                }
            }
        } else {
            if (_timeLeftMillis.value != 0L) {
                _timeLeftMillis.value = 0
                FocusGuardLogger.log("PomodoroManager", "Timer zerado (sessão inativa ou nula)")
            }
            stopTicker()
        }
    }

    suspend fun startSession(durationMinutes: Int, isBreak: Boolean = false, isBlockingEnabled: Boolean = true) {
        try {
            val durationMillis = durationMinutes * 60 * 1000L
            val endTime = System.currentTimeMillis() + durationMillis
            val session = PomodoroSession(
                id = 1,
                endTime = endTime,
                durationMillis = durationMillis,
                isActive = true,
                isBreak = isBreak,
                isBlockingEnabled = isBlockingEnabled
            )
            FocusGuardLogger.log("PomodoroManager", "Iniciando nova sessão: ${durationMinutes}min, Break=$isBreak")
            dao.insertOrUpdate(session)
            if (isBlockingEnabled) {
                StrictPomodoroLock.save(context, endTime, durationMillis)
                // Iniciar watchdog foreground service
                PomodoroForegroundService.start(context)
                // Agendar alarme watchdog como failsafe
                PomodoroForegroundService.scheduleWatchdogAlarm(context)
            } else {
                StrictPomodoroLock.clear(context)
            }
            _currentSession.value = session
            startTicker()
            context.sendBroadcast(Intent(BlockingAccessibilityService.ACTION_REFRESH_BLOCKING).setPackage(context.packageName))
            FocusGuardLogger.log("PomodoroManager", "Notificando BlockingSessionManager para bloqueio de apps (Enabled=$isBlockingEnabled).")
            sessionManager.startPomodoroSession(durationMillis, isBlockingEnabled)
            if (isBlockingEnabled) {
                launchStrictLockActivity()
            }
            FocusGuardLogger.log("PomodoroManager", "Sessão de Pomodoro iniciada com sucesso.")
        } catch (e: Exception) {
            FocusGuardLogger.logError("PomodoroManager", "Erro ao iniciar sessão", e)
        }
    }

    suspend fun stopSession() {
        try {
            FocusGuardLogger.log("PomodoroManager", "Encerrando sessão de Pomodoro...")
            dao.deleteSession()
            StrictPomodoroLock.clear(context)
            // Parar watchdog foreground service e cancelar alarme
            PomodoroForegroundService.stop(context)
            _currentSession.value = null
            _timeLeftMillis.value = 0
            try {
                val notification = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                val ringtone = android.media.RingtoneManager.getRingtone(context, notification)
                ringtone.play()
                FocusGuardLogger.log("PomodoroManager", "Aviso sonoro disparado.")
            } catch (e: Exception) {
                FocusGuardLogger.logError("PomodoroManager", "Falha ao tocar som", e)
            }
            FocusGuardLogger.log("PomodoroManager", "Sessão deletada do banco. Atualizando BlockingSessionManager.")
            sessionManager.endPomodoroSession()
            context.sendBroadcast(Intent("com.focusguard.ACTION_REFRESH_BLOCKING").setPackage(context.packageName))
            _onSessionFinished.emit(Unit)
            FocusGuardLogger.log("PomodoroManager", "Pomodoro encerrado com sucesso. App desbloqueado.")
        } catch (e: Exception) {
            FocusGuardLogger.logError("PomodoroManager", "Erro ao parar sessão", e)
        }
    }

    fun isPomodoroActive(): Boolean {
        return _currentSession.value?.isActive == true || StrictPomodoroLock.isActive(context)
    }

    private fun launchStrictLockActivity() {
        val intent = Intent(context, PomodoroLockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { FocusGuardLogger.logError("PomodoroManager", "Falha ao abrir tela de bloqueio rigoroso", it) }
    }
}
