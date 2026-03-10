package com.focusguard.manager

import android.content.Context
import android.widget.Toast
import com.focusguard.database.AppDatabase
import com.focusguard.database.BlockSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Manager for blocking sessions with day-based countdown
 */
class BlockingSessionManager(private val context: Context) {

    private val database = AppDatabase.getDatabase(context)
    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * Start a new blocking session for a specified number of days
     */
    fun startBlockingSession(durationDays: Int, blockedAppsCount: Int, blockedWebsitesCount: Int) {
        scope.launch {
            try {
                val session = BlockSession(
                    startTime = System.currentTimeMillis(),
                    endTime = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(durationDays.toLong()),
                    durationDays = durationDays,
                    isActive = true,
                    blockedAppsCount = blockedAppsCount,
                    blockedWebsitesCount = blockedWebsitesCount
                )
                database.blockSessionDao().insertBlockSession(session)
                
                Toast.makeText(
                    context,
                    "Blocking session started for $durationDays days",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "Failed to start blocking session: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Get the active blocking session
     */
    suspend fun getActiveSession(): BlockSession? {
        return try {
            val session = database.blockSessionDao().getActiveSession()
            
            // Check if session has expired
            if (session != null && session.endTime != null && System.currentTimeMillis() >= session.endTime) {
                // Session has expired, mark as inactive
                val expiredSession = session.copy(isActive = false)
                database.blockSessionDao().updateBlockSession(expiredSession)
                null
            } else {
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
     * Check if blocking session is active
     */
    suspend fun isBlockingActive(): Boolean {
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
                    val endedSession = session.copy(isActive = false)
                    database.blockSessionDao().updateBlockSession(endedSession)
                    
                    Toast.makeText(
                        context,
                        "Blocking session ended",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "Failed to end blocking session: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
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
                appendLine("Duration: ${session.durationDays} days")
                appendLine("Blocked Apps: ${session.blockedAppsCount}")
                appendLine("Blocked Websites: ${session.blockedWebsitesCount}")
                appendLine("Remaining Time: ${getRemainingTimeFormatted()}")
                appendLine("Started: ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm").format(session.startTime)}")
                if (session.endTime != null) {
                    appendLine("Ends: ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm").format(session.endTime)}")
                }
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
