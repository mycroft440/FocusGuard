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
 * grants cover one foreground visit. Website grants cover the current visit to
 * the matching rule and are revoked when URL matching observes navigation away;
 * a bounded timeout is a fail-closed fallback if no further browser event arrives.
 */
object PasswordTargetAccessGrant {
    private const val APP_OPEN_TIMEOUT_MILLIS = 15_000L
    private const val APP_POLL_MILLIS = 350L
    private const val EVENT_LOOKBACK_MILLIS = 30_000L
    private const val WEBSITE_GRANT_TIMEOUT_MILLIS = 5 * 60_000L

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

    private suspend fun monitorSingleAppVisit(context: Context, target: String) {
        try {
            val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return revokePackageWithoutCancellingSelf(target)
            val grantedAt = SystemClock.elapsedRealtime()
            var targetSeenForeground = false

            while (target in grantedPackages) {
                val foreground = mostRecentForegroundPackage(usage)
                if (!targetSeenForeground) {
                    if (foreground == target) {
                        targetSeenForeground = true
                    } else if (SystemClock.elapsedRealtime() - grantedAt >= APP_OPEN_TIMEOUT_MILLIS) {
                        revokePackageWithoutCancellingSelf(target)
                        return
                    }
                } else if (!foreground.isNullOrBlank() && foreground != target) {
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
