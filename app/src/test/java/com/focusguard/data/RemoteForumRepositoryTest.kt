package com.focusguard.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteForumRepositoryTest {
    private val now = 1_800_000_000_000L
    private val sessions = InMemorySessions()
    private val transport = ScriptedTransport()
    private val repository = RemoteForumRepository(transport, sessions, clock = { now })

    @Test
    fun `sign in stores the session the backend issued`() = runBlocking {
        transport.reply(ok(JSONObject()
            .put("session", "v1.uabc.123.sig")
            .put("userId", "u0123456789abcdef01234567")
            .put("expiresAt", now + 1_000L)))

        val session = repository.signIn("id-token", ForumAuthor("ignored", "  Ana  ", 3))

        assertThat(session.userId).isEqualTo("u0123456789abcdef01234567")
        assertThat(sessions.saved).isEqualTo(session)
        val request = transport.requests.single()
        assertThat(request.getInt("v")).isEqualTo(1)
        assertThat(request.getString("action")).isEqualTo("signIn")
        assertThat(request.getString("idToken")).isEqualTo("id-token")
        assertThat(request.has("session")).isFalse()
        assertThat(request.getJSONObject("author").getString("displayName")).isEqualTo("Ana")
        assertThat(request.getJSONObject("author").getInt("avatarId")).isEqualTo(3)
    }

    @Test
    fun `calls without a session fail before reaching the network`() {
        val error = assertThrows(ForumApiException::class.java) {
            runBlocking { repository.loadPosts("anyone") }
        }
        assertThat(error.code).isEqualTo(ForumErrorCode.UNAUTHORIZED)
        assertThat(transport.requests).isEmpty()
    }

    @Test
    fun `an expired session is dropped instead of being sent`() = runBlocking {
        sessions.saved = ForumSession("token", "u1", expiresAtMillis = now)

        assertThat(repository.currentSession()).isNull()
        assertThat(sessions.saved).isNull()
    }

    @Test
    fun `the backend rejecting the session clears it`() {
        sessions.saved = validSession()
        transport.reply(JSONObject().put("ok", false).put("error", "unauthorized"))

        val error = assertThrows(ForumApiException::class.java) {
            runBlocking { repository.loadPosts("u1") }
        }
        assertThat(error.code).isEqualTo(ForumErrorCode.UNAUTHORIZED)
        assertThat(sessions.saved).isNull()
    }

    @Test
    fun `backend error codes keep the session and map to typed errors`() {
        sessions.saved = validSession()
        val expected = mapOf(
            "rate_limited" to ForumErrorCode.RATE_LIMITED,
            "banned" to ForumErrorCode.BANNED,
            "not_found" to ForumErrorCode.NOT_FOUND,
            "busy" to ForumErrorCode.BUSY,
            "unsupported_version" to ForumErrorCode.BAD_REQUEST,
            "something_new" to ForumErrorCode.SERVER
        )
        expected.forEach { (server, code) ->
            transport.reply(JSONObject().put("ok", false).put("error", server))
            val error = assertThrows(ForumApiException::class.java) {
                runBlocking { repository.loadPosts("u1") }
            }
            assertThat(error.code).isEqualTo(code)
        }
        assertThat(sessions.saved).isNotNull()
    }

    @Test
    fun `a page carries the cursor and drops malformed or repeated posts`() = runBlocking {
        sessions.saved = validSession()
        transport.reply(ok(JSONObject()
            .put("posts", JSONArray()
                .put(postJson("p1", "Primeiro"))
                .put(postJson("p1", "Repetido"))
                .put(postJson("p2", "   "))
                .put(postJson("../etc", "Caminho"))
                .put(postJson("p3", "Terceiro").put("likedByMe", true).put("likeCount", -4)))
            .put("nextCursor", "41.1800000000000")))

        val page = repository.loadPostsPage("ignored", cursor = "60.1800000000500")

        assertThat(page.posts.map(ForumPost::id)).containsExactly("p1", "p3").inOrder()
        assertThat(page.posts[0].body).isEqualTo("Primeiro")
        assertThat(page.posts[1].likedByMe).isTrue()
        assertThat(page.posts[1].likeCount).isEqualTo(0)
        assertThat(page.nextCursor).isEqualTo("41.1800000000000")
        val request = transport.requests.single()
        assertThat(request.getString("session")).isEqualTo("token")
        assertThat(request.getString("cursor")).isEqualTo("60.1800000000500")
    }

    @Test
    fun `a JSON null cursor means the feed ended`() = runBlocking {
        sessions.saved = validSession()
        transport.reply(ok(JSONObject().put("posts", JSONArray()).put("nextCursor", JSONObject.NULL)))

        assertThat(repository.loadPostsPage("u1", cursor = null).nextCursor).isNull()
        assertThat(transport.requests.single().has("cursor")).isFalse()
    }

    @Test
    fun `blank posts and comments never leave the device`() = runBlocking {
        sessions.saved = validSession()

        assertThat(repository.publish(ForumAuthor("u1", "Ana", 0), " \n ")).isNull()
        assertThat(repository.addComment("p1", ForumAuthor("u1", "Ana", 0), "  ")).isNull()
        assertThat(transport.requests).isEmpty()
    }

    @Test
    fun `reports name the comment, not the post`() = runBlocking {
        sessions.saved = validSession()
        transport.reply(ok(JSONObject().put("reported", true)))

        assertThat(repository.reportComment("p1", "c9", ForumAuthor("u1", "Ana", 0))).isTrue()

        val request = transport.requests.single()
        assertThat(request.getString("action")).isEqualTo("report")
        assertThat(request.getString("targetType")).isEqualTo("comment")
        assertThat(request.getString("targetId")).isEqualTo("c9")
    }

    @Test
    fun `notifications belong to the signed in user`() = runBlocking {
        sessions.saved = validSession()
        transport.reply(ok(JSONObject().put("notifications", JSONArray()
            .put(JSONObject()
                .put("id", "nl-p1-u2")
                .put("actorId", "u2")
                .put("actorName", "Bia")
                .put("actorAvatarId", 2)
                .put("postId", "p1")
                .put("type", "LIKE")
                .put("createdAt", 5L)
                .put("read", false))
            .put(JSONObject()
                .put("id", "nx")
                .put("actorId", "u2")
                .put("postId", "p1")
                .put("type", "SHARE")))))

        val notifications = repository.loadNotifications("ignored")

        assertThat(notifications).hasSize(1)
        assertThat(notifications[0].recipientUserId).isEqualTo("u1")
        assertThat(notifications[0].actorName).isEqualTo("Bia")
        assertThat(notifications[0].type).isEqualTo(ForumNotificationType.LIKE)
    }

    private fun validSession() = ForumSession("token", "u1", expiresAtMillis = now + 60_000L)

    private fun ok(data: JSONObject) = JSONObject().put("ok", true).put("data", data)

    private fun postJson(id: String, body: String) = JSONObject()
        .put("id", id)
        .put("authorId", "u2")
        .put("authorName", "Bia")
        .put("avatarId", 1)
        .put("body", body)
        .put("createdAt", 10L)
        .put("likeCount", 0)
        .put("commentsCount", 0)

    private class InMemorySessions : ForumSessionStorage {
        var saved: ForumSession? = null

        override suspend fun load(): ForumSession? = saved

        override suspend fun save(session: ForumSession) {
            saved = session
        }

        override suspend fun clear() {
            saved = null
        }
    }

    private class ScriptedTransport : ForumTransport {
        val requests = mutableListOf<JSONObject>()
        private val replies = ArrayDeque<JSONObject>()

        fun reply(envelope: JSONObject) {
            replies.addLast(envelope)
        }

        override suspend fun send(request: JSONObject): JSONObject {
            requests += JSONObject(request.toString())
            return replies.removeFirst()
        }
    }
}
