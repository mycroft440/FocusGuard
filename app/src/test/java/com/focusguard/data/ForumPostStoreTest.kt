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
class ForumPostStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication().applicationContext

    @Before
    fun clearForumPosts() {
        context.getSharedPreferences(
            ForumPostStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
    }

    @Test
    fun `published posts survive a fresh store and newest appears first`() {
        val store = ForumPostStore(context)

        store.publish(
            authorName = " Alice ",
            avatarId = 2,
            body = "  Primeiro post  ",
            nowMillis = 1_000L
        )
        store.publish(
            authorName = "Bob",
            avatarId = 3,
            body = "Segundo post",
            nowMillis = 2_000L
        )

        val posts = ForumPostStore(context).load()

        assertThat(posts).hasSize(2)
        assertThat(posts[0].authorName).isEqualTo("Bob")
        assertThat(posts[0].body).isEqualTo("Segundo post")
        assertThat(posts[1].authorName).isEqualTo("Alice")
        assertThat(posts[1].body).isEqualTo("Primeiro post")
    }

    @Test
    fun `blank post is not persisted`() {
        val store = ForumPostStore(context)

        assertThat(store.publish("Alice", 0, "   \n  ", nowMillis = 1_000L)).isNull()
        assertThat(store.load()).isEmpty()
    }

    @Test
    fun `draft input is limited by Unicode code points`() {
        val input = "🙂".repeat(ForumPostPolicy.MAX_BODY_CODE_POINTS + 5)

        val limited = ForumPostPolicy.limitBodyInput(input)

        assertThat(ForumPostPolicy.bodyCodePointCount(limited))
            .isEqualTo(ForumPostPolicy.MAX_BODY_CODE_POINTS)
    }
}
