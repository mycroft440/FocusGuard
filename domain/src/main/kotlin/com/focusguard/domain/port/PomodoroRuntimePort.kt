package com.focusguard.domain.port

/** Service, notification and activity effects owned by the Android shell. */
interface PomodoroRuntimePort {
    fun hasNotificationListenerAccess(): Boolean
    fun requestNotificationRefresh()
    fun startForegroundTimer()
    fun stopForegroundTimer()
    fun scheduleWatchdog()
    fun cancelWatchdog()
    fun publishBlockingChanged()
    fun launchStrictLock()
}
