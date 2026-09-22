package com.focusguard.accessibility.website.compatibility

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import com.focusguard.utils.FocusGuardLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal enum class BrowserClassification {
    NOT_BROWSER,
    PROBABLE_BROWSER,
    CONFIRMED_BROWSER,
    UNKNOWN;

    /** Only structural recognition may authorize the automatic generic route. */
    val isBrowserLike: Boolean
        get() = this == CONFIRMED_BROWSER
}

internal enum class BrowserProbeResult {
    HANDLED,
    NOT_HANDLED,
    UNKNOWN
}

internal enum class BrowserDetectionReason {
    STRUCTURAL_BROWSER_CONFIRMED,
    STRUCTURAL_BROWSER_INCOMPLETE,
    KNOWN_BROWSER_PROFILE,
    PACKAGE_QUERY_UNKNOWN,
    NO_GENERIC_HANDLER,
    DETECTOR_UNINITIALIZED,

    // Kept for source compatibility with older diagnostics/tests. They are no
    // longer emitted by the v4 classifier because default/history/score do not
    // authorize browser identity.
    HTTP_HTTPS_CONFIRMED,
    CURRENT_VERSION_HISTORY,
    PARTIAL_GENERIC_HANDLER
}

internal data class BrowserDetectionDecision(
    val classification: BrowserClassification,
    val reason: BrowserDetectionReason,
    val fromCache: Boolean = false
)

/**
 * Package-level structural evidence used by the pure v4 classifier.
 *
 * [genericHttps] represents G (all HTTPS probes share one usable Activity),
 * [broadHttpsFilter] represents B for that common Activity, [browserCategory]
 * represents C, [genericHttp] represents H and [customTabsService] represents T.
 */
internal data class BrowserCapabilityEvidence(
    val genericHttp: BrowserProbeResult = BrowserProbeResult.NOT_HANDLED,
    val genericHttps: BrowserProbeResult = BrowserProbeResult.NOT_HANDLED,
    val broadHttpsFilter: BrowserProbeResult = BrowserProbeResult.NOT_HANDLED,
    val browserCategory: BrowserProbeResult = BrowserProbeResult.NOT_HANDLED,
    val customTabsService: BrowserProbeResult = BrowserProbeResult.NOT_HANDLED
)

/** Pure classification kept separate from Android queries so recognition stays testable. */
internal object BrowserClassificationPolicy {
    fun classify(evidence: BrowserCapabilityEvidence): BrowserClassification =
        decide(evidence).classification

    @Suppress("UNUSED_PARAMETER")
    fun decide(
        evidence: BrowserCapabilityEvidence,
        strongCurrentVersionHistory: Boolean = false,
        knownBrowserProfile: Boolean = false
    ): BrowserDetectionDecision {
        // A registered owner is resolved before PackageManager collection in production.
        // Keeping this branch in the pure policy makes that invariant explicit for callers/tests.
        if (knownBrowserProfile) {
            return BrowserDetectionDecision(
                BrowserClassification.CONFIRMED_BROWSER,
                BrowserDetectionReason.KNOWN_BROWSER_PROFILE
            )
        }

        // G and B are central evidence. Failure to collect either is inconclusive,
        // never a negative and never upgraded by history/default-browser preference.
        if (evidence.genericHttps == BrowserProbeResult.UNKNOWN ||
            evidence.broadHttpsFilter == BrowserProbeResult.UNKNOWN
        ) {
            return BrowserDetectionDecision(
                BrowserClassification.UNKNOWN,
                BrowserDetectionReason.PACKAGE_QUERY_UNKNOWN
            )
        }

        val g = evidence.genericHttps == BrowserProbeResult.HANDLED
        val b = evidence.broadHttpsFilter == BrowserProbeResult.HANDLED
        val c = evidence.browserCategory == BrowserProbeResult.HANDLED
        val h = evidence.genericHttp == BrowserProbeResult.HANDLED
        val t = evidence.customTabsService == BrowserProbeResult.HANDLED

        if (g && b && (c || (h && t))) {
            return BrowserDetectionDecision(
                BrowserClassification.CONFIRMED_BROWSER,
                BrowserDetectionReason.STRUCTURAL_BROWSER_CONFIRMED
            )
        }

        // G without the complete structural contract is diagnostic/probable only.
        // It must not enter the automatic generic blocking route.
        if (g) {
            return BrowserDetectionDecision(
                BrowserClassification.PROBABLE_BROWSER,
                BrowserDetectionReason.STRUCTURAL_BROWSER_INCOMPLETE
            )
        }

        val auxiliaryUnknown = evidence.browserCategory == BrowserProbeResult.UNKNOWN ||
            evidence.genericHttp == BrowserProbeResult.UNKNOWN ||
            evidence.customTabsService == BrowserProbeResult.UNKNOWN
        if (auxiliaryUnknown) {
            return BrowserDetectionDecision(
                BrowserClassification.UNKNOWN,
                BrowserDetectionReason.PACKAGE_QUERY_UNKNOWN
            )
        }

        return BrowserDetectionDecision(
            BrowserClassification.NOT_BROWSER,
            BrowserDetectionReason.NO_GENERIC_HANDLER
        )
    }
}

