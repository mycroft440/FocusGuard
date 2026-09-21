package com.focusguard.accessibility.website.redirection

import android.content.Context
import com.focusguard.utils.WebsiteBlocker
import java.net.URI
import java.util.Locale

/**
 * Persists the global website redirect destination and publishes an immutable
 * runtime snapshot to [WebsiteRedirectDestination].
 */
internal object WebsiteRedirectDestinationStore {
    enum class SaveResult {
        SAVED,
        INVALID_URL,
        BLOCKED_BY_ACTIVE_RULE
    }

    private const val PREFERENCES = "website_redirect_destination"
    private const val KEY_URL = "url"
    private const val KEY_REVISION = "revision"

    fun initialize(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val stored = preferences.getString(KEY_URL, null)
        val destination = stored?.let(::destinationFromUserInput)
        if (destination != null) {
            WebsiteRedirectDestination.install(destination)
        } else {
            WebsiteRedirectDestination.resetToDefault()
        }
    }

    fun save(
        context: Context,
        rawUrl: String,
        activeBlockedRules: Collection<String> = emptySet()
    ): SaveResult {
        val destination = destinationFromUserInput(rawUrl) ?: return SaveResult.INVALID_URL
        val normalizedRules = WebsiteBlocker.normalizeRules(activeBlockedRules)
        if (WebsiteBlocker.findMatchingRule(destination.url, normalizedRules) != null) {
            return SaveResult.BLOCKED_BY_ACTIVE_RULE
        }

        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val nextRevision = preferences.getLong(KEY_REVISION, 0L) + 1L
        if (!preferences.edit()
                .putString(KEY_URL, destination.url)
                .putLong(KEY_REVISION, nextRevision)
                .commit()
        ) {
            return SaveResult.INVALID_URL
        }
        WebsiteRedirectDestination.install(destination)
        return SaveResult.SAVED
    }

    fun reset(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val nextRevision = preferences.getLong(KEY_REVISION, 0L) + 1L
        preferences.edit()
            .remove(KEY_URL)
            .putLong(KEY_REVISION, nextRevision)
            .apply()
        WebsiteRedirectDestination.resetToDefault()
    }

    fun revision(context: Context): Long =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getLong(KEY_REVISION, 0L)

    internal fun destinationFromUserInput(rawUrl: String): WebsiteRedirectDestination? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return null
        val candidate = if (SCHEME_REGEX.containsMatchIn(trimmed)) trimmed else "https://$trimmed"
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.US)
            ?.takeIf { it == "http" || it == "https" }
            ?: return null
        if (uri.userInfo != null || !uri.rawFragment.isNullOrEmpty()) return null
        if (!uri.rawQuery.isNullOrEmpty()) return null
        if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return null
        val host = WebsiteBlocker.extractDomain(candidate).takeIf(String::isNotBlank) ?: return null
        val port = uri.port
        if (port != -1 && !(
                (scheme == "https" && port == 443) ||
                    (scheme == "http" && port == 80)
            )
        ) return null

        val normalizedUrl = "$scheme://$host/"
        return runCatching {
            WebsiteRedirectDestination(
                url = normalizedUrl,
                acceptedRootHosts = setOf(host),
                acceptedSchemes = setOf(scheme)
            )
        }.getOrNull()
    }

    private val SCHEME_REGEX = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
}
