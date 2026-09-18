package com.focusguard.data

import java.security.MessageDigest

data class ForumAuthor(
    val userId: String,
    val displayName: String,
    val avatarId: Int
)

enum class ForumNotificationType {
    LIKE,
    COMMENT
}

data class ForumNotification(
    val id: String,
    val recipientUserId: String,
    val actorId: String,
    val actorName: String,
    val actorAvatarId: Int,
    val postId: String,
    val type: ForumNotificationType,
    val createdAtMillis: Long,
    val isRead: Boolean = false
)

interface ForumRepository {
    suspend fun loadPosts(currentUserId: String): List<ForumPost>

    suspend fun loadUserPosts(userId: String): List<ForumPost>

    suspend fun publish(
        author: ForumAuthor,
        body: String,
        nowMillis: Long = System.currentTimeMillis()
    ): ForumPost?

    suspend fun toggleLike(
        postId: String,
        actor: ForumAuthor,
        nowMillis: Long = System.currentTimeMillis()
    ): ForumPost?

    suspend fun loadComments(postId: String): List<ForumComment>

    suspend fun addComment(
        postId: String,
        author: ForumAuthor,
        body: String,
        nowMillis: Long = System.currentTimeMillis()
    ): ForumComment?

    suspend fun loadNotifications(userId: String): List<ForumNotification>

    suspend fun markAllNotificationsRead(userId: String): List<ForumNotification>
}

fun UserProfile.toForumAuthor(): ForumAuthor = ForumAuthor(
    userId = userId,
    displayName = displayName,
    avatarId = avatarId
)

/**
 * Logical layout used by forum storage adapters.
 *
 * Google Drive does not expose real filesystem paths, but the future Drive adapter can map
 * each segment to a folder/file ID while preserving the same deterministic hierarchy.
 */
object ForumStorageLayout {
    const val USERS_DIRECTORY = "users"
    const val POSTS_DIRECTORY = "posts"
    const val COMMENTS_DIRECTORY = "comments"
    const val LIKES_DIRECTORY = "likes"
    const val NOTIFICATIONS_DIRECTORY = "notifications"
    const val POST_FILE_NAME = "post.json"
    const val PROFILE_FILE_NAME = "profile.json"

    fun userFolder(userId: String): String =
        "$USERS_DIRECTORY/${component(userId)}"

    fun userProfileFile(userId: String): String =
        "${userFolder(userId)}/$PROFILE_FILE_NAME"

    fun userNotificationsFolder(userId: String): String =
        "${userFolder(userId)}/$NOTIFICATIONS_DIRECTORY"

    fun notificationFile(userId: String, notificationId: String): String =
        "${userNotificationsFolder(userId)}/${component(notificationId)}.json"

    fun postFolder(postId: String): String =
        "$POSTS_DIRECTORY/${component(postId)}"

    fun postFile(postId: String): String =
        "${postFolder(postId)}/$POST_FILE_NAME"

    fun commentsFolder(postId: String): String =
        "${postFolder(postId)}/$COMMENTS_DIRECTORY"

    fun commentFile(postId: String, commentId: String): String =
        "${commentsFolder(postId)}/${component(commentId)}.json"

    fun likesFolder(postId: String): String =
        "${postFolder(postId)}/$LIKES_DIRECTORY"

    fun likeRecordId(postId: String, userId: String): String {
        val source = "${component(postId)}:${component(userId)}"
        return sha256(source)
    }

    fun likeNotificationId(postId: String, userId: String): String =
        "like-${likeRecordId(postId, userId)}"

    fun commentNotificationId(commentId: String): String =
        "comment-${sha256(component(commentId))}"

    fun likeFile(postId: String, userId: String): String =
        "${likesFolder(postId)}/${likeRecordId(postId, userId)}.json"

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }

    private fun component(value: String): String {
        val normalized = value.trim()
        require(normalized.isNotEmpty()) { "Forum storage ID cannot be blank." }
        require(COMPONENT_REGEX.matches(normalized)) {
            "Forum storage ID contains unsupported characters."
        }
        require(normalized != "." && normalized != "..") {
            "Forum storage ID cannot be a relative path segment."
        }
        return normalized
    }

    private val COMPONENT_REGEX = Regex("[A-Za-z0-9._-]+")
}
