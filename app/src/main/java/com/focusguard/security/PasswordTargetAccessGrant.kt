package com.focusguard.security

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Ephemeral authorization for opening an app protected by a PASSWORD session.
 *
 * The configured block is never deleted. After a correct target credential the
 * app receives one foreground visit: the grant waits for that package to open and
 * is revoked when Usage Access reports that another app became foreground. A
 * process restart also fails closed because grants are deliberately not persisted.
 */
object PasswordTargetAccessGrant {
    private const val OPEN_TIMEOUT_MILLIS = 15_000L
    private const val POLL_MILLIS = 350L
    private const val EVENT_LOOKBACK_MILLIS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val grantedPackages = ConcurrentHashMap.newKeySet<String>()
    private val monitorJobs = ConcurrentHashMap<String, Job>()

    fun grantPackage(context: Context, packageName: String) {
        val target = packageName.takeIf(String::isNotBlank) ?: return
        grantedPackages.add(target)
        monitorJobs.remove(target)?.cancel()
        monitorJobs[target] = scope.launch {
            monitorSingleVisit(context.applicationContext, target)
        }
    }

    fun isPackageGranted(packageName: String): Boolean =
        packageName.isNotBlank() && packageName in grantedPackages

    fun revokePackage(packageName: String?) {
        val target = packageName?.takeIf(String::isNotBlank) ?: return
        grantedPackages.remove(target)
        monitorJobs.remove(target)?.cancel()
    }

    fun clear() {
        grantedPackages.clear()
        monitorJobs.values.forEach(Job::cancel)
        monitorJobs.clear()
    }

    private suspend fun monitorSingleVisit(context: Context, target: String) {
        try {
            val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return revokeWithoutCancellingSelf(target)
            val grantedAt = SystemClock.elapsedRealtime()
            var targetSeenForeground = false

            while (target in grantedPackages) {
                val foreground = mostRecentForegroundPackage(usage)
                if (!targetSeenForeground) {
                    if (foreground == target) {
                        targetSeenForeground = true
                    } else if (SystemClock.elapsedRealtime() - grantedAt >= OPEN_TIMEOUT_MILLIS) {
                        revokeWithoutCancellingSelf(target)
                        return
                    }
                } else if (!foreground.isNullOrBlank() && foreground != target) {
                    revokeWithoutCancellingSelf(target)
                    return
                }
                delay(POLL_MILLIS)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            revokeWithoutCancellingSelf(target)
        } finally {
            monitorJobs.remove(target)
        }
    }

    private fun mostRecentForegroundPackage(manager: UsageStatsManager): String? {
        val end = System.currentTimeMillis()
        val events = manager.queryEvents(end - EVENT_LOOKBACK_MILLIS, end)
        val event = UsageEvents.Event()
        var latestPackage: String? = null
        var latestTime = Long.MIN_VALUE
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val foregroundEvent = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            if (foregroundEvent && event.timeStamp >= latestTime) {
                latestTime = event.timeStamp
                latestPackage = event.packageName
            }
        }
        return latestPackage
    }

    private fun revokeWithoutCancellingSelf(target: String) {
        grantedPackages.remove(target)
    }
}
