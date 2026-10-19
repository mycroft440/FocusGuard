package com.focusguard.ui.compose.screens

import com.focusguard.data.ForumAccountGateway
import com.focusguard.data.ForumApiException
import com.focusguard.data.ForumAuthor
import com.focusguard.data.ForumComment
import com.focusguard.data.ForumErrorCode
import com.focusguard.data.ForumGoogleSignInResult
import com.focusguard.data.ForumNotification
import com.focusguard.data.ForumPost
import com.focusguard.data.ForumPostPage
import com.focusguard.data.ForumRepository
import com.focusguard.data.ForumSession
import com.focusguard.data.UserProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForumViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val profile = UserProfile(displayName = "Ana", avatarId = 2, userId = "local-ana")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `without a stored session the shared forum asks for sign-in`() = runTest(dispatcher) {
        val backend = FakeBackend(session = null)
        val viewModel = ForumViewModel(backend, backend)

        viewModel.bind(profile)
        advanceUntilIdle()

        assertThat(viewModel.state.value.access).isEqualTo(ForumAccess.SIGNED_OUT)
        assertThat(backend.feedRequests).isEqualTo(0)
    }

    @Test
    fun `signing in loads the feed as the backend user, not the local profile`() =
        runTest(dispatcher) {
            val backend = FakeBackend(session = null, posts = listOf(post("p1", "u-bia")))
            val viewModel = ForumViewModel(backend, backend)
            viewModel.bind(profile)
            advanceUntilIdle()

            viewModel.onSignInStarted()
            assertThat(viewModel.state.value.access).isEqualTo(ForumAccess.SIGNING_IN)
            viewModel.onSignInFinished(ForumGoogleSignInResult.Success("token"))
            advanceUntilIdle()

            val state = viewModel.state.value
            assertThat(state.access).isEqualTo(ForumAccess.SIGNED_IN)
            assertThat(state.currentUserId).isEqualTo("u-ana")
            assertThat(state.feed.items.map(ForumPost::id)).containsExactly("p1")
            assertThat(backend.signInAuthor).isEqualTo(ForumAuthor("", "Ana", 2))
        }

    @Test
    fun `cancelled and failed Google sign-in go back to the card`() = runTest(dispatcher) {
        val backend = FakeBackend(session = null)
        val viewModel = ForumViewModel(backend, backend)
        viewModel.bind(profile)
        advanceUntilIdle()

        viewModel.onSignInStarted()
        viewModel.onSignInFinished(ForumGoogleSignInResult.Cancelled)
        assertThat(viewModel.state.value.access).isEqualTo(ForumAccess.SIGNED_OUT)
        assertThat(viewModel.state.value.signInProblem).isNull()

        viewModel.onSignInStarted()
        viewModel.onSignInFinished(ForumGoogleSignInResult.NoGoogleAccount)
        assertThat(viewModel.state.value.signInProblem).isEqualTo(ForumProblem.NO_GOOGLE_ACCOUNT)

        backend.signInError = ForumApiException(ForumErrorCode.INVALID_TOKEN)
        viewModel.onSignInStarted()
        viewModel.onSignInFinished(ForumGoogleSignInResult.Success("bad"))
        advanceUntilIdle()
        assertThat(viewModel.state.value.access).isEqualTo(ForumAccess.SIGNED_OUT)
        assertThat(viewModel.state.value.signInProblem).isEqualTo(ForumProblem.SIGN_IN_FAILED)
    }

    @Test
    fun `a session rejected mid-use sends the person back to sign in`() = runTest(dispatcher) {
        val backend = FakeBackend(posts = listOf(post("p1", "u-bia")))
        val viewModel = ForumViewModel(backend, backend)
        viewModel.bind(profile)
        advanceUntilIdle()
        assertThat(viewModel.state.value.feed.items).hasSize(1)

        backend.feedError = ForumApiException(ForumErrorCode.UNAUTHORIZED)
        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.access).isEqualTo(ForumAccess.SIGNED_OUT)
        assertThat(state.signInProblem).isEqualTo(ForumProblem.SESSION_EXPIRED)
        assertThat(state.feed.items).isEmpty()
        assertThat(state.message).isNull()
    }

    @Test
    fun `offline first load shows a retry state instead of an empty forum`() =
        runTest(dispatcher) {
            val backend = FakeBackend(posts = listOf(post("p1", "u-bia")))
            backend.feedError = java.io.IOException("offline")
            val viewModel = ForumViewModel(backend, backend)
            viewModel.bind(profile)
            advanceUntilIdle()

            assertThat(viewModel.state.value.feed.loaded).isFalse()
            assertThat(viewModel.state.value.feed.problem).isEqualTo(ForumProblem.NETWORK)

            backend.feedError = null
            viewModel.refresh()
            advanceUntilIdle()
            assertThat(viewModel.state.value.feed.items).hasSize(1)
            assertThat(viewModel.state.value.feed.problem).isNull()
        }

    @Test
    fun `a like shows at once and rolls back when the server fails`() = runTest(dispatcher) {
        val original = post("p1", "u-bia", likeCount = 3)
        val backend = FakeBackend(posts = listOf(original))
        val viewModel = ForumViewModel(backend, backend)
        viewModel.bind(profile)
        advanceUntilIdle()

        backend.likeError = ForumApiException(ForumErrorCode.NETWORK)
        viewModel.toggleLike(original)
        val optimistic = viewModel.state.value.feed.items.single()
        assertThat(optimistic.likedByMe).isTrue()
        assertThat(optimistic.likeCount).isEqualTo(4)

        advanceUntilIdle()
        assertThat(viewModel.state.value.feed.items.single()).isEqualTo(original)
        val message = viewModel.state.value.message as ForumMessage.Failure
        assertThat(message.problem).isEqualTo(ForumProblem.NETWORK)

        viewModel.consumeMessage(message.id)
        assertThat(viewModel.state.value.message).isNull()
    }

    @Test
    fun `load more appends the next page without repeating posts`() = runTest(dispatcher) {
        val backend = FakeBackend(posts = (1..5).map { index -> post("p$index", "u-bia") }, pageSize = 2)
        val viewModel = ForumViewModel(backend, backend)
        viewModel.bind(profile)
        advanceUntilIdle()
        assertThat(viewModel.state.value.feedCursor).isEqualTo("2")

        viewModel.loadMore()
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.feed.items.map(ForumPost::id))
            .containsExactly("p1", "p2", "p3", "p4", "p5").inOrder()
        assertThat(state.feedCursor).isNull()
        assertThat(state.loadingMore).isFalse()
    }

    @Test
    fun `publishing puts the new post on top and clears the draft`() = runTest(dispatcher) {
        val backend = FakeBackend(posts = listOf(post("p1", "u-bia")))
        val viewModel = ForumViewModel(backend, backend)
        viewModel.bind(profile)
        advanceUntilIdle()

        var cleared = false
        viewModel.publish("Olá") { cleared = true }
        assertThat(viewModel.state.value.publishing).isTrue()
        advanceUntilIdle()

        assertThat(cleared).isTrue()
        assertThat(viewModel.state.value.publishing).isFalse()
        assertThat(viewModel.state.value.feed.items.map(ForumPost::body))
            .containsExactly("Olá", "corpo p1").inOrder()
    }

    @Test
    fun `a post that vanished on the server leaves the list`() = runTest(dispatcher) {
        val gone = post("p1", "u-bia")
        val backend = FakeBackend(posts = listOf(gone, post("p2", "u-bia")))
        val viewModel = ForumViewModel(backend, backend)
        viewModel.bind(profile)
        advanceUntilIdle()

        backend.commentsError = ForumApiException(ForumErrorCode.NOT_FOUND)
        assertThat(viewModel.loadComments(gone.id)).isNull()

        assertThat(viewModel.state.value.feed.items.map(ForumPost::id)).containsExactly("p2")
    }

    @Test
    fun `local forum loads with the profile identity and no account`() = runTest(dispatcher) {
        val backend = FakeBackend(posts = listOf(post("p1", "local-ana")), shared = false)
        val viewModel = ForumViewModel(backend, accounts = null)

        viewModel.bind(profile)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.access).isEqualTo(ForumAccess.LOCAL)
        assertThat(state.currentUserId).isEqualTo("local-ana")
        assertThat(state.feed.items).hasSize(1)
    }

    private fun post(id: String, authorId: String, likeCount: Int = 0) = ForumPost(
        id = id,
        authorName = "Bia",
        avatarId = 1,
        body = "corpo $id",
        createdAtMillis = 1_000L,
        likeCount = likeCount,
        authorId = authorId
    )

    private class FakeBackend(
        var session: ForumSession? = ForumSession("token", "u-ana", Long.MAX_VALUE),
        posts: List<ForumPost> = emptyList(),
        private val pageSize: Int = 20,
        private val shared: Boolean = true
    ) : ForumRepository, ForumAccountGateway {
        private val posts = posts.toMutableList()
        var feedRequests = 0
        var feedError: Exception? = null
        var likeError: Exception? = null
        var commentsError: Exception? = null
        var signInError: Exception? = null
        var signInAuthor: ForumAuthor? = null

        override val isShared: Boolean
            get() = shared

        override suspend fun currentSession(): ForumSession? = session

        override suspend fun signIn(idToken: String, profile: ForumAuthor): ForumSession {
            signInError?.let { throw it }
            signInAuthor = profile
            return ForumSession(idToken, "u-ana", Long.MAX_VALUE).also { session = it }
        }

        override suspend fun signOut() {
            session = null
        }

        override suspend fun loadPosts(currentUserId: String): List<ForumPost> =
            loadPostsPage(currentUserId, null).posts

        override suspend fun loadPostsPage(currentUserId: String, cursor: String?): ForumPostPage {
            feedRequests++
            feedError?.let { throw it }
            val start = cursor?.toInt() ?: 0
            val end = minOf(start + pageSize, posts.size)
            // Repeats the last post of the previous page, as a shifting feed would.
            val from = if (start > 0) start - 1 else 0
            return ForumPostPage(
                posts = posts.subList(from, end).toList(),
                nextCursor = end.takeIf { it < posts.size }?.toString()
            )
        }

        override suspend fun loadUserPosts(userId: String): List<ForumPost> =
            posts.filter { it.authorId == userId }

        override suspend fun publish(author: ForumAuthor, body: String, nowMillis: Long): ForumPost =
            ForumPost(
                id = "new",
                authorName = author.displayName,
                avatarId = author.avatarId,
                body = body,
                createdAtMillis = nowMillis,
                authorId = author.userId
            ).also { posts.add(0, it) }

        override suspend fun toggleLike(postId: String, actor: ForumAuthor, nowMillis: Long): ForumPost? {
            likeError?.let { throw it }
            return posts.firstOrNull { it.id == postId }
        }

        override suspend fun loadComments(postId: String): List<ForumComment> {
            commentsError?.let { throw it }
            return emptyList()
        }

        override suspend fun addComment(
            postId: String,
            author: ForumAuthor,
            body: String,
            nowMillis: Long
        ): ForumComment? = null

        override suspend fun deletePost(postId: String, actor: ForumAuthor): Boolean = true

        override suspend fun deleteComment(postId: String, commentId: String, actor: ForumAuthor) = true

        override suspend fun loadNotifications(userId: String): List<ForumNotification> = emptyList()

        override suspend fun markAllNotificationsRead(userId: String): List<ForumNotification> =
            emptyList()
    }
}
