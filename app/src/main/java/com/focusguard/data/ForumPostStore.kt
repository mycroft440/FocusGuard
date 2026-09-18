package com.focusguard.data

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ForumComment(
    val id: String,
    val authorName: String,
    val avatarId: Int,
    val body: String,
    val createdAtMillis: Long,
    val postId: String = "",
    val authorId: String = ""
)

data class ForumPost(
    val id: String,
    val authorName: String,
    val avatarId: Int,
    val body: String,
    val createdAtMillis: Long,
    val likeCount: Int = 0,
    val likedByMe: Boolean = false,
    val commentsCount: Int = 0,
    val authorId: String = ""
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

/**
 * Local adapter used until a remote forum backend is configured.
 *
 * The persisted shape intentionally mirrors the future remote layout: the post feed contains
 * summaries only, while likes, comments and notifications are stored independently.
 */
class ForumPostStore(context: Context) : ForumRepository {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val lock = Any()

    override suspend fun loadPosts(currentUserId: String): List<ForumPost> =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                loadPostsInternal(UserProfilePolicy.normalizeUserId(currentUserId))
            }
        }

    override suspend fun loadUserPosts(userId: String): List<ForumPost> =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val normalizedUserId = UserProfilePolicy.normalizeUserId(userId)
                if (normalizedUserId.isBlank()) {
                    emptyList()
                } else {
                    loadPostsInternal(normalizedUserId)
                        .filter { post -> post.authorId == normalizedUserId }
                }
            }
        }

    override suspend fun publish(
        author: ForumAuthor,
        body: String,
        nowMillis: Long
    ): ForumPost? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val normalizedBody = ForumPostPolicy.normalizeForPublish(body)
            val authorId = UserProfilePolicy.normalizeUserId(author.userId)
            if (normalizedBody.isBlank() || authorId.isBlank()) {
                return@synchronized null
            }

            migrateLegacyInteractions(authorId)
            val post = ForumPost(
                id = nowMillis.coerceAtLeast(0L).toString() + "-" + UUID.randomUUID(),
                authorName = UserProfilePolicy.normalizeName(author.displayName),
                avatarId = UserProfilePolicy.normalizeAvatarId(author.avatarId),
                body = normalizedBody,
                createdAtMillis = nowMillis.coerceAtLeast(0L),
                authorId = authorId
            )
            persistPosts(listOf(post) + loadPostsInternal(authorId))
            post
        }
    }

    override suspend fun toggleLike(
        postId: String,
        actor: ForumAuthor,
        nowMillis: Long
    ): ForumPost? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val actorId = UserProfilePolicy.normalizeUserId(actor.userId)
            if (actorId.isBlank()) return@synchronized null

            migrateLegacyInteractions(actorId)
            val posts = loadPostsInternal(actorId)
            val post = posts.firstOrNull { it.id == postId } ?: return@synchronized null
            val likedUserIds = readLikeUserIds(postId).toMutableSet()
            val wasLiked = actorId in likedUserIds

            if (wasLiked) {
                likedUserIds.remove(actorId)
                removeNotification(
                    userId = post.authorId,
                    notificationId = ForumStorageLayout.likeNotificationId(postId, actorId)
                )
            } else {
                likedUserIds.add(actorId)
                createInteractionNotification(
                    post = post,
                    actor = actor,
                    type = ForumNotificationType.LIKE,
                    notificationId = ForumStorageLayout.likeNotificationId(postId, actorId),
                    nowMillis = nowMillis
                )
            }

            val nextLikeCount = (
                post.likeCount + if (wasLiked) -1 else 1
            ).coerceAtLeast(likedUserIds.size)

            val updated = post.copy(
                likedByMe = !wasLiked,
                likeCount = nextLikeCount
            )
            persistLikeUserIds(postId, likedUserIds)
            persistPosts(posts.map { candidate ->
                if (candidate.id == postId) updated else candidate
            })
            updated
        }
    }

    override suspend fun loadComments(postId: String): List<ForumComment> =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                loadCommentsInternal(postId)
            }
        }

    override suspend fun addComment(
        postId: String,
        author: ForumAuthor,
        body: String,
        nowMillis: Long
    ): ForumComment? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val normalizedBody = ForumPostPolicy.normalizeCommentForPublish(body)
            val authorId = UserProfilePolicy.normalizeUserId(author.userId)
            if (normalizedBody.isBlank() || authorId.isBlank()) {
                return@synchronized null
            }

            migrateLegacyInteractions(authorId)
            val posts = loadPostsInternal(authorId)
            val post = posts.firstOrNull { it.id == postId } ?: return@synchronized null
            val comment = ForumComment(
                id = nowMillis.coerceAtLeast(0L).toString() + "-" + UUID.randomUUID(),
                authorName = UserProfilePolicy.normalizeName(author.displayName),
                avatarId = UserProfilePolicy.normalizeAvatarId(author.avatarId),
                body = normalizedBody,
                createdAtMillis = nowMillis.coerceAtLeast(0L),
                postId = postId,
                authorId = authorId
            )

            val comments = loadCommentsInternal(postId) + comment
            persistComments(postId, comments)
            persistPosts(posts.map { candidate ->
                if (candidate.id == postId) {
                    post.copy(commentsCount = comments.size)
                } else {
                    candidate
                }
            })
            createInteractionNotification(
                post = post,
                actor = author,
                type = ForumNotificationType.COMMENT,
                notificationId = ForumStorageLayout.commentNotificationId(comment.id),
                nowMillis = nowMillis
            )
            comment
        }
    }

    override suspend fun loadNotifications(userId: String): List<ForumNotification> =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val normalizedUserId = UserProfilePolicy.normalizeUserId(userId)
                if (normalizedUserId.isBlank()) {
                    emptyList()
                } else {
                    readNotifications(normalizedUserId)
                }
            }
        }

    override suspend fun markAllNotificationsRead(
        userId: String
    ): List<ForumNotification> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val normalizedUserId = UserProfilePolicy.normalizeUserId(userId)
            if (normalizedUserId.isBlank()) {
                return@synchronized emptyList()
            }

            val updated = readNotifications(normalizedUserId)
                .map { notification -> notification.copy(isRead = true) }
            persistNotifications(normalizedUserId, updated)
            updated
        }
    }

    private fun loadPostsInternal(currentUserId: String): List<ForumPost> {
        val raw = preferences.getString(POSTS_KEY, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
        val posts = ArrayList<ForumPost>(array.length())

        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val body = ForumPostPolicy.normalizeForPublish(json.optString(BODY_KEY))
            if (body.isBlank()) continue

            val postId = json.optString(ID_KEY).ifBlank { "legacy-$index" }
            val likesRaw = preferences.getString(likesKey(postId), null)
            val likedByMe = if (likesRaw != null && currentUserId.isNotBlank()) {
                currentUserId in readLikeUserIds(postId)
            } else {
                json.optBoolean(LIKED_BY_ME_KEY, false)
            }
            val commentsCount = when {
                json.has(COMMENTS_COUNT_KEY) ->
                    json.optInt(COMMENTS_COUNT_KEY, 0).coerceAtLeast(0)
                preferences.contains(commentsKey(postId)) ->
                    readComments(
                        rawArray = preferences.getString(commentsKey(postId), null),
                        postId = postId
                    ).size
                else ->
                    readComments(
                        array = json.optJSONArray(COMMENTS_KEY),
                        postId = postId
                    ).size
            }

            posts += ForumPost(
                id = postId,
                authorName = UserProfilePolicy.normalizeName(json.optString(AUTHOR_KEY)),
                avatarId = UserProfilePolicy.normalizeAvatarId(
                    json.optInt(AVATAR_KEY, UserProfilePolicy.DEFAULT_AVATAR_ID)
                ),
                body = body,
                createdAtMillis = json.optLong(CREATED_AT_KEY, 0L).coerceAtLeast(0L),
                likeCount = json.optInt(LIKE_COUNT_KEY, 0)
                    .coerceAtLeast(readLikeUserIds(postId).size),
                likedByMe = likedByMe,
                commentsCount = commentsCount,
                authorId = UserProfilePolicy.normalizeUserId(json.optString(AUTHOR_ID_KEY))
            )
        }

        return posts.sortedByDescending(ForumPost::createdAtMillis)
    }

    private fun loadCommentsInternal(postId: String): List<ForumComment> {
        val dedicated = preferences.getString(commentsKey(postId), null)
        if (dedicated != null) {
            return readComments(rawArray = dedicated, postId = postId)
        }

        val rawPosts = preferences.getString(POSTS_KEY, null) ?: return emptyList()
        val posts = runCatching { JSONArray(rawPosts) }.getOrElse { return emptyList() }
        for (index in 0 until posts.length()) {
            val json = posts.optJSONObject(index) ?: continue
            val candidateId = json.optString(ID_KEY).ifBlank { "legacy-$index" }
            if (candidateId == postId) {
                return readComments(
                    array = json.optJSONArray(COMMENTS_KEY),
                    postId = postId
                )
            }
        }
        return emptyList()
    }

    private fun createInteractionNotification(
        post: ForumPost,
        actor: ForumAuthor,
        type: ForumNotificationType,
        notificationId: String,
        nowMillis: Long
    ) {
        val recipientUserId = UserProfilePolicy.normalizeUserId(post.authorId)
        val actorId = UserProfilePolicy.normalizeUserId(actor.userId)
        if (
            recipientUserId.isBlank() ||
            actorId.isBlank() ||
            recipientUserId == actorId
        ) {
            return
        }

        val notification = ForumNotification(
            id = notificationId,
            recipientUserId = recipientUserId,
            actorId = actorId,
            actorName = UserProfilePolicy.normalizeName(actor.displayName),
            actorAvatarId = UserProfilePolicy.normalizeAvatarId(actor.avatarId),
            postId = post.id,
            type = type,
            createdAtMillis = nowMillis.coerceAtLeast(0L)
        )
        val existing = readNotifications(recipientUserId)
            .filterNot { candidate -> candidate.id == notification.id }
        persistNotifications(recipientUserId, listOf(notification) + existing)
    }

    private fun removeNotification(userId: String, notificationId: String) {
        val normalizedUserId = UserProfilePolicy.normalizeUserId(userId)
        if (normalizedUserId.isBlank()) return
        val remaining = readNotifications(normalizedUserId)
            .filterNot { notification -> notification.id == notificationId }
        persistNotifications(normalizedUserId, remaining)
    }

    private fun readNotifications(userId: String): List<ForumNotification> {
        val raw = preferences.getString(notificationsKey(userId), null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
        val notifications = ArrayList<ForumNotification>(array.length())

        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val id = json.optString(NOTIFICATION_ID_KEY)
            val actorId = UserProfilePolicy.normalizeUserId(
                json.optString(NOTIFICATION_ACTOR_ID_KEY)
            )
            val postId = json.optString(NOTIFICATION_POST_ID_KEY)
            val type = runCatching {
                ForumNotificationType.valueOf(json.optString(NOTIFICATION_TYPE_KEY))
            }.getOrNull()
            if (
                id.isBlank() ||
                actorId.isBlank() ||
                postId.isBlank() ||
                type == null
            ) {
                continue
            }

            notifications += ForumNotification(
                id = id,
                recipientUserId = userId,
                actorId = actorId,
                actorName = UserProfilePolicy.normalizeName(
                    json.optString(NOTIFICATION_ACTOR_NAME_KEY)
                ),
                actorAvatarId = UserProfilePolicy.normalizeAvatarId(
                    json.optInt(
                        NOTIFICATION_ACTOR_AVATAR_KEY,
                        UserProfilePolicy.DEFAULT_AVATAR_ID
                    )
                ),
                postId = postId,
                type = type,
                createdAtMillis = json.optLong(
                    NOTIFICATION_CREATED_AT_KEY,
                    0L
                ).coerceAtLeast(0L),
                isRead = json.optBoolean(NOTIFICATION_READ_KEY, false)
            )
        }

        return notifications.sortedByDescending(ForumNotification::createdAtMillis)
    }

    private fun persistNotifications(
        userId: String,
        notifications: List<ForumNotification>
    ) {
        val array = JSONArray()
        notifications.forEach { notification ->
            array.put(
                JSONObject()
                    .put(NOTIFICATION_ID_KEY, notification.id)
                    .put(NOTIFICATION_RECIPIENT_ID_KEY, notification.recipientUserId)
                    .put(NOTIFICATION_ACTOR_ID_KEY, notification.actorId)
                    .put(NOTIFICATION_ACTOR_NAME_KEY, notification.actorName)
                    .put(NOTIFICATION_ACTOR_AVATAR_KEY, notification.actorAvatarId)
                    .put(NOTIFICATION_POST_ID_KEY, notification.postId)
                    .put(NOTIFICATION_TYPE_KEY, notification.type.name)
                    .put(NOTIFICATION_CREATED_AT_KEY, notification.createdAtMillis)
                    .put(NOTIFICATION_READ_KEY, notification.isRead)
            )
        }
        preferences.edit()
            .putString(notificationsKey(userId), array.toString())
            .commit()
    }

    private fun migrateLegacyInteractions(currentUserId: String) {
        val rawPosts = preferences.getString(POSTS_KEY, null) ?: return
        val posts = runCatching { JSONArray(rawPosts) }.getOrElse { return }
        val editor = preferences.edit()
        var changed = false

        for (index in 0 until posts.length()) {
            val json = posts.optJSONObject(index) ?: continue
            val postId = json.optString(ID_KEY).ifBlank { "legacy-$index" }

            if (!preferences.contains(commentsKey(postId))) {
                val legacyComments = json.optJSONArray(COMMENTS_KEY)
                if (legacyComments != null && legacyComments.length() > 0) {
                    editor.putString(commentsKey(postId), legacyComments.toString())
                    changed = true
                }
            }

            if (
                currentUserId.isNotBlank() &&
                !preferences.contains(likesKey(postId)) &&
                json.optBoolean(LIKED_BY_ME_KEY, false)
            ) {
                editor.putString(
                    likesKey(postId),
                    JSONArray().put(currentUserId).toString()
                )
                changed = true
            }
        }

        if (changed) editor.commit()
    }

    private fun readComments(
        array: JSONArray?,
        postId: String
    ): List<ForumComment> {
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
                    "legacy-comment-${postId.hashCode()}-$index"
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
                ).coerceAtLeast(0L),
                postId = postId,
                authorId = UserProfilePolicy.normalizeUserId(
                    json.optString(COMMENT_AUTHOR_ID_KEY)
                )
            )
        }

        return comments.sortedBy(ForumComment::createdAtMillis)
    }

    private fun readComments(
        rawArray: String?,
        postId: String
    ): List<ForumComment> {
        if (rawArray.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(rawArray) }.getOrElse { return emptyList() }
        return readComments(array = array, postId = postId)
    }

    private fun persistPosts(posts: List<ForumPost>) {
        val array = JSONArray()
        posts.forEach { post ->
            array.put(
                JSONObject()
                    .put(ID_KEY, post.id)
                    .put(AUTHOR_ID_KEY, post.authorId)
                    .put(AUTHOR_KEY, post.authorName)
                    .put(AVATAR_KEY, post.avatarId)
                    .put(BODY_KEY, post.body)
                    .put(CREATED_AT_KEY, post.createdAtMillis)
                    .put(LIKE_COUNT_KEY, post.likeCount.coerceAtLeast(0))
                    .put(LIKED_BY_ME_KEY, post.likedByMe)
                    .put(COMMENTS_COUNT_KEY, post.commentsCount.coerceAtLeast(0))
            )
        }
        preferences.edit().putString(POSTS_KEY, array.toString()).commit()
    }

    private fun persistComments(postId: String, comments: List<ForumComment>) {
        val array = JSONArray()
        comments.forEach { comment ->
            array.put(
                JSONObject()
                    .put(COMMENT_ID_KEY, comment.id)
                    .put(COMMENT_POST_ID_KEY, postId)
                    .put(COMMENT_AUTHOR_ID_KEY, comment.authorId)
                    .put(COMMENT_AUTHOR_KEY, comment.authorName)
                    .put(COMMENT_AVATAR_KEY, comment.avatarId)
                    .put(COMMENT_BODY_KEY, comment.body)
                    .put(COMMENT_CREATED_AT_KEY, comment.createdAtMillis)
            )
        }
        preferences.edit().putString(commentsKey(postId), array.toString()).commit()
    }

    private fun readLikeUserIds(postId: String): Set<String> {
        val raw = preferences.getString(likesKey(postId), null) ?: return emptySet()
        val array = runCatching { JSONArray(raw) }.getOrElse { return emptySet() }
        return buildSet {
            for (index in 0 until array.length()) {
                val userId = UserProfilePolicy.normalizeUserId(array.optString(index))
                if (userId.isNotBlank()) add(userId)
            }
        }
    }

    private fun persistLikeUserIds(postId: String, userIds: Set<String>) {
        val array = JSONArray()
        userIds.sorted().forEach { userId -> array.put(userId) }
        preferences.edit().putString(likesKey(postId), array.toString()).commit()
    }

    private fun commentsKey(postId: String): String = COMMENTS_PREFIX + postId

    private fun likesKey(postId: String): String = LIKES_PREFIX + postId

    private fun notificationsKey(userId: String): String = NOTIFICATIONS_PREFIX + userId

    internal companion object {
        const val PREFERENCES_NAME = "focusguard_forum_posts"
        internal const val POSTS_KEY = "posts"

        private const val COMMENTS_PREFIX = "comments:"
        private const val LIKES_PREFIX = "likes:"
        private const val NOTIFICATIONS_PREFIX = "notifications:"

        private const val ID_KEY = "id"
        private const val AUTHOR_ID_KEY = "author_id"
        private const val AUTHOR_KEY = "author"
        private const val AVATAR_KEY = "avatar"
        private const val BODY_KEY = "body"
        private const val CREATED_AT_KEY = "created_at"
        private const val LIKE_COUNT_KEY = "like_count"
        private const val LIKED_BY_ME_KEY = "liked_by_me"
        private const val COMMENTS_COUNT_KEY = "comments_count"
        private const val COMMENTS_KEY = "comments"

        private const val COMMENT_ID_KEY = "id"
        private const val COMMENT_POST_ID_KEY = "post_id"
        private const val COMMENT_AUTHOR_ID_KEY = "author_id"
        private const val COMMENT_AUTHOR_KEY = "author"
        private const val COMMENT_AVATAR_KEY = "avatar"
        private const val COMMENT_BODY_KEY = "body"
        private const val COMMENT_CREATED_AT_KEY = "created_at"

        private const val NOTIFICATION_ID_KEY = "id"
        private const val NOTIFICATION_RECIPIENT_ID_KEY = "recipient_id"
        private const val NOTIFICATION_ACTOR_ID_KEY = "actor_id"
        private const val NOTIFICATION_ACTOR_NAME_KEY = "actor_name"
        private const val NOTIFICATION_ACTOR_AVATAR_KEY = "actor_avatar"
        private const val NOTIFICATION_POST_ID_KEY = "post_id"
        private const val NOTIFICATION_TYPE_KEY = "type"
        private const val NOTIFICATION_CREATED_AT_KEY = "created_at"
        private const val NOTIFICATION_READ_KEY = "read"
    }
}
