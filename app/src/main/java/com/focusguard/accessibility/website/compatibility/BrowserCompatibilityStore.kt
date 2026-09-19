package com.focusguard.accessibility.website.compatibility

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.pm.PackageInfoCompat
import com.focusguard.utils.BrowserUiCapabilityPolicy
import com.focusguard.utils.WebsiteBlocker
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal enum class BrowserCompatibilityStatus {
    UNKNOWN,
    SUPPORTED,
    DEGRADED,
    UNSUPPORTED
}

internal enum class BrowserIdentificationMethod {
    EVENT_SOURCE,
    CACHED_RESOURCE_ID,
    STRONG_RESOURCE_ID,
    SEMANTIC_TREE
}

internal enum class BrowserUrlRecoveryMethod { CLICK, FOCUS, REVEAL_TOOLBAR }

internal enum class BrowserActivationMethod {
    FOCUS,
    CLICK
}

internal enum class BrowserWriteMethod {
    SET_TEXT,
    PASTE
}

internal enum class BrowserSubmitMethod {
    IME_ENTER,
    ANNOUNCED_EDITOR_ACTION,
    CERTIFIED_GO_BUTTON
}

internal data class BrowserCompatibilityRecord(
    val packageName: String,
    val status: BrowserCompatibilityStatus = BrowserCompatibilityStatus.UNKNOWN,
    val packageVersionCode: Long? = null,
    val preferredAddressBarEntryName: String? = null,
    val identificationMethod: BrowserIdentificationMethod? = null,
    val preferredUrlEntryName: String? = null,
    val preferredUrlMethod: BrowserIdentificationMethod? = null,
    val urlRecoveryMethod: BrowserUrlRecoveryMethod? = null,
    val activationMethod: BrowserActivationMethod? = null,
    val writeMethod: BrowserWriteMethod? = null,
    val submitMethod: BrowserSubmitMethod? = null,
    val consecutiveObservationFailures: Int = 0,
    val consecutiveRedirectionFailures: Int = 0,
    val updatedAtMillis: Long = 0L
)

/**
 * Persistent compatibility memory for browser Accessibility surfaces.
 *
 * A successful resource id/method is attempted first on the next visit. The
 * complete fail-closed discovery path remains available whenever the cached
 * strategy stops working, so browser updates can self-heal the profile.
 *
 * Strong browser evidence is version-bound. Selectors/methods from an older browser version may
 * still be attempted as hints, but they cannot promote an inconclusive PackageManager probe into
 * PROBABLE_BROWSER until the current version produces fresh positive evidence.
 */
internal object BrowserCompatibilityStore {
    private const val PREFS_NAME = "browser_compatibility_cache_v1"
    private const val KEY_PACKAGES = "packages"
    private const val KEY_PREFIX = "record:"
    private const val OBSERVATION_FAILURE_MIN_SPAN_MILLIS = 200L
    private const val SUPPORTED_FAILURE_THRESHOLD = 3
    private const val REDIRECTION_DEGRADED_THRESHOLD = 2
    private const val REDIRECTION_UNSUPPORTED_THRESHOLD = 5
    private const val REDIRECTION_FAILURE_DEBOUNCE_MILLIS = 150L
    private const val NATIVE_UI_EVIDENCE_MAX_AGE_NANOS = 1_500_000_000L

    private val lock = Any()
    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null
    private val cache = linkedMapOf<String, BrowserCompatibilityRecord>()
    private val packageVersionCache = mutableMapOf<String, Long>()
    private val pendingRedirects = mutableMapOf<String, PendingRedirect>()
    private val firstObservationFailureAt = mutableMapOf<String, Long>()
    private val lastRedirectionFailureAt = mutableMapOf<String, Long>()
    private var verifiedHttpsHandlerPackages: Set<String> = emptySet()
    private var lastUnobservablePackage: String? = null
    private var lastNativeUiPackage: String? = null
    private var lastNativeUiEvidenceAtNanos: Long = 0L
    private val _testedRecords = MutableStateFlow<List<BrowserCompatibilityRecord>>(emptyList())

    val testedRecords: StateFlow<List<BrowserCompatibilityRecord>> = _testedRecords.asStateFlow()

