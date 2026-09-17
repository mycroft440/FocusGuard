package com.focusguard.accessibility.website.compatibility

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import com.focusguard.utils.FocusGuardLogger
import java.util.concurrent.ConcurrentHashMap

internal enum class BrowserClassification {
    NOT_BROWSER,
    PROBABLE_BROWSER,
    CONFIRMED_BROWSER,
    UNKNOWN;

    val isBrowserLike: Boolean
        get() = this == PROBABLE_BROWSER || this == CONFIRMED_BROWSER
}

internal enum class BrowserProbeResult {
    HANDLED,
    NOT_HANDLED,
    UNKNOWN
}

internal enum class BrowserDetectionReason {
    HTTP_HTTPS_CONFIRMED,
    CURRENT_VERSION_HISTORY,
    PACKAGE_QUERY_UNKNOWN,
    PARTIAL_GENERIC_HANDLER,
    NO_GENERIC_HANDLER,
    DETECTOR_UNINITIALIZED
}

internal data class BrowserDetectionDecision(
    val classification: BrowserClassification,
    val reason: BrowserDetectionReason,
    val fromCache: Boolean = false
)

internal data class BrowserCapabilityEvidence(
    val genericHttp: BrowserProbeResult = BrowserProbeResult.NOT_HANDLED,
    val genericHttps: BrowserProbeResult = BrowserProbeResult.NOT_HANDLED
)

/** Pure classification kept separate from Android queries so false-positive rules stay testable. */
internal object BrowserClassificationPolicy {
    fun classify(evidence: BrowserCapabilityEvidence): BrowserClassification =
        decide(evidence, strongCurrentVersionHistory = false).classification

    fun decide(
        evidence: BrowserCapabilityEvidence,
        strongCurrentVersionHistory: Boolean
    ): BrowserDetectionDecision {
        if (evidence.genericHttp == BrowserProbeResult.HANDLED &&
            evidence.genericHttps == BrowserProbeResult.HANDLED
        ) {
            return BrowserDetectionDecision(
                BrowserClassification.CONFIRMED_BROWSER,
                BrowserDetectionReason.HTTP_HTTPS_CONFIRMED
            )
        }

        val queryUncertain = evidence.genericHttp == BrowserProbeResult.UNKNOWN ||
            evidence.genericHttps == BrowserProbeResult.UNKNOWN
        val partialGenericHandling = evidence.genericHttp == BrowserProbeResult.HANDLED ||
            evidence.genericHttps == BrowserProbeResult.HANDLED

        if (queryUncertain && strongCurrentVersionHistory) {
            return BrowserDetectionDecision(
                BrowserClassification.PROBABLE_BROWSER,
                BrowserDetectionReason.CURRENT_VERSION_HISTORY
            )
        }

        return when {
            queryUncertain -> BrowserDetectionDecision(
                BrowserClassification.UNKNOWN,
                BrowserDetectionReason.PACKAGE_QUERY_UNKNOWN
            )
            partialGenericHandling -> BrowserDetectionDecision(
                BrowserClassification.UNKNOWN,
                BrowserDetectionReason.PARTIAL_GENERIC_HANDLER
            )
            else -> BrowserDetectionDecision(
                BrowserClassification.NOT_BROWSER,
                BrowserDetectionReason.NO_GENERIC_HANDLER
            )
        }
    }
}

/** Keeps inconclusive PackageManager failures cheap while still retrying them quickly. */
internal object BrowserUnknownRetryPolicy {
    private const val INITIAL_RETRY_MILLIS = 250L
    private const val MAX_RETRY_MILLIS = 4_000L

    fun retryDelayMillis(attempt: Int): Long {
        val exponent = (attempt.coerceAtLeast(1) - 1).coerceAtMost(4)
        return (INITIAL_RETRY_MILLIS shl exponent).coerceAtMost(MAX_RETRY_MILLIS)
    }
}

