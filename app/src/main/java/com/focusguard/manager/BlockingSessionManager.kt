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
import java.util.concurrent.TimeUnit
import java.util.Calendar
import java.util.Locale
import com.focusguard.admin.DeviceOwnerManager

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

    fun startTimerSession(days: Int, hours: Int, blockedAppsCount: Int, blockedWebsitesCount: Int) {
        scope.launch {
            try {
                val startMillis = System.currentTimeMillis()
                val endMillis = startMillis + TimeUnit.DAYS.toMillis(days.toLong()) + TimeUnit.HOURS.toMillis(hours.toLong())

                val session = BlockSession(
                    startTime = startMillis,
                    endTime = endMillis,
                    isActive = true,
                    isRecurring = false,
                    blockedAppsCount = blockedAppsCount,
                    blockedWebsitesCount = blockedWebsitesCount
                )
                
                val sessionId = database.blockSessionDao().insertNewSession(session).toInt()
                
                val allApps = database.blockedAppDao().getAllBlockedApps()
                allApps.forEach { 
                    database.sessionAppCrossRefDao().insert(SessionAppCrossRef(sessionId, it.packageName))
                }
                val allSites = database.blockedWebsiteDao().getAllBlockedWebsites()
                allSites.forEach {
                    database.sessionWebsiteCrossRefDao().insert(SessionWebsiteCrossRef(sessionId, it.domain))
                }

                val appsToBlock = allApps.map { it.packageName }
                deviceOwnerManager.blockApps(appsToBlock)
                deviceOwnerManager.enforceBlockingPolicies()

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Sessão iniciada.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Erro: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun startRecurringSession(
        startHour: Int, startMinute: Int, endHour: Int, endMinute: Int,
        daysOfWeek: String, durationMonths: Int, blockedAppsCount: Int, blockedWebsitesCount: Int
    ) {
        scope.launch {
            try {
                val startMillis = System.currentTimeMillis()
                val cal = Calendar.getInstance()
                cal.add(Calendar.MONTH, durationMonths)
                val endMillis = cal.timeInMillis

                val session = BlockSession(
                    startTime = startMillis, endTime = endMillis, isActive = true, isRecurring = true,
                    recurringStartHour = startHour, recurringStartMinute = startMinute,
                    recurringEndHour = endHour, recurringEndMinute = endMinute,
                    recurringDaysOfWeek = daysOfWeek, recurringDurationMonths = durationMonths,
                    blockedAppsCount = blockedAppsCount, blockedWebsitesCount = blockedWebsitesCount
                )

                val sessionId = database.blockSessionDao().insertNewSession(session).toInt()

                val allApps = database.blockedAppDao().getAllBlockedApps()
                allApps.forEach { database.sessionAppCrossRefDao().insert(SessionAppCrossRef(sessionId, it.packageName)) }
                val allSites = database.blockedWebsiteDao().getAllBlockedWebsites()
                allSites.forEach { database.sessionWebsiteCrossRefDao().insert(SessionWebsiteCrossRef(sessionId, it.domain)) }

                if (isCurrentlyInBlockingWindow(session)) {
                    val appsToBlock = allApps.map { it.packageName }
                    deviceOwnerManager.blockApps(appsToBlock)
                    deviceOwnerManager.enforceBlockingPolicies()
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Sessão recorrente agendada.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Erro: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun isCurrentlyInBlockingWindow(session: BlockSession?): Boolean {
        if (session == null || !session.isActive) return false
        val now = Calendar.getInstance()

        if (!session.isRecurring) {
            return (session.endTime == null) || (now.timeInMillis < session.endTime)
        } else {
            if (session.endTime != null && now.timeInMillis > session.endTime) return false

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

    suspend fun getActiveSessions(): List<BlockSession> {
        return try {
            val sessions = database.blockSessionDao().getAllActiveSessions()
            val validSessions = mutableListOf<BlockSession>()
            val appsToUnblock = mutableSetOf<String>()

            for (session in sessions) {
                if (session.endTime != null && System.currentTimeMillis() >= session.endTime) {
                    val expiredSession = session.copy(isActive = false)
                    database.blockSessionDao().updateBlockSession(expiredSession)
                    val sessionApps = database.sessionAppCrossRefDao().getAppsForSessions(listOf(session.id))
                    appsToUnblock.addAll(sessionApps)
                } else {
                    validSessions.add(session)
                }
            }

            if (appsToUnblock.isNotEmpty()) {
                val validSessionIds = validSessions.map { it.id }
                val stillBlockedApps = if (validSessionIds.isNotEmpty()) database.sessionAppCrossRefDao().getAppsForSessions(validSessionIds).toSet() else emptySet()

                val actuallyUnblock = appsToUnblock.subtract(stillBlockedApps)
                if (actuallyUnblock.isNotEmpty()) {
                    deviceOwnerManager.unblockApps(actuallyUnblock.toList())
                }
                
                if (validSessions.isEmpty()) {
                    deviceOwnerManager.clearBlockingPolicies()
                } else {
                    deviceOwnerManager.enforceBlockingPolicies()
                }
            }
            validSessions
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getEarliestEndingSession(): BlockSession? {
        val sessions = getActiveSessions()
        return sessions.filter { !it.isRecurring && it.endTime != null }.minByOrNull { it.endTime!! }
    }

    suspend fun getRemainingDays(): Int {
        return try {
            val session = getEarliestEndingSession() ?: return 0
            val endTime = session.endTime ?: return 0
            val remainingMillis = endTime - System.currentTimeMillis()
            if (remainingMillis <= 0) return 0
            TimeUnit.MILLISECONDS.toDays(remainingMillis).toInt()
        } catch (e: Exception) {
            0
        }
    }

    suspend fun getRemainingHours(): Int {
        return try {
            val session = getEarliestEndingSession() ?: return 0
            val endTime = session.endTime ?: return 0
            val remainingMillis = endTime - System.currentTimeMillis()
            if (remainingMillis <= 0) return 0
            TimeUnit.MILLISECONDS.toHours(remainingMillis).toInt() % 24
        } catch (e: Exception) {
            0
        }
    }

    suspend fun getRemainingTimeFormatted(): String {
        return try {
            val sessions = getActiveSessions()
            if (sessions.isEmpty()) return "Nenhuma sessão ativa"
            val session = getEarliestEndingSession()
            if (session == null) {
                if (sessions.any { it.isRecurring }) return "Modo Recorrente Ativo"
                return "Sessão sem tempo definido"
            }
            
            val endTime = session.endTime ?: return "Sessão sem tempo"
            val remainingMillis = endTime - System.currentTimeMillis()
            if (remainingMillis <= 0) return "Sessão encerrada"

            val days = TimeUnit.MILLISECONDS.toDays(remainingMillis)
            val hours = TimeUnit.MILLISECONDS.toHours(remainingMillis) % 24
            val minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMillis) % 60

            buildString {
                if (days > 0) append("${days}d ")
                if (hours > 0) append("${hours}h ")
                if (minutes > 0) append("${minutes}min")
                if (isEmpty()) append("Menos de um minuto")
            }
        } catch (e: Exception) {
            "Erro ao calcular tempo"
        }
    }

    suspend fun isBlockingActive(): Boolean {
        val sessions = getActiveSessions()
        return sessions.any { isCurrentlyInBlockingWindow(it) }
    }

    suspend fun hasRegisteredSession(): Boolean {
        return getActiveSessions().isNotEmpty()
    }

    fun endBlockingSession() {
        scope.launch {
            try {
                val sessions = getActiveSessions()
                if (sessions.isNotEmpty()) {
                    for (session in sessions) {
                        val endedSession = session.copy(isActive = false)
                        database.blockSessionDao().updateBlockSession(endedSession)
                    }
                    val allBlockedApps = database.sessionAppCrossRefDao().getAppsForSessions(sessions.map { it.id })
                    deviceOwnerManager.unblockApps(allBlockedApps)
                    deviceOwnerManager.clearBlockingPolicies()

                    withContext(Dispatchers.Main) { Toast.makeText(context, "Todas as sessões encerradas", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(context, "Falha: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    suspend fun getSessionDetails(): String {
        return try {
            val sessions = getActiveSessions()
            if (sessions.isEmpty()) return "Nenhuma sessão ativa"
            val dateFormatter = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

            buildString {
                appendLine("=== Sessões Ativas (${sessions.size}) ===")
                sessions.forEachIndexed { index, session ->
                    appendLine("Sessão #${index + 1}")
                    if (session.isRecurring) {
                        appendLine("Modo: RECORRENTE")
                        appendLine("Entre: ${String.format(Locale.getDefault(), "%02d:%02d", session.recurringStartHour, session.recurringStartMinute)} e ${String.format(Locale.getDefault(), "%02d:%02d", session.recurringEndHour, session.recurringEndMinute)}")
                    } else {
                        appendLine("Modo: SESSÃO ÚNICA")
                        if (session.endTime != null) appendLine("Fim: ${dateFormatter.format(session.endTime)}")
                    }
                    appendLine("---")
                }
            }
        } catch (e: Exception) {
            "Erro ao recuperar detalhes"
        }
    }

    suspend fun getSessionsHistory(): List<BlockSession> {
        return try {
            database.blockSessionDao().getAllSessions()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