/** UNKNOWN results retry quickly but remain bounded below the v4 five-second cache ceiling. */
internal object BrowserUnknownRetryPolicy {
    private val retryDelaysMillis = longArrayOf(250L, 500L, 1_000L, 2_000L, 4_000L)

    fun retryDelayMillis(attempt: Int): Long =
        retryDelaysMillis[(attempt.coerceAtLeast(1) - 1).coerceAtMost(retryDelaysMillis.lastIndex)]
}

/**
 * Detects browsers only for packages that do not already have a specific owner.
 *
 * Recognition follows the v4 structural contract:
 *   G && B && (C || (H && T))
 *
 * G is six HTTPS probes (three reserved hosts, two paths each) with one common
 * exported/enabled Activity. B requires the matching filters for that Activity to
 * be broad: no authority/path/SSP/relative-URI restriction. C is MAIN+APP_BROWSER,
 * H is two HTTP hosts with a common usable Activity, and T is an exported/enabled
 * Custom Tabs provider service usable by FocusGuard. All queries are package-scoped
 * and local; no network request is performed.
 *
 * Unknown-package PackageManager collection is executed by one bounded worker. A
 * result that exceeds the v4 deadline is returned as inconclusive and its late
 * worker result is never allowed to populate the cache.
 */
internal object BrowserDetector {
    private const val CUSTOM_TABS_SERVICE_ACTION =
        "android.support.customtabs.action.CustomTabsService"
    private const val POSITIVE_CACHE_MILLIS = 60_000L
    private const val NEGATIVE_CACHE_MILLIS = 5_000L
    internal const val COLLECTION_DEADLINE_MILLIS = 1_000L

    internal val HTTPS_PROBES = listOf(
        "https://focusguard-a.invalid/",
        "https://focusguard-a.invalid/focusguard-check",
        "https://focusguard-b.invalid/",
        "https://focusguard-b.invalid/focusguard-check",
        "https://focusguard-c.invalid/",
        "https://focusguard-c.invalid/focusguard-check"
    )
    internal val HTTP_PROBES = listOf(
        "http://focusguard-a.invalid/",
        "http://focusguard-b.invalid/focusguard-check"
    )

    // Compatibility aliases retained for existing callers/tests.
    internal val HTTPS_PROBE: String = HTTPS_PROBES.first()
    internal val HTTP_PROBE: String = HTTP_PROBES.first()

    private data class PackageIdentity(
        val versionCode: Long,
        val firstInstallTime: Long,
        val lastUpdateTime: Long,
        val applicationEnabled: Boolean,
        val enabledSetting: Int
    )

    private data class CacheEntry(
        val identity: PackageIdentity,
        val decision: BrowserDetectionDecision,
        val expiresAtElapsedMillis: Long,
        val unknownAttempt: Int
    )

