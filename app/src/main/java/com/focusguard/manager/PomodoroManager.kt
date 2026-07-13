package com.focusguard.manager

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import com.focusguard.database.AppDatabase
import com.focusguard.database.PomodoroSession
import com.focusguard.service.BlockingAccessibilityService
import com.focusguard.service.PomodoroForegroundService
import com.focusguard.ui.PomodoroLockActivity
import com.focusguard.utils.FocusGuardLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val finishMutex = Mutex()

    private val _currentSession = MutableStateFlow<PomodoroSession?>(null)
    val currentSession: StateFlow<PomodoroSession?> = _currentSession.asStateFlow()

    private val _timeLeftMillis = MutableStateFlow(0L)
    val timeLeftMillis: StateFlow<Long> = _timeLeftMillis.asStateFlow()

    private val _onSessionFinished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val onSessionFinished = _onSessionFinished.asSharedFlow()

    private var tickerJob: Job? = null

    init {
        loadSession()
    }

    private fun loadSession() {
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                val session = dao.getPomodoroSessionSync()
                when {
                    session?.isActive == true && session.endTime > now -> restoreSession(session)
                    session != null -> cleanupExpiredState()
                    StrictPomodoroLock.getEndTime(context) > now -> {
                        val endTime = StrictPomodoroLock.getEndTime(context)
                        val remaining = endTime - now
                        val restored = PomodoroSession(
                            id = 1,
                            endTime = endTime,
                            durationMillis = remaining,
                            isActive = true,
                            isBreak = false,
                            isBlockingEnabled = true
                        )
                        dao.insertOrUpdate(restored)
                        restoreSession(restored)
                        sessionManager.startPomodoroSession(remaining, true)
                    }
                    else -> cleanupExpiredState()
                }
                updateTimeLeft()
            } catch (error: Exception) {
                FocusGuardLogger.logError(
                    "PomodoroManager",
                    "Falha ao recuperar Pomodoro",
                    error
                )
                cleanupExpiredState()
            }
        }
    }

    private suspend fun restoreSession(session: PomodoroSession) {
        _currentSession.value = session
        if (session.isBlockingEnabled) {
            StrictPomodoroLock.save(context, session.endTime, session.durationMillis)
            PomodoroForegroundService.start(context)
            PomodoroForegroundService.scheduleWatchdogAlarm(context)
            launchStrictLockActivity()
        } else {
            StrictPomodoroLock.clear(context)
            PomodoroForegroundService.stop(context)
        }
        startTicker()
    }

    private suspend fun cleanupExpiredState() {
        stopTicker()
        dao.deleteSession()
        StrictPomodoroLock.clear(context)
        PomodoroForegroundService.stop(context)
        _currentSession.value = null
        _timeLeftMillis.value = 0L
        sessionManager.endPomodoroSessionAndWait()
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
                    scope.launch { stopSession(playSound = true) }
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

    suspend fun startSession(
        durationMinutes: Int,
        isBreak: Boolean = false,
        isBlockingEnabled: Boolean = true
    ) {
        require(durationMinutes in 1..24 * 60) { "Duração do Pomodoro inválida" }

        finishMutex.withLock {
            val durationMillis = durationMinutes * 60_000L
            val endTime = System.currentTimeMillis() + durationMillis
            val session = PomodoroSession(
                id = 1,
                endTime = endTime,
                durationMillis = durationMillis,
                isActive = true,
                isBreak = isBreak,
                isBlockingEnabled = isBlockingEnabled
            )

            dao.insertOrUpdate(session)
            _currentSession.value = session
            _timeLeftMillis.value = durationMillis

            if (isBlockingEnabled) {
                StrictPomodoroLock.save(context, endTime, durationMillis)
                PomodoroForegroundService.start(context)
                PomodoroForegroundService.scheduleWatchdogAlarm(context)
            } else {
                StrictPomodoroLock.clear(context)
                PomodoroForegroundService.stop(context)
            }

            sessionManager.startPomodoroSession(durationMillis, isBlockingEnabled)
            notifyBlockingChanged()
            startTicker()
            if (isBlockingEnabled) launchStrictLockActivity()
        }
    }

    suspend fun stopSession() {
        stopSession(playSound = true)
    }

    private suspend fun stopSession(playSound: Boolean) {
        finishMutex.withLock {
            val hadSession = _currentSession.value != null || dao.getPomodoroSessionSync() != null ||
                StrictPomodoroLock.getEndTime(context) > 0L

            stopTicker()
            dao.deleteSession()
            StrictPomodoroLock.clear(context)
            PomodoroForegroundService.stop(context)
            _currentSession.value = null
            _timeLeftMillis.value = 0L
            sessionManager.endPomodoroSessionAndWait()
            notifyBlockingChanged()

            if (hadSession) {
                if (playSound) playCompletionSound()
                _onSessionFinished.tryEmit(Unit)
            }
        }
    }

    fun isPomodoroActive(): Boolean {
        val session = _currentSession.value
        return session?.isActive == true && session.endTime > System.currentTimeMillis() ||
            StrictPomodoroLock.isActive(context)
    }

    private fun notifyBlockingChanged() {
        context.sendBroadcast(
            Intent(BlockingAccessibilityService.ACTION_REFRESH_BLOCKING)
                .setPackage(context.packageName)
        )
    }

    private fun playCompletionSound() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(context, uri)?.play()
        }.onFailure {
            FocusGuardLogger.logError(
                "PomodoroManager",
                "Falha ao tocar aviso do Pomodoro",
                it
            )
        }
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
