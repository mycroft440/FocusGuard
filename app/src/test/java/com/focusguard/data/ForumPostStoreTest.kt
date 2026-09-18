package com.focusguard.data

import android.content.Context
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
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
    fun `published posts survive a fresh store and newest appears first`() = runBlocking {
        val store = ForumPostStore(context)

        store.publish(
            author = author("user-alice", " Alice ", 2),
            body = "  Primeiro post  ",
            nowMillis = 1_000L
        )
        store.publish(
            author = author("user-bob", "Bob", 3),
            body = "Segundo post",
            nowMillis = 2_000L
        )

        val posts = ForumPostStore(context).loadPosts("user-alice")

        assertThat(posts).hasSize(2)
        assertThat(posts[0].authorId).isEqualTo("user-bob")
        assertThat(posts[0].authorName).isEqualTo("Bob")
        assertThat(posts[0].body).isEqualTo("Segundo post")
        assertThat(posts[1].authorId).isEqualTo("user-alice")
        assertThat(posts[1].authorName).isEqualTo("Alice")
        assertThat(posts[1].body).isEqualTo("Primeiro post")
    }

    @Test
    fun `blank post is not persisted`() = runBlocking {
        val store = ForumPostStore(context)

        assertThat(
            store.publish(
                author = author("user-alice", "Alice", 0),
                body = "   \n  ",
                nowMillis = 1_000L
            )
        ).isNull()
        assertThat(store.loadPosts("user-alice")).isEmpty()
    }

    @Test
    fun `draft input is limited by Unicode code points`() {
        val input = "🙂".repeat(ForumPostPolicy.MAX_BODY_CODE_POINTS + 5)

        val limited = ForumPostPolicy.limitBodyInput(input)

        assertThat(ForumPostPolicy.bodyCodePointCount(limited))
            .isEqualTo(ForumPostPolicy.MAX_BODY_CODE_POINTS)
    }

    @Test
    fun `legacy posts load with empty interactions`() = runBlocking {
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

        val post = ForumPostStore(context).loadPosts("user-alice").single()

        assertThat(post.likeCount).isEqualTo(0)
        assertThat(post.likedByMe).isFalse()
        assertThat(post.commentsCount).isEqualTo(0)
        assertThat(ForumPostStore(context).loadComments(post.id)).isEmpty()
    }

    @Test
    fun `one user has at most one like record per post`() = runBlocking {
        val store = ForumPostStore(context)
        val post = requireNotNull(
            store.publish(
                author = author("user-alice", "Alice", 0),
                body = "Olá",
                nowMillis = 1_000L
            )
        )

        val liked = requireNotNull(store.toggleLike(post.id, "user-bob"))
        assertThat(liked.likedByMe).isTrue()
        assertThat(liked.likeCount).isEqualTo(1)

        val bobView = ForumPostStore(context).loadPosts("user-bob").single()
        val aliceView = ForumPostStore(context).loadPosts("user-alice").single()
        assertThat(bobView.likedByMe).isTrue()
        assertThat(aliceView.likedByMe).isFalse()
        assertThat(aliceView.likeCount).isEqualTo(1)

        val unliked = requireNotNull(ForumPostStore(context).toggleLike(post.id, "user-bob"))
        assertThat(unliked.likedByMe).isFalse()
        assertThat(unliked.likeCount).isEqualTo(0)
    }

    @Test
    fun `different users create independent likes`() = runBlocking {
        val store = ForumPostStore(context)
        val post = requireNotNull(
            store.publish(
                author = author("user-owner", "Owner", 0),
                body = "Post",
                nowMillis = 1_000L
            )
        )

        store.toggleLike(post.id, "user-a")
        store.toggleLike(post.id, "user-b")

        val userAView = store.loadPosts("user-a").single()
        val userBView = store.loadPosts("user-b").single()
        val ownerView = store.loadPosts("user-owner").single()

        assertThat(userAView.likeCount).isEqualTo(2)
        assertThat(userAView.likedByMe).isTrue()
        assertThat(userBView.likedByMe).isTrue()
        assertThat(ownerView.likedByMe).isFalse()
    }

    @Test
    fun `comment is stored separately and post keeps only its count`() = runBlocking {
        val store = ForumPostStore(context)
        val post = requireNotNull(
            store.publish(
                author = author("user-alice", "Alice", 0),
                body = "Post",
                nowMillis = 1_000L
            )
        )

        val comment = store.addComment(
            postId = post.id,
            author = author("user-bob", " Bob ", 3),
            body = "  Comentário útil  ",
            nowMillis = 2_000L
        )

        assertThat(comment).isNotNull()
        val persistedPost = ForumPostStore(context).loadPosts("user-alice").single()
        val persistedComment = ForumPostStore(context).loadComments(post.id).single()

        assertThat(persistedPost.commentsCount).isEqualTo(1)
        assertThat(persistedComment.postId).isEqualTo(post.id)
        assertThat(persistedComment.authorId).isEqualTo("user-bob")
        assertThat(persistedComment.authorName).isEqualTo("Bob")
        assertThat(persistedComment.avatarId).isEqualTo(3)
        assertThat(persistedComment.body).isEqualTo("Comentário útil")
        assertThat(persistedComment.createdAtMillis).isEqualTo(2_000L)

        val rawPosts = context.getSharedPreferences(
            ForumPostStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).getString(ForumPostStore.POSTS_KEY, null).orEmpty()
        val postJson = JSONArray(rawPosts).getJSONObject(0)
        assertThat(postJson.has("comments")).isFalse()
        assertThat(postJson.getInt("comments_count")).isEqualTo(1)
    }

    @Test
    fun `legacy embedded comments survive normalization on next write`() = runBlocking {
        val legacyComment = JSONObject()
            .put("id", "legacy-comment")
            .put("author", "Bob")
            .put("avatar", 3)
            .put("body", "Comentário antigo")
            .put("created_at", 2_000L)
        val legacyPost = JSONObject()
            .put("id", "legacy-post")
            .put("author", "Alice")
            .put("avatar", 1)
            .put("body", "Post anterior")
            .put("created_at", 1_000L)
            .put("comments", JSONArray().put(legacyComment))

        context.getSharedPreferences(
            ForumPostStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).edit()
            .putString(ForumPostStore.POSTS_KEY, JSONArray().put(legacyPost).toString())
            .commit()

        val store = ForumPostStore(context)
        assertThat(store.loadPosts("user-alice").single().commentsCount).isEqualTo(1)
        assertThat(store.loadComments("legacy-post").single().body)
            .isEqualTo("Comentário antigo")

        store.publish(
            author = author("user-alice", "Alice", 1),
            body = "Novo post",
            nowMillis = 3_000L
        )

        assertThat(store.loadComments("legacy-post").single().body)
            .isEqualTo("Comentário antigo")
    }

    @Test
    fun `blank comment is rejected`() = runBlocking {
        val store = ForumPostStore(context)
        val post = requireNotNull(
            store.publish(
                author = author("user-alice", "Alice", 0),
                body = "Post",
                nowMillis = 1_000L
            )
        )

        assertThat(
            store.addComment(
                postId = post.id,
                author = author("user-bob", "Bob", 1),
                body = "  \n ",
                nowMillis = 2_000L
            )
        ).isNull()
        assertThat(store.loadComments(post.id)).isEmpty()
        assertThat(store.loadPosts("user-alice").single().commentsCount).isEqualTo(0)
    }

    @Test
    fun `comment input is limited by Unicode code points`() {
        val input = "🙂".repeat(ForumPostPolicy.MAX_COMMENT_CODE_POINTS + 5)

        val limited = ForumPostPolicy.limitCommentInput(input)

        assertThat(ForumPostPolicy.commentCodePointCount(limited))
            .isEqualTo(ForumPostPolicy.MAX_COMMENT_CODE_POINTS)
    }

    private fun author(userId: String, name: String, avatarId: Int): ForumAuthor =
        ForumAuthor(
            userId = userId,
            displayName = name,
            avatarId = avatarId
        )
}