    private data class ActivityProbe(
        val status: BrowserProbeResult,
        val broadByComponent: Map<String, Boolean> = emptyMap()
    ) {
        val components: Set<String>
            get() = broadByComponent.keys
    }

    private class CollectionLease(val id: Long) {
        private val valid = AtomicBoolean(true)
        fun invalidate() = valid.set(false)
        fun isValid(): Boolean = valid.get()
    }

    @Volatile
    private var appContext: Context? = null
    private val classificationCache = ConcurrentHashMap<String, CacheEntry>()
    private val collectionLeaseCounter = AtomicLong(0L)
    private val collectionExecutor = ThreadPoolExecutor(
        1,
        1,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        { runnable ->
            Thread(runnable, "FocusGuard-BrowserDetector").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy()
    ).apply {
        allowCoreThreadTimeOut(true)
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        classificationCache.clear()
    }

    fun invalidate(packageName: String) {
        if (packageName.isNotBlank()) classificationCache.remove(packageName)
    }

    fun invalidateAll() {
        classificationCache.clear()
    }

    fun classify(packageName: String): BrowserClassification = detect(packageName).classification

    fun detect(packageName: String): BrowserDetectionDecision {
        if (packageName.isBlank()) return unknownDecision()

        // Registered packages never enter unknown-browser collection, even when a
        // particular browser version/mode later proves unavailable to its adapter.
        if (BrowserProfileRegistry.isKnownBrowserPackage(packageName)) {
            return BrowserDetectionDecision(
                BrowserClassification.CONFIRMED_BROWSER,
                BrowserDetectionReason.KNOWN_BROWSER_PROFILE
            )
        }

        val context = appContext ?: return BrowserDetectionDecision(
            BrowserClassification.UNKNOWN,
            BrowserDetectionReason.DETECTOR_UNINITIALIZED
        )
        return detectUnknownBounded(context, packageName)
    }

    private fun detectUnknownBounded(
        context: Context,
        packageName: String
    ): BrowserDetectionDecision {
        val lease = CollectionLease(collectionLeaseCounter.incrementAndGet())
        val future = try {
            collectionExecutor.submit<BrowserDetectionDecision> {
                detectUnknownBlocking(context, packageName, lease)
            }
        } catch (_: RejectedExecutionException) {
            return unknownDecision()
        }

        return try {
            future.get(COLLECTION_DEADLINE_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            lease.invalidate()
            future.cancel(true)
            FocusGuardLogger.addBreadcrumb(
                "BrowserDetector[$packageName]: collection_timeout"
            )
            unknownDecision()
        } catch (_: InterruptedException) {
            lease.invalidate()
            future.cancel(true)
            Thread.currentThread().interrupt()
            unknownDecision()
        } catch (_: java.util.concurrent.ExecutionException) {
            lease.invalidate()
            unknownDecision()
        }
    }

    private fun detectUnknownBlocking(
        context: Context,
        packageName: String,
        lease: CollectionLease
    ): BrowserDetectionDecision {
        val identity = readPackageIdentity(context, packageName) ?: return unknownDecision()
        if (!lease.isValid()) return unknownDecision()

        val now = SystemClock.elapsedRealtime()
        val previous = classificationCache[packageName]
        if (previous != null &&
            previous.identity == identity &&
            now < previous.expiresAtElapsedMillis
        ) {
            return previous.decision.copy(fromCache = true)
        }

        val evidence = collectEvidence(context, packageName)
        if (!lease.isValid()) return unknownDecision()

        val decision = BrowserClassificationPolicy.decide(evidence)
        val unknownAttempt = if (decision.classification == BrowserClassification.UNKNOWN) {
            (previous?.takeIf { it.identity == identity }?.unknownAttempt ?: 0) + 1
        } else {
            0
        }
        val cacheMillis = when (decision.classification) {
            BrowserClassification.CONFIRMED_BROWSER -> POSITIVE_CACHE_MILLIS
            BrowserClassification.UNKNOWN -> minOf(
                NEGATIVE_CACHE_MILLIS,
                BrowserUnknownRetryPolicy.retryDelayMillis(unknownAttempt)
            )
            BrowserClassification.PROBABLE_BROWSER,
            BrowserClassification.NOT_BROWSER -> NEGATIVE_CACHE_MILLIS
        }
        if (!lease.isValid()) return unknownDecision()
        classificationCache[packageName] = CacheEntry(
            identity = identity,
            decision = decision,
            expiresAtElapsedMillis = SystemClock.elapsedRealtime() + cacheMillis,
            unknownAttempt = unknownAttempt
        )
        FocusGuardLogger.addBreadcrumb(
            "BrowserDetector[$packageName]: ${decision.reason.name}"
        )
        return decision
    }

    private fun collectEvidence(context: Context, packageName: String): BrowserCapabilityEvidence {
        val httpsProbes = HTTPS_PROBES.map { url ->
            queryBrowsableActivity(context, packageName, url, includeResolvedFilter = true)
        }
        val httpsUnknown = httpsProbes.any { it.status == BrowserProbeResult.UNKNOWN }
        val commonHttpsComponents = if (httpsUnknown) emptySet() else {
            intersectComponents(httpsProbes)
        }
        val g = when {
            httpsUnknown -> BrowserProbeResult.UNKNOWN
            commonHttpsComponents.isNotEmpty() -> BrowserProbeResult.HANDLED
            else -> BrowserProbeResult.NOT_HANDLED
        }
        val b = when {
            httpsUnknown -> BrowserProbeResult.UNKNOWN
            commonHttpsComponents.isEmpty() -> BrowserProbeResult.NOT_HANDLED
            commonHttpsComponents.any { component ->
                httpsProbes.all { probe -> probe.broadByComponent[component] == true }
            } -> BrowserProbeResult.HANDLED
            else -> BrowserProbeResult.NOT_HANDLED
        }

        val category = queryBrowserCategory(context, packageName)

        val httpProbes = HTTP_PROBES.map { url ->
            queryBrowsableActivity(context, packageName, url, includeResolvedFilter = false)
        }
        val h = when {
            httpProbes.any { it.status == BrowserProbeResult.UNKNOWN } -> BrowserProbeResult.UNKNOWN
            intersectComponents(httpProbes).isNotEmpty() -> BrowserProbeResult.HANDLED
            else -> BrowserProbeResult.NOT_HANDLED
        }

        val customTabs = queryCustomTabsProvider(context, packageName)

        return BrowserCapabilityEvidence(
            genericHttp = h,
            genericHttps = g,
            broadHttpsFilter = b,
            browserCategory = category,
            customTabsService = customTabs
        )
    }

    private fun intersectComponents(probes: List<ActivityProbe>): Set<String> {
        if (probes.isEmpty() || probes.any { it.status != BrowserProbeResult.HANDLED }) {
            return emptySet()
        }
        return probes.drop(1).fold(probes.first().components.toMutableSet()) { common, probe ->
            common.apply { retainAll(probe.components) }
        }
    }

    internal fun probeGenericHandler(
        context: Context,
        packageName: String,
        url: String
    ): BrowserProbeResult = queryBrowsableActivity(
        context = context,
        packageName = packageName,
        url = url,
        includeResolvedFilter = false
    ).status

    private fun queryBrowsableActivity(
        context: Context,
        packageName: String,
        url: String,
        includeResolvedFilter: Boolean
    ): ActivityProbe = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            setPackage(packageName)
        }
        val flags = PackageManager.MATCH_DEFAULT_ONLY or
            if (includeResolvedFilter) PackageManager.GET_RESOLVED_FILTER else 0
        @Suppress("DEPRECATION")
        val matches = context.packageManager.queryIntentActivities(intent, flags)
        val usable = linkedMapOf<String, Boolean>()
        matches.forEach { resolveInfo ->
            val activity = resolveInfo.activityInfo
                ?.takeIf { isUsableActivity(context, it, packageName) }
                ?: return@forEach
            val component = componentKey(activity)
            val broad = includeResolvedFilter && isBroadWebFilter(resolveInfo.filter)
            // PackageManager may return more than one matching filter for the same
            // Activity. The Activity is broad for this probe when at least one of
            // those filters is broad; result ordering must never change B.
            usable[component] = usable[component] == true || broad
        }
        ActivityProbe(
            status = if (usable.isEmpty()) {
                BrowserProbeResult.NOT_HANDLED
            } else {
                BrowserProbeResult.HANDLED
            },
            broadByComponent = usable
        )
    } catch (_: RuntimeException) {
        ActivityProbe(BrowserProbeResult.UNKNOWN)
    }

