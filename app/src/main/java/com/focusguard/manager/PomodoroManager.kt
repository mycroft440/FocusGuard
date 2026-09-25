package com.focusguard.manager

import com.focusguard.monetization.MonetizationStateStore
import android.content.Context
import com.focusguard.database.AppDatabase
import com.focusguard.database.PomodoroSession
import com.focusguard.pomodoro.PomodoroAlarmController
import com.focusguard.pomodoro.PomodoroCyclePolicy
import com.focusguard.pomodoro.PomodoroCycleRuntime
import com.focusguard.pomodoro.PomodoroNotificationController
import com.focusguard.pomodoro.PomodoroPhase
import com.focusguard.pomodoro.PomodoroPlanConfig
import com.focusguard.pomodoro.PomodoroPlanStore
import com.focusguard.security.BlockTargetPolicy
import com.focusguard.service.FocusModeNotificationService
import com.focusguard.service.PomodoroForegroundService
import com.focusguard.utils.FocusGuardLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class PomodoroManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        /** Estado da tela de bloqueio do antigo Pomodoro rigoroso. */
        private const val LEGACY_STRICT_LOCK_PREFS = "focusguard_strict_pomodoro_lock"

        @Volatile
        private var legacyInstance: PomodoroManager? = null

        fun getInstance(context: Context): PomodoroManager {
            return try {
                val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    PomodoroManagerEntryPoint::class.java
                )
                entryPoint.pomodoroManager()
            } catch (error: Exception) {
                FocusGuardLogger.logError(
                    "PomodoroManager",
                    "Hilt indisponível; usando singleton legado",
                    error
                )
                synchronized(this) {
                    legacyInstance ?: PomodoroManager(context.applicationContext)
                        .also { legacyInstance = it }
                }
            }
        }
    }

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface PomodoroManagerEntryPoint {
        fun pomodoroManager(): PomodoroManager
    }

    private val database = AppDatabase.getDatabase(context)
    private val dao = database.pomodoroSessionDao()
    private val sessionManager = BlockingSessionManager.getInstance(context)
    private val planStore = PomodoroPlanStore(context)
    private val notificationController = PomodoroNotificationController(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val finishMutex = Mutex()

    private val _currentSession = MutableStateFlow<PomodoroSession?>(null)
    val currentSession: StateFlow<PomodoroSession?> = _currentSession.asStateFlow()

    private val _timeLeftMillis = MutableStateFlow(0L)
    val timeLeftMillis: StateFlow<Long> = _timeLeftMillis.asStateFlow()

    private val _cycleState = MutableStateFlow<PomodoroCycleRuntime?>(null)
    val cycleState: StateFlow<PomodoroCycleRuntime?> = _cycleState.asStateFlow()

    /** Emitido quando o plano encerra naturalmente ou por ação manual do usuário. */
    private val _onSessionFinished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val onSessionFinished = _onSessionFinished.asSharedFlow()

    private var tickerJob: Job? = null
    private var alarmJob: Job? = null

    init {
        loadSession()
    }

    fun loadSavedConfig(): PomodoroPlanConfig = planStore.loadConfig()

    fun saveConfig(config: PomodoroPlanConfig): PomodoroPlanConfig = planStore.saveConfig(config)

    fun hasNotificationPolicyAccess(): Boolean = notificationController.hasPolicyAccess()

    fun hasNotificationListenerAccess(): Boolean =
        notificationController.hasNotificationListenerAccess(FocusModeNotificationService::class.java)

    private fun loadSession() {
        scope.launch {
            try {
                finishMutex.withLock {
                    discardLegacyStrictPomodoro()
                    val now = System.currentTimeMillis()
                    val session = dao.getPomodoroSessionSync()
                    val runtime = planStore.readRuntime()?.takeIf { it.active }

                    if (runtime != null) {
                        _cycleState.value = runtime
                        if (runtime.config.silenceNotifications) {
                            notificationController.apply(runtime.config)
                        }
                        FocusModeNotificationService.requestRefresh(context)

                        when {
                            session?.isActive == true && session.endTime > now -> {
                                restoreSessionLocked(session, runtime)
                            }
                            runtime.intervalEndTime > now && runtime.intervalDurationMillis > 0L -> {
                                val restored = PomodoroSession(
                                    id = 1,
                                    endTime = runtime.intervalEndTime,
                                    durationMillis = runtime.intervalDurationMillis,
                                    isActive = true,
                                    isBreak = runtime.phase != PomodoroPhase.FOCUS
                                )
                                dao.insertOrUpdate(restored)
                                restoreSessionLocked(restored, runtime)
                            }
                            session != null || runtime.intervalEndTime > 0L -> {
                                _currentSession.value = session ?: PomodoroSession(
                                    id = 1,
                                    endTime = runtime.intervalEndTime,
                                    durationMillis = runtime.intervalDurationMillis,
                                    isActive = true,
                                    isBreak = runtime.phase != PomodoroPhase.FOCUS
                                )
                                finishCurrentIntervalLocked(playAlarm = false)
                            }
                            else -> {
                                startIntervalLocked(
                                    phase = runtime.phase,
                                    config = runtime.config,
                                    ensureForegroundService = true
                                )
                            }
                        }
                    } else if (session?.isActive == true && session.endTime > now) {
                        val legacyConfig = planStore.loadConfig().copy(
                            focusMinutes = ((session.durationMillis + 59_999L) / 60_000L)
                                .toInt()
                                .coerceAtLeast(1),
                            targetSessions = 1
                        ).normalized()
                        val legacyRuntime = PomodoroCycleRuntime(
                            active = true,
                            phase = if (session.isBreak) {
                                PomodoroPhase.SHORT_BREAK
                            } else {
                                PomodoroPhase.FOCUS
                            },
                            completedFocusSessions = 0,
                            config = legacyConfig,
                            intervalEndTime = session.endTime,
                            intervalDurationMillis = session.durationMillis
                        )
                        planStore.saveRuntime(legacyRuntime)
                        _cycleState.value = legacyRuntime
                        restoreSessionLocked(session, legacyRuntime)
                    } else {
                        cleanupAllStateLocked(emitFinished = false, cancelAlarm = true)
                    }
                    updateTimeLeft()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                FocusGuardLogger.logError(
                    "PomodoroManager",
                    "Falha ao recuperar Pomodoro",
                    error
                )
                finishMutex.withLock {
                    cleanupAllStateLocked(emitFinished = false, cancelAlarm = true)
                }
            }
        }
    }

    private suspend fun restoreSessionLocked(
        session: PomodoroSession,
        runtime: PomodoroCycleRuntime
    ) {
        _currentSession.value = session
        _cycleState.value = runtime.copy(
            intervalEndTime = session.endTime,
            intervalDurationMillis = session.durationMillis
        ).also(planStore::saveRuntime)

        applyNotificationPolicyForInterval(runtime.config)

        PomodoroForegroundService.start(context)
        PomodoroForegroundService.scheduleWatchdogAlarm(context)
        startTicker()
    }

    /**
     * O Pomodoro rigoroso foi removido. Versões antigas podiam deixar um bloqueio POMODORO
     * ativo no banco e o estado da tela de bloqueio salvo; os dois são descartados aqui, e os
     * bloqueios são reconciliados se algum bloqueio antigo estava ativo.
     */
    private suspend fun discardLegacyStrictPomodoro() {
        runCatching {
            context.deleteSharedPreferences(LEGACY_STRICT_LOCK_PREFS)
            context.createDeviceProtectedStorageContext()
                .deleteSharedPreferences(LEGACY_STRICT_LOCK_PREFS)
        }
        // Uma falha aqui não pode derrubar a restauração do Pomodoro em andamento. As sessões
        // POMODORO que sobrarem já não bloqueiam (BlockingSessionManager.participatesInBlocking).
        try {
            val deactivated = database.blockSessionDao()
                .deactivateActiveSessionsByType(BlockTargetPolicy.SESSION_TYPE_POMODORO)
            if (deactivated > 0) sessionManager.checkAndEnforce()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FocusGuardLogger.logError(
                "PomodoroManager",
                "Falha ao descartar o antigo Pomodoro rigoroso",
                error
            )
        }
    }

    private fun applyNotificationPolicyForInterval(config: PomodoroPlanConfig) {
        if (config.silenceNotifications && !notificationController.apply(config)) {
            FocusGuardLogger.log(
                "PomodoroManager",
                "Não Perturbe não pôde ser reaplicado neste intervalo"
            )
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (true) {
                val session = _currentSession.value ?: break
                val remaining = session.endTime - System.currentTimeMillis()
                if (!session.isActive || remaining <= 0L) {
                    _timeLeftMillis.value = 0L
                    tickerJob = null
                    finishMutex.withLock {
                        finishCurrentIntervalLocked(playAlarm = true)
                    }
                    break
                }
                _timeLeftMillis.value = remaining
                delay(1_000L)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun updateTimeLeft() {
        val session = _currentSession.value
        _timeLeftMillis.value = if (session?.isActive == true) {
            (session.endTime - System.currentTimeMillis()).coerceAtLeast(0L)
        } else {
            0L
        }
    }

    suspend fun startPlan(config: PomodoroPlanConfig) {
        val normalized = config.normalized()
        check(!normalized.silenceNotifications || notificationController.hasPolicyAccess()) {
            "Acesso ao Não Perturbe é necessário para silenciar notificações"
        }
        check(
            !normalized.hideNotifications ||
                notificationController.hasNotificationListenerAccess(
                    FocusModeNotificationService::class.java)
        ) {
            "Acesso às notificações é necessário para ocultá-las"
        }

        finishMutex.withLock {
            cleanupAllStateLocked(emitFinished = false, cancelAlarm = true)
            val saved = planStore.saveConfig(normalized)
            val runtime = planStore.beginRuntime(saved)
            _cycleState.value = runtime
            if (saved.silenceNotifications) {
                check(notificationController.apply(saved)) {
                    "Não foi possível ativar o Não Perturbe do Pomodoro"
                }
            }
            FocusModeNotificationService.requestRefresh(context)
            try {
                startIntervalLocked(
                    phase = PomodoroPhase.FOCUS,
                    config = saved,
                    ensureForegroundService = true
                )
            } catch (cancelled: CancellationException) {
                cleanupAllStateLocked(emitFinished = false, cancelAlarm = true)
                throw cancelled
            } catch (error: Exception) {
                cleanupAllStateLocked(emitFinished = false, cancelAlarm = true)
                throw error
            }
        }
    }

    private suspend fun startIntervalLocked(
        phase: PomodoroPhase,
        config: PomodoroPlanConfig,
        ensureForegroundService: Boolean
    ) {
        val durationMinutes = PomodoroCyclePolicy.durationMinutes(config, phase)
        val durationMillis = durationMinutes * 60_000L
        val endTime = System.currentTimeMillis() + durationMillis

        val session = PomodoroSession(
            id = 1,
            endTime = endTime,
            durationMillis = durationMillis,
            isActive = true,
            isBreak = phase != PomodoroPhase.FOCUS
        )
        dao.insertOrUpdate(session)
        _currentSession.value = session
        _timeLeftMillis.value = durationMillis

        val currentRuntime = _cycleState.value ?: planStore.beginRuntime(config)
        val updatedRuntime = currentRuntime.copy(
            active = true,
            phase = phase,
            config = config.normalized(),
            intervalEndTime = endTime,
            intervalDurationMillis = durationMillis
        )
        planStore.saveRuntime(updatedRuntime)
        _cycleState.value = updatedRuntime

        applyNotificationPolicyForInterval(config)

        if (ensureForegroundService) {
            PomodoroForegroundService.start(context)
        }
        PomodoroForegroundService.scheduleWatchdogAlarm(context)

        FocusModeNotificationService.requestRefresh(context)
        startTicker()
    }

    private suspend fun finishCurrentIntervalLocked(playAlarm: Boolean) {
        val runtime = _cycleState.value ?: planStore.readRuntime()
        val session = _currentSession.value ?: dao.getPomodoroSessionSync()
        if (runtime == null || !runtime.active || session == null) {
            cleanupAllStateLocked(emitFinished = false, cancelAlarm = false)
            return
        }

        stopTicker()
        dao.deleteSession()
        PomodoroForegroundService.cancelWatchdogAlarm(context)
        _currentSession.value = null
        _timeLeftMillis.value = 0L

        if (playAlarm) {
            alarmJob?.cancel()
            alarmJob = scope.launch {
                runCatching { PomodoroAlarmController.play(context, runtime.config) }
                    .onFailure { error ->
                        if (error !is CancellationException) {
                            FocusGuardLogger.logError(
                                "PomodoroManager",
                                "Falha ao tocar alarme configurado",
                                error
                            )
                        }
                    }
            }
        }

        when (runtime.phase) {
            PomodoroPhase.FOCUS -> {
                val completed = runtime.completedFocusSessions + 1
                val nextBreak = PomodoroCyclePolicy.nextBreakAfterFocus(
                    config = runtime.config,
                    completedFocusSessions = completed
                )
                if (nextBreak == null) {
                    val finished = runtime.copy(
                        completedFocusSessions = completed,
                        intervalEndTime = 0L,
                        intervalDurationMillis = 0L
                    )
                    _cycleState.value = finished
                    cleanupAllStateLocked(
                        emitFinished = true,
                        cancelAlarm = false
                    )
                    return
                }

                val nextRuntime = runtime.copy(
                    phase = nextBreak,
                    completedFocusSessions = completed,
                    intervalEndTime = 0L,
                    intervalDurationMillis = 0L
                )
                planStore.saveRuntime(nextRuntime)
                _cycleState.value = nextRuntime
                startIntervalLocked(
                    phase = nextBreak,
                    config = runtime.config,
                    ensureForegroundService = true
                )
            }

            PomodoroPhase.SHORT_BREAK,
            PomodoroPhase.LONG_BREAK -> {
                val nextRuntime = runtime.copy(
                    phase = PomodoroPhase.FOCUS,
                    intervalEndTime = 0L,
                    intervalDurationMillis = 0L
                )
                planStore.saveRuntime(nextRuntime)
                _cycleState.value = nextRuntime
                startIntervalLocked(
                    phase = PomodoroPhase.FOCUS,
                    config = runtime.config,
                    ensureForegroundService = true
                )
            }
        }
    }

    suspend fun stopSession() {
        finishMutex.withLock {
            val hadActivePlan = _cycleState.value?.active == true ||
                planStore.readRuntime()?.active == true ||
                _currentSession.value?.isActive == true
            cleanupAllStateLocked(emitFinished = hadActivePlan, cancelAlarm = true)
        }
    }

    private suspend fun cleanupAllStateLocked(
        emitFinished: Boolean,
        cancelAlarm: Boolean
    ) {
        stopTicker()
        if (cancelAlarm) {
            alarmJob?.cancel()
            alarmJob = null
        }
        dao.deleteSession()
        _currentSession.value = null
        _timeLeftMillis.value = 0L
        _cycleState.value = null
        planStore.clearRuntime()
        PomodoroForegroundService.stop(context)
        notificationController.restore()
        FocusModeNotificationService.requestRefresh(context)
        if (emitFinished) {
            MonetizationStateStore.markPomodoroCompletionAdPending(context)
            _onSessionFinished.tryEmit(Unit)
        }
    }

    fun isPomodoroActive(): Boolean {
        val runtime = _cycleState.value ?: planStore.readRuntime()
        return runtime?.active == true ||
            (_currentSession.value?.isActive == true &&
                (_currentSession.value?.endTime ?: 0L) > System.currentTimeMillis())
    }
}
