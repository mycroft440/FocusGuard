package com.focusguard.data

import android.content.Context

data class UserProfile(
    val displayName: String = "",
    val avatarId: Int = UserProfilePolicy.DEFAULT_AVATAR_ID
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
        avatarId = normalizeAvatarId(profile.avatarId)
    )

    fun normalizeName(value: String): String = value
        .replace(WHITESPACE_REGEX, " ")
        .trim()
        .takeCodePoints(MAX_NAME_LENGTH)

    fun normalizeAvatarId(avatarId: Int): Int =
        avatarId.takeIf { it in 0 until AVATAR_COUNT } ?: DEFAULT_AVATAR_ID

    private fun String.takeCodePoints(maxCodePoints: Int): String {
        if (codePointCount(0, length) <= maxCodePoints) return this
        return substring(0, offsetByCodePoints(0, maxCodePoints))
    }

    private val WHITESPACE_REGEX = Regex("\\s+")
}

class UserProfileStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): UserProfile = UserProfilePolicy.normalize(
        UserProfile(
            displayName = preferences.getString(DISPLAY_NAME_KEY, "").orEmpty(),
            avatarId = preferences.getInt(
                AVATAR_ID_KEY,
                UserProfilePolicy.DEFAULT_AVATAR_ID
            )
        )
    )

    fun save(profile: UserProfile): UserProfile {
        val normalizedProfile = UserProfilePolicy.normalize(profile)
        preferences.edit()
            .putString(DISPLAY_NAME_KEY, normalizedProfile.displayName)
            .putInt(AVATAR_ID_KEY, normalizedProfile.avatarId)
            .apply()
        return normalizedProfile
    }

    private companion object {
        const val PREFERENCES_NAME = "focusguard_user_profile"
        const val DISPLAY_NAME_KEY = "display_name"
        const val AVATAR_ID_KEY = "avatar_id"
    }
}