    private fun queryBrowserCategory(
        context: Context,
        packageName: String
    ): BrowserProbeResult = try {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_BROWSER)
            setPackage(packageName)
        }
        @Suppress("DEPRECATION")
        val matches = context.packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY
        )
        if (matches.any { resolve ->
                resolve.activityInfo?.let { isUsableActivity(context, it, packageName) } == true
            }
        ) {
            BrowserProbeResult.HANDLED
        } else {
            BrowserProbeResult.NOT_HANDLED
        }
    } catch (_: RuntimeException) {
        BrowserProbeResult.UNKNOWN
    }

    private fun queryCustomTabsProvider(
        context: Context,
        packageName: String
    ): BrowserProbeResult = try {
        val intent = Intent(CUSTOM_TABS_SERVICE_ACTION).setPackage(packageName)
        @Suppress("DEPRECATION")
        val matches = context.packageManager.queryIntentServices(intent, 0)
        if (matches.any { resolve ->
                resolve.serviceInfo?.let { isUsableService(context, it, packageName) } == true
            }
        ) {
            BrowserProbeResult.HANDLED
        } else {
            BrowserProbeResult.NOT_HANDLED
        }
    } catch (_: RuntimeException) {
        BrowserProbeResult.UNKNOWN
    }

    private fun isUsableActivity(
        context: Context,
        activity: ActivityInfo,
        packageName: String
    ): Boolean =
        activity.packageName == packageName &&
            activity.exported &&
            activity.enabled &&
            activity.applicationInfo?.enabled != false &&
            permissionUsableByFocusGuard(context, activity.permission)

    private fun isUsableService(
        context: Context,
        service: ServiceInfo,
        packageName: String
    ): Boolean =
        service.packageName == packageName &&
            service.exported &&
            service.enabled &&
            service.applicationInfo?.enabled != false &&
            permissionUsableByFocusGuard(context, service.permission)

    private fun permissionUsableByFocusGuard(context: Context, permission: String?): Boolean =
        permission.isNullOrBlank() ||
            context.packageManager.checkPermission(permission, context.packageName) ==
            PackageManager.PERMISSION_GRANTED

    private fun componentKey(activity: ActivityInfo): String =
        "${activity.packageName}/${activity.name}"

    private fun isBroadWebFilter(filter: IntentFilter?): Boolean {
        if (filter == null) return false
        if (filter.countDataAuthorities() > 0 ||
            filter.countDataPaths() > 0 ||
            filter.countDataSchemeSpecificParts() > 0
        ) return false
        if (Build.VERSION.SDK_INT >= 35 && filter.countUriRelativeFilterGroups() > 0) {
            return false
        }
        return true
    }

    private fun readPackageIdentity(
        context: Context,
        packageName: String
    ): PackageIdentity? = try {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(packageName, 0)
        val enabledSetting = runCatching {
            context.packageManager.getApplicationEnabledSetting(packageName)
        }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        PackageIdentity(
            versionCode = if (Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            },
            firstInstallTime = info.firstInstallTime,
            lastUpdateTime = info.lastUpdateTime,
            applicationEnabled = info.applicationInfo?.enabled != false,
            enabledSetting = enabledSetting
        )
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private fun unknownDecision(): BrowserDetectionDecision = BrowserDetectionDecision(
        BrowserClassification.UNKNOWN,
        BrowserDetectionReason.PACKAGE_QUERY_UNKNOWN
    )
}
