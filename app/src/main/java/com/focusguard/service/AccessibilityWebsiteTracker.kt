package com.focusguard.service

import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import com.focusguard.data.PredefinedWebsites
import com.focusguard.database.AppDatabase
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.UsageLimitForegroundPolicy
import com.focusguard.utils.WebsiteBlocker
import com.focusguard.utils.WebsiteUsageLimitPolicy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Persists website usage and returns rules that became blocking after the write. */
@Singleton
class AccessibilityWebsiteUsageStore @Inject constructor(
    private val database: AppDatabase,
    private val sessionManager: BlockingSessionManager
) {
    private val dateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }

    suspend fun addUsageAndFindExceeded(domain: String, deltaMillis: Long): Set<String> {
        val today = dateFormat.get()!!.format(Date())
        database.dailyUsageStatDao().addUsage(domain, today, deltaMillis)
        val limits = database.websiteUsageLimitDao().getAllStatic().filter { it.isEnabled }
        val matchingRules = WebsiteBlocker.findMatchingRules(
            domain,
            WebsiteBlocker.normalizeRules(limits.map { it.domain })
        )
        if (matchingRules.isEmpty()) return emptySet()

        val usageByRule = WebsiteUsageLimitPolicy.aggregateUsageByRule(
            usageByIdentifier = database.dailyUsageStatDao()
                .getStatsForDateStatic(today)
                .map { it.identifier to it.timeSpentMs },
            configuredRules = limits.map { it.domain }
        )
        val now = System.currentTimeMillis()
        val exceededRules = limits.mapNotNullTo(linkedSetOf()) { limit ->
            val rule = WebsiteBlocker.normalizeRule(limit.domain)
            rule.takeIf {
                rule in matchingRules && WebsiteUsageLimitPolicy.shouldBlock(
                    usedMillis = usageByRule[rule] ?: 0L,
                    dailyLimitMinutes = limit.dailyLimitMinutes,
                    lockMode = limit.lockMode,
                    lockUntilTimestamp = limit.lockUntilTimestamp,
                    nowMillis = now
                )
            }
        }
        if (exceededRules.isNotEmpty()) sessionManager.checkAndEnforce()
        return exceededRules
    }
}

