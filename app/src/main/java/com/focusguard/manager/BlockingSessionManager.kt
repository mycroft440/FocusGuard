package com.focusguard.manager

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.room.withTransaction
import com.focusguard.R
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.database.AppDatabase
import com.focusguard.database.BlockSession
import com.focusguard.database.SessionAppCrossRef
import com.focusguard.database.SessionWebsiteCrossRef
import com.focusguard.receiver.BlockingScheduleCalculator
import com.focusguard.receiver.BlockingScheduleReceiver
import com.focusguard.service.BlockingAccessibilityService
import com.focusguard.service.PomodoroForegroundService
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.WebsiteBlocker
import com.focusguard.utils.WebsiteUsageLimitPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class BlockingSessionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    enum class EndSessionResult {
        ENDED,
        NOT_FOUND,
        POMODORO_NOT_REVOCABLE,
        TIME_NOT_REVOCABLE,
        FAILED
    }

    companion object {
        @Volatile
        private var legacyInstance: BlockingSessionManager? = null

        fun getInstance(context: Context): BlockingSessionManager {
            return try {
                val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    BlockingSessionManagerEntryPoint::class.java
                )
                entryPoint.blockingSessionManager()
            } catch (error: Exception) {
                FocusGuardLogger.logError(
                    "BlockingSessionManager",
                    "Hilt indisponível; usando singleton legado",
                    error
                )
                synchronized(this) {
                    legacyInstance ?: BlockingSessionManager(context.applicationContext)
                        .also { legacyInstance = it }
                }
            }
        }
    }

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface BlockingSessionManagerEntryPoint {
        fun blockingSessionManager(): BlockingSessionManager
    }

    private val database = AppDatabase.getDatabase(context)
    private val deviceOwnerManager = DeviceOwnerManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val enforcementMutex = Mutex()

    val activeSessionsFlow: Flow<List<BlockSession>> =
        database.blockSessionDao().getAllActiveSessions()

    val isBlockingActiveFlow: Flow<Boolean> = activeSessionsFlow.map { sessions ->
        sessions.any(::isCurrentlyInBlockingWindow)
    }

    val hasRegisteredSessionFlow: Flow<Boolean> = activeSessionsFlow.map { it.isNotEmpty() }

    val sessionDetailsFlow: Flow<String> = activeSessionsFlow.map { sessions ->
        if (sessions.isEmpty()) return@map "Nenhuma sessão ativa"
        val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        buildString {
            appendLine("=== Sessões Ativas (${sessions.size}) ===")
            sessions.forEachIndexed { index, session ->
                appendLine("Sessão #${index + 1} (${session.sessionType})")
                appendLine(if (session.isFixed24h) "Modo: FIXO 24H" else "Modo: AGENDADO")
                session.endTime?.let { appendLine("Término: ${formatter.format(it)}") }
                appendLine("---")
            }
        }
    }

    fun startPasswordSession(
        isFixed24h: Boolean,
        startHour: Int = 0,
        startMinute: Int = 0,
        endHour: Int = 0,
        endMinute: Int = 0,
        daysOfWeek: String = "",
        apps: List<String>,
        sites: List<String>
    ) {
        scope.launch {
            runCatching {
                database.withTransaction {
                    val session = BlockSession(
                        startTime = System.currentTimeMillis(),
                        isActive = true,
                        isRecurring = !isFixed24h,
                        recurringStartHour = startHour,
                        recurringStartMinute = startMinute,
                        recurringEndHour = endHour,
                        recurringEndMinute = endMinute,
                        recurringDaysOfWeek = daysOfWeek,
                        blockedAppsCount = apps.distinct().size,
                        blockedWebsitesCount = sites.distinct().size,
                        sessionType = "PASSWORD",
                        isFixed24h = isFixed24h
                    )
                    val sessionId = database.blockSessionDao().insertNewSession(session).toInt()
                    apps.distinct().forEach {
                        database.sessionAppCrossRefDao().insert(SessionAppCrossRef(sessionId, it))
                    }
                    sites.map(WebsiteBlocker::extractDomain).filter { it.isNotBlank() }.distinct()
                        .forEach {
                            database.sessionWebsiteCrossRefDao()
                                .insert(SessionWebsiteCrossRef(sessionId, it))
                        }
                }
                checkAndEnforce()
            }.onSuccess {
                showToast(R.string.bloqueio_por_senha_iniciado, Toast.LENGTH_LONG)
            }.onFailure {
                FocusGuardLogger.logError("BlockingSessionManager", "Erro ao iniciar sessão", it)
                showToast(R.string.erro_ao_iniciar_sessao, Toast.LENGTH_SHORT)
            }
        }
    }

    fun startTimeSession(
        days: Int,
        hours: Int,
        isFixed24h: Boolean,
        startHour: Int = 0,
        startMinute: Int = 0,
        endHour: Int = 0,
        endMinute: Int = 0,
        daysOfWeek: String = "",
        apps: List<String>,
        sites: List<String>
    ) {
        scope.launch {
            runCatching {
                database.withTransaction {
                    val startMillis = System.currentTimeMillis()
                    val duration = TimeUnit.DAYS.toMillis(days.toLong()) +
                        TimeUnit.HOURS.toMillis(hours.toLong())
                    require(duration > 0L) { "A duração da sessão deve ser positiva" }
                    val session = BlockSession(
                        startTime = startMillis,
                        endTime = startMillis + duration,
                        isActive = true,
                        isRecurring = !isFixed24h,
                        recurringStartHour = startHour,
                        recurringStartMinute = startMinute,
                        recurringEndHour = endHour,
                        recurringEndMinute = endMinute,
                        recurringDaysOfWeek = daysOfWeek,
                        blockedAppsCount = apps.distinct().size,
                        blockedWebsitesCount = sites.distinct().size,
                        sessionType = "TIME",
                        isFixed24h = isFixed24h
                    )
                    val sessionId = database.blockSessionDao().insertNewSession(session).toInt()
                    apps.distinct().forEach {
                        database.sessionAppCrossRefDao().insert(SessionAppCrossRef(sessionId, it))
                    }
                    sites.map(WebsiteBlocker::extractDomain).filter { it.isNotBlank() }.distinct()
                        .forEach {
                            database.sessionWebsiteCrossRefDao()
                                .insert(SessionWebsiteCrossRef(sessionId, it))
                        }
                }
                checkAndEnforce()
            }.onSuccess {
                showToast(R.string.bloqueio_por_tempo_iniciado, Toast.LENGTH_LONG)
            }.onFailure {
                FocusGuardLogger.logError(
                    "BlockingSessionManager",
                    "Erro ao iniciar sessão por tempo",
                    it
                )
                showToast(R.string.erro_ao_iniciar_sessao, Toast.LENGTH_SHORT)
            }
        }
    }

    fun startPomodoroSession(durationMs: Long, isBlockingEnabled: Boolean = true) {
        scope.launch {
            runCatching {
                require(durationMs > 0L) { "A duração do Pomodoro deve ser positiva" }
                database.withTransaction {
                    database.blockSessionDao().deactivateActiveSessionsByType("POMODORO")
                    val startMillis = System.currentTimeMillis()
                    database.blockSessionDao().insertNewSession(
                        BlockSession(
                            startTime = startMillis,
                            endTime = startMillis + durationMs,
                            isActive = true,
                            sessionType = "POMODORO",
                            isFixed24h = true,
                            isBlockingEnabled = isBlockingEnabled
                        )
                    )
                }
                checkAndEnforce()
            }.onSuccess {
                showToast(R.string.modo_pomodoro_ativado_foco_total, Toast.LENGTH_LONG)
            }.onFailure {
                FocusGuardLogger.logError(
                    "BlockingSessionManager",
                    "Erro ao iniciar Pomodoro",
                    it
                )
            }
        }
    }

    fun endPomodoroSession() {
        scope.launch { endPomodoroSessionAndWait() }
    }

    suspend fun endPomodoroSessionAndWait(): Boolean {
        return runCatching {
            val changed = database.blockSessionDao()
                .deactivateActiveSessionsByType("POMODORO") > 0
            StrictPomodoroLock.clear(context)
            PomodoroForegroundService.stop(context)
            checkAndEnforce()
            changed
        }.onFailure {
            FocusGuardLogger.logError(
                "BlockingSessionManager",
                "Erro ao encerrar Pomodoro",
                it
            )
        }.getOrDefault(false)
    }

    suspend fun hasTimeSession(): Boolean {
        return database.blockSessionDao().getAllActiveSessionsStatic()
            .any { it.sessionType == "TIME" }
    }

    fun appendToTimeSession(
        addedDays: Int,
        addedHours: Int,
        additionalApps: List<String>,
        additionalSites: List<String>
    ) {
        scope.launch {
            runCatching {
                val session = database.blockSessionDao().getAllActiveSessionsStatic()
                    .filter { it.sessionType == "TIME" }
                    .maxByOrNull { it.startTime }
                    ?: return@runCatching
                val addedMillis = TimeUnit.DAYS.toMillis(addedDays.toLong()) +
                    TimeUnit.HOURS.toMillis(addedHours.toLong())
                require(addedMillis >= 0L) { "Extensão inválida" }
                database.withTransaction {
                    additionalApps.distinct().forEach {
                        database.sessionAppCrossRefDao().insert(SessionAppCrossRef(session.id, it))
                    }
                    additionalSites.map(WebsiteBlocker::extractDomain)
                        .filter { it.isNotBlank() }
                        .distinct()
                        .forEach {
                            database.sessionWebsiteCrossRefDao()
                                .insert(SessionWebsiteCrossRef(session.id, it))
                        }
                    val apps = database.sessionAppCrossRefDao()
                        .getAppsForSessions(listOf(session.id))
                    val sites = database.sessionWebsiteCrossRefDao()
                        .getWebsitesForSessions(listOf(session.id))
                    database.blockSessionDao().updateBlockSession(
                        session.copy(
                            endTime = (session.endTime ?: System.currentTimeMillis()) + addedMillis,
                            blockedAppsCount = apps.distinct().size,
                            blockedWebsitesCount = sites.distinct().size
                        )
                    )
                }
                checkAndEnforce()
            }.onFailure {
                FocusGuardLogger.logError(
                    "BlockingSessionManager",
                    "Erro ao estender sessão",
                    it
                )
            }
        }
    }

    fun endPasswordSessions() {
        scope.launch {
            runCatching {
                database.blockSessionDao().deactivateActiveSessionsByType("PASSWORD")
                checkAndEnforce()
            }.onSuccess {
                showToast(R.string.bloqueios_por_senha_encerrados, Toast.LENGTH_SHORT)
            }.onFailure {
                FocusGuardLogger.logError(
                    "BlockingSessionManager",
                    "Erro ao encerrar sessões por senha",
                    it
                )
            }
        }
    }

    fun endSession(sessionId: Int) {
        scope.launch { endSessionAndWait(sessionId) }
    }

    suspend fun endSessionAndWait(sessionId: Int): EndSessionResult {
        return try {
            val session = database.blockSessionDao().getActiveSessionById(sessionId)
                ?: return EndSessionResult.NOT_FOUND
            when {
                session.sessionType == "POMODORO" -> EndSessionResult.POMODORO_NOT_REVOCABLE
                session.sessionType == "TIME" && isCurrentlyInBlockingWindow(session) ->
                    EndSessionResult.TIME_NOT_REVOCABLE
                database.blockSessionDao().deactivateSession(sessionId) == 0 ->
                    EndSessionResult.NOT_FOUND
                else -> {
                    checkAndEnforce()
                    EndSessionResult.ENDED
                }
            }
        } catch (error: Exception) {
            FocusGuardLogger.logError(
                "BlockingSessionManager",
                "Erro ao encerrar sessão $sessionId",
                error
            )
            EndSessionResult.FAILED
        }
    }

    suspend fun findResponsibleSessionId(
        blockedPackage: String?,
        blockedDomain: String?
    ): Int? {
        val sessions = database.blockSessionDao().getAllActiveSessionsStatic()
            .filter { it.sessionType == "PASSWORD" && isCurrentlyInBlockingWindow(it) }
            .sortedByDescending { it.startTime }

        for (session in sessions) {
            if (!blockedPackage.isNullOrBlank()) {
                val apps = database.sessionAppCrossRefDao()
                    .getAppsForSessions(listOf(session.id))
                if (blockedPackage in apps) return session.id
            }
            if (!blockedDomain.isNullOrBlank()) {
                val sites = database.sessionWebsiteCrossRefDao()
                    .getWebsitesForSessions(listOf(session.id))
                if (WebsiteBlocker.isUrlBlocked(blockedDomain, sites)) return session.id
            }
        }

        return sessions.singleOrNull()?.id
    }

    suspend fun checkAndEnforce() {
        enforcementMutex.withLock {
            try {
                val now = System.currentTimeMillis()
                val beforeExpiration = database.blockSessionDao().getAllActiveSessionsStatic()
                val expiredPomodoro = beforeExpiration.any {
                    it.sessionType == "POMODORO" && it.endTime != null && it.endTime <= now
                }
                database.blockSessionDao().deactivateExpiredSessions(now)
                if (expiredPomodoro) {
                    StrictPomodoroLock.clear(context)
                    PomodoroForegroundService.stop(context)
                }

                val activeSessions = database.blockSessionDao().getAllActiveSessionsStatic()
                val enforcingSessions = activeSessions.filter(::isCurrentlyInBlockingWindow)
                val enforcingIds = enforcingSessions.map { it.id }
                val strictPomodoro = enforcingSessions.any {
                    it.sessionType == "POMODORO" && it.isBlockingEnabled
                }

                setDoNotDisturbMode(strictPomodoro)

                val sessionApps = if (strictPomodoro) {
                    getInstalledUserAppsExceptPhone()
                } else {
                    getAppsForSessions(enforcingIds)
                }
                val sessionSites = getSitesForSessions(enforcingIds)

                val activeAppLimits = database.appUsageLimitDao().getAllActiveLimitsStatic()
                val limitApps = getExceededAppLimits(activeAppLimits, now)

                val activeWebsiteLimits = database.websiteUsageLimitDao().getAllStatic()
                    .filter { it.isEnabled }
                val policyExpirations = (
                    activeAppLimits.mapNotNull { limit ->
                        if (limit.lockMode.equals("TIME", ignoreCase = true)) {
                            limit.lockUntilTimestamp?.takeIf { it > now }
                        } else null
                    } + activeWebsiteLimits.mapNotNull { limit ->
                        if (limit.lockMode.equals("TIME", ignoreCase = true)) {
                            limit.lockUntilTimestamp?.takeIf { it > now }
                        } else null
                    }
                )
                val nextDailyReset = if (
                    activeAppLimits.isNotEmpty() || activeWebsiteLimits.isNotEmpty()
                ) {
                    BlockingScheduleCalculator.nextLocalMidnight(now)
                } else null
                BlockingScheduleReceiver.scheduleNext(
                    context = context,
                    sessions = activeSessions,
                    additionalBoundaries = policyExpirations + listOfNotNull(nextDailyReset),
                    nowMillis = now
                )
                val activeWebsiteDomains = WebsiteBlocker.normalizeDomains(
                    activeWebsiteLimits.map { it.domain }
                )
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
                val usageByWebsite = mutableMapOf<String, Long>()
                database.dailyUsageStatDao().getStatsForDateStatic(today).forEach { row ->
                    WebsiteBlocker.findMatchingRule(
                        row.identifier,
                        activeWebsiteDomains
                    )?.let { rule ->
                        usageByWebsite[rule] = (usageByWebsite[rule] ?: 0L) + row.timeSpentMs
                    }
                }
                val limitSites = activeWebsiteLimits.filter { limit ->
                    val normalizedDomain = WebsiteBlocker.extractDomain(limit.domain)
                    WebsiteUsageLimitPolicy.shouldBlock(
                        usedMillis = usageByWebsite[normalizedDomain] ?: 0L,
                        dailyLimitMinutes = limit.dailyLimitMinutes,
                        lockMode = limit.lockMode,
                        lockUntilTimestamp = limit.lockUntilTimestamp,
                        nowMillis = now
                    )
                }.map { WebsiteBlocker.extractDomain(it.domain) }

                val sitesToBlock = (sessionSites + limitSites)
                    .map(WebsiteBlocker::extractDomain)
                    .filter { it.isNotBlank() }
                    .distinct()
                val websiteAppsToBlock = WebsiteBlocker.appPackageDomainsFor(sitesToBlock)
                    .keys
                    .filter(::isPackageInstalled)
                val appsToBlock = (sessionApps + limitApps + websiteAppsToBlock)
                    .filter { it.isNotBlank() }
                    .distinct()

                val allSessionApps = getAppsForSessions(activeSessions.map { it.id })
                val allSessionSites = getSitesForSessions(activeSessions.map { it.id })
                val allKnownWebsiteApps = WebsiteBlocker.appPackageDomainsFor(
                    allSessionSites + activeWebsiteLimits.map { it.domain }
                ).keys.filter(::isPackageInstalled)
                val allKnownApps = (
                    allSessionApps +
                        activeAppLimits.map { it.packageName } +
                        allKnownWebsiteApps
                ).distinct()

                deviceOwnerManager.syncSuspendedApps(
                    allAppsInSessions = allKnownApps,
                    appsToBlockNow = appsToBlock,
                    allowedSystemApps = websiteAppsToBlock.toSet()
                )

                if (sitesToBlock.isEmpty()) {
                    deviceOwnerManager.clearWebsiteRestrictions()
                } else {
                    deviceOwnerManager.enforceWebsiteRestrictions(sitesToBlock)
                }

                if (enforcingSessions.isEmpty() && appsToBlock.isEmpty() && sitesToBlock.isEmpty()) {
                    deviceOwnerManager.clearBlockingPolicies()
                } else {
                    deviceOwnerManager.enforceBlockingPolicies()
                }
                deviceOwnerManager.applyNuclearShield()

                context.sendBroadcast(
                    Intent(BlockingAccessibilityService.ACTION_REFRESH_BLOCKING)
                        .setPackage(context.packageName)
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                FocusGuardLogger.logError(
                    "BlockingSessionManager",
                    "Erro ao reconciliar bloqueios",
                    error
                )
            }
        }
    }

    fun isCurrentlyInBlockingWindow(session: BlockSession?): Boolean {
        if (session == null || !session.isActive) return false
        val now = Calendar.getInstance()
        if (session.endTime != null && now.timeInMillis >= session.endTime) return false
        if (session.isFixed24h) return true

        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes = session.recurringStartHour * 60 + session.recurringStartMinute
        val endMinutes = session.recurringEndHour * 60 + session.recurringEndMinute
        val overnight = startMinutes > endMinutes
        val afterMidnight = overnight && currentMinutes < endMinutes
        val logicalDay = now.clone() as Calendar
        if (afterMidnight) logicalDay.add(Calendar.DAY_OF_YEAR, -1)

        if (session.recurringDaysOfWeek.isNotBlank()) {
            val allowedDays = session.recurringDaysOfWeek.split(',')
                .map(String::trim)
                .filter(String::isNotBlank)
                .toSet()
            if (logicalDay.get(Calendar.DAY_OF_WEEK).toString() !in allowedDays) return false
        }

        return if (startMinutes <= endMinutes) {
            currentMinutes in startMinutes until endMinutes
        } else {
            currentMinutes >= startMinutes || currentMinutes < endMinutes
        }
    }

    private suspend fun getExceededAppLimits(
        limits: List<com.focusguard.database.AppUsageLimit>,
        now: Long
    ): List<String> {
        if (limits.isEmpty()) return emptyList()
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE)
            as? android.app.usage.UsageStatsManager ?: return emptyList()
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val usage = usageStatsManager.queryAndAggregateUsageStats(startOfDay, now)

        return limits.filter { limit ->
            val usedMinutes = (usage[limit.packageName]?.totalTimeInForeground ?: 0L) / 60_000L
            usedMinutes >= limit.dailyLimitMinutes &&
                limit.preventOpeningAfterLimit &&
                WebsiteUsageLimitPolicy.isBlockingModeActive(
                    limit.lockMode,
                    limit.lockUntilTimestamp,
                    now
                )
        }.map { it.packageName }
    }

    private fun getInstalledUserAppsExceptPhone(): List<String> {
        val phoneWhitelist = setOf(
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.phone",
            "com.android.server.telecom",
            "com.samsung.android.dialer",
            "com.samsung.android.incallui"
        )
        return try {
            context.packageManager
                .getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { app ->
                    app.packageName != context.packageName &&
                        app.packageName !in phoneWhitelist &&
                        app.flags and ApplicationInfo.FLAG_SYSTEM == 0
                }
                .map { it.packageName }
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(
                "BlockingSessionManager",
                "Falha ao listar aplicativos instalados",
                error
            )
            emptyList()
        }
    }

    @Suppress("DEPRECATION")
    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0L)
                )
            } else {
                context.packageManager.getApplicationInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(
                "BlockingSessionManager",
                "Falha ao verificar pacote associado a site",
                error
            )
            false
        }
    }

    private suspend fun getAppsForSessions(ids: List<Int>): List<String> {
        return if (ids.isEmpty()) emptyList()
        else database.sessionAppCrossRefDao().getAppsForSessions(ids)
    }

    private suspend fun getSitesForSessions(ids: List<Int>): List<String> {
        return if (ids.isEmpty()) emptyList()
        else database.sessionWebsiteCrossRefDao().getWebsitesForSessions(ids)
    }

    private fun setDoNotDisturbMode(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            if (manager.isNotificationPolicyAccessGranted) {
                manager.setInterruptionFilter(
                    if (enabled) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    else NotificationManager.INTERRUPTION_FILTER_ALL
                )
            }
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(
                "BlockingSessionManager",
                "Erro ao alterar Não Perturbe",
                error
            )
        }
    }

    private suspend fun showToast(resourceId: Int, duration: Int) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, context.getString(resourceId), duration).show()
        }
    }
}
