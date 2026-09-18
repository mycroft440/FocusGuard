package com.focusguard.data

import android.content.Context
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class ForumPost(
    val id: String,
    val authorName: String,
    val avatarId: Int,
    val body: String,
    val createdAtMillis: Long
)

object ForumPostPolicy {
    const val MAX_BODY_CODE_POINTS = 1000
    const val MAX_STORED_POSTS = 200

    fun limitBodyInput(value: String): String = value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .takeCodePoints(MAX_BODY_CODE_POINTS)

    fun normalizeForPublish(value: String): String = limitBodyInput(value).trim()

    fun bodyCodePointCount(value: String): Int =
        value.codePointCount(0, value.length)

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
                createdAtMillis = json.optLong(CREATED_AT_KEY, 0L).coerceAtLeast(0L)
            )
        }

        return posts
            .sortedByDescending(ForumPost::createdAtMillis)
            .take(ForumPostPolicy.MAX_STORED_POSTS)
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
            id = "$nowMillis-\${UUID.randomUUID()}",
            authorName = UserProfilePolicy.normalizeName(authorName),
            avatarId = UserProfilePolicy.normalizeAvatarId(avatarId),
            body = normalizedBody,
            createdAtMillis = nowMillis.coerceAtLeast(0L)
        )
        persist((listOf(post) + load()).take(ForumPostPolicy.MAX_STORED_POSTS))
        return post
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
            )
        }
        preferences.edit().putString(POSTS_KEY, array.toString()).apply()
    }

    internal companion object {
        const val PREFERENCES_NAME = "focusguard_forum_posts"
        private const val POSTS_KEY = "posts"
        private const val ID_KEY = "id"
        private const val AUTHOR_KEY = "author"
        private const val AVATAR_KEY = "avatar"
        private const val BODY_KEY = "body"
        private const val CREATED_AT_KEY = "created_at"
    }
}
