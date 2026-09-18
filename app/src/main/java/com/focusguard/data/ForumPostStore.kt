package com.focusguard.data

import android.content.Context
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class ForumComment(
    val id: String,
    val authorName: String,
    val avatarId: Int,
    val body: String,
    val createdAtMillis: Long
)

data class ForumPost(
    val id: String,
    val authorName: String,
    val avatarId: Int,
    val body: String,
    val createdAtMillis: Long,
    val likeCount: Int = 0,
    val likedByMe: Boolean = false,
    val comments: List<ForumComment> = emptyList()
)

object ForumPostPolicy {
    const val MAX_BODY_CODE_POINTS = 1000
    const val MAX_COMMENT_CODE_POINTS = 500

    fun limitBodyInput(value: String): String =
        normalizeLineBreaks(value).takeCodePoints(MAX_BODY_CODE_POINTS)

    fun normalizeForPublish(value: String): String = limitBodyInput(value).trim()

    fun limitCommentInput(value: String): String =
        normalizeLineBreaks(value).takeCodePoints(MAX_COMMENT_CODE_POINTS)

    fun normalizeCommentForPublish(value: String): String =
        limitCommentInput(value).trim()

    fun bodyCodePointCount(value: String): Int =
        value.codePointCount(0, value.length)

    fun commentCodePointCount(value: String): Int =
        value.codePointCount(0, value.length)

    private fun normalizeLineBreaks(value: String): String = value
        .replace("\r\n", "\n")
        .replace('\r', '\n')

    private fun String.takeCodePoints(maxCodePoints: Int): String {
        if (codePointCount(0, length) <= maxCodePoints) return this
        return substring(0, offsetByCodePoints(0, maxCodePoints))
    }
}

class ForumPostStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): List<ForumPost> {
        val raw = preferences.getString(POSTS_KEY, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
        val posts = ArrayList<ForumPost>(array.length())

        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val body = ForumPostPolicy.normalizeForPublish(json.optString(BODY_KEY))
            if (body.isBlank()) continue

            posts += ForumPost(
                id = json.optString(ID_KEY).ifBlank { "legacy-$index" },
                authorName = UserProfilePolicy.normalizeName(json.optString(AUTHOR_KEY)),
                avatarId = UserProfilePolicy.normalizeAvatarId(
                    json.optInt(AVATAR_KEY, UserProfilePolicy.DEFAULT_AVATAR_ID)
                ),
                body = body,
                createdAtMillis = json.optLong(CREATED_AT_KEY, 0L).coerceAtLeast(0L),
                likeCount = json.optInt(LIKE_COUNT_KEY, 0).coerceAtLeast(0),
                likedByMe = json.optBoolean(LIKED_BY_ME_KEY, false),
                comments = readComments(
                    json.optJSONArray(COMMENTS_KEY),
                    postIndex = index
                )
            )
        }

