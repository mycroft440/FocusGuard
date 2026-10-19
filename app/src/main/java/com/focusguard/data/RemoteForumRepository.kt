package com.focusguard.data

import org.json.JSONArray
import org.json.JSONObject

/** Sign-in side of a shared forum; the local adapter has no accounts. */
interface ForumAccountGateway {
    suspend fun currentSession(): ForumSession?

    suspend fun signIn(idToken: String, profile: ForumAuthor): ForumSession

    suspend fun signOut()
}

/**
 * Forum shared by every HardBlock user, stored by forum-backend/Code.gs in a single
 * Google Sheets file. The backend identifies the caller from the session, so the user IDs
 * passed to these methods are ignored: nobody can act as someone else by changing them.
 *
 * Failures are thrown as [ForumApiException]; an expired or revoked session also clears
 * the stored one, so the next screen asks for sign-in again.
 */
class RemoteForumRepository(
    private val transport: ForumTransport,
    private val sessions: ForumSessionStorage,
    private val clock: () -> Long = System::currentTimeMillis
) : ForumRepository, ForumAccountGateway {

    override val isShared: Boolean
        get() = true

    override suspend fun currentSession(): ForumSession? {
        val session = sessions.load() ?: return null
        if (session.expiresAtMillis <= clock()) {
            sessions.clear()
            return null
        }
        return session
    }

    override suspend fun signIn(idToken: String, profile: ForumAuthor): ForumSession {
        val data = call(action = "signIn", authenticated = false) {
            put("idToken", idToken)
            put("author", authorJson(profile))
        }
        val userId = UserProfilePolicy.normalizeUserId(data.optString("userId"))
        val token = data.optString("session")
        val expiresAt = data.optLong("expiresAt", 0L)
        if (userId.isBlank() || token.isBlank() || expiresAt <= clock()) {
            throw ForumApiException(ForumErrorCode.SERVER)
        }
        return ForumSession(token = token, userId = userId, expiresAtMillis = expiresAt)
            .also { session -> sessions.save(session) }
    }

    override suspend fun signOut() {
        sessions.clear()
    }

    override suspend fun loadPosts(currentUserId: String): List<ForumPost> =
        loadPostsPage(currentUserId, cursor = null).posts

    override suspend fun loadPostsPage(currentUserId: String, cursor: String?): ForumPostPage {
        val data = call("listPosts") {
            if (cursor != null) put("cursor", cursor)
        }
        return ForumPostPage(
            posts = ForumWireFormat.posts(data.optJSONArray("posts")),
            nextCursor = data.optStringOrNull("nextCursor")
        )
    }

    override suspend fun loadUserPosts(userId: String): List<ForumPost> =
        ForumWireFormat.posts(call("listUserPosts").optJSONArray("posts"))

    override suspend fun publish(
        author: ForumAuthor,
        body: String,
        nowMillis: Long
    ): ForumPost? {
        val normalizedBody = ForumPostPolicy.normalizeForPublish(body)
        if (normalizedBody.isBlank()) return null
        val data = call("publishPost") {
            put("body", normalizedBody)
            put("author", authorJson(author))
        }
        return data.optJSONObject("post")?.let(ForumWireFormat::post)
            ?: throw ForumApiException(ForumErrorCode.SERVER)
    }

    override suspend fun toggleLike(
        postId: String,
        actor: ForumAuthor,
        nowMillis: Long
    ): ForumPost? {
        val data = call("toggleLike") {
            put("postId", postId)
            put("author", authorJson(actor))
        }
        return data.optJSONObject("post")?.let(ForumWireFormat::post)
            ?: throw ForumApiException(ForumErrorCode.SERVER)
    }

    override suspend fun loadComments(postId: String): List<ForumComment> =
        ForumWireFormat.comments(
            call("listComments") { put("postId", postId) }.optJSONArray("comments"),
            postId
        )

    override suspend fun addComment(
        postId: String,
        author: ForumAuthor,
        body: String,
        nowMillis: Long
    ): ForumComment? {
        val normalizedBody = ForumPostPolicy.normalizeCommentForPublish(body)
        if (normalizedBody.isBlank()) return null
        val data = call("addComment") {
            put("postId", postId)
            put("body", normalizedBody)
            put("author", authorJson(author))
        }
        return data.optJSONObject("comment")?.let { json -> ForumWireFormat.comment(json, postId) }
            ?: throw ForumApiException(ForumErrorCode.SERVER)
    }

    override suspend fun deletePost(postId: String, actor: ForumAuthor): Boolean {
        call("deletePost") { put("postId", postId) }
        return true
    }

    override suspend fun deleteComment(
        postId: String,
        commentId: String,
        actor: ForumAuthor
    ): Boolean {
        call("deleteComment") {
            put("postId", postId)
            put("commentId", commentId)
        }
        return true
    }

    override suspend fun reportPost(postId: String, actor: ForumAuthor): Boolean {
        call("report") {
            put("targetType", "post")
            put("targetId", postId)
        }
        return true
    }

    override suspend fun reportComment(
        postId: String,
        commentId: String,
        actor: ForumAuthor
    ): Boolean {
        call("report") {
            put("targetType", "comment")
            put("targetId", commentId)
        }
        return true
    }

    override suspend fun loadNotifications(userId: String): List<ForumNotification> =
        notificationsFrom(call("listNotifications"))

    override suspend fun markAllNotificationsRead(userId: String): List<ForumNotification> =
        notificationsFrom(call("markNotificationsRead"))

    private suspend fun notificationsFrom(data: JSONObject): List<ForumNotification> =
        ForumWireFormat.notifications(
            data.optJSONArray("notifications"),
            recipientUserId = currentSession()?.userId.orEmpty()
        )

    private suspend fun call(
        action: String,
        authenticated: Boolean = true,
        fill: JSONObject.() -> Unit = {}
    ): JSONObject {
        val request = JSONObject()
            .put("v", API_VERSION)
            .put("action", action)
        if (authenticated) {
            val session = currentSession() ?: throw ForumApiException(ForumErrorCode.UNAUTHORIZED)
            request.put("session", session.token)
        }
        request.fill()

        val envelope = transport.send(request)
        if (envelope.optBoolean("ok", false)) {
            return envelope.optJSONObject("data") ?: JSONObject()
        }
        val code = ForumErrorCode.fromServer(envelope.optString("error"))
        if (code == ForumErrorCode.UNAUTHORIZED) sessions.clear()
        throw ForumApiException(code)
    }

    private fun authorJson(author: ForumAuthor): JSONObject = JSONObject()
        .put("displayName", UserProfilePolicy.normalizeName(author.displayName))
        .put("avatarId", UserProfilePolicy.normalizeAvatarId(author.avatarId))

    private companion object {
        const val API_VERSION = 1
    }
}

