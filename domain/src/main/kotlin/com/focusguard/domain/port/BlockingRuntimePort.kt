package com.focusguard.domain.port

data class BlockingSnapshot(
    val blockedApps: Set<String>,
    val blockedSites: Set<String>,
    val blockingActive: Boolean,
    val strictPomodoro: Boolean
)

enum class BlockingUserMessage {
    POMODORO_STARTED,
    PASSWORD_SESSIONS_ENDED
}
/** Android effects emitted by the blocking use cases. */
interface BlockingRuntimePort {
    suspend fun showUserMessage(message: BlockingUserMessage)
    fun stopPomodoroForeground()
    fun scheduleReconciliation(atMillis: Long?)
    fun publishSnapshot(snapshot: BlockingSnapshot)
}
