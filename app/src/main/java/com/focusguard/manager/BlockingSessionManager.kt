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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit
import java.util.Calendar
import java.util.Locale
import com.focusguard.admin.DeviceOwnerManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import com.focusguard.receiver.SessionExpirationReceiver

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

    fun startPasswordSession(
        isFixed24h: Boolean,
        startHour: Int = 0, startMinute: Int = 0, endHour: Int = 0, endMinute: Int = 0,
        daysOfWeek: String = "",
        apps: List<String>,
        sites: List<String>
    ) {
        scope.launch {
            try {
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
                database.sessionAppCrossRefDao().insertAll(apps.map { SessionAppCrossRef(sessionId, it) })
                database.sessionWebsiteCrossRefDao().insertAll(sites.map { SessionWebsiteCrossRef(sessionId, it) })

                checkAndEnforce()
                com.focusguard.utils.FocusGuardLogger.log("SessionManager", "Sessão por senha criada. Apps: ${apps.size}, Sites: ${sites.size}")
                withContext(Dispatchers.Main) { Toast.makeText(context, "Bloqueio por Senha iniciado.", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                com.focusguard.utils.FocusGuardLogger.logError("SessionManager", "Erro ao criar sessão por senha", e)
                withContext(Dispatchers.Main) { Toast.makeText(context, "Erro: ${e.message}", Toast.LENGTH_SHORT).show() }
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
                database.sessionAppCrossRefDao().insertAll(apps.map { SessionAppCrossRef(sessionId, it) })
                database.sessionWebsiteCrossRefDao().insertAll(sites.map { SessionWebsiteCrossRef(sessionId, it) })

                checkAndEnforce()
                scheduleExpirationAlarm(sessionId, endMillis)
                com.focusguard.utils.FocusGuardLogger.log("SessionManager", "Sessão de tempo criada: $days dias, $hours horas. Apps: ${apps.size}, Sites: ${sites.size}")
                withContext(Dispatchers.Main) { Toast.makeText(context, "Bloqueio por Tempo iniciado.", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                com.focusguard.utils.FocusGuardLogger.logError("SessionManager", "Erro ao criar sessão de tempo", e)
                withContext(Dispatchers.Main) { Toast.makeText(context, "Erro: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    suspend fun hasTimeSession(): Boolean {
        return database.blockSessionDao().getAllActiveSessions().any { it.sessionType == "TIME" }
    }

    fun appendToTimeSession(addedDays: Int, addedHours: Int, additionalApps: List<String>, additionalSites: List<String>) {
        scope.launch {
            val sessions = database.blockSessionDao().getAllActiveSessions().filter { it.sessionType == "TIME" }
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
                scheduleExpirationAlarm(session.id, newEndTime)
                com.focusguard.utils.FocusGuardLogger.log("SessionManager", "Tempo e/ou apps adicionados à sessão. Adicionado: $addedDays dias, $addedHours horas")
                withContext(Dispatchers.Main) { Toast.makeText(context, "Tempo e/ou apps adicionados.", Toast.LENGTH_LONG).show() }
            }
        }
    }

    fun endPasswordSessions() {
        scope.launch {
            try {
                val sessions = database.blockSessionDao().getAllActiveSessions().filter { it.sessionType == "PASSWORD" }
                for (session in sessions) {
                    database.blockSessionDao().updateBlockSession(session.copy(isActive = false))
                }
                checkAndEnforce()
                com.focusguard.utils.FocusGuardLogger.log("SessionManager", "Bloqueios por Senha encerrados manualmente")
                withContext(Dispatchers.Main) { Toast.makeText(context, "Bloqueios por Senha encerrados", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                com.focusguard.utils.FocusGuardLogger.logError("SessionManager", "Erro ao encerrar bloqueios por senha", e)
                withContext(Dispatchers.Main) { Toast.makeText(context, "Falha: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    suspend fun checkAndEnforce(appsToForceUnblock: List<String> = emptyList()) {
        val sessions = database.blockSessionDao().getAllActiveSessions()
        val enforcingSessions = sessions.filter { isCurrentlyInBlockingWindow(it) }

        if (enforcingSessions.isEmpty()) {
            val allActiveApps = database.sessionAppCrossRefDao().getAppsForSessions(sessions.map { it.id })
            // Combine apps from active but not enforcing sessions with apps we were told to unblock
            val toUnblock = (allActiveApps + appsToForceUnblock).toSet().toList()
            deviceOwnerManager.unblockApps(toUnblock)
            deviceOwnerManager.clearBlockingPolicies()
            deviceOwnerManager.clearWebsiteRestrictions()
        } else {
            val enforcingIds = enforcingSessions.map { it.id }
            val appsToBlock = database.sessionAppCrossRefDao().getAppsForSessions(enforcingIds)
            val sitesToBlock = database.sessionWebsiteCrossRefDao().getWebsitesForSessions(enforcingIds)
            
            // Even if some sessions expired, we only unblock apps that aren't in ANY enforcing session
            if (appsToForceUnblock.isNotEmpty()) {
                val stillBlocked = appsToBlock.toSet()
                val actuallyUnblock = appsToForceUnblock.filter { !stillBlocked.contains(it) }
                if (actuallyUnblock.isNotEmpty()) {
                    deviceOwnerManager.unblockApps(actuallyUnblock)
                }
            }
            
            deviceOwnerManager.blockApps(appsToBlock)
            deviceOwnerManager.enforceWebsiteRestrictions(sitesToBlock)
            deviceOwnerManager.enforceBlockingPolicies()
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

        val isInTimeRange = if (startVal < endVal) {
            currentTimeVal in startVal until endVal
        } else if (startVal > endVal) {
            currentTimeVal >= startVal || currentTimeVal < endVal
        } else {
            // startVal == endVal: Se não for Fixed24h, mas os horários forem iguais,
            // tratamos como um bloqueio de 24h para aquele dia.
            true
        }

        if (!isInTimeRange) return false

        // Day of week check: if it's "overnight" and we are after midnight, 
        // the "logical day" for the block is actually yesterday.
        if (session.recurringDaysOfWeek.isNotEmpty()) {
            val logicalDayCal = now.clone() as Calendar
            if (isAfterMidnightBeforeEnd) {
                logicalDayCal.add(Calendar.DAY_OF_YEAR, -1)
            }
            val logicalDayOfWeek = logicalDayCal.get(Calendar.DAY_OF_WEEK).toString()
            val allowedDays = session.recurringDaysOfWeek.split(",").map { it.trim() }
            if (!allowedDays.contains(logicalDayOfWeek)) return false
        }

        return true
    }

    suspend fun getActiveSessions(): List<BlockSession> {
        return try {
            val sessions = database.blockSessionDao().getAllActiveSessions()
            val expiredSessions = mutableListOf<BlockSession>()
            val validSessions = mutableListOf<BlockSession>()

            for (session in sessions) {
                if (session.sessionType == "TIME" && session.endTime != null && System.currentTimeMillis() >= session.endTime) {
                    expiredSessions.add(session)
                } else {
                    validSessions.add(session)
                }
            }

            if (expiredSessions.isNotEmpty()) {
                // ATOMICIDADE: Primeiro coletamos os apps que devem ser desbloqueados
                val expiredIds = expiredSessions.map { it.id }
                val appsFromExpired = database.sessionAppCrossRefDao().getAppsForSessions(expiredIds)
                
                // Forçamos o checkAndEnforce a considerar esses apps para desbloqueio
                // ANTES de marcar como inativo no DB (para que checkAndEnforce os veja se necessário)
                // Na verdade, passamos explicitamente para garantir que unblockApps seja chamado.
                checkAndEnforce(appsFromExpired)

                // Somente após o comando de unblock (enviado via checkAndEnforce), atualizamos o DB
                for (session in expiredSessions) {
                    database.blockSessionDao().updateBlockSession(session.copy(isActive = false))
                }
                com.focusguard.utils.FocusGuardLogger.log("SessionManager", "${expiredSessions.size} sessões expiradas processadas atomicamente.")
            }

            validSessions
        } catch (e: Exception) {
            com.focusguard.utils.FocusGuardLogger.logError("SessionManager", "Erro em getActiveSessions", e)
            emptyList()
        }
    }

    suspend fun isBlockingActive(): Boolean {
        val sessions = getActiveSessions()
        return sessions.any { isCurrentlyInBlockingWindow(it) }
    }

    suspend fun hasRegisteredSession(): Boolean {
        return getActiveSessions().isNotEmpty()
    }

    suspend fun getSessionDetails(): String {
        return try {
            val sessions = getActiveSessions()
            if (sessions.isEmpty()) return "Nenhuma sessão ativa"
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
        } catch (e: Exception) {
            "Erro ao recuperar detalhes"
        }
    }

    fun getActiveSessionsFlow(): Flow<List<BlockSession>> {
        return database.blockSessionDao().getAllActiveSessionsFlow()
    }

    fun getBlockedAppsFlow(): Flow<List<String>> {
        return database.sessionAppCrossRefDao().getAppsForActiveSessionsFlow()
    }

    fun getBlockedWebsitesFlow(): Flow<List<String>> {
        return database.sessionWebsiteCrossRefDao().getWebsitesForActiveSessionsFlow()
    }

    private fun scheduleExpirationAlarm(sessionId: Int, endTime: Long) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, SessionExpirationReceiver::class.java).apply {
                action = "com.focusguard.ACTION_SESSION_EXPIRED"
                putExtra("SESSION_ID", sessionId)
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context, 
                sessionId, 
                intent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                endTime,
                pendingIntent
            )
            com.focusguard.utils.FocusGuardLogger.log("SessionManager", "Alarme de expiração agendado para a sessão $sessionId em $endTime")
        } catch (e: Exception) {
            com.focusguard.utils.FocusGuardLogger.logError("SessionManager", "Falha ao agendar alarme", e)
        }
    }
}
