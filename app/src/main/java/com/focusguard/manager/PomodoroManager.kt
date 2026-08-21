package com.focusguard.manager

import android.content.Context
import android.content.Intent
import com.focusguard.database.AppDatabase
import com.focusguard.database.PomodoroSession
import com.focusguard.database.BlockSessionType
import com.focusguard.focusmode.FocusModeStore
import com.focusguard.pomodoro.PomodoroAlarmController
import com.focusguard.pomodoro.PomodoroCyclePolicy
import com.focusguard.pomodoro.PomodoroCycleRuntime
import com.focusguard.pomodoro.PomodoroNotificationController
import com.focusguard.pomodoro.PomodoroPhase
import com.focusguard.pomodoro.PomodoroPlanConfig
import com.focusguard.pomodoro.PomodoroPlanStore
import com.focusguard.security.ProtectionPermissionGate
import com.focusguard.service.BlockingAccessibilityService
import com.focusguard.service.FocusModeNotificationService
import com.focusguard.service.PomodoroForegroundService
import com.focusguard.ui.PomodoroLockActivity
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
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val sessionManager: BlockingSessionManager,
    private val protectionPermissionGate: ProtectionPermissionGate,
    private val planStore: PomodoroPlanStore,
    private val notificationController: PomodoroNotificationController
) {

    companion object {
        private const val STRICT_ARM_TIMEOUT_MILLIS = 3_000L
        private const val STRICT_ARM_POLL_MILLIS = 25L

    }

    private val dao = database.pomodoroSessionDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val finishMutex = Mutex()

    private val _currentSession = MutableStateFlow<PomodoroSession?>(null)
    val currentSession: StateFlow<PomodoroSession?> = _currentSession.asStateFlow()

    private val _timeLeftMillis = MutableStateFlow(0L)
    val timeLeftMillis: StateFlow<Long> = _timeLeftMillis.asStateFlow()

    private val _cycleState = MutableStateFlow<PomodoroCycleRuntime?>(null)
    val cycleState: StateFlow<PomodoroCycleRuntime?> = _cycleState.asStateFlow()

    /** Emitido quando o plano inteiro termina, não a cada pausa. */
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
                                    isBreak = runtime.phase != PomodoroPhase.FOCUS,
                                    isBlockingEnabled = runtime.phase == PomodoroPhase.FOCUS &&
                                        runtime.config.strictBlocking
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
                                    isBreak = runtime.phase != PomodoroPhase.FOCUS,
                                    isBlockingEnabled = runtime.phase == PomodoroPhase.FOCUS &&
                                        runtime.config.strictBlocking
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
                            targetSessions = 1,
                            strictBlocking = session.isBlockingEnabled
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
                    } else if (StrictPomodoroLock.getEndTime(context) > now) {
                        val endTime = StrictPomodoroLock.getEndTime(context)
                        val remaining = endTime - now
                        val config = planStore.loadConfig().copy(
                            focusMinutes = ((remaining + 59_999L) / 60_000L).toInt().coerceAtLeast(1),
                            targetSessions = 1,
                            strictBlocking = true
                        ).normalized()
                        val restored = PomodoroSession(
                            id = 1,
                            endTime = endTime,
                            durationMillis = remaining,
                            isActive = true,
                            isBreak = false,
                            isBlockingEnabled = true
                        )
                        val restoredRuntime = PomodoroCycleRuntime(
                            active = true,
                            phase = PomodoroPhase.FOCUS,
                            completedFocusSessions = 0,
                            config = config,
                            intervalEndTime = endTime,
                            intervalDurationMillis = remaining
                        )
                        dao.insertOrUpdate(restored)
                        planStore.saveRuntime(restoredRuntime)
                        _cycleState.value = restoredRuntime
                        restoreSessionLocked(restored, restoredRuntime)
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

        if (runtime.config.strictBlocking && !runtime.config.silenceNotifications) {
            notificationController.captureCurrentFilter()
        }
        reconcileRestoredBlockingState(session)
        applyNotificationPolicyForInterval(runtime.config)

        PomodoroForegroundService.start(context)
        if (session.isBlockingEnabled) {
            StrictPomodoroLock.save(context, session.endTime, session.durationMillis)
            PomodoroForegroundService.scheduleWatchdogAlarm(context)
            launchStrictLockActivity()
        } else {
            StrictPomodoroLock.clear(context)
            PomodoroForegroundService.cancelWatchdogAlarm(context)
        }
        startTicker()
    }

    private suspend fun reconcileRestoredBlockingState(session: PomodoroSession) {
        if (!session.isBlockingEnabled) {
            clearLegacyPomodoroBlockingLocked()
            return
        }

        val now = System.currentTimeMillis()
        val existingStrictSession = database.blockSessionDao()
            .getAllActiveSessionsStatic()
            .any { blockSession ->
                blockSession.sessionType == BlockSessionType.POMODORO &&
                    blockSession.isBlockingEnabled &&
                    (blockSession.endTime ?: 0L) > now
            }

        if (!existingStrictSession) {
            clearLegacyPomodoroBlockingLocked()
            val remaining = (session.endTime - now).coerceAtLeast(1L)
            sessionManager.startPomodoroSession(remaining, true)
            awaitStrictPomodoroEnforcement()
        } else {
            sessionManager.checkAndEnforceStrict()
        }
    }

    /**
     * Remove somente o registro de bloqueio POMODORO do motor legado e reconcilia
     * as políticas. Não toca no PomodoroForegroundService: ele pertence ao plano
     * inteiro e deve sobreviver continuamente às transições foco/pausa.
     */
    private suspend fun clearLegacyPomodoroBlockingLocked() {
        database.blockSessionDao().deactivateActiveSessionsByType(BlockSessionType.POMODORO)
        StrictPomodoroLock.clear(context)
        sessionManager.checkAndEnforceStrict()
    }

    private suspend fun awaitStrictPomodoroEnforcement() {
        val deadline = android.os.SystemClock.elapsedRealtime() + STRICT_ARM_TIMEOUT_MILLIS
        while (true) {
            val now = System.currentTimeMillis()
            val armed = database.blockSessionDao()
                .getAllActiveSessionsStatic()
                .any { blockSession ->
                    blockSession.sessionType == BlockSessionType.POMODORO &&
                        blockSession.isBlockingEnabled &&
                        (blockSession.endTime ?: 0L) > now
                }
            if (armed) {
                sessionManager.checkAndEnforceStrict()
                return
            }
            check(android.os.SystemClock.elapsedRealtime() < deadline) {
                "O bloqueio rigoroso não pôde ser armado a tempo"
            }
            delay(STRICT_ARM_POLL_MILLIS)
        }
    }

    private fun applyNotificationPolicyForInterval(config: PomodoroPlanConfig) {
        if (config.silenceNotifications) {
            if (!notificationController.apply(config)) {
                FocusGuardLogger.log(
                    "PomodoroManager",
                    "Não Perturbe não pôde ser reaplicado neste intervalo"
                )
            }
        } else if (config.strictBlocking) {
            notificationController.restoreForActivePlan()
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
                if (session.isBlockingEnabled) {
                    StrictPomodoroLock.save(context, session.endTime, session.durationMillis)
                }
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
        check(!normalized.strictBlocking || !FocusModeStore.isActive(context)) {
            "O Pomodoro rigoroso não pode substituir um Modo Foco ativo"
        }
        check(!normalized.strictBlocking || protectionPermissionGate.read().isReady) {
            "Todas as permissões de proteção são necessárias para o Pomodoro com bloqueio"
        }
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

    suspend fun startSession(
        durationMinutes: Int,
        isBreak: Boolean = false,
        isBlockingEnabled: Boolean = true
    ) {
        require(durationMinutes in 1..24 * 60) { "Duração do Pomodoro inválida" }
        val base = planStore.loadConfig()
        val config = if (isBreak) {
            base.copy(
                shortBreakMinutes = durationMinutes,
                targetSessions = 1,
                strictBlocking = false
            )
        } else {
            base.copy(
                focusMinutes = durationMinutes,
                targetSessions = 1,
                strictBlocking = isBlockingEnabled
            )
        }.normalized()

        if (!isBreak) {
            startPlan(config)
            return
        }

        finishMutex.withLock {
            cleanupAllStateLocked(emitFinished = false, cancelAlarm = true)
            val runtime = PomodoroCycleRuntime(
                active = true,
                phase = PomodoroPhase.SHORT_BREAK,
                completedFocusSessions = 0,
                config = config,
                intervalEndTime = 0L,
                intervalDurationMillis = 0L
            )
            planStore.saveRuntime(runtime)
            _cycleState.value = runtime
            startIntervalLocked(
                phase = PomodoroPhase.SHORT_BREAK,
                config = config,
                ensureForegroundService = true
            )
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
        val blocking = phase == PomodoroPhase.FOCUS && config.strictBlocking

        val session = PomodoroSession(
            id = 1,
            endTime = endTime,
            durationMillis = durationMillis,
            isActive = true,
            isBreak = phase != PomodoroPhase.FOCUS,
            isBlockingEnabled = blocking
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

        if (config.strictBlocking && !config.silenceNotifications) {
            notificationController.captureCurrentFilter()
        }

        clearLegacyPomodoroBlockingLocked()
        if (blocking) {
            sessionManager.startPomodoroSession(durationMillis, true)
            awaitStrictPomodoroEnforcement()
        }

        applyNotificationPolicyForInterval(config)

        if (ensureForegroundService) {
            PomodoroForegroundService.start(context)
        }
        if (blocking) {
            StrictPomodoroLock.save(context, endTime, durationMillis)
            PomodoroForegroundService.scheduleWatchdogAlarm(context)
        } else {
            StrictPomodoroLock.clear(context)
            PomodoroForegroundService.cancelWatchdogAlarm(context)
        }

        notifyBlockingChanged()
        FocusModeNotificationService.requestRefresh(context)
        startTicker()
        if (blocking) launchStrictLockActivity()
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
        StrictPomodoroLock.clear(context)
        PomodoroForegroundService.cancelWatchdogAlarm(context)
        _currentSession.value = null
        _timeLeftMillis.value = 0L
        clearLegacyPomodoroBlockingLocked()
        notifyBlockingChanged()

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
                    ensureForegroundService = false
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
                    ensureForegroundService = false
                )
            }
        }
    }

    suspend fun stopSession() {
        finishMutex.withLock {
            cleanupAllStateLocked(emitFinished = false, cancelAlarm = true)
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
        StrictPomodoroLock.clear(context)
        _currentSession.value = null
        _timeLeftMillis.value = 0L
        _cycleState.value = null
        planStore.clearRuntime()
        clearLegacyPomodoroBlockingLocked()
        PomodoroForegroundService.stop(context)
        notificationController.restore()
        notifyBlockingChanged()
        FocusModeNotificationService.requestRefresh(context)
        if (emitFinished) _onSessionFinished.tryEmit(Unit)
    }

    fun isPomodoroActive(): Boolean {
        val runtime = _cycleState.value ?: planStore.readRuntime()
        return runtime?.active == true ||
            (_currentSession.value?.isActive == true &&
                (_currentSession.value?.endTime ?: 0L) > System.currentTimeMillis()) ||
            StrictPomodoroLock.isActive(context)
    }

    private fun notifyBlockingChanged() {
        context.sendBroadcast(
            Intent(BlockingAccessibilityService.ACTION_REFRESH_BLOCKING)
                .setPackage(context.packageName)
        )
    }

    private fun launchStrictLockActivity() {
        val intent = Intent(context, PomodoroLockActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                FocusGuardLogger.logError(
                    "PomodoroManager",
                    "Falha ao abrir bloqueio rigoroso",
                    it
                )
            }
    }
}
