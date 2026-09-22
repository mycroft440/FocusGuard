package com.focusguard.service

/**
 * Runs at most one heavy recovery per live browser surface. Repeated content events
 * from the same surface epoch are coalesced into that recovery instead of restarting
 * its grace/deadline. A real package/window/generation/surface change cancels the old work.
 */
internal class BrowserRecoveryCoordinator {
    private var active: BrowserInspectionCoordinator.Token? = null
    private var pending: BrowserInspectionCoordinator.Token? = null
    private var activeCancelled = false

    @Synchronized
    fun offer(token: BrowserInspectionCoordinator.Token): Boolean {
        val current = active
        if (current != null && !activeCancelled && sameDocument(current, token)) {
            current.permitSequenceAdvance()
            return false
        }
        if (token == current && !activeCancelled) return false
        if (current != null) {
            pending = token
            activeCancelled = true
            return false
        }
        token.permitSequenceAdvance()
        active = token
        activeCancelled = false
        return true
    }

    @Synchronized
    fun isCurrent(token: BrowserInspectionCoordinator.Token): Boolean =
        active === token && !activeCancelled

    @Synchronized
    fun finish(token: BrowserInspectionCoordinator.Token): BrowserInspectionCoordinator.Token? {
        if (active !== token) return null
        val replacement = pending
        pending = null
        activeCancelled = false
        if (replacement != null) replacement.permitSequenceAdvance()
        active = replacement
        return replacement
    }

    @Synchronized
    fun cancel(packageName: String) {
        if (active?.packageName == packageName) activeCancelled = true
        if (pending?.packageName == packageName) pending = null
    }

    @Synchronized
    fun clear() {
        active = null
        pending = null
        activeCancelled = false
    }

    private fun sameDocument(
        first: BrowserInspectionCoordinator.Token,
        second: BrowserInspectionCoordinator.Token
    ): Boolean = first.packageName == second.packageName &&
        first.windowId == second.windowId &&
        first.generation == second.generation &&
        first.surfaceEpoch == second.surfaceEpoch
}
