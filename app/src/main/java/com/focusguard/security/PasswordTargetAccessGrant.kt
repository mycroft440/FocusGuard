package com.focusguard.security

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.SystemClock
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.utils.WebsiteBlocker
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Ephemeral authorization for targets protected by a PASSWORD session.
 *
 * A correct target credential never edits/deletes the configured block. App
 * grants cover exactly one foreground visit. Website grants cover the current
 * visit to the matching rule and are revoked when URL matching observes
 * navigation away; a bounded timeout is a fail-closed fallback if no further
 * browser event arrives.
 */
object PasswordTargetAccessGrant {
    private const val APP_OPEN_TIMEOUT_MILLIS = 15_000L
    private const val APP_POLL_MILLIS = 200L
    private const val EVENT_LOOKBACK_MILLIS = 30_000L
    private const val WEBSITE_GRANT_TIMEOUT_MILLIS = 5 * 60_000L

    internal data class AppVisitObservation(
        val latestForegroundPackage: String?,
        val latestTargetForegroundAt: Long,
        val latestTargetBackgroundAt: Long
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val grantedPackages = ConcurrentHashMap.newKeySet<String>()
    private val appMonitorJobs = ConcurrentHashMap<String, Job>()
    private val websiteExpiryElapsed = ConcurrentHashMap<String, Long>()
    private val websiteMonitorJobs = ConcurrentHashMap<String, Job>()
    @Volatile private var applicationContext: Context? = null

    fun grantPackage(context: Context, packageName: String) {
        val target = packageName.takeIf(String::isNotBlank) ?: return
        val appContext = context.applicationContext
        applicationContext = appContext
        grantedPackages.add(target)
        appMonitorJobs.remove(target)?.cancel()

        // Device Owner can suspend the package independently of Accessibility.
        // Temporarily unsuspend only this authenticated target; the normal
        // reconciliation re-suspends it as soon as the one-visit grant ends.
        DeviceOwnerManager.getInstance(appContext).unblockApps(listOf(target))

        appMonitorJobs[target] = scope.launch {
            monitorSingleAppVisit(appContext, target)
        }
    }

    fun isPackageGranted(packageName: String): Boolean =
        packageName.isNotBlank() && packageName in grantedPackages

    fun revokePackage(packageName: String?) {
        val target = packageName?.takeIf(String::isNotBlank) ?: return
        grantedPackages.remove(target)
        appMonitorJobs.remove(target)?.cancel()
        reconcileProtection()
    }

    fun grantWebsite(context: Context, ruleOrDomain: String) {
        val rule = WebsiteBlocker.normalizeRule(ruleOrDomain).takeIf(String::isNotBlank) ?: return
        val appContext = context.applicationContext
        applicationContext = appContext
        websiteExpiryElapsed[rule] = SystemClock.elapsedRealtime() + WEBSITE_GRANT_TIMEOUT_MILLIS
        websiteMonitorJobs.remove(rule)?.cancel()
        websiteMonitorJobs[rule] = scope.launch {
            delay(WEBSITE_GRANT_TIMEOUT_MILLIS)
            revokeWebsiteRule(rule)
        }

        // Rebuild managed-browser URL policy without this authenticated rule.
        scope.launch {
            DeviceOwnerManager.getInstance(appContext).invalidateWebsitePolicyCache()
            BlockingSessionManager.getInstance(appContext).checkAndEnforce()
        }
    }

    fun isWebsiteRuleGranted(ruleOrDomain: String): Boolean {
        val rule = WebsiteBlocker.normalizeRule(ruleOrDomain)
        if (rule.isBlank()) return false
        val expiry = websiteExpiryElapsed[rule] ?: return false
        if (SystemClock.elapsedRealtime() < expiry) return true
        revokeWebsiteRule(rule)
        return false
    }

    /**
     * Called while evaluating the browser's current URL. A grant survives while
     * the browser remains on its rule and is revoked as soon as another URL is
     * observed, restoring protection for the next visit.
     */
    fun onWebsiteCandidateObserved(
        urlOrDomain: String,
        configuredRules: Collection<String>
    ) {
        if (websiteExpiryElapsed.isEmpty()) return
        val grantedSnapshot = websiteExpiryElapsed.keys.toList()
        grantedSnapshot.forEach { grantedRule ->
            if (grantedRule !in WebsiteBlocker.normalizeRules(configuredRules)) return@forEach
            val stillOnGrantedTarget = WebsiteBlocker.matchesRuleIgnoringGrants(
                urlOrDomain = urlOrDomain,
                normalizedRule = grantedRule
            )
            if (!stillOnGrantedTarget) revokeWebsiteRule(grantedRule)
        }
    }

    fun revokeWebsiteRule(ruleOrDomain: String?) {
        val rule = ruleOrDomain?.let(WebsiteBlocker::normalizeRule)?.takeIf(String::isNotBlank)
            ?: return
        val existed = websiteExpiryElapsed.remove(rule) != null
        websiteMonitorJobs.remove(rule)?.cancel()
        if (existed) reconcileProtection(invalidateWebsitePolicy = true)
    }

    fun clear() {
        grantedPackages.clear()
        appMonitorJobs.values.forEach(Job::cancel)
        appMonitorJobs.clear()
        websiteExpiryElapsed.clear()
        websiteMonitorJobs.values.forEach(Job::cancel)
        websiteMonitorJobs.clear()
    }

    internal fun grantedWebsiteRulesSnapshot(): Set<String> =
        websiteExpiryElapsed.keys.filterTo(linkedSetOf(), ::isWebsiteRuleGranted)

    /**
     * Pure policy used by the monitor and unit tests. Once the authenticated app
     * has actually reached foreground, either a newer lifecycle-background event
     * for that target or another foreground package ends the one-visit grant.
     *
     * Watching the target's own PAUSED/STOPPED/BACKGROUND event is important:
     * some launchers/OEMs publish the next foreground event late, which previously
     * left the grant alive and made the next app entry appear permanently unlocked.
     */
    internal fun shouldRevokeAppGrant(
        target: String,
        targetSeenForeground: Boolean,
        observation: AppVisitObservation
    ): Boolean {
        if (!targetSeenForeground || target.isBlank()) return false
        if (observation.latestTargetBackgroundAt > observation.latestTargetForegroundAt) {
            return true
        }
        val foreground = observation.latestForegroundPackage
        return !foreground.isNullOrBlank() && foreground != target
    }

    private suspend fun monitorSingleAppVisit(context: Context, target: String) {
        try {
            val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return revokePackageWithoutCancellingSelf(target)
            val grantedAtElapsed = SystemClock.elapsedRealtime()
            val grantedAtWallClock = System.currentTimeMillis()
            var targetSeenForeground = false

            while (target in grantedPackages) {
                val observation = observeAppVisit(
                    manager = usage,
                    target = target,
                    notBeforeMillis = grantedAtWallClock
                )
                if (!targetSeenForeground) {
                    val targetIsCurrentlyForeground =
                        observation.latestForegroundPackage == target &&
                            observation.latestTargetForegroundAt >=
                            observation.latestTargetBackgroundAt
                    if (targetIsCurrentlyForeground) {
                        targetSeenForeground = true
                    } else if (
                        SystemClock.elapsedRealtime() - grantedAtElapsed >= APP_OPEN_TIMEOUT_MILLIS
                    ) {
                        revokePackageWithoutCancellingSelf(target)
                        return
                    }
                } else if (shouldRevokeAppGrant(target, targetSeenForeground, observation)) {
                    revokePackageWithoutCancellingSelf(target)
                    return
                }
                delay(APP_POLL_MILLIS)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            revokePackageWithoutCancellingSelf(target)
        } finally {
            appMonitorJobs.remove(target)
        }
    }

    private fun observeAppVisit(
        manager: UsageStatsManager,
        target: String,
        notBeforeMillis: Long
    ): AppVisitObservation {
        val end = System.currentTimeMillis()
        val start = maxOf(end - EVENT_LOOKBACK_MILLIS, notBeforeMillis)
        val events = manager.queryEvents(start, end)
        val event = UsageEvents.Event()
        var latestForegroundPackage: String? = null
        var latestForegroundAt = Long.MIN_VALUE
        var latestTargetForegroundAt = Long.MIN_VALUE
        var latestTargetBackgroundAt = Long.MIN_VALUE

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.timeStamp < notBeforeMillis) continue

            val foregroundEvent = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            if (foregroundEvent && event.timeStamp >= latestForegroundAt) {
                latestForegroundAt = event.timeStamp
                latestForegroundPackage = event.packageName
            }
            if (
                foregroundEvent &&
                event.packageName == target &&
                event.timeStamp >= latestTargetForegroundAt
            ) {
                latestTargetForegroundAt = event.timeStamp
            }

            val backgroundEvent = event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND ||
                event.eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                event.eventType == UsageEvents.Event.ACTIVITY_STOPPED
            if (
                backgroundEvent &&
                event.packageName == target &&
                event.timeStamp >= latestTargetBackgroundAt
            ) {
                latestTargetBackgroundAt = event.timeStamp
            }
        }

        return AppVisitObservation(
            latestForegroundPackage = latestForegroundPackage,
            latestTargetForegroundAt = latestTargetForegroundAt,
            latestTargetBackgroundAt = latestTargetBackgroundAt
        )
    }

    private fun revokePackageWithoutCancellingSelf(target: String) {
        val existed = grantedPackages.remove(target)
        if (existed) reconcileProtection()
    }

    private fun reconcileProtection(invalidateWebsitePolicy: Boolean = false) {
        val context = applicationContext ?: return
        scope.launch {
            if (invalidateWebsitePolicy) {
                DeviceOwnerManager.getInstance(context).invalidateWebsitePolicyCache()
            }
            BlockingSessionManager.getInstance(context).checkAndEnforce()
        }
    }
}
