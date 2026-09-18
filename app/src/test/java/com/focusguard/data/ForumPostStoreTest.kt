package com.focusguard.data

import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
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

    @Test
    fun `legacy posts load with empty interactions`() {
        val legacy = JSONArray().put(
            JSONObject()
                .put("id", "legacy-post")
                .put("author", "Alice")
                .put("avatar", 1)
                .put("body", "Post anterior")
                .put("created_at", 5_000L)
        )
        context.getSharedPreferences(
            ForumPostStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).edit()
            .putString(ForumPostStore.POSTS_KEY, legacy.toString())
            .commit()

        val post = ForumPostStore(context).load().single()

        assertThat(post.likeCount).isEqualTo(0)
        assertThat(post.likedByMe).isFalse()
        assertThat(post.comments).isEmpty()
    }

    @Test
    fun `like toggles once and survives a fresh store`() {
        val store = ForumPostStore(context)
        val post = requireNotNull(
            store.publish("Alice", 0, "Olá", nowMillis = 1_000L)
        )

        val liked = requireNotNull(store.toggleLike(post.id))

        assertThat(liked.likedByMe).isTrue()
        assertThat(liked.likeCount).isEqualTo(1)
        assertThat(ForumPostStore(context).load().single().likeCount).isEqualTo(1)

        val unliked = requireNotNull(ForumPostStore(context).toggleLike(post.id))

        assertThat(unliked.likedByMe).isFalse()
        assertThat(unliked.likeCount).isEqualTo(0)
    }

    @Test
    fun `comment is normalized persisted and attached to its post`() {
        val store = ForumPostStore(context)
        val post = requireNotNull(
            store.publish("Alice", 0, "Post", nowMillis = 1_000L)
        )

        val comment = store.addComment(
            postId = post.id,
            authorName = " Bob ",
            avatarId = 3,
            body = "  Comentário útil  ",
            nowMillis = 2_000L
        )

        assertThat(comment).isNotNull()
        val persisted = ForumPostStore(context).load().single().comments.single()
        assertThat(persisted.authorName).isEqualTo("Bob")
        assertThat(persisted.avatarId).isEqualTo(3)
        assertThat(persisted.body).isEqualTo("Comentário útil")
        assertThat(persisted.createdAtMillis).isEqualTo(2_000L)
    }

    @Test
    fun `blank comment is rejected`() {
        val store = ForumPostStore(context)
        val post = requireNotNull(
            store.publish("Alice", 0, "Post", nowMillis = 1_000L)
        )

        assertThat(
            store.addComment(post.id, "Bob", 1, "  \n ", nowMillis = 2_000L)
        ).isNull()
        assertThat(store.load().single().comments).isEmpty()
    }

    @Test
    fun `comment input is limited by Unicode code points`() {
        val input = "🙂".repeat(ForumPostPolicy.MAX_COMMENT_CODE_POINTS + 5)

        val limited = ForumPostPolicy.limitCommentInput(input)

        assertThat(ForumPostPolicy.commentCodePointCount(limited))
            .isEqualTo(ForumPostPolicy.MAX_COMMENT_CODE_POINTS)
    }
}
