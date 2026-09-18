package com.focusguard.data

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ForumStorageLayoutTest {

    @Test
    fun `forum layout keeps posts comments likes and users separated`() {
        assertThat(ForumStorageLayout.userFolder("user-123"))
            .isEqualTo("users/user-123")
        assertThat(ForumStorageLayout.userProfileFile("user-123"))
            .isEqualTo("users/user-123/profile.json")
        assertThat(ForumStorageLayout.userNotificationsFolder("user-123"))
            .isEqualTo("users/user-123/notifications")
        assertThat(
            ForumStorageLayout.notificationFile("user-123", "notification-1")
        ).isEqualTo("users/user-123/notifications/notification-1.json")
        assertThat(ForumStorageLayout.postFile("post-456"))
            .isEqualTo("posts/post-456/post.json")
        assertThat(ForumStorageLayout.commentFile("post-456", "comment-789"))
            .isEqualTo("posts/post-456/comments/comment-789.json")
        assertThat(ForumStorageLayout.likeFile("post-456", "user-123"))
            .startsWith("posts/post-456/likes/")
    }

    @Test
    fun `same post and user always produce same like record id`() {
        val first = ForumStorageLayout.likeRecordId("post-456", "user-123")
        val second = ForumStorageLayout.likeRecordId("post-456", "user-123")

        assertThat(first).isEqualTo(second)
        assertThat(first).hasLength(64)
    }

    @Test
    fun `different users cannot collide by display name`() {
        val first = ForumStorageLayout.likeRecordId("post-456", "user-a")
        val second = ForumStorageLayout.likeRecordId("post-456", "user-b")

        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `interaction notification ids are deterministic`() {
        val likeId = ForumStorageLayout.likeNotificationId("post-456", "user-123")
        val sameLikeId = ForumStorageLayout.likeNotificationId("post-456", "user-123")
        val commentId = ForumStorageLayout.commentNotificationId("comment-789")

        assertThat(likeId).isEqualTo(sameLikeId)
        assertThat(likeId).startsWith("like-")
        assertThat(commentId).startsWith("comment-")
        assertThat(likeId).isNotEqualTo(commentId)
    }

    @Test
    fun `path separators are rejected from storage identifiers`() {
        assertThrows(IllegalArgumentException::class.java) {
            ForumStorageLayout.postFolder("../post")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ForumStorageLayout.userFolder("folder/user")
        }
    }
}
