package com.focusguard.accessibility.website.compatibility

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal enum class BrowserCompatibilityStatus {
    UNKNOWN,
    SUPPORTED,
    UNSUPPORTED
}

internal enum class BrowserIdentificationMethod {
    EVENT_SOURCE,
    CACHED_RESOURCE_ID,
    STRONG_RESOURCE_ID,
    SEMANTIC_TREE
}

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
    val preferredAddressBarEntryName: String? = null,
    val identificationMethod: BrowserIdentificationMethod? = null,
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
 */
internal object BrowserCompatibilityStore {
    private const val PREFS_NAME = "browser_compatibility_cache_v1"
    private const val KEY_PACKAGES = "packages"
    private const val KEY_PREFIX = "record:"
    private const val OBSERVATION_FAILURE_MIN_SPAN_MILLIS = 200L
    private const val SUPPORTED_FAILURE_THRESHOLD = 3
    private const val REDIRECTION_FAILURE_THRESHOLD = 3
    private const val REDIRECTION_FAILURE_DEBOUNCE_MILLIS = 150L

    private val lock = Any()
    private var prefs: SharedPreferences? = null
    private val cache = linkedMapOf<String, BrowserCompatibilityRecord>()
    private val pendingRedirects = mutableMapOf<String, PendingRedirect>()
    private val firstObservationFailureAt = mutableMapOf<String, Long>()
    private val lastRedirectionFailureAt = mutableMapOf<String, Long>()
    private val _testedRecords = MutableStateFlow<List<BrowserCompatibilityRecord>>(emptyList())

    val testedRecords: StateFlow<List<BrowserCompatibilityRecord>> = _testedRecords.asStateFlow()

    private data class PendingRedirect(
        val normalizedTarget: String,
        val submitted: Boolean
    )

