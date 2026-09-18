package com.focusguard.data

import android.content.Context
import java.util.UUID

data class UserProfile(
    val displayName: String = "",
    val avatarId: Int = UserProfilePolicy.DEFAULT_AVATAR_ID,
    val userId: String = ""
) {
    val isConfigured: Boolean
        get() = displayName.isNotBlank()
}

object UserProfilePolicy {
    const val MAX_NAME_LENGTH = 40
    const val AVATAR_COUNT = 5
    const val DEFAULT_AVATAR_ID = 0

    fun limitNameInput(value: String): String = value.takeCodePoints(MAX_NAME_LENGTH)

    fun normalize(profile: UserProfile): UserProfile = UserProfile(
        displayName = normalizeName(profile.displayName),
        avatarId = normalizeAvatarId(profile.avatarId),
        userId = normalizeUserId(profile.userId)
    )

    fun normalizeName(value: String): String = value
        .replace(WHITESPACE_REGEX, " ")
        .trim()
        .takeCodePoints(MAX_NAME_LENGTH)

    fun normalizeAvatarId(avatarId: Int): Int =
        avatarId.takeIf { it in 0 until AVATAR_COUNT } ?: DEFAULT_AVATAR_ID

    fun normalizeUserId(value: String): String =
        value.trim().takeIf(USER_ID_REGEX::matches).orEmpty()

    private fun String.takeCodePoints(maxCodePoints: Int): String {
        if (codePointCount(0, length) <= maxCodePoints) return this
        return substring(0, offsetByCodePoints(0, maxCodePoints))
    }

    private val WHITESPACE_REGEX = Regex("\\s+")
    private val USER_ID_REGEX = Regex("[A-Za-z0-9._-]+")
}

class UserProfileStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): UserProfile {
        val userId = resolveUserId()
        return UserProfilePolicy.normalize(
            UserProfile(
                displayName = preferences.getString(DISPLAY_NAME_KEY, "").orEmpty(),
                avatarId = preferences.getInt(
                    AVATAR_ID_KEY,
                    UserProfilePolicy.DEFAULT_AVATAR_ID
                ),
                userId = userId
            )
        )
    }

    fun save(profile: UserProfile): UserProfile {
        val requestedUserId = UserProfilePolicy.normalizeUserId(profile.userId)
        val normalizedProfile = UserProfilePolicy.normalize(
            profile.copy(
                userId = requestedUserId.ifBlank(::resolveUserId)
            )
        )
        preferences.edit()
            .putString(DISPLAY_NAME_KEY, normalizedProfile.displayName)
            .putInt(AVATAR_ID_KEY, normalizedProfile.avatarId)
            .putString(USER_ID_KEY, normalizedProfile.userId)
            .apply()
        return normalizedProfile
    }

    private fun resolveUserId(): String {
        val stored = UserProfilePolicy.normalizeUserId(
            preferences.getString(USER_ID_KEY, "").orEmpty()
        )
        if (stored.isNotBlank()) return stored

        val generated = UUID.randomUUID().toString()
        preferences.edit().putString(USER_ID_KEY, generated).commit()
        return generated
    }

    internal companion object {
        const val PREFERENCES_NAME = "focusguard_user_profile"
        const val DISPLAY_NAME_KEY = "display_name"
        const val AVATAR_ID_KEY = "avatar_id"
        const val USER_ID_KEY = "user_id"
    }
}