        return posts.sortedByDescending(ForumPost::createdAtMillis)
    }

    fun publish(
        authorName: String,
        avatarId: Int,
        body: String,
        nowMillis: Long = System.currentTimeMillis()
    ): ForumPost? {
        val normalizedBody = ForumPostPolicy.normalizeForPublish(body)
        if (normalizedBody.isBlank()) return null

        val post = ForumPost(
            id = nowMillis.toString() + "-" + UUID.randomUUID(),
            authorName = UserProfilePolicy.normalizeName(authorName),
            avatarId = UserProfilePolicy.normalizeAvatarId(avatarId),
            body = normalizedBody,
            createdAtMillis = nowMillis.coerceAtLeast(0L)
        )
        persist(listOf(post) + load())
        return post
    }

    fun toggleLike(postId: String): ForumPost? {
        var updated: ForumPost? = null
        val posts = load().map { post ->
            if (post.id != postId) {
                post
            } else {
                val nextLikedByMe = !post.likedByMe
                post.copy(
                    likedByMe = nextLikedByMe,
                    likeCount = (
                        post.likeCount + if (nextLikedByMe) 1 else -1
                    ).coerceAtLeast(0)
                ).also { updated = it }
            }
        }
        if (updated != null) persist(posts)
        return updated
    }

    fun addComment(
        postId: String,
        authorName: String,
        avatarId: Int,
        body: String,
        nowMillis: Long = System.currentTimeMillis()
    ): ForumComment? {
        val normalizedBody = ForumPostPolicy.normalizeCommentForPublish(body)
        if (normalizedBody.isBlank()) return null

        val comment = ForumComment(
            id = nowMillis.toString() + "-" + UUID.randomUUID(),
            authorName = UserProfilePolicy.normalizeName(authorName),
            avatarId = UserProfilePolicy.normalizeAvatarId(avatarId),
            body = normalizedBody,
            createdAtMillis = nowMillis.coerceAtLeast(0L)
        )

        var postFound = false
        val posts = load().map { post ->
            if (post.id != postId) {
                post
            } else {
                postFound = true
                post.copy(comments = post.comments + comment)
            }
        }
        if (!postFound) return null

        persist(posts)
        return comment
    }

    private fun readComments(array: JSONArray?, postIndex: Int): List<ForumComment> {
        if (array == null) return emptyList()
        val comments = ArrayList<ForumComment>(array.length())

        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val body = ForumPostPolicy.normalizeCommentForPublish(
                json.optString(COMMENT_BODY_KEY)
            )
            if (body.isBlank()) continue

            comments += ForumComment(
                id = json.optString(COMMENT_ID_KEY).ifBlank {
                    "legacy-comment-$postIndex-$index"
                },
                authorName = UserProfilePolicy.normalizeName(
                    json.optString(COMMENT_AUTHOR_KEY)
                ),
                avatarId = UserProfilePolicy.normalizeAvatarId(
                    json.optInt(
                        COMMENT_AVATAR_KEY,
                        UserProfilePolicy.DEFAULT_AVATAR_ID
                    )
                ),
                body = body,
                createdAtMillis = json.optLong(
                    COMMENT_CREATED_AT_KEY,
                    0L
                ).coerceAtLeast(0L)
            )
        }

        return comments.sortedBy(ForumComment::createdAtMillis)
    }

    private fun persist(posts: List<ForumPost>) {
        val array = JSONArray()
        posts.forEach { post ->
            array.put(
                JSONObject()
                    .put(ID_KEY, post.id)
                    .put(AUTHOR_KEY, post.authorName)
                    .put(AVATAR_KEY, post.avatarId)
                    .put(BODY_KEY, post.body)
                    .put(CREATED_AT_KEY, post.createdAtMillis)
                    .put(LIKE_COUNT_KEY, post.likeCount.coerceAtLeast(0))
                    .put(LIKED_BY_ME_KEY, post.likedByMe)
                    .put(COMMENTS_KEY, writeComments(post.comments))
            )
        }
        preferences.edit().putString(POSTS_KEY, array.toString()).apply()
    }

    private fun writeComments(comments: List<ForumComment>): JSONArray {
        val array = JSONArray()
        comments.forEach { comment ->
            array.put(
                JSONObject()
                    .put(COMMENT_ID_KEY, comment.id)
                    .put(COMMENT_AUTHOR_KEY, comment.authorName)
                    .put(COMMENT_AVATAR_KEY, comment.avatarId)
                    .put(COMMENT_BODY_KEY, comment.body)
                    .put(COMMENT_CREATED_AT_KEY, comment.createdAtMillis)
            )
        }
        return array
    }

    internal companion object {
        const val PREFERENCES_NAME = "focusguard_forum_posts"
        internal const val POSTS_KEY = "posts"

        private const val ID_KEY = "id"
        private const val AUTHOR_KEY = "author"
        private const val AVATAR_KEY = "avatar"
        private const val BODY_KEY = "body"
        private const val CREATED_AT_KEY = "created_at"
        private const val LIKE_COUNT_KEY = "like_count"
        private const val LIKED_BY_ME_KEY = "liked_by_me"
        private const val COMMENTS_KEY = "comments"

        private const val COMMENT_ID_KEY = "id"
        private const val COMMENT_AUTHOR_KEY = "author"
        private const val COMMENT_AVATAR_KEY = "avatar"
        private const val COMMENT_BODY_KEY = "body"
        private const val COMMENT_CREATED_AT_KEY = "created_at"
    }
}