/** Tracks the active website while the screen stays interactive. */
class AccessibilityWebsiteTracker(
    private val service: BlockingAccessibilityService,
    private val scope: CoroutineScope,
    private val usageStore: AccessibilityWebsiteUsageStore,
    private val powerManager: () -> PowerManager?,
    private val foregroundPackage: () -> String?,
    private val browserPackages: () -> Set<String>,
    private val blockedWebsiteRules: () -> Set<String>,
    private val limitedWebsiteRules: () -> Set<String>,
    private val onWebsiteBlocked: (domain: String, packageName: String) -> Unit,
    private val onUsageLimitExceeded: (
        domain: String,
        packageName: String,
        exceededRules: Set<String>
    ) -> Unit
) {
    private data class UsageSlice(
        val domain: String,
        val deltaMillis: Long,
        val packageName: String
    )

    private val trackingLock = Any()
    @Volatile private var trackedDomain: String? = null
    @Volatile private var trackedPackageName: String? = null
    @Volatile private var trackedSinceMillis = 0L
    private var trackingJob: Job? = null

    fun handleBrowserEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName !in browserPackages()) return

        val pornographyCategoryActive = blockedWebsiteRules().any(
            WebsiteBlocker::isPornographyRule
        )
        val fastAddressText = WebsiteBlocker.extractAddressBarTextFromEvent(
            event,
            packageName
        )
        val fastUrl = fastAddressText?.let(WebsiteBlocker::extractUrlCandidate)
            ?: WebsiteBlocker.extractUrlFromEvent(event, packageName)
        val root = if (fastUrl == null) service.rootInActiveWindow ?: event.source else null
        val url = fastUrl ?: WebsiteBlocker.extractUrlFromRoot(root, packageName)
        val addressText = fastAddressText
            ?: WebsiteBlocker.extractAddressBarTextFromRoot(root, packageName)
        val now = System.currentTimeMillis()

        if (pornographyCategoryActive) {
            val addressBarHasBlockedSearch = addressText?.let(
                WebsiteBlocker::isPornographySearchInput
            ) == true
            val googlePageFieldHasBlockedSearch = fastAddressText == null &&
                url?.let(WebsiteBlocker::isGoogleUrl) == true &&
                WebsiteBlocker.extractEditableTextFromEvent(event)?.let(
                    WebsiteBlocker::containsPornographySearchTerm
                ) == true
            if (addressBarHasBlockedSearch || googlePageFieldHasBlockedSearch) {
                onWebsiteBlocked(PredefinedWebsites.PORNOGRAPHY_RULE, packageName)
                recycle(root)
                return
            }
        }

        if (!url.isNullOrBlank()) {
            val domain = WebsiteBlocker.extractDomain(url)
            update(url, packageName, now)
            val matchingRule = WebsiteBlocker.findMatchingRule(url, blockedWebsiteRules())
            if (matchingRule != null) {
                val blockTarget = if (WebsiteBlocker.isPornographyRule(matchingRule)) {
                    matchingRule
                } else {
                    domain
                }
                onWebsiteBlocked(blockTarget, packageName)
                recycle(root)
                return
            }
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            stop(now)
        }
        recycle(root)
    }

    fun update(urlOrDomain: String, packageName: String, now: Long) {
        val matchingRules = WebsiteBlocker.findMatchingRules(
            urlOrDomain,
            limitedWebsiteRules()
        )
        if (matchingRules.isEmpty()) {
            stop(now)
            return
        }
        val pornographyGoogleSurface =
            WebsiteBlocker.isPornographyGoogleSearchUrl(urlOrDomain) ||
                WebsiteBlocker.isGoogleImagesUrl(urlOrDomain)
        val usageDomain = if (
            PredefinedWebsites.PORNOGRAPHY_RULE in matchingRules && pornographyGoogleSurface
        ) {
            PredefinedWebsites.PORNOGRAPHY_RULE
        } else {
            WebsiteBlocker.extractDomain(urlOrDomain)
                .ifBlank { WebsiteBlocker.normalizeRule(urlOrDomain) }
        }

        var usageToPersist: UsageSlice? = null
        synchronized(trackingLock) {
            val previousDomain = trackedDomain
            val previousPackage = trackedPackageName
            if (previousDomain == usageDomain && previousPackage == packageName) {
                val delta = (now - trackedSinceMillis).coerceIn(0L, MAX_USAGE_DELTA_MILLIS)
                if (delta >= MIN_USAGE_SLICE_MILLIS) {
                    usageToPersist = UsageSlice(usageDomain, delta, packageName)
                    trackedSinceMillis = now
                }
            } else {
                if (previousDomain != null && previousPackage != null) {
                    val delta = (now - trackedSinceMillis)
                        .coerceIn(0L, MAX_USAGE_DELTA_MILLIS)
                    if (delta >= MIN_USAGE_SLICE_MILLIS) {
                        usageToPersist = UsageSlice(previousDomain, delta, previousPackage)
                    }
                }
                trackedDomain = usageDomain
                trackedPackageName = packageName
                trackedSinceMillis = now
            }
        }
        usageToPersist?.let(::persist)
        startPulse()
    }

    fun stop(now: Long = System.currentTimeMillis()) {
        var usageToPersist: UsageSlice? = null
        synchronized(trackingLock) {
            val domain = trackedDomain
            val packageName = trackedPackageName
            if (domain != null && packageName != null) {
                val delta = (now - trackedSinceMillis).coerceIn(0L, MAX_USAGE_DELTA_MILLIS)
                if (delta >= MIN_USAGE_SLICE_MILLIS) {
                    usageToPersist = UsageSlice(domain, delta, packageName)
                }
            }
            trackedDomain = null
            trackedPackageName = null
            trackedSinceMillis = 0L
        }
        trackingJob?.cancel()
        trackingJob = null
        usageToPersist?.let(::persist)
    }

    private fun startPulse() {
        if (trackingJob?.isActive == true) return
        trackingJob = scope.launch {
            while (isActive) {
                delay(WEBSITE_PULSE_MILLIS)
                var usageToPersist: UsageSlice? = null
                var shouldStop = false
                synchronized(trackingLock) {
                    val domain = trackedDomain
                    val packageName = trackedPackageName
                    if (domain == null || packageName == null) return@launch
                    if (!UsageLimitForegroundPolicy.shouldCountWebsiteUsage(
                            trackedPackageName = packageName,
                            foregroundPackageName = foregroundPackage(),
                            isDeviceInteractive = powerManager()?.isInteractive == true
                        )
                    ) {
                        trackedDomain = null
                        trackedPackageName = null
                        trackedSinceMillis = 0L
                        shouldStop = true
                        return@synchronized
                    }
                    val now = System.currentTimeMillis()
                    val delta = (now - trackedSinceMillis)
                        .coerceIn(0L, MAX_USAGE_DELTA_MILLIS)
                    if (delta >= MIN_USAGE_SLICE_MILLIS) {
                        trackedSinceMillis = now
                        usageToPersist = UsageSlice(domain, delta, packageName)
                    }
                }
                if (shouldStop) return@launch
                usageToPersist?.let { persistNow(it) }
            }
        }
    }

    private fun persist(usage: UsageSlice) {
        scope.launch { persistNow(usage) }
    }

    private suspend fun persistNow(usage: UsageSlice) {
        try {
            val exceededRules = usageStore.addUsageAndFindExceeded(
                usage.domain,
                usage.deltaMillis
            )
            if (exceededRules.isEmpty()) return
            scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                val stillActive = synchronized(trackingLock) {
                    trackedDomain == usage.domain && trackedPackageName == usage.packageName
                }
                if (!stillActive) return@launch
                onUsageLimitExceeded(usage.domain, usage.packageName, exceededRules)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FocusGuardLogger.logError(
                "A11y",
                "Falha ao registrar uso de ${usage.domain}",
                error
            )
        }
    }

    private fun recycle(node: android.view.accessibility.AccessibilityNodeInfo?) {
        if (node != null) runCatching { node.recycle() }
    }

    private companion object {
        const val WEBSITE_PULSE_MILLIS = 5_000L
        const val MAX_USAGE_DELTA_MILLIS = 15_000L
        const val MIN_USAGE_SLICE_MILLIS = 1_000L
    }
}
