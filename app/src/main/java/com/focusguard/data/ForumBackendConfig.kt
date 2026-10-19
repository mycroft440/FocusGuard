package com.focusguard.data

import android.content.Context
import com.focusguard.BuildConfig

/**
 * Where the shared forum lives. Both values come from gradle.properties
 * (`focusguard.forumBackendUrl`, `focusguard.forumGoogleWebClientId`); with either one
 * missing the forum keeps working on this device only.
 */
data class ForumBackendConfig(
    val endpointUrl: String,
    val googleWebClientId: String
) {
    val isConfigured: Boolean
        get() = isAppsScriptEndpoint(endpointUrl) && isWebClientId(googleWebClientId)

    companion object {
        fun fromBuildConfig(): ForumBackendConfig = ForumBackendConfig(
            endpointUrl = BuildConfig.FORUM_BACKEND_URL.trim(),
            googleWebClientId = BuildConfig.FORUM_GOOGLE_WEB_CLIENT_ID.trim()
        )

        // Only the deployed /exec URL answers without a Google login; /dev needs the owner's.
        internal fun isAppsScriptEndpoint(url: String): Boolean =
            url.startsWith("https://script.google.com/") && url.endsWith("/exec")

        internal fun isWebClientId(value: String): Boolean =
            value.endsWith(".apps.googleusercontent.com") &&
                value.length > ".apps.googleusercontent.com".length
    }
}

/** One forum store per process, so every screen shares the same lock and session. */
object ForumRepositoryProvider {
    @Volatile
    private var instance: ForumRepository? = null

    fun get(context: Context): ForumRepository =
        instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

    private fun create(context: Context): ForumRepository {
        val config = ForumBackendConfig.fromBuildConfig()
        return if (config.isConfigured) {
            RemoteForumRepository(
                transport = HttpForumTransport(config.endpointUrl),
                sessions = SecureForumSessionStore(context)
            )
        } else {
            ForumPostStore(context)
        }
    }
}