/**
 * Detects generic browsers without treating one App Link or one WebView surface as a browser.
 *
 * Probes are package-scoped and use a reserved `.invalid` host, so no network request is made.
 * A package must accept both generic HTTP and HTTPS URLs. One scheme alone stays UNKNOWN and
 * therefore cannot enable the new opaque-browser fail-closed path. Stable results are cached until
 * the package changes. UNKNOWN results are retried with a short bounded backoff; a PackageManager
 * query failure may become PROBABLE_BROWSER only when the compatibility store has strong positive
 * evidence recorded for the exact currently installed package version.
 */
internal object BrowserDetector {
    internal const val HTTP_PROBE = "http://focusguard-browser-check.invalid/"
    internal const val HTTPS_PROBE = "https://focusguard-browser-check.invalid/"

    private data class CacheEntry(
        val decision: BrowserDetectionDecision,
        val retryAtElapsedMillis: Long,
        val unknownAttempt: Int
    )

    @Volatile
    private var appContext: Context? = null
    private val classificationCache = ConcurrentHashMap<String, CacheEntry>()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        classificationCache.clear()
    }

    fun invalidate(packageName: String) {
        if (packageName.isNotBlank()) classificationCache.remove(packageName)
    }

    fun classify(packageName: String): BrowserClassification = detect(packageName).classification

    fun detect(packageName: String): BrowserDetectionDecision {
        if (packageName.isBlank()) {
            return BrowserDetectionDecision(
                BrowserClassification.UNKNOWN,
                BrowserDetectionReason.PACKAGE_QUERY_UNKNOWN
            )
        }
        val context = appContext ?: return BrowserDetectionDecision(
            BrowserClassification.UNKNOWN,
            BrowserDetectionReason.DETECTOR_UNINITIALIZED
        )
        val now = SystemClock.elapsedRealtime()
        val previous = classificationCache[packageName]
        if (previous != null &&
            (previous.decision.classification != BrowserClassification.UNKNOWN ||
                now < previous.retryAtElapsedMillis)
        ) {
            return previous.decision.copy(fromCache = true)
        }

        val evidence = BrowserCapabilityEvidence(
            genericHttp = probeGenericHandler(context, packageName, HTTP_PROBE),
            genericHttps = probeGenericHandler(context, packageName, HTTPS_PROBE)
        )
        val rawUncertain = evidence.genericHttp == BrowserProbeResult.UNKNOWN ||
            evidence.genericHttps == BrowserProbeResult.UNKNOWN
        val decision = BrowserClassificationPolicy.decide(
            evidence = evidence,
            strongCurrentVersionHistory = rawUncertain &&
                BrowserCompatibilityStore.hasStrongCurrentVersionBrowserEvidence(packageName)
        )
        val unknownAttempt = if (decision.classification == BrowserClassification.UNKNOWN) {
            (previous?.unknownAttempt ?: 0) + 1
        } else {
            0
        }
        val retryAt = if (decision.classification == BrowserClassification.UNKNOWN) {
            now + BrowserUnknownRetryPolicy.retryDelayMillis(unknownAttempt)
        } else {
            Long.MAX_VALUE
        }
        classificationCache[packageName] = CacheEntry(decision, retryAt, unknownAttempt)
        FocusGuardLogger.addBreadcrumb(
            "BrowserDetector[$packageName]: ${decision.reason.name}"
        )
        return decision
    }

    internal fun probeGenericHandler(
        context: Context,
        packageName: String,
        url: String
    ): BrowserProbeResult = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            setPackage(packageName)
        }
        @Suppress("DEPRECATION")
        val matches = context.packageManager.queryIntentActivities(intent, 0)
        if (matches.any { it.activityInfo?.packageName == packageName }) {
            BrowserProbeResult.HANDLED
        } else {
            BrowserProbeResult.NOT_HANDLED
        }
    } catch (_: RuntimeException) {
        BrowserProbeResult.UNKNOWN
    }
}
