package com.focusguard.manager

import android.content.Context
import android.widget.Toast
import com.focusguard.database.AppDatabase
import com.focusguard.database.BlockSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.Calendar
import com.focusguard.admin.DeviceOwnerManager
import android.util.Log

/**
 * Manager for blocking sessions with day-based countdown
 */
class BlockingSessionManager(private val context: Context) {

    private val database = AppDatabase.getDatabase(context)
    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * Start a new blocking session (Specific Hours or Recurring)
     */
    fun startBlockingSession(
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        isRecurring: Boolean,
        blockedAppsCount: Int, 
        blockedWebsitesCount: Int
    ) {
        scope.launch {
            try {
                // Configurando Calendários para extrair timestamps iniciais
                val startCal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, startHour)
                    set(Calendar.MINUTE, startMinute)
                    set(Calendar.SECOND, 0)
                }

                val endCal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, endHour)
                    set(Calendar.MINUTE, endMinute)
                    set(Calendar.SECOND, 0)
                }
                
                // Se não for recorrente, e o endHour for menor, significa que termia amanhã
                if (!isRecurring && endCal.before(startCal)) {
                    endCal.add(Calendar.DAY_OF_YEAR, 1)
                }

                val session = BlockSession(
                    startTime = startCal.timeInMillis,
                    endTime = if (isRecurring) null else endCal.timeInMillis,
                    isActive = true,
                    isRecurring = isRecurring,
                    recurringStartHour = startHour,
                    recurringStartMinute = startMinute,
                    recurringEndHour = endHour,
                    recurringEndMinute = endMinute,
                    blockedAppsCount = blockedAppsCount,
                    blockedWebsitesCount = blockedWebsitesCount
                )
                database.blockSessionDao().insertBlockSession(session)
                
                // Força policiamento imediatamente se o horário atual já estiver no período de bloqueio
                if(isCurrentlyInBlockingWindow(session)) {
                    Log.e("NUCLEAR_DEBUG", "Starting blocking session inside window. Enforcing policies.")
                    DeviceOwnerManager(context).enforceBlockingPolicies()
                } else {
                    Log.e("NUCLEAR_DEBUG", "Starting recurring session outside window. Policies will enforce soon.")
                }

                withContext(Dispatchers.Main) {
                    val recText = if(isRecurring) " everyday." else "."
                    Toast.makeText(
                        context,
                        "Blocking session started from $startHour:$startMinute to $endHour:$endMinute$recText",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Failed to start blocking session: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * Check if current time falls within the blocking window (either absolute or recurring)
     */
    fun isCurrentlyInBlockingWindow(session: BlockSession?): Boolean {
        if (session == null || !session.isActive) return false
        val now = Calendar.getInstance()
        
        if (!session.isRecurring) {
            // Sessão Simples
            return (session.endTime == null) || (now.timeInMillis < session.endTime)
        } else {
            // Sessão Diária
            val nowHour = now.get(Calendar.HOUR_OF_DAY)
            val nowMin = now.get(Calendar.MINUTE)
            val currentTimeVal = nowHour * 60 + nowMin
            
            val startVal = session.recurringStartHour * 60 + session.recurringStartMinute
            val endVal = session.recurringEndHour * 60 + session.recurringEndMinute
            
            if (startVal <= endVal) {
                // Ex: 10:00 as 18:00
                return currentTimeVal in startVal..endVal
            } else {
                // Ex: 22:00 as 06:00 do dia seguinte
                return currentTimeVal >= startVal || currentTimeVal <= endVal
            }
        }
    }

    /**
     * Get the active blocking session (automatically expires non-recurring sessions)
     */
    suspend fun getActiveSession(): BlockSession? {
        return try {
            val session = database.blockSessionDao().getActiveSession()
            
            // Se for sessão de tempo fixo e já passou do Limite de Fim, inativa e retira o DeviceOwner
            if (session != null && !session.isRecurring && session.endTime != null && System.currentTimeMillis() >= session.endTime) {
                Log.e("NUCLEAR_DEBUG", "Blocking session expired naturally. Clearing policies.")
                val expiredSession = session.copy(isActive = false)
                database.blockSessionDao().updateBlockSession(expiredSession)
                
                DeviceOwnerManager(context).clearBlockingPolicies()
                null
            } else {
                // Se for recorrente, ou ainda tiver tempo, manter a sessão viva
                session
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get remaining days for the active session
     */
    suspend fun getRemainingDays(): Int {
        return try {
            val session = getActiveSession() ?: return 0
            val remainingMillis = (session.endTime ?: 0) - System.currentTimeMillis()
            val remainingDays = TimeUnit.MILLISECONDS.toDays(remainingMillis).toInt()
            maxOf(0, remainingDays)
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Get remaining hours for the active session
     */
    suspend fun getRemainingHours(): Int {
        return try {
            val session = getActiveSession() ?: return 0
            val remainingMillis = (session.endTime ?: 0) - System.currentTimeMillis()
            val remainingHours = TimeUnit.MILLISECONDS.toHours(remainingMillis).toInt()
            maxOf(0, remainingHours)
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Get remaining time formatted as string
     */
    suspend fun getRemainingTimeFormatted(): String {
        return try {
            val session = getActiveSession() ?: return "No active session"
            val remainingMillis = (session.endTime ?: 0) - System.currentTimeMillis()
            
            if (remainingMillis <= 0) {
                return "Blocking session ended"
            }
            
            val days = TimeUnit.MILLISECONDS.toDays(remainingMillis)
            val hours = TimeUnit.MILLISECONDS.toHours(remainingMillis) % 24
            val minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMillis) % 60
            
            buildString {
                if (days > 0) append("$days days ")
                if (hours > 0) append("$hours hours ")
                if (minutes > 0) append("$minutes minutes")
                if (isEmpty()) append("Less than a minute")
            }
        } catch (e: Exception) {
            "Error calculating time"
        }
    }

    /**
     * Check if blocking is intensely applied RIGHT NOW (DeviceOwner & Accessibility active frame)
     */
    suspend fun isBlockingActive(): Boolean {
        val session = getActiveSession()
        return isCurrentlyInBlockingWindow(session)
    }
    
    /**
     * Check if there's any session registered (even if paused by recurring logic)
     */
    suspend fun hasRegisteredSession(): Boolean {
        return getActiveSession() != null
    }

    /**
     * End the blocking session
     */
    fun endBlockingSession() {
        scope.launch {
            try {
                val session = getActiveSession()
                if (session != null) {
                    Log.e("NUCLEAR_DEBUG", "Manual/Programmatic end of blocking session. Clearing policies.")
                    val endedSession = session.copy(isActive = false)
                    database.blockSessionDao().updateBlockSession(endedSession)
                    
                    // Clear policies when forcefully stopped
                    DeviceOwnerManager(context).clearBlockingPolicies()

                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "Blocking session ended",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Failed to end blocking session: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * Get session details
     */
    suspend fun getSessionDetails(): String {
        return try {
            val session = getActiveSession() ?: return "No active blocking session"
            
            buildString {
                appendLine("=== Blocking Session Details ===")
                if (session.isRecurring) {
                    appendLine("Mode: RECURRING DAILY")
                    appendLine("Active between: ${String.format("%02d:%02d", session.recurringStartHour, session.recurringStartMinute)} and ${String.format("%02d:%02d", session.recurringEndHour, session.recurringEndMinute)}")
                } else {
                    appendLine("Mode: SINGLE SESSION")
                    val formatter = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm")
                    if(session.endTime != null) appendLine("Ends on: ${formatter.format(session.endTime)}")
                }
                appendLine("Blocked Apps: ${session.blockedAppsCount}")
                appendLine("Blocked Websites: ${session.blockedWebsitesCount}")
                appendLine("Started on: ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm").format(session.startTime)}")
            }
        } catch (e: Exception) {
            "Error retrieving session details"
        }
    }

    /**
     * Get all sessions history
     */
    suspend fun getSessionsHistory(): List<BlockSession> {
        return try {
            database.blockSessionDao().getAllSessions()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
