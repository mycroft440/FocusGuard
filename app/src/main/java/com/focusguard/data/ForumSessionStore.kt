package com.focusguard.data

import android.content.Context
import com.focusguard.utils.SecurePrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A session issued by the forum backend after Google sign-in. */
data class ForumSession(
    val token: String,
    val userId: String,
    val expiresAtMillis: Long
)

interface ForumSessionStorage {
    suspend fun load(): ForumSession?

    suspend fun save(session: ForumSession)

    suspend fun clear()
}

/**
 * Keeps the session in the encrypted vault. App backup is disabled, so a reinstall
 * starts signed out; signing in with the same Google account brings the same user back.
 */
class SecureForumSessionStore(context: Context) : ForumSessionStorage {
    private val appContext = context.applicationContext

    override suspend fun load(): ForumSession? = withContext(Dispatchers.IO) {
        val prefs = SecurePrefsManager(appContext).prefs
        val token = prefs.getString(TOKEN_KEY, null)
        val userId = prefs.getString(USER_ID_KEY, null)
        val expiresAt = prefs.getLong(EXPIRES_AT_KEY, 0L)
        if (token.isNullOrBlank() || userId.isNullOrBlank() || expiresAt <= 0L) {
            null
        } else {
            ForumSession(token = token, userId = userId, expiresAtMillis = expiresAt)
        }
    }

    override suspend fun save(session: ForumSession) {
        withContext(Dispatchers.IO) {
            SecurePrefsManager(appContext).prefs.edit()
                .putString(TOKEN_KEY, session.token)
                .putString(USER_ID_KEY, session.userId)
                .putLong(EXPIRES_AT_KEY, session.expiresAtMillis)
                .commit()
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            SecurePrefsManager(appContext).prefs.edit()
                .remove(TOKEN_KEY)
                .remove(USER_ID_KEY)
                .remove(EXPIRES_AT_KEY)
                .commit()
        }
    }

    private companion object {
        const val TOKEN_KEY = "forum_session_token"
        const val USER_ID_KEY = "forum_session_user_id"
        const val EXPIRES_AT_KEY = "forum_session_expires_at"
    }
}