/**
 * Reads the backend's JSON defensively: anything malformed is dropped instead of
 * reaching the UI, and duplicate IDs are removed because the lists use them as keys.
 */
internal object ForumWireFormat {
    private val ID_REGEX = Regex("[A-Za-z0-9_-]{1,80}")

    fun posts(array: JSONArray?): List<ForumPost> =
        objects(array).mapNotNull(::post).distinctBy(ForumPost::id)

    fun post(json: JSONObject): ForumPost? {
        val id = json.optString("id").takeIf(ID_REGEX::matches) ?: return null
        val body = ForumPostPolicy.normalizeForPublish(json.optString("body"))
        if (body.isBlank()) return null
        return ForumPost(
            id = id,
            authorName = UserProfilePolicy.normalizeName(json.optString("authorName")),
            avatarId = UserProfilePolicy.normalizeAvatarId(json.optInt("avatarId", 0)),
            body = body,
            createdAtMillis = json.optLong("createdAt", 0L).coerceAtLeast(0L),
            likeCount = json.optInt("likeCount", 0).coerceAtLeast(0),
            likedByMe = json.optBoolean("likedByMe", false),
            commentsCount = json.optInt("commentsCount", 0).coerceAtLeast(0),
            authorId = UserProfilePolicy.normalizeUserId(json.optString("authorId"))
        )
    }

    fun comments(array: JSONArray?, postId: String): List<ForumComment> =
        objects(array)
            .mapNotNull { json -> comment(json, postId) }
            .distinctBy(ForumComment::id)

    fun comment(json: JSONObject, postId: String): ForumComment? {
        val id = json.optString("id").takeIf(ID_REGEX::matches) ?: return null
        val body = ForumPostPolicy.normalizeCommentForPublish(json.optString("body"))
        if (body.isBlank()) return null
        return ForumComment(
            id = id,
            authorName = UserProfilePolicy.normalizeName(json.optString("authorName")),
            avatarId = UserProfilePolicy.normalizeAvatarId(json.optInt("avatarId", 0)),
            body = body,
            createdAtMillis = json.optLong("createdAt", 0L).coerceAtLeast(0L),
            postId = json.optString("postId").takeIf(ID_REGEX::matches) ?: postId,
            authorId = UserProfilePolicy.normalizeUserId(json.optString("authorId"))
        )
    }

    fun notifications(array: JSONArray?, recipientUserId: String): List<ForumNotification> =
        objects(array)
            .mapNotNull { json -> notification(json, recipientUserId) }
            .distinctBy(ForumNotification::id)

    private fun notification(json: JSONObject, recipientUserId: String): ForumNotification? {
        val id = json.optString("id").takeIf(ID_REGEX::matches) ?: return null
        val postId = json.optString("postId").takeIf(ID_REGEX::matches) ?: return null
        val actorId = UserProfilePolicy.normalizeUserId(json.optString("actorId"))
        val type = when (json.optString("type")) {
            "LIKE" -> ForumNotificationType.LIKE
            "COMMENT" -> ForumNotificationType.COMMENT
            else -> return null
        }
        if (actorId.isBlank()) return null
        return ForumNotification(
            id = id,
            recipientUserId = recipientUserId,
            actorId = actorId,
            actorName = UserProfilePolicy.normalizeName(json.optString("actorName")),
            actorAvatarId = UserProfilePolicy.normalizeAvatarId(json.optInt("actorAvatarId", 0)),
            postId = postId,
            type = type,
            createdAtMillis = json.optLong("createdAt", 0L).coerceAtLeast(0L),
            isRead = json.optBoolean("read", false)
        )
    }

    private fun objects(array: JSONArray?): List<JSONObject> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull(array::optJSONObject)
    }
}

/** org.json turns a JSON null into the text "null" with optString; this does not. */
private fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotBlank)
