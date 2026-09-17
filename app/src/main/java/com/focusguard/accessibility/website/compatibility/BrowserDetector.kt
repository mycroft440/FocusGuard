package com.focusguard.accessibility.website.compatibility

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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

internal data class BrowserCapabilityEvidence(
    val browserRole: BrowserProbeResult = BrowserProbeResult.NOT_HANDLED,
    val genericHttp: BrowserProbeResult = BrowserProbeResult.NOT_HANDLED,
    val genericHttpsPrimary: BrowserProbeResult = BrowserProbeResult.NOT_HANDLED,
    val genericHttpsSecondary: BrowserProbeResult = BrowserProbeResult.NOT_HANDLED
)

/** Pure classification kept separate from Android queries so false-positive rules stay testable. */
internal object BrowserClassificationPolicy {
    fun classify(evidence: BrowserCapabilityEvidence): BrowserClassification {
        if (evidence.browserRole == BrowserProbeResult.HANDLED) {
            return BrowserClassification.CONFIRMED_BROWSER
        }

        val genericProbes = listOf(
            evidence.genericHttp,
            evidence.genericHttpsPrimary,
            evidence.genericHttpsSecondary
        )
        val handled = genericProbes.count { it == BrowserProbeResult.HANDLED }
        val unknown = genericProbes.any { it == BrowserProbeResult.UNKNOWN } ||
            evidence.browserRole == BrowserProbeResult.UNKNOWN

        return when {
            evidence.genericHttp == BrowserProbeResult.HANDLED &&
                evidence.genericHttpsPrimary == BrowserProbeResult.HANDLED &&
                evidence.genericHttpsSecondary == BrowserProbeResult.HANDLED ->
                BrowserClassification.CONFIRMED_BROWSER

            evidence.genericHttpsPrimary == BrowserProbeResult.HANDLED &&
                evidence.genericHttpsSecondary == BrowserProbeResult.HANDLED ->
                BrowserClassification.PROBABLE_BROWSER

            handled >= 2 -> BrowserClassification.PROBABLE_BROWSER
            handled == 1 || unknown -> BrowserClassification.UNKNOWN
            else -> BrowserClassification.NOT_BROWSER
        }
    }
}

/**
 * Detects generic browsers without treating one App Link or one WebView surface as a browser.
 *
 * Probes are package-scoped and use reserved `.invalid` hosts, so no network request is made and
 * host-specific apps do not become browser evidence. Results are cached until the package changes.
 */
internal object BrowserDetector {
    private const val HTTP_PROBE = "http://focusguard-browser-check-a.invalid/"
    private const val HTTPS_PROBE_PRIMARY = "https://focusguard-browser-check-a.invalid/"
    private const val HTTPS_PROBE_SECONDARY = "https://focusguard-browser-check-b.invalid/"

    @Volatile
    private var appContext: Context? = null
    private val classificationCache = ConcurrentHashMap<String, BrowserClassification>()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        classificationCache.clear()
    }

    fun invalidate(packageName: String) {
        if (packageName.isNotBlank()) classificationCache.remove(packageName)
    }

    fun classify(packageName: String): BrowserClassification {
        if (packageName.isBlank()) return BrowserClassification.UNKNOWN
        classificationCache[packageName]?.let { return it }
        val context = appContext ?: return BrowserClassification.UNKNOWN
        val classification = BrowserClassificationPolicy.classify(
            BrowserCapabilityEvidence(
                browserRole = probeBrowserRole(context, packageName),
                genericHttp = probeGenericHandler(context, packageName, HTTP_PROBE),
                genericHttpsPrimary = probeGenericHandler(
                    context,
                    packageName,
                    HTTPS_PROBE_PRIMARY
                ),
                genericHttpsSecondary = probeGenericHandler(
                    context,
                    packageName,
                    HTTPS_PROBE_SECONDARY
                )
            )
        )
        classificationCache[packageName] = classification
        return classification
    }

    private fun probeBrowserRole(context: Context, packageName: String): BrowserProbeResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return BrowserProbeResult.NOT_HANDLED
        return try {
            val roleManager = context.getSystemService(RoleManager::class.java)
                ?: return BrowserProbeResult.UNKNOWN
            if (!roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
                BrowserProbeResult.NOT_HANDLED
            } else if (packageName in roleManager.getRoleHolders(RoleManager.ROLE_BROWSER)) {
                BrowserProbeResult.HANDLED
            } else {
                BrowserProbeResult.NOT_HANDLED
            }
        } catch (_: RuntimeException) {
            BrowserProbeResult.UNKNOWN
        }
    }

    private fun probeGenericHandler(
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
