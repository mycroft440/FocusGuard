package com.focusguard.manager

import android.content.Context
import android.widget.Toast
import com.focusguard.database.AppDatabase
import com.focusguard.database.BlockSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.Calendar
import java.util.Locale
import com.focusguard.admin.DeviceOwnerManager

/**
 * Manager for blocking sessions with day-based countdown.
 * Handles both timer-based and recurring sessions.
 */
class BlockingSessionManager(private val context: Context) {

    private val database = AppDatabase.getDatabase(context)
    private val deviceOwnerManager = DeviceOwnerManager(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Start a new timer-based blocking session (Countdown).
     * @param days Number of days for the session
     * @param hours Number of hours for the session
     * @param blockedAppsCount Number of apps being blocked
     * @param blockedWebsitesCount Number of websites being blocked
     */
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
                // Deactivate all old sessions first
                database.blockSessionDao().deactivateAllSessions()
                database.blockSessionDao().insertBlockSession(session)
                
                val appsToBlock = database.blockedAppDao().getAllBlockedApps().map { it.packageName }
                deviceOwnerManager.blockApps(appsToBlock)

                deviceOwnerManager.enforceBlockingPolicies()

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Sessão de foco iniciada.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Erro ao iniciar sessão: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Start a new recurring blocking session.
     * @param startHour Hour to start blocking (0-23)
     * @param startMinute Minute to start blocking (0-59)
     * @param endHour Hour to end blocking (0-23)
     * @param endMinute Minute to end blocking (0-59)
     * @param daysOfWeek Comma-separated day numbers (1=Sunday, 7=Saturday)
     * @param durationMonths How many months the recurring session lasts
     * @param blockedAppsCount Number of apps being blocked
     * @param blockedWebsitesCount Number of websites being blocked
     */
    fun startRecurringSession(
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        daysOfWeek: String,
        durationMonths: Int,
        blockedAppsCount: Int,
        blockedWebsitesCount: Int
    ) {
        scope.launch {
            try {
                val startMillis = System.currentTimeMillis()
                val cal = Calendar.getInstance()
                cal.add(Calendar.MONTH, durationMonths)
                val endMillis = cal.timeInMillis

                val session = BlockSession(
                    startTime = startMillis,
                    endTime = endMillis,
                    isActive = true,
                    isRecurring = true,
                    recurringStartHour = startHour,
                    recurringStartMinute = startMinute,
                    recurringEndHour = endHour,
                    recurringEndMinute = endMinute,
                    recurringDaysOfWeek = daysOfWeek,
                    recurringDurationMonths = durationMonths,
                    blockedAppsCount = blockedAppsCount,
                    blockedWebsitesCount = blockedWebsitesCount
                )

                // Deactivate all old sessions first
                database.blockSessionDao().deactivateAllSessions()
                database.blockSessionDao().insertBlockSession(session)

                if (isCurrentlyInBlockingWindow(session)) {
                    val appsToBlock = database.blockedAppDao().getAllBlockedApps().map { it.packageName }
                    deviceOwnerManager.blockApps(appsToBlock)
                    deviceOwnerManager.enforceBlockingPolicies()
                } else {
                    val appsToUnblock = database.blockedAppDao().getAllBlockedApps().map { it.packageName }
                    deviceOwnerManager.unblockApps(appsToUnblock)
                    deviceOwnerManager.clearBlockingPolicies()
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Sessão recorrente agendada.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Erro ao agendar sessão: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Check if current time falls within the blocking window (either absolute or recurring).
     * @param session The session to check, or null
     * @return true if blocking should be active right now
     */
    fun isCurrentlyInBlockingWindow(session: BlockSession?): Boolean {
        if (session == null || !session.isActive) return false
        val now = Calendar.getInstance()

        if (!session.isRecurring) {
            // Simple session: active until endTime
            return (session.endTime == null) || (now.timeInMillis < session.endTime)
        } else {
            // Check if recurring session has expired its month limit
            if (session.endTime != null && now.timeInMillis > session.endTime) {
                return false
            }

            // Check time window
            val nowHour = now.get(Calendar.HOUR_OF_DAY)
            val nowMin = now.get(Calendar.MINUTE)
            val currentTimeVal = nowHour * 60 + nowMin

            val startVal = session.recurringStartHour * 60 + session.recurringStartMinute
            val endVal = session.recurringEndHour * 60 + session.recurringEndMinute

            val isOvernight = startVal > endVal
            val isAfterMidnightBeforeEnd = isOvernight && currentTimeVal < endVal

            // Se passou da meia-noite num bloqueio overnight, consideramos o dia lógico como "ontem"
            val logicalDayCal = now.clone() as Calendar
            if (isAfterMidnightBeforeEnd) {
                logicalDayCal.add(Calendar.DAY_OF_YEAR, -1)
            }

            if (session.recurringDaysOfWeek.isNotEmpty()) {
                val logicalDayOfWeek = logicalDayCal.get(Calendar.DAY_OF_WEEK).toString()
                if (!session.recurringDaysOfWeek.split(",").map { it.trim() }.contains(logicalDayOfWeek)) {
                    return false
                }
            }

            return if (startVal <= endVal) {
                // e.g., 10:00 to 18:00
                currentTimeVal in startVal until endVal
            } else {
                // e.g., 22:00 to 06:00 (overnight)
                currentTimeVal >= startVal || currentTimeVal < endVal
            }
        }
    }

    /**
     * Get the active blocking session (automatically expires non-recurring sessions).
     * @return The active session, or null if none
     */
    suspend fun getActiveSession(): BlockSession? {
        return try {
            val session = database.blockSessionDao().getActiveSession()

            if (session != null && session.endTime != null && System.currentTimeMillis() >= session.endTime) {
                // Session has expired (both timer and recurring month limit)
                val expiredSession = session.copy(isActive = false)
                database.blockSessionDao().updateBlockSession(expiredSession)
                
                val appsToUnblock = database.blockedAppDao().getAllBlockedApps().map { it.packageName }
                deviceOwnerManager.unblockApps(appsToUnblock)
                deviceOwnerManager.clearBlockingPolicies()
                null
            } else {
                session
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get remaining days for the active session.
     */
    suspend fun getRemainingDays(): Int {
        return try {
            val session = getActiveSession() ?: return 0
            val endTime = session.endTime ?: return 0
            val remainingMillis = endTime - System.currentTimeMillis()
            if (remainingMillis <= 0) return 0
            TimeUnit.MILLISECONDS.toDays(remainingMillis).toInt()
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Get remaining hours for the active session.
     */
    suspend fun getRemainingHours(): Int {
        return try {
            val session = getActiveSession() ?: return 0
            val endTime = session.endTime ?: return 0
            val remainingMillis = endTime - System.currentTimeMillis()
            if (remainingMillis <= 0) return 0
            TimeUnit.MILLISECONDS.toHours(remainingMillis).toInt()
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Get remaining time formatted as string.
     */
    suspend fun getRemainingTimeFormatted(): String {
        return try {
            val session = getActiveSession() ?: return "Nenhuma sessão ativa"
            val endTime = session.endTime ?: return "Sessão sem tempo definido"
            val remainingMillis = endTime - System.currentTimeMillis()

            if (remainingMillis <= 0) {
                return "Sessão de bloqueio encerrada"
            }

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

    /**
     * Check if blocking is actively applied RIGHT NOW.
     */
    suspend fun isBlockingActive(): Boolean {
        val session = getActiveSession()
        return isCurrentlyInBlockingWindow(session)
    }

    /**
     * Check if there's any session registered (even if paused by recurring logic).
     */
    suspend fun hasRegisteredSession(): Boolean {
        return getActiveSession() != null
    }

    /**
     * End the blocking session.
     */
    fun endBlockingSession() {
        scope.launch {
            try {
                val session = getActiveSession()
                if (session != null) {
                    val endedSession = session.copy(isActive = false)
                    database.blockSessionDao().updateBlockSession(endedSession)
                    
                    val appsToUnblock = database.blockedAppDao().getAllBlockedApps().map { it.packageName }
                    deviceOwnerManager.unblockApps(appsToUnblock)
                    deviceOwnerManager.clearBlockingPolicies()

                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Sessão de bloqueio encerrada", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Falha ao encerrar sessão: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Get session details as formatted string.
     */
    suspend fun getSessionDetails(): String {
        return try {
            val session = getActiveSession() ?: return "Nenhuma sessão de bloqueio ativa"
            val dateFormatter = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

            buildString {
                appendLine("=== Detalhes da Sessão ===")
                if (session.isRecurring) {
                    appendLine("Modo: RECORRENTE DIÁRIO")
                    appendLine("Ativo entre: ${String.format(Locale.getDefault(), "%02d:%02d", session.recurringStartHour, session.recurringStartMinute)} e ${String.format(Locale.getDefault(), "%02d:%02d", session.recurringEndHour, session.recurringEndMinute)}")
                    if (session.recurringDaysOfWeek.isNotEmpty()) {
                        appendLine("Dias: ${session.recurringDaysOfWeek}")
                    }
                } else {
                    appendLine("Modo: SESSÃO ÚNICA")
                    if (session.endTime != null) appendLine("Termina em: ${dateFormatter.format(session.endTime)}")
                }
                appendLine("Apps bloqueados: ${session.blockedAppsCount}")
                appendLine("Sites bloqueados: ${session.blockedWebsitesCount}")
                appendLine("Iniciada em: ${dateFormatter.format(session.startTime)}")
            }
        } catch (e: Exception) {
            "Erro ao recuperar detalhes da sessão"
        }
    }

    /**
     * Get all sessions history.
     */
    suspend fun getSessionsHistory(): List<BlockSession> {
        return try {
            database.blockSessionDao().getAllSessions()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
