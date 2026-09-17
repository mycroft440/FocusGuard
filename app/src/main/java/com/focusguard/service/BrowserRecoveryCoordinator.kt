package com.focusguard.service

/** One heavy recovery at a time; a newer observation always gets a pending turn. */
internal class BrowserRecoveryCoordinator {
    private var active: BrowserInspectionCoordinator.Token? = null
    private var pending: BrowserInspectionCoordinator.Token? = null
    private var activeCancelled = false

    @Synchronized
    fun offer(token: BrowserInspectionCoordinator.Token): Boolean {
        if (token == active && !activeCancelled) return false
        if (active != null) {
            pending = token
            activeCancelled = true
            return false
        }
        active = token
        activeCancelled = false
        return true
    }

    @Synchronized
    fun isCurrent(token: BrowserInspectionCoordinator.Token): Boolean =
        active == token && !activeCancelled

    @Synchronized
    fun finish(token: BrowserInspectionCoordinator.Token): BrowserInspectionCoordinator.Token? {
        if (active != token) return null
        active = pending
        pending = null
        activeCancelled = false
        return active
    }

    @Synchronized
    fun cancel(packageName: String) {
        if (active?.packageName == packageName) activeCancelled = true
        if (pending?.packageName == packageName) pending = null
    }

    @Synchronized
    fun clear() {
        activeCancelled = true
        pending = null
    }
}
