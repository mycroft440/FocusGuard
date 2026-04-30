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
    private val deviceOwnerManager = DeviceOwnerManager(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Flow-based Reactive API
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
                if (session.isFixed24h) {
                    appendLine("Modo: FIXO 24H")
                } else {
                    appendLine("Modo: AGENDADO")
                    appendLine("Entre: ${String.format(Locale.getDefault(), "%02d:%02d", session.recurringStartHour, session.recurringStartMinute)} e ${String.format(Locale.getDefault(), "%02d:%02d", session.recurringEndHour, session.recurringEndMinute)}")
                }
                if (session.sessionType == "TIME" && session.endTime != null) {
                    appendLine("Término do Tempo: ${dateFormatter.format(session.endTime)}")
                }
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
                        recurringStartHour = startHour, recurringStartMinute = startMinute,
                        recurringEndHour = endHour, recurringEndMinute = endMinute,
                        recurringDaysOfWeek = daysOfWeek, 
                        blockedAppsCount = apps.size, blockedWebsitesCount = sites.size,
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
                com.focusguard.utils.FocusGuardLogger.logError("Manager", "Erro ao iniciar sessão", e)
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
                        recurringStartHour = startHour, recurringStartMinute = startMinute,
                        recurringEndHour = endHour, recurringEndMinute = endMinute,
                        recurringDaysOfWeek = daysOfWeek,
                        blockedAppsCount = apps.size, blockedWebsitesCount = sites.size,
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
                com.focusguard.utils.FocusGuardLogger.logError("Manager", "Erro ao iniciar sessão tempo", e)
                withContext(Dispatchers.Main) { Toast.makeText(context, "Erro ao iniciar sessão", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    fun startPomodoroSession(durationMs: Long) {
        scope.launch {
            try {
                FocusGuardLogger.log("BlockingSessionManager", "Iniciando sessÃ£o de Pomodoro no sistema: ${durationMs/1000}s")
                database.withTransaction {
                    val startMillis = System.currentTimeMillis()
                    val endMillis = startMillis + durationMs

                    val session = BlockSession(
                        startTime = startMillis,
                        endTime = endMillis,
                        isActive = true,
                        sessionType = "POMODORO",
                        isFixed24h = true,
                        blockedAppsCount = 0,
                        blockedWebsitesCount = 0
                    )

                    database.blockSessionDao().insertNewSession(session)
                }

                checkAndEnforce()
                FocusGuardLogger.log("BlockingSessionManager", "SessÃ£o de Pomodoro registrada com sucesso.")
                withContext(Dispatchers.Main) { 
                    Toast.makeText(context, "MODO POMODORO ATIVADO: Foco Total.", Toast.LENGTH_LONG).show() 
                }
            } catch (e: Exception) {
                FocusGuardLogger.logError("BlockingSessionManager", "Erro ao iniciar pomodoro", e)
            }
        }
    }

    fun endPomodoroSession() {
        scope.launch {
            try {
                FocusGuardLogger.log("BlockingSessionManager", "Encerrando sessão POMODORO no BlockingSessionManager.")
                val sessions = database.blockSessionDao().getAllActiveSessions().first().filter { it.sessionType == "POMODORO" }
                for (session in sessions) {
                    database.blockSessionDao().updateBlockSession(session.copy(isActive = false))
                }
                checkAndEnforce()
                FocusGuardLogger.log("BlockingSessionManager", "Sessões POMODORO encerradas com sucesso no BlockSessionDao.")
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
            FocusGuardLogger.log("BlockingSessionManager", "Tentando adicionar tempo à sessão TIME")
            val sessions = database.blockSessionDao().getAllActiveSessions().first().filter { it.sessionType == "TIME" }
            if (sessions.isNotEmpty()) {
                val session = sessions.first()
                val addedMillis = TimeUnit.DAYS.toMillis(addedDays.toLong()) + TimeUnit.HOURS.toMillis(addedHours.toLong())
                val newEndTime = (session.endTime ?: System.currentTimeMillis()) + addedMillis
                
                val existingApps = database.sessionAppCrossRefDao().getAppsForSessions(listOf(session.id))
                val existingSites = database.sessionWebsiteCrossRefDao().getWebsitesForSessions(listOf(session.id))
                
                val finalAppCount = (existingApps + additionalApps).toSet().size
                val finalSiteCount = (existingSites + additionalSites).toSet().size

                val updatedSession = session.copy(
                    endTime = newEndTime,
                    blockedAppsCount = finalAppCount,
                    blockedWebsitesCount = finalSiteCount
                )
                database.blockSessionDao().updateBlockSession(updatedSession)

                additionalApps.forEach { database.sessionAppCrossRefDao().insert(SessionAppCrossRef(session.id, it)) }
                additionalSites.forEach { database.sessionWebsiteCrossRefDao().insert(SessionWebsiteCrossRef(session.id, it)) }

                checkAndEnforce()
                FocusGuardLogger.log("BlockingSessionManager", "Tempo adicionado com sucesso. Novo endTime: $newEndTime")
                withContext(Dispatchers.Main) { Toast.makeText(context, "Tempo e/ou apps adicionados.", Toast.LENGTH_LONG).show() }
            } else {
                FocusGuardLogger.log("BlockingSessionManager", "Nenhuma sessão TIME ativa encontrada para adicionar tempo.")
            }
        }
    }

    fun endPasswordSessions() {
        scope.launch {
            try {
                FocusGuardLogger.log("BlockingSessionManager", "Encerrando sessões PASSWORD")
                val sessions = database.blockSessionDao().getAllActiveSessions().first().filter { it.sessionType == "PASSWORD" }
                for (session in sessions) {
                    database.blockSessionDao().updateBlockSession(session.copy(isActive = false))
                }
                checkAndEnforce()
                FocusGuardLogger.log("BlockingSessionManager", "Sessões PASSWORD encerradas.")
                withContext(Dispatchers.Main) { Toast.makeText(context, "Bloqueios por Senha encerrados", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                FocusGuardLogger.logError("BlockingSessionManager", "Erro ao encerrar sessões PASSWORD", e)
                withContext(Dispatchers.Main) { Toast.makeText(context, "Falha: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    fun endSession(sessionId: Int) {
        scope.launch {
            try {
                FocusGuardLogger.log("BlockingSessionManager", "Tentando encerrar sessão ID: $sessionId")
                val sessions = database.blockSessionDao().getAllActiveSessions().first()
                val session = sessions.find { it.id == sessionId }
                if (session != null) {
                    if (session.sessionType == "POMODORO") {
                        FocusGuardLogger.log("BlockingSessionManager", "Tentativa de encerrar POMODORO negada (ID: $sessionId)")
                        withContext(Dispatchers.Main) { 
                            Toast.makeText(context, "O Pomodoro não pode ser interrompido!", Toast.LENGTH_LONG).show() 
                        }
                        return@launch
                    }
                    database.blockSessionDao().updateBlockSession(session.copy(isActive = false))
                    checkAndEnforce()
                    FocusGuardLogger.log("BlockingSessionManager", "Sessão $sessionId encerrada com sucesso.")
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Bloqueio encerrado", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) {
                FocusGuardLogger.logError("BlockingSessionManager", "Erro ao encerrar sessão $sessionId", e)
                withContext(Dispatchers.Main) { Toast.makeText(context, "Falha ao encerrar: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    suspend fun checkAndEnforce() {
        try {
            FocusGuardLogger.log("BlockingSessionManager", "Iniciando checkAndEnforce...")
            val sessions = database.blockSessionDao().getAllActiveSessions().first()
            val enforcingSessions = sessions.filter { isCurrentlyInBlockingWindow(it) }

            FocusGuardLogger.log("BlockingSessionManager", "Sessões ativas: ${sessions.size}, Sessões aplicando bloqueio agora: ${enforcingSessions.size}")

            val enforcingIds = enforcingSessions.map { it.id }
            
            val isPomodoroActive = enforcingSessions.any { it.sessionType == "POMODORO" }
            
            val sessionAppsToBlock = if (isPomodoroActive) {
                FocusGuardLogger.log("BlockingSessionManager", "Modo POMODORO detectado no enforce. Bloqueando TODOS os apps.")
                context.packageManager.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                    .map { it.packageName }
            } else {
                database.sessionAppCrossRefDao().getAppsForSessions(enforcingIds)
            }
            
            val sitesToBlock = database.sessionWebsiteCrossRefDao().getWebsitesForSessions(enforcingIds)

            // New: Check Daily Usage Limits
            val limitAppsToBlock = mutableListOf<String>()
            val activeLimits = database.appUsageLimitDao().getAllActiveLimits().first()
            
            if (activeLimits.isNotEmpty()) {
                val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
                if (usageStatsManager != null) {
                    val cal = Calendar.getInstance()
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    val startOfDay = cal.timeInMillis
                    
                    val stats = usageStatsManager.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, startOfDay, System.currentTimeMillis())
                    
                    activeLimits.forEach { limit ->
                        val stat = stats.find { it.packageName == limit.packageName }
                        val usageMinutes = (stat?.totalTimeInForeground ?: 0L) / 1000 / 60
                        if (usageMinutes >= limit.dailyLimitMinutes) {
                            limitAppsToBlock.add(limit.packageName)
                        }
                    }
                }
            }

            val allAppsToBlock = (sessionAppsToBlock + limitAppsToBlock).toSet().toList()
            val allAppsInAnySession = database.sessionAppCrossRefDao().getAppsForSessions(sessions.map { it.id })

            if (allAppsToBlock.isEmpty() && sitesToBlock.isEmpty()) {
                deviceOwnerManager.unblockApps(allAppsInAnySession)
                deviceOwnerManager.clearBlockingPolicies()
                deviceOwnerManager.clearWebsiteRestrictions()
            } else {
                // SOTA: Differential synchronization
                deviceOwnerManager.syncSuspendedApps(allAppsInAnySession, allAppsToBlock)
                deviceOwnerManager.enforceWebsiteRestrictions(sitesToBlock)
                deviceOwnerManager.enforceBlockingPolicies()
            }
            
            // Notify Accessibility Service to update immediately
            val intent = android.content.Intent("com.focusguard.ACTION_REFRESH_BLOCKING")
            intent.setPackage(context.packageName)
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            com.focusguard.utils.FocusGuardLogger.log("Manager", "Erro no checkAndEnforce: ${e.message}")
        }
    }

    fun isCurrentlyInBlockingWindow(session: BlockSession?): Boolean {
        if (session == null || !session.isActive) return false
        val now = Calendar.getInstance()

        if (session.sessionType == "TIME" && session.endTime != null && now.timeInMillis > session.endTime) {
            return false // Time session expired
        }

        if (session.isFixed24h) {
            return true
        }

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

        return if (startVal <= endVal) {
            currentTimeVal in startVal until endVal
        } else {
            currentTimeVal >= startVal || currentTimeVal < endVal
        }
    }
}