    fun initialize(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val packageNames = prefs?.getStringSet(KEY_PACKAGES, emptySet()).orEmpty()
            packageNames.sorted().forEach { packageName ->
                readRecord(packageName)?.let { cache[packageName] = it }
            }
            publishLocked()
        }
    }

    fun preferredAddressBarEntryName(packageName: String): String? = synchronized(lock) {
        cache[packageName]?.preferredAddressBarEntryName?.takeIf(String::isNotBlank)
    }

    fun preferredActivationMethod(packageName: String): BrowserActivationMethod? =
        synchronized(lock) { cache[packageName]?.activationMethod }

    fun preferredWriteMethod(packageName: String): BrowserWriteMethod? =
        synchronized(lock) { cache[packageName]?.writeMethod }

    fun preferredSubmitMethod(packageName: String): BrowserSubmitMethod? =
        synchronized(lock) { cache[packageName]?.submitMethod }

    fun prioritizeAddressBarEntryNames(
        packageName: String,
        defaults: Iterable<String>
    ): List<String> {
        val preferred = preferredAddressBarEntryName(packageName)
        return buildList {
            if (!preferred.isNullOrBlank()) add(preferred)
            defaults.forEach { entry ->
                if (entry.isNotBlank() && entry != preferred) add(entry)
            }
        }
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
            val previous = recordForLocked(packageName)
            val entryName = browserOwnedEntryName(packageName, viewIdResourceName)
                ?: previous.preferredAddressBarEntryName
            val pending = pendingRedirects[packageName]
            val redirectVerified = pending?.submitted == true &&
                observedValueMatchesTarget(observedValue, pending.normalizedTarget)
            if (redirectVerified) pendingRedirects.remove(packageName)

            saveLocked(
                previous.copy(
                    status = if (redirectVerified) {
                        BrowserCompatibilityStatus.SUPPORTED
                    } else {
                        previous.status
                    },
                    preferredAddressBarEntryName = entryName,
                    identificationMethod = method,
                    consecutiveObservationFailures = 0,
                    consecutiveRedirectionFailures = if (redirectVerified) {
                        0
                    } else {
                        previous.consecutiveRedirectionFailures
                    },
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
            pendingRedirects[packageName] = PendingRedirect(
                normalizedTarget = normalizedTarget,
                submitted = false
            )
        }
        previous.copy(
            preferredAddressBarEntryName = entryName,
            writeMethod = method,
            consecutiveRedirectionFailures = 0,
            updatedAtMillis = System.currentTimeMillis()
        )
    }

    fun recordSubmitAccepted(
        packageName: String,
        viewIdResourceName: String?,
        method: BrowserSubmitMethod
    ) = updateMethod(packageName, viewIdResourceName) { previous, entryName ->
        pendingRedirects[packageName]?.let { pending ->
            pendingRedirects[packageName] = pending.copy(submitted = true)
        }
        previous.copy(
            preferredAddressBarEntryName = entryName,
            submitMethod = method,
            consecutiveRedirectionFailures = 0,
            updatedAtMillis = System.currentTimeMillis()
        )
    }

    /**
     * Called after one complete address-bar lookup returned no match. Transient
     * browser UI states inside the same 200 ms observability grace do not mark a
     * browser unsupported; the failure must persist across that grace window.
     */
    fun recordUnobservableFailure(packageName: String) {
        if (packageName.isBlank()) return
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val firstFailureAt = firstObservationFailureAt.getOrPut(packageName) { now }
            val previous = recordForLocked(packageName)
            val failures = previous.consecutiveObservationFailures + 1
            val sustainedFailure = now - firstFailureAt >= OBSERVATION_FAILURE_MIN_SPAN_MILLIS
            val unsupported = sustainedFailure && (
                previous.status != BrowserCompatibilityStatus.SUPPORTED ||
                    failures >= SUPPORTED_FAILURE_THRESHOLD
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

    /**
     * Failed redirection calls can happen repeatedly inside one retry window, so
     * failures are debounced before they are allowed to change the public status.
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
            val unsupported = failures >= REDIRECTION_FAILURE_THRESHOLD
            if (unsupported) pendingRedirects.remove(packageName)
            saveLocked(
                previous.copy(
                    status = if (unsupported) {
                        BrowserCompatibilityStatus.UNSUPPORTED
                    } else {
                        previous.status
                    },
                    consecutiveRedirectionFailures = failures,
                    updatedAtMillis = now
                )
            )
        }
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
                preferredAddressBarEntryName = json.optString("preferred_entry")
                    .takeIf(String::isNotBlank),
                identificationMethod = enumOrNull<BrowserIdentificationMethod>(
                    json.optString("identification")
                ),
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
        record.preferredAddressBarEntryName?.let { put("preferred_entry", it) }
        record.identificationMethod?.let { put("identification", it.name) }
        record.activationMethod?.let { put("activation", it.name) }
        record.writeMethod?.let { put("write", it.name) }
        record.submitMethod?.let { put("submit", it.name) }
        put("observation_failures", record.consecutiveObservationFailures)
        put("redirection_failures", record.consecutiveRedirectionFailures)
        put("updated_at", record.updatedAtMillis)
    }.toString()

    private fun browserOwnedEntryName(packageName: String, viewIdResourceName: String?): String? {
        val value = viewIdResourceName?.trim().orEmpty()
        val prefix = "$packageName:id/"
        return value.takeIf { it.startsWith(prefix) && it.length > prefix.length }
            ?.substring(prefix.length)
            ?.takeIf(String::isNotBlank)
    }

    private fun observedValueMatchesTarget(observedValue: String?, normalizedTarget: String): Boolean {
        if (normalizedTarget.isEmpty()) return false
        val observed = normalizeAddress(observedValue)
        if (observed.isEmpty()) return false
        return observed == normalizedTarget ||
            observed.startsWith("$normalizedTarget/") ||
            observed.contains(normalizedTarget)
    }

    private fun normalizeAddress(value: String?): String = value.orEmpty()
        .trim()
        .lowercase(Locale.ROOT)
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .trimEnd('/')

    private inline fun <reified T : Enum<T>> enumOrNull(value: String): T? =
        value.takeIf(String::isNotBlank)?.let { raw ->
            runCatching { enumValueOf<T>(raw) }.getOrNull()
        }
}
