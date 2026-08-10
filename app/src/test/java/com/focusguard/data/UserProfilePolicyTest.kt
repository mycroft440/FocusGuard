package com.focusguard.data

import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserProfilePolicyTest {

    private val context: Context = RuntimeEnvironment.getApplication().applicationContext

    @Before
    fun clearStoredProfile() {
        context.getSharedPreferences("focusguard_user_profile", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `normalize trims and collapses whitespace in display name`() {
        val profile = UserProfilePolicy.normalize(
            UserProfile(displayName = "  Ana\n   Silva  ", avatarId = 2)
        )

        assertThat(profile.displayName).isEqualTo("Ana Silva")
        assertThat(profile.avatarId).isEqualTo(2)
    }

    @Test
    fun `name length is limited by visible unicode characters`() {
        val emojiName = "🙂".repeat(UserProfilePolicy.MAX_NAME_LENGTH + 3)

        val limitedName = UserProfilePolicy.limitNameInput(emojiName)

        assertThat(limitedName.codePointCount(0, limitedName.length))
            .isEqualTo(UserProfilePolicy.MAX_NAME_LENGTH)
        assertThat(limitedName.endsWith("🙂")).isTrue()
    }

    @Test
    fun `invalid avatar falls back to default`() {
        assertThat(UserProfilePolicy.normalizeAvatarId(-1))
            .isEqualTo(UserProfilePolicy.DEFAULT_AVATAR_ID)
        assertThat(UserProfilePolicy.normalizeAvatarId(UserProfilePolicy.AVATAR_COUNT))
            .isEqualTo(UserProfilePolicy.DEFAULT_AVATAR_ID)
    }

    @Test
    fun `all five avatar identifiers are accepted`() {
        val avatarIds = (0 until UserProfilePolicy.AVATAR_COUNT)

        assertThat(avatarIds.map(UserProfilePolicy::normalizeAvatarId))
            .containsExactlyElementsIn(avatarIds)
            .inOrder()
    }

    @Test
    fun `saved profile is restored after creating a new store`() {
        val savedProfile = UserProfileStore(context).save(
            UserProfile(displayName = "  João   Lima ", avatarId = 4)
        )

        val restoredProfile = UserProfileStore(context).load()

        assertThat(savedProfile).isEqualTo(UserProfile("João Lima", 4))
        assertThat(restoredProfile).isEqualTo(savedProfile)
    }
}