    private data class PendingRedirect(
        val normalizedTarget: String,
        val submitted: Boolean,
        val candidateSubmitMethod: BrowserSubmitMethod? = null,
        val candidateSubmitEntryName: String? = null,
        val acceptedSubmitMethods: Set<BrowserSubmitMethod> = emptySet()
    )

    fun initialize(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            appContext = context.applicationContext
            prefs = appContext!!.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            verifiedHttpsHandlerPackages = queryHttpsHandlerPackages(appContext!!)
            val packageNames = prefs?.getStringSet(KEY_PACKAGES, emptySet()).orEmpty()
            val stalePackages = linkedSetOf<String>()
            packageNames.sorted().forEach { packageName ->
                val record = readRecord(packageName)
                if (record != null && shouldRetainRecord(record, verifiedHttpsHandlerPackages)) {
                    cache[packageName] = record
                } else {
                    stalePackages += packageName
                }
            }
            if (stalePackages.isNotEmpty()) {
                val retainedPackages = packageNames - stalePackages
                val editor = prefs?.edit()?.putStringSet(KEY_PACKAGES, retainedPackages)
                stalePackages.forEach { packageName -> editor?.remove(KEY_PREFIX + packageName) }
                editor?.apply()
            }
            publishLocked()
        }
    }

    fun invalidatePackageMetadata(packageName: String) {
        if (packageName.isBlank()) return
        synchronized(lock) {
            packageVersionCache.remove(packageName)
        }
    }

    fun hasStrongCurrentVersionBrowserEvidence(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return synchronized(lock) {
            hasStrongBrowserEvidenceForVersion(
                record = cache[packageName],
                installedVersionCode = installedVersionCodeLocked(packageName)
            )
        }
    }

    internal fun hasStrongBrowserEvidenceForVersion(
        record: BrowserCompatibilityRecord?,
        installedVersionCode: Long?
    ): Boolean {
        if (record == null || installedVersionCode == null ||
            record.packageVersionCode != installedVersionCode
        ) {
            return false
        }
        return record.status == BrowserCompatibilityStatus.SUPPORTED ||
            record.status == BrowserCompatibilityStatus.DEGRADED ||
            (!record.preferredUrlEntryName.isNullOrBlank() && record.preferredUrlMethod != null) ||
            record.urlRecoveryMethod != null
    }

    fun preferredAddressBarEntryName(packageName: String): String? = synchronized(lock) {
        cache[packageName]?.preferredAddressBarEntryName?.takeIf(String::isNotBlank)
    }

    fun preferredUrlEntryName(packageName: String): String? =
        synchronized(lock) { cache[packageName]?.preferredUrlEntryName }

    fun preferredUrlMethod(packageName: String): BrowserIdentificationMethod? =
        synchronized(lock) { cache[packageName]?.preferredUrlMethod }

    fun preferredUrlRecoveryMethod(packageName: String): BrowserUrlRecoveryMethod? =
        synchronized(lock) { cache[packageName]?.urlRecoveryMethod }

    fun prioritizeUrlEntryNames(packageName: String, defaults: Iterable<String>): List<String> =
        synchronized(lock) {
            val preferred = cache[packageName]?.preferredUrlEntryName?.takeIf(String::isNotBlank)
            val orderedDefaults = BrowserProfileRegistry.prioritizeAddressBarEntryNames(
                packageName = packageName,
                defaults = defaults
            )
            buildList {
                // A temporary editor can expose a valid URL while typing. Once a
                // stable display selector is available, do not keep that editor
                // ahead of the display-mode address component on later reads.
                if (BrowserUiCapabilityPolicy.isStableUrlEntryName(preferred)) add(preferred!!)
                orderedDefaults
                    .filter(BrowserUiCapabilityPolicy::isStableUrlEntryName)
                    .forEach { entry -> if (entry !in this) add(entry) }
                if (!preferred.isNullOrBlank() && preferred !in this) add(preferred)
                orderedDefaults.forEach { entry -> if (entry !in this) add(entry) }
            }
        }

    fun recordUrlRecoverySuccess(packageName: String, method: BrowserUrlRecoveryMethod) {
        synchronized(lock) {
            val previous = recordForLocked(packageName)
            saveLocked(
                previous.copy(
                    packageVersionCode = installedVersionCodeLocked(packageName)
                        ?: previous.packageVersionCode,
                    urlRecoveryMethod = method,
                    updatedAtMillis = System.currentTimeMillis()
                )
            )
        }
    }

    /** Acceptance of a submit action is not proof that the page navigated. */
    fun recordNavigationConfirmed(packageName: String) {
        synchronized(lock) {
            val pending = pendingRedirects[packageName] ?: return
            if (!pending.submitted) return
            pendingRedirects.remove(packageName)
            val previous = recordForLocked(packageName)
            saveLocked(
                previous.copy(
                    status = BrowserCompatibilityStatus.SUPPORTED,
                    packageVersionCode = installedVersionCodeLocked(packageName)
                        ?: previous.packageVersionCode,
                    preferredAddressBarEntryName = pending.candidateSubmitEntryName
                        ?: previous.preferredAddressBarEntryName,
                    submitMethod = pending.candidateSubmitMethod ?: previous.submitMethod,
                    consecutiveRedirectionFailures = 0,
                    updatedAtMillis = System.currentTimeMillis()
                )
            )
        }
    }

    fun finishRedirection(packageName: String) {
        synchronized(lock) { pendingRedirects.remove(packageName) }
    }

    fun preferredActivationMethod(packageName: String): BrowserActivationMethod? =
        synchronized(lock) { cache[packageName]?.activationMethod }

    fun preferredWriteMethod(packageName: String): BrowserWriteMethod? =
        synchronized(lock) { cache[packageName]?.writeMethod }

    fun preferredSubmitMethod(packageName: String): BrowserSubmitMethod? =
        synchronized(lock) { cache[packageName]?.submitMethod }

    /**
     * Submission acceptance is transaction-local evidence only. If a method already
     * returned true for the current replacement address but no navigation was later
     * confirmed, retries must advance to another certified strategy instead of
     * repeating that accepted no-op forever.
     */
    fun mayAttemptSubmitMethod(
        packageName: String,
        method: BrowserSubmitMethod
    ): Boolean = synchronized(lock) {
        method !in pendingRedirects[packageName]?.acceptedSubmitMethods.orEmpty()
    }

    fun prioritizeAddressBarEntryNames(
        packageName: String,
        defaults: Iterable<String>
    ): List<String> {
        // WebsiteBlocker uses this discovery order for observation as well as
        // action collection. Keep reads URL-specific; actionable node ranking still
        // uses preferredAddressBarEntryName independently in BrowserUiCapabilityPolicy.
        return prioritizeUrlEntryNames(packageName, defaults)
    }

    fun recordIdentificationSuccess(
        packageName: String,
        viewIdResourceName: String?,
        method: BrowserIdentificationMethod,
        observedValue: String? = null
    ) {
        if (packageName.isBlank()) return
        synchronized(lock) {
            firstObservationFailureAt.remove(packageName)
            if (lastUnobservablePackage == packageName) lastUnobservablePackage = null
            if (lastNativeUiPackage == packageName) {
                lastNativeUiPackage = null
                lastNativeUiEvidenceAtNanos = 0L
            }
            val previous = recordForLocked(packageName)
            val entryName = browserOwnedEntryName(packageName, viewIdResourceName)
                ?: previous.preferredAddressBarEntryName
            val validUrl = pendingRedirects[packageName] == null &&
                observedValue?.let(WebsiteBlocker::extractUrlCandidate) != null
            val urlEntry = browserOwnedEntryName(packageName, viewIdResourceName)
            saveLocked(
                previous.copy(
                    packageVersionCode = if (validUrl) {
                        installedVersionCodeLocked(packageName) ?: previous.packageVersionCode
                    } else {
                        previous.packageVersionCode
                    },
                    preferredAddressBarEntryName = entryName,
                    identificationMethod = method,
                    preferredUrlEntryName = if (validUrl) urlEntry else previous.preferredUrlEntryName,
                    preferredUrlMethod = if (validUrl) method else previous.preferredUrlMethod,
                    consecutiveObservationFailures = 0,
                    updatedAtMillis = System.currentTimeMillis()
                )
            )
        }
    }

    fun recordActivationSuccess(
        packageName: String,
        viewIdResourceName: String?,
        method: BrowserActivationMethod
    ) = updateMethod(packageName, viewIdResourceName) { previous, entryName ->
        previous.copy(
            preferredAddressBarEntryName = entryName,
            activationMethod = method,
            updatedAtMillis = System.currentTimeMillis()
        )
    }

    fun recordWriteSuccess(
        packageName: String,
        viewIdResourceName: String?,
        method: BrowserWriteMethod,
        replacementText: String?
    ) = updateMethod(packageName, viewIdResourceName) { previous, entryName ->
        val normalizedTarget = normalizeAddress(replacementText)
        if (normalizedTarget.isNotEmpty()) {
            val existing = pendingRedirects[packageName]
            val acceptedMethods = if (existing?.normalizedTarget == normalizedTarget) {
                existing.acceptedSubmitMethods
            } else {
                emptySet()
            }
            pendingRedirects[packageName] = PendingRedirect(
                normalizedTarget = normalizedTarget,
                submitted = false,
                acceptedSubmitMethods = acceptedMethods
            )
        }
        previous.copy(
            preferredAddressBarEntryName = entryName,
            writeMethod = method,
            updatedAtMillis = System.currentTimeMillis()
        )
    }

    fun recordSubmitAccepted(
        packageName: String,
        viewIdResourceName: String?,
        method: BrowserSubmitMethod
    ) {
        if (packageName.isBlank()) return
        synchronized(lock) {
            val pending = pendingRedirects[packageName] ?: return
            pendingRedirects[packageName] = pending.copy(
                submitted = true,
                candidateSubmitMethod = method,
                candidateSubmitEntryName = browserOwnedEntryName(packageName, viewIdResourceName),
                acceptedSubmitMethods = pending.acceptedSubmitMethods + method
            )
        }
    }

    /**
     * Called after one complete address-bar lookup returned no match. Transient
     * browser UI states inside the same 200 ms observability grace do not mark a
     * browser unsupported; the failure must persist across that grace window.
     *
     * Speculative recognition also calls this lookup for foreground packages that
     * have not yet been proven to be browsers. Those misses must stay side-effect
     * free, otherwise System UI, launchers, keyboards and other native components
     * become fake "unsupported browsers" in the settings screen.
     */
    fun recordUnobservableFailure(packageName: String) {
        if (packageName.isBlank()) return
        synchronized(lock) {
            lastUnobservablePackage = packageName
            val existing = cache[packageName]
            if (packageName !in verifiedHttpsHandlerPackages &&
                existing?.hasPositiveBrowserEvidence() != true
            ) {
                return
            }

            val now = System.currentTimeMillis()
            val firstFailureAt = firstObservationFailureAt.getOrPut(packageName) { now }
            val previous = recordForLocked(packageName)
            val failures = previous.consecutiveObservationFailures + 1
            val sustainedFailure = now - firstFailureAt >= OBSERVATION_FAILURE_MIN_SPAN_MILLIS
            val trustedPreviousStatus = previous.status == BrowserCompatibilityStatus.SUPPORTED ||
                previous.status == BrowserCompatibilityStatus.DEGRADED
            val unsupported = sustainedFailure && (
                !trustedPreviousStatus || failures >= SUPPORTED_FAILURE_THRESHOLD
            )
            saveLocked(
                previous.copy(
                    status = if (unsupported) {
                        BrowserCompatibilityStatus.UNSUPPORTED
                    } else {
                        previous.status
                    },
                    consecutiveObservationFailures = failures,
                    updatedAtMillis = now
                )
            )
        }
    }

    /** Marks browser-owned native menu/settings UI without pretending it is a URL bar. */
    fun recordNativeUiEvidence(packageName: String) {
        if (packageName.isBlank()) return
        synchronized(lock) {
            lastNativeUiPackage = packageName
            lastNativeUiEvidenceAtNanos = System.nanoTime()
        }
    }

    fun latestUnobservableSurfaceHasNativeUiEvidence(): Boolean = synchronized(lock) {
        val failedPackage = lastUnobservablePackage ?: return@synchronized false
        if (failedPackage != lastNativeUiPackage) return@synchronized false
        val observedAt = lastNativeUiEvidenceAtNanos
        observedAt > 0L &&
            System.nanoTime() - observedAt in 0L..NATIVE_UI_EVIDENCE_MAX_AGE_NANOS
    }

    /**
     * The active website transition currently performs two full same-tab attempts. Two failures
     * now degrade the learned profile and clear the preferred submit method instead of permanently
     * declaring the browser unsupported. Continued failures eventually mark it unsupported; any
     * later confirmed navigation immediately restores SUPPORTED and resets the failure counter.
     */
    fun recordRedirectionFailure(packageName: String) {
        if (packageName.isBlank()) return
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val previousFailureAt = lastRedirectionFailureAt[packageName] ?: 0L
            if (now - previousFailureAt < REDIRECTION_FAILURE_DEBOUNCE_MILLIS) return
            lastRedirectionFailureAt[packageName] = now

            val previous = recordForLocked(packageName)
            val failures = previous.consecutiveRedirectionFailures + 1
            val nextStatus = statusAfterRedirectionFailure(previous.status, failures)
            if (failures >= REDIRECTION_DEGRADED_THRESHOLD) pendingRedirects.remove(packageName)
            saveLocked(
                previous.copy(
                    status = nextStatus,
                    submitMethod = if (failures >= REDIRECTION_DEGRADED_THRESHOLD) {
                        null
                    } else {
                        previous.submitMethod
                    },
                    consecutiveRedirectionFailures = failures,
                    updatedAtMillis = now
                )
            )
        }
    }

    internal fun statusAfterRedirectionFailure(
        previousStatus: BrowserCompatibilityStatus,
        failures: Int
    ): BrowserCompatibilityStatus = when {
        failures >= REDIRECTION_UNSUPPORTED_THRESHOLD -> BrowserCompatibilityStatus.UNSUPPORTED
        failures >= REDIRECTION_DEGRADED_THRESHOLD -> BrowserCompatibilityStatus.DEGRADED
        else -> previousStatus
    }

    private inline fun updateMethod(
        packageName: String,
        viewIdResourceName: String?,
        transform: (BrowserCompatibilityRecord, String?) -> BrowserCompatibilityRecord
    ) {
        if (packageName.isBlank()) return
        synchronized(lock) {
            val previous = recordForLocked(packageName)
            val entryName = browserOwnedEntryName(packageName, viewIdResourceName)
                ?: previous.preferredAddressBarEntryName
            saveLocked(transform(previous, entryName))
        }
    }

    private fun recordForLocked(packageName: String): BrowserCompatibilityRecord =
        cache[packageName] ?: BrowserCompatibilityRecord(packageName = packageName)

    private fun installedVersionCodeLocked(packageName: String): Long? {
        packageVersionCache[packageName]?.let { return it }
        val context = appContext ?: return null
        val versionCode = runCatching {
            @Suppress("DEPRECATION")
            PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(packageName, 0))
        }.getOrNull()
        if (versionCode != null) packageVersionCache[packageName] = versionCode
        return versionCode
    }

    private fun saveLocked(record: BrowserCompatibilityRecord) {
        val previous = cache[record.packageName]
        if (previous != null && previous.copy(updatedAtMillis = record.updatedAtMillis) == record) {
            return
        }
        cache[record.packageName] = record
        val targetPrefs = prefs ?: return publishLocked()
        val packages = targetPrefs.getStringSet(KEY_PACKAGES, emptySet()).orEmpty().toMutableSet()
        packages += record.packageName
        targetPrefs.edit()
            .putStringSet(KEY_PACKAGES, packages)
            .putString(KEY_PREFIX + record.packageName, encode(record))
            .apply()
        publishLocked()
    }

    private fun publishLocked() {
        _testedRecords.value = cache.values
            .asSequence()
            .filter { it.status != BrowserCompatibilityStatus.UNKNOWN }
            .filter { shouldRetainRecord(it, verifiedHttpsHandlerPackages) }
            .sortedWith(compareBy({ it.status.name }, { it.packageName }))
            .toList()
    }

    private fun readRecord(packageName: String): BrowserCompatibilityRecord? {
        val raw = prefs?.getString(KEY_PREFIX + packageName, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            BrowserCompatibilityRecord(
                packageName = packageName,
                status = enumOrNull<BrowserCompatibilityStatus>(json.optString("status"))
                    ?: BrowserCompatibilityStatus.UNKNOWN,
                packageVersionCode = if (json.has("package_version_code")) {
                    json.optLong("package_version_code")
                } else {
                    null
                },
                preferredAddressBarEntryName = json.optString("preferred_entry")
                    .takeIf(String::isNotBlank),
                identificationMethod = enumOrNull<BrowserIdentificationMethod>(
                    json.optString("identification")
                ),
                preferredUrlEntryName = json.optString("url_entry").takeIf(String::isNotBlank),
                preferredUrlMethod = enumOrNull<BrowserIdentificationMethod>(json.optString("url_method")),
                urlRecoveryMethod = enumOrNull<BrowserUrlRecoveryMethod>(json.optString("url_recovery")),
                activationMethod = enumOrNull<BrowserActivationMethod>(
                    json.optString("activation")
                ),
                writeMethod = enumOrNull<BrowserWriteMethod>(json.optString("write")),
                submitMethod = enumOrNull<BrowserSubmitMethod>(json.optString("submit")),
                consecutiveObservationFailures = json.optInt("observation_failures", 0),
                consecutiveRedirectionFailures = json.optInt("redirection_failures", 0),
                updatedAtMillis = json.optLong("updated_at", 0L)
            )
        }.getOrNull()
    }

    private fun encode(record: BrowserCompatibilityRecord): String = JSONObject().apply {
        put("status", record.status.name)
        record.packageVersionCode?.let { put("package_version_code", it) }
        record.preferredAddressBarEntryName?.let { put("preferred_entry", it) }
        record.identificationMethod?.let { put("identification", it.name) }
        record.preferredUrlEntryName?.let { put("url_entry", it) }
        record.preferredUrlMethod?.let { put("url_method", it.name) }
        record.urlRecoveryMethod?.let { put("url_recovery", it.name) }
        record.activationMethod?.let { put("activation", it.name) }
        record.writeMethod?.let { put("write", it.name) }
        record.submitMethod?.let { put("submit", it.name) }
        put("observation_failures", record.consecutiveObservationFailures)
        put("redirection_failures", record.consecutiveRedirectionFailures)
        put("updated_at", record.updatedAtMillis)
    }.toString()

    private fun browserOwnedEntryName(packageName: String, viewIdResourceName: String?): String? =
        BrowserUiCapabilityPolicy.browserOwnedEntryName(packageName, viewIdResourceName)

    private fun observedValueMatchesTarget(observedValue: String?, normalizedTarget: String): Boolean {
        if (normalizedTarget.isEmpty()) return false
        val observed = normalizeAddress(observedValue)
        if (observed.isEmpty()) return false
        return observed == normalizedTarget ||
            observed.startsWith("$normalizedTarget/") ||
            observed.startsWith("$normalizedTarget?") ||
            observed.startsWith("$normalizedTarget#")
    }

    private fun normalizeAddress(value: String?): String = value.orEmpty()
        .trim()
        .lowercase(Locale.ROOT)
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .trimEnd('/')

    private fun BrowserCompatibilityRecord.hasPositiveBrowserEvidence(): Boolean =
        !preferredAddressBarEntryName.isNullOrBlank() ||
            identificationMethod != null ||
            activationMethod != null ||
            writeMethod != null ||
            submitMethod != null

    internal fun shouldRetainRecord(
        record: BrowserCompatibilityRecord,
        verifiedHttpsHandlers: Set<String>
    ): Boolean = record.packageName in verifiedHttpsHandlers || record.hasPositiveBrowserEvidence()

    private fun queryHttpsHandlerPackages(context: Context): Set<String> = runCatching {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        @Suppress("DEPRECATION")
        context.packageManager.queryIntentActivities(browserIntent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }.getOrDefault(emptySet())

    private inline fun <reified T : Enum<T>> enumOrNull(value: String): T? =
        value.takeIf(String::isNotBlank)?.let { raw ->
            runCatching { enumValueOf<T>(raw) }.getOrNull()
        }
}
