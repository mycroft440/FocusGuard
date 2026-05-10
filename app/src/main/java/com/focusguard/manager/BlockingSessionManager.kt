package com.focusguard.manager

import android.content.Context
import android.widget.Toast
import com.focusguard.database.AppDatabase
import com.focusguard.database.BlockSession
import com.focusguard.database.SessionAppCrossRef
import com.focusguard.database.SessionWebsiteCrossRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import java.util.concurrent.TimeUnit
import java.util.Calendar
import java.util.Locale
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.service.BlockingAccessibilityService

class BlockingSessionManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: BlockingSessionManager? = null

        fun getInstance(context: Context): BlockingSessionManager {
            return instance ?: synchronized(this) {
                instance ?: BlockingSessionManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val database = AppDatabase.getDatabase(context)
    private val deviceOwnerManager = DeviceOwnerManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val activeSessionsFlow: Flow<List<BlockSession>> = database.blockSessionDao().getAllActiveSessions()

    val isBlockingActiveFlow: Flow<Boolean> = activeSessionsFlow.map { sessions ->
        sessions.any { isCurrentlyInBlockingWindow(it) }
    }

    val hasRegisteredSessionFlow: Flow<Boolean> = activeSessionsFlow.map { it.isNotEmpty() }

    val sessionDetailsFlow: Flow<String> = activeSessionsFlow.map { sessions ->
        if (sessions.isEmpty()) return@map "Nenhuma sessão ativa"
        val dateFormatter = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        buildString {
            appendLine("=== Sessões Ativas (${sessions.size}) ===")
            sessions.forEachIndexed { index, session ->
                appendLine("Sessão #${index + 1} (${session.sessionType})")
                if (session.isFixed24h) appendLine("Modo: FIXO 24H") else appendLine("Modo: AGENDADO")
                if (session.sessionType == "TIME" && session.endTime != null) appendLine("Término do Tempo: ${dateFormatter.format(session.endTime)}")
                appendLine("---")
            }
        }
    }

    fun startPasswordSession(
        isFixed24h: Boolean,
        startHour: Int = 0, startMinute: Int = 0, endHour: Int = 0, endMinute: Int = 0,
        daysOfWeek: String = "",
        apps: List<String>,
        sites: List<String>
    ) {
        scope.launch {
            try {
                database.withTransaction {
                    val startMillis = System.currentTimeMillis()
                    val session = BlockSession(
                        startTime = startMillis,
                        isActive = true,
                        isRecurring = !isFixed24h,
                        recurringStartHour = startHour,
                        recurringStartMinute = startMinute,
                        recurringEndHour = endHour,
                        recurringEndMinute = endMinute,
                        recurringDaysOfWeek = daysOfWeek,
                        blockedAppsCount = apps.size,
                        blockedWebsitesCount = sites.size,
                        sessionType = "PASSWORD",
                        isFixed24h = isFixed24h
                    )
                    val sessionId = database.blockSessionDao().insertNewSession(session).toInt()
                    apps.forEach { database.sessionAppCrossRefDao().insert(SessionAppCrossRef(sessionId, it)) }
                    sites.forEach { database.sessionWebsiteCrossRefDao().insert(SessionWebsiteCrossRef(sessionId, it)) }
                }
                checkAndEnforce()
                withContext(Dispatchers.Main) { Toast.makeText(context, "Bloqueio por Senha iniciado.", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                FocusGuardLogger.logError("Manager", "Erro ao iniciar sessão", e)
                withContext(Dispatchers.Main) { Toast.makeText(context, "Erro ao iniciar sessão", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    fun startTimeSession(
        days: Int, hours: Int,
        isFixed24h: Boolean,
        startHour: Int = 0, startMinute: Int = 0, endHour: Int = 0, endMinute: Int = 0,
        daysOfWeek: String = "",
        apps: List<String>,
        sites: List<String>
    ) {
        scope.launch {
            try {
                database.withTransaction {
                    val startMillis = System.currentTimeMillis()
                    val endMillis = startMillis + TimeUnit.DAYS.toMillis(days.toLong()) + TimeUnit.HOURS.toMillis(hours.toLong())
                    val session = BlockSession(
                        startTime = startMillis,
                        endTime = endMillis,
                        isActive = true,
                        isRecurring = !isFixed24h,
                        recurringStartHour = startHour,
                        recurringStartMinute = startMinute,
                        recurringEndHour = endHour,
                        recurringEndMinute = endMinute,
                        recurringDaysOfWeek = daysOfWeek,
                        blockedAppsCount = apps.size,
                        blockedWebsitesCount = sites.size,
                        sessionType = "TIME",
                        isFixed24h = isFixed24h
                    )
                    val sessionId = database.blockSessionDao().insertNewSession(session).toInt()
                    apps.forEach { database.sessionAppCrossRefDao().insert(SessionAppCrossRef(sessionId, it)) }
                    sites.forEach { database.sessionWebsiteCrossRefDao().insert(SessionWebsiteCrossRef(sessionId, it)) }
                }
                checkAndEnforce()
                withContext(Dispatchers.Main) { Toast.makeText(context, "Bloqueio por Tempo iniciado.", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                FocusGuardLogger.logError("Manager", "Erro ao iniciar sessão tempo", e)
                withContext(Dispatchers.Main) { Toast.makeText(context, "Erro ao iniciar sessão", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    fun startPomodoroSession(durationMs: Long, isBlockingEnabled: Boolean = true) {
        scope.launch {
            try {
                FocusGuardLogger.log("BlockingSessionManager", "Iniciando sessão de Pomodoro no sistema: ${durationMs/1000}s")
                database.withTransaction {
                    val startMillis = System.currentTimeMillis()
                    val endMillis = startMillis + durationMs
                    val session = BlockSession(
                        startTime = startMillis,
                        endTime = endMillis,
                        isActive = true,
                        sessionType = "POMODORO",
                        isFixed24h = true,
                        blockedWebsitesCount = 0,
                        isBlockingEnabled = isBlockingEnabled
                    )
                    database.blockSessionDao().insertNewSession(session)
                }
                checkAndEnforce()
                FocusGuardLogger.log("BlockingSessionManager", "Sessão de Pomodoro registrada com sucesso.")
                withContext(Dispatchers.Main) { Toast.makeText(context, "MODO POMODORO ATIVADO: Foco Total.", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                FocusGuardLogger.logError("BlockingSessionManager", "Erro ao iniciar pomodoro", e)
            }
        }
    }

    fun endPomodoroSession() {
        scope.launch {
            try {
                val sessions = database.blockSessionDao().getAllActiveSessions().first().filter { it.sessionType == "POMODORO" }
                for (session in sessions) database.blockSessionDao().updateBlockSession(session.copy(isActive = false))
                setDoNotDisturbMode(false)
                checkAndEnforce()
            } catch (e: Exception) {
                FocusGuardLogger.logError("BlockingSessionManager", "Erro ao encerrar sessão POMODORO", e)
            }
        }
    }

    suspend fun hasTimeSession(): Boolean {
        return database.blockSessionDao().getAllActiveSessions().first().any { it.sessionType == "TIME" }
    }

    fun appendToTimeSession(addedDays: Int, addedHours: Int, additionalApps: List<String>, additionalSites: List<String>) {
        scope.launch {
            val sessions = database.blockSessionDao().getAllActiveSessions().first().filter { it.sessionType == "TIME" }
            if (sessions.isNotEmpty()) {
                val session = sessions.first()
                val addedMillis = TimeUnit.DAYS.toMillis(addedDays.toLong()) + TimeUnit.HOURS.toMillis(addedHours.toLong())
                val newEndTime = (session.endTime ?: System.currentTimeMillis()) + addedMillis
                val existingApps = database.sessionAppCrossRefDao().getAppsForSessions(listOf(session.id))
                val existingSites = database.sessionWebsiteCrossRefDao().getWebsitesForSessions(listOf(session.id))
                database.blockSessionDao().updateBlockSession(session.copy(endTime = newEndTime, blockedAppsCount = (existingApps + additionalApps).toSet().size, blockedWebsitesCount = (existingSites + additionalSites).toSet().size))
                additionalApps.forEach { database.sessionAppCrossRefDao().insert(SessionAppCrossRef(session.id, it)) }
                additionalSites.forEach { database.sessionWebsiteCrossRefDao().insert(SessionWebsiteCrossRef(session.id, it)) }
                checkAndEnforce()
            }
        }
    }

    fun endPasswordSessions() {
        scope.launch {
            try {
                val sessions = database.blockSessionDao().getAllActiveSessions().first().filter { it.sessionType == "PASSWORD" }
                for (session in sessions) database.blockSessionDao().updateBlockSession(session.copy(isActive = false))
                checkAndEnforce()
                withContext(Dispatchers.Main) { Toast.makeText(context, "Bloqueios por Senha encerrados", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                FocusGuardLogger.logError("BlockingSessionManager", "Erro ao encerrar sessões PASSWORD", e)
            }
        }
    }

    fun endSession(sessionId: Int) {
        scope.launch {
            try {
                val sessions = database.blockSessionDao().getAllActiveSessions().first()
                val session = sessions.find { it.id == sessionId }
                if (session != null) {
                    if (session.sessionType == "POMODORO") {
                        withContext(Dispatchers.Main) { Toast.makeText(context, "O Pomodoro não pode ser interrompido!", Toast.LENGTH_LONG).show() }
                        return@launch
                    }
                    if (session.sessionType == "TIME" && isCurrentlyInBlockingWindow(session)) {
                        withContext(Dispatchers.Main) { Toast.makeText(context, "Bloqueios por tempo não podem ser revogados até o fim do período!", Toast.LENGTH_LONG).show() }
                        return@launch
                    }
                    database.blockSessionDao().updateBlockSession(session.copy(isActive = false))
                    checkAndEnforce()
                }
            } catch (e: Exception) {
                FocusGuardLogger.logError("BlockingSessionManager", "Erro ao encerrar sessão $sessionId", e)
            }
        }
    }

    suspend fun checkAndEnforce() {
        try {
            val allActiveSessions = database.blockSessionDao().getAllActiveSessions().first()
            for (session in allActiveSessions) {
                if (session.sessionType == "TIME" && session.endTime != null && System.currentTimeMillis() > session.endTime) {
                    database.blockSessionDao().updateBlockSession(session.copy(isActive = false))
                }
            }

            val allActiveSessionsUpdated = database.blockSessionDao().getAllActiveSessions().first()
            val enforcingSessions = allActiveSessionsUpdated.filter { isCurrentlyInBlockingWindow(it) }
            val enforcingIds = enforcingSessions.map { it.id }
            val isPomodoroActive = enforcingSessions.any { it.sessionType == "POMODORO" }

            val sessionAppsToBlock = if (isPomodoroActive) {
                val pomodoroSession = enforcingSessions.find { it.sessionType == "POMODORO" }
                if (pomodoroSession?.isBlockingEnabled == true) {
                    setDoNotDisturbMode(true)
                    val allApps = context.packageManager.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA).map { it.packageName }
                    val phoneWhitelist = listOf("com.android.dialer", "com.google.android.dialer", "com.android.phone", "com.android.server.telecom", "com.samsung.android.dialer", "com.samsung.android.incallui")
                    allApps.filter { pkg -> !phoneWhitelist.contains(pkg) && pkg != context.packageName }
                } else {
                    database.sessionAppCrossRefDao().getAppsForSessions(enforcingIds)
                }
            } else {
                database.sessionAppCrossRefDao().getAppsForSessions(enforcingIds)
            }

            val sitesToBlock = database.sessionWebsiteCrossRefDao().getWebsitesForSessions(enforcingIds)
            val limitAppsToBlock = mutableListOf<String>()
            val activeLimits = database.appUsageLimitDao().getAllActiveLimits().first()
            val now = System.currentTimeMillis()

            if (activeLimits.isNotEmpty()) {
                val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
                if (usageStatsManager != null) {
                    val cal = Calendar.getInstance()
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    val startOfDay = cal.timeInMillis
                    val stats = usageStatsManager.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, startOfDay, now)

                    activeLimits.forEach { limit ->
                        val stat = stats.find { it.packageName == limit.packageName }
                        val usageMinutes = (stat?.totalTimeInForeground ?: 0L) / 1000 / 60
                        if (usageMinutes >= limit.dailyLimitMinutes) {
                            when (limit.lockMode.uppercase(Locale.ROOT)) {
                                "WARNING" -> {
                                    FocusGuardLogger.log("BlockingSessionManager", "Limite WARNING atingido para ${limit.packageName}; aviso temporário sem bloqueio permanente.")
                                }
                                "PASSWORD" -> {
                                    limitAppsToBlock.add(limit.packageName)
                                }
                                "TIME" -> {
                                    val lockStillValid = limit.lockUntilTimestamp == null || limit.lockUntilTimestamp > now
                                    if (lockStillValid && limit.preventOpeningAfterLimit) {
                                        limitAppsToBlock.add(limit.packageName)
                                    }
                                }
                                else -> {
                                    if (limit.preventOpeningAfterLimit) limitAppsToBlock.add(limit.packageName)
                                }
                            }
                        }
                    }
                }
            }

            val allAppsToBlock = (sessionAppsToBlock + limitAppsToBlock).toSet().toList()
            val allAppsInAnySession = database.sessionAppCrossRefDao().getAppsForSessions(allActiveSessionsUpdated.map { it.id })
            val allKnownApps = (allAppsInAnySession + activeLimits.map { it.packageName }).toSet().toList()

            if (allAppsToBlock.isEmpty() && sitesToBlock.isEmpty()) {
                deviceOwnerManager.unblockApps(allKnownApps)
                deviceOwnerManager.clearBlockingPolicies()
                deviceOwnerManager.clearWebsiteRestrictions()
                // Garante que o Shield Permanente (Anti-Uninstall/SafeMode) continue ativo se for DO
                deviceOwnerManager.applyNuclearShield()
            } else {
                deviceOwnerManager.syncSuspendedApps(allKnownApps, allAppsToBlock)
                deviceOwnerManager.enforceWebsiteRestrictions(sitesToBlock)
                deviceOwnerManager.enforceBlockingPolicies()
            }

            context.sendBroadcast(android.content.Intent(BlockingAccessibilityService.ACTION_REFRESH_BLOCKING).setPackage(context.packageName))
        } catch (e: Exception) {
            FocusGuardLogger.log("Manager", "Erro no checkAndEnforce: ${e.message}")
        }
    }

    fun isCurrentlyInBlockingWindow(session: BlockSession?): Boolean {
        if (session == null || !session.isActive) return false
        val now = Calendar.getInstance()
        if (session.sessionType == "TIME" && session.endTime != null && now.timeInMillis > session.endTime) return false
        if (session.isFixed24h) return true
        val nowHour = now.get(Calendar.HOUR_OF_DAY)
        val nowMin = now.get(Calendar.MINUTE)
        val currentTimeVal = nowHour * 60 + nowMin
        val startVal = session.recurringStartHour * 60 + session.recurringStartMinute
        val endVal = session.recurringEndHour * 60 + session.recurringEndMinute
        val isOvernight = startVal > endVal
        val isAfterMidnightBeforeEnd = isOvernight && currentTimeVal < endVal
        val logicalDayCal = now.clone() as Calendar
        if (isAfterMidnightBeforeEnd) logicalDayCal.add(Calendar.DAY_OF_YEAR, -1)
        if (session.recurringDaysOfWeek.isNotEmpty()) {
            val logicalDayOfWeek = logicalDayCal.get(Calendar.DAY_OF_WEEK).toString()
            if (!session.recurringDaysOfWeek.split(",").map { it.trim() }.contains(logicalDayOfWeek)) return false
        }
        return if (startVal <= endVal) currentTimeVal in startVal until endVal else currentTimeVal >= startVal || currentTimeVal < endVal
    }

    private fun setDoNotDisturbMode(enabled: Boolean) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && notificationManager.isNotificationPolicyAccessGranted) {
                notificationManager.setInterruptionFilter(if (enabled) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY else android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        } catch (e: Exception) {
            FocusGuardLogger.logError("BlockingSessionManager", "Erro ao alterar DND", e)
        }
    }
}
