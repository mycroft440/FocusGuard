package com.focusguard.ui.compose.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.focusguard.data.ForumAccountGateway
import com.focusguard.data.ForumApiException
import com.focusguard.data.ForumAuthor
import com.focusguard.data.ForumComment
import com.focusguard.data.ForumErrorCode
import com.focusguard.data.ForumGoogleSignInResult
import com.focusguard.data.ForumNotification
import com.focusguard.data.ForumPost
import com.focusguard.data.ForumRepository
import com.focusguard.data.ForumRepositoryProvider
import com.focusguard.data.UserProfile
import com.focusguard.utils.FocusGuardLogger
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ForumSection {
    FEED,
    NOTIFICATIONS,
    MY_POSTS
}

enum class ForumAccess {
    /** Posts stay on this device; there is no account. */
    LOCAL,
    CHECKING,
    SIGNED_OUT,
    SIGNING_IN,
    SIGNED_IN
}

enum class ForumProblem {
    NETWORK,
    RATE_LIMITED,
    BANNED,
    NOT_FOUND,
    SESSION_EXPIRED,
    SIGN_IN_FAILED,
    NO_GOOGLE_ACCOUNT,
    GENERIC
}

data class ForumList<T>(
    val items: List<T> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val problem: ForumProblem? = null
)

sealed interface ForumMessage {
    val id: Long

    data class Failure(override val id: Long, val problem: ForumProblem) : ForumMessage

    data class ReportSent(override val id: Long) : ForumMessage
}

data class ForumUiState(
    val access: ForumAccess = ForumAccess.CHECKING,
    val shared: Boolean = false,
    val currentUserId: String = "",
    val section: ForumSection = ForumSection.FEED,
    val feed: ForumList<ForumPost> = ForumList(),
    val feedCursor: String? = null,
    val loadingMore: Boolean = false,
    val myPosts: ForumList<ForumPost> = ForumList(),
    val notifications: ForumList<ForumNotification> = ForumList(),
    val publishing: Boolean = false,
    val signInProblem: ForumProblem? = null,
    val message: ForumMessage? = null
) {
    val unreadNotificationCount: Int
        get() = notifications.items.count { notification -> !notification.isRead }

    val canUseForum: Boolean
        get() = access == ForumAccess.LOCAL || access == ForumAccess.SIGNED_IN
}

/**
 * Forum state shared by the tab, kept across tab switches. Every repository call goes
 * through [attempt], so a dropped connection or a server error becomes a message on
 * screen instead of an exception that would close the app.
 */
class ForumViewModel internal constructor(
    private val repository: ForumRepository,
    private val accounts: ForumAccountGateway?
) : ViewModel() {

    private val mutableState = MutableStateFlow(
        ForumUiState(
            access = if (accounts == null) ForumAccess.LOCAL else ForumAccess.CHECKING,
            shared = repository.isShared
        )
    )
    val state: StateFlow<ForumUiState> = mutableState.asStateFlow()

    private var profile = UserProfile()
    private var bound = false
    private var messageCounter = 0L
    private val pendingLikes = mutableSetOf<String>()
    private var feedJob: Job? = null
    private var myPostsJob: Job? = null
    private var notificationsJob: Job? = null

    /** Called on every composition with the current profile; loads only the first time. */
    fun bind(profile: UserProfile) {
        val previousUserId = this.profile.userId
        this.profile = profile

        if (accounts == null) {
            if (bound && previousUserId == profile.userId) return
            bound = true
            mutableState.update { state -> state.copy(currentUserId = profile.userId) }
            reloadAll()
            return
        }

        if (bound) return
        bound = true
        viewModelScope.launch {
            val session = try {
                accounts.currentSession()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                FocusGuardLogger.logError(TAG, "Forum session could not be read", error)
                null
            }
            if (session == null) {
                mutableState.update { state -> state.copy(access = ForumAccess.SIGNED_OUT) }
            } else {
                mutableState.update { state ->
                    state.copy(access = ForumAccess.SIGNED_IN, currentUserId = session.userId)
                }
                reloadAll()
            }
        }
    }

    fun selectSection(section: ForumSection) {
        mutableState.update { state -> state.copy(section = section) }
        when (section) {
            ForumSection.FEED -> {
                val feed = state.value.feed
                if (!feed.loaded && !feed.loading) loadFeed()
            }
            ForumSection.NOTIFICATIONS -> loadNotifications(markRead = true)
            ForumSection.MY_POSTS -> loadMyPosts()
        }
    }

    fun refresh() {
        when (state.value.section) {
            ForumSection.FEED -> {
                loadFeed()
                loadNotifications(markRead = false)
            }
            ForumSection.NOTIFICATIONS -> loadNotifications(markRead = true)
            ForumSection.MY_POSTS -> loadMyPosts()
        }
    }

    fun loadMore() {
        val current = state.value
        val cursor = current.feedCursor ?: return
        if (!current.canUseForum || current.loadingMore || current.feed.loading) return

        mutableState.update { state -> state.copy(loadingMore = true) }
        feedJob = viewModelScope.launch {
            when (val outcome = attempt { repository.loadPostsPage(currentUserId(), cursor) }) {
                is Attempt.Success -> mutableState.update { state ->
                    state.copy(
                        feed = state.feed.copy(
                            items = (state.feed.items + outcome.value.posts).distinctBy(ForumPost::id)
                        ),
                        feedCursor = outcome.value.nextCursor,
                        loadingMore = false
                    )
                }
                is Attempt.Failure -> {
                    mutableState.update { state -> state.copy(loadingMore = false) }
                    showProblem(outcome.problem)
                }
            }
        }
    }

    fun publish(body: String, onPublished: () -> Unit) {
        val current = state.value
        if (!current.canUseForum || current.publishing) return

        mutableState.update { state -> state.copy(publishing = true) }
        viewModelScope.launch {
            when (val outcome = attempt { repository.publish(author(), body) }) {
                is Attempt.Success -> {
                    val post = outcome.value
                    mutableState.update { state ->
                        if (post == null) {
                            state.copy(publishing = false)
                        } else {
                            state.copy(
                                publishing = false,
                                feed = state.feed.copy(items = listOf(post) + state.feed.items.without(post.id)),
                                myPosts = if (state.myPosts.loaded) {
                                    state.myPosts.copy(items = listOf(post) + state.myPosts.items.without(post.id))
                                } else {
                                    state.myPosts
                                }
                            )
                        }
                    }
                    if (post != null) onPublished()
                }
                is Attempt.Failure -> {
                    mutableState.update { state -> state.copy(publishing = false) }
                    showProblem(outcome.problem)
                }
            }
        }
    }

    /** Flips the heart at once and settles on the server's answer (or rolls back). */
    fun toggleLike(post: ForumPost) {
        if (!state.value.canUseForum || !pendingLikes.add(post.id)) return

        replacePost(
            post.copy(
                likedByMe = !post.likedByMe,
                likeCount = (post.likeCount + if (post.likedByMe) -1 else 1).coerceAtLeast(0)
            )
        )
        viewModelScope.launch {
            try {
                when (val outcome = attempt { repository.toggleLike(post.id, author()) }) {
                    is Attempt.Success -> replacePost(outcome.value ?: post)
                    is Attempt.Failure -> {
                        if (outcome.problem == ForumProblem.NOT_FOUND) removePost(post.id) else replacePost(post)
                        showProblem(outcome.problem)
                    }
                }
            } finally {
                pendingLikes.remove(post.id)
            }
        }
    }

    /** Null means the comments could not be loaded; the message is already on screen. */
    suspend fun loadComments(postId: String): List<ForumComment>? =
        when (val outcome = attempt { repository.loadComments(postId) }) {
            is Attempt.Success -> outcome.value
            is Attempt.Failure -> {
                if (outcome.problem == ForumProblem.NOT_FOUND) removePost(postId)
                showProblem(outcome.problem)
                null
            }
        }

    suspend fun addComment(postId: String, body: String): ForumComment? {
        if (!state.value.canUseForum) return null
        return when (val outcome = attempt { repository.addComment(postId, author(), body) }) {
            is Attempt.Success -> outcome.value?.also { adjustCommentsCount(postId, +1) }
            is Attempt.Failure -> {
                if (outcome.problem == ForumProblem.NOT_FOUND) removePost(postId)
                showProblem(outcome.problem)
                null
            }
        }
    }

    suspend fun deleteComment(postId: String, comment: ForumComment): Boolean =
        when (val outcome = attempt { repository.deleteComment(postId, comment.id, author()) }) {
            is Attempt.Success -> {
                if (outcome.value) adjustCommentsCount(postId, -1) else showProblem(ForumProblem.GENERIC)
                outcome.value
            }
            is Attempt.Failure -> {
                showProblem(outcome.problem)
                false
            }
        }

    fun deletePost(post: ForumPost) {
        viewModelScope.launch {
            when (val outcome = attempt { repository.deletePost(post.id, author()) }) {
                is Attempt.Success ->
                    if (outcome.value) removePost(post.id) else showProblem(ForumProblem.GENERIC)
                is Attempt.Failure -> showProblem(outcome.problem)
            }
        }
    }

    fun reportPost(post: ForumPost) {
        report { repository.reportPost(post.id, author()) }
    }

    fun reportComment(postId: String, comment: ForumComment) {
        report { repository.reportComment(postId, comment.id, author()) }
    }

    fun onSignInStarted() {
        mutableState.update { state ->
            state.copy(access = ForumAccess.SIGNING_IN, signInProblem = null)
        }
    }

    fun onSignInFinished(result: ForumGoogleSignInResult) {
        when (result) {
            is ForumGoogleSignInResult.Success -> completeSignIn(result.idToken)
            ForumGoogleSignInResult.Cancelled -> signedOut(problem = null)
            ForumGoogleSignInResult.NoGoogleAccount -> signedOut(ForumProblem.NO_GOOGLE_ACCOUNT)
            ForumGoogleSignInResult.Failed -> signedOut(ForumProblem.SIGN_IN_FAILED)
        }
    }

    fun signOut() {
        val gateway = accounts ?: return
        cancelLoads()
        viewModelScope.launch {
            attempt { gateway.signOut() }
            mutableState.value = ForumUiState(access = ForumAccess.SIGNED_OUT, shared = repository.isShared)
        }
    }

    fun consumeMessage(id: Long) {
        mutableState.update { state ->
            if (state.message?.id == id) state.copy(message = null) else state
        }
    }

    private fun completeSignIn(idToken: String) {
        val gateway = accounts ?: return
        viewModelScope.launch {
            when (val outcome = attempt { gateway.signIn(idToken, author()) }) {
                is Attempt.Success -> {
                    mutableState.update { state ->
                        state.copy(
                            access = ForumAccess.SIGNED_IN,
                            currentUserId = outcome.value.userId,
                            signInProblem = null
                        )
                    }
                    reloadAll()
                }
                is Attempt.Failure -> signedOut(
                    when (outcome.problem) {
                        ForumProblem.NETWORK, ForumProblem.RATE_LIMITED -> outcome.problem
                        else -> ForumProblem.SIGN_IN_FAILED
                    }
                )
            }
        }
    }

    private fun signedOut(problem: ForumProblem?) {
        mutableState.update { state ->
            state.copy(access = ForumAccess.SIGNED_OUT, signInProblem = problem)
        }
    }

    private fun reloadAll() {
        loadFeed()
        loadNotifications(markRead = state.value.section == ForumSection.NOTIFICATIONS)
        if (state.value.section == ForumSection.MY_POSTS) loadMyPosts()
    }

    private fun loadFeed() {
        if (!state.value.canUseForum) return
        feedJob?.cancel()
        mutableState.update { state ->
            state.copy(feed = state.feed.copy(loading = true, problem = null), loadingMore = false)
        }
        feedJob = viewModelScope.launch {
            when (val outcome = attempt { repository.loadPostsPage(currentUserId(), cursor = null) }) {
                is Attempt.Success -> mutableState.update { state ->
                    state.copy(
                        feed = ForumList(items = outcome.value.posts, loaded = true),
                        feedCursor = outcome.value.nextCursor
                    )
                }
                is Attempt.Failure -> {
                    mutableState.update { state ->
                        state.copy(feed = state.feed.copy(loading = false, problem = outcome.problem))
                    }
                    // With posts already on screen the error card is not shown, so say it here.
                    if (state.value.feed.loaded) showProblem(outcome.problem)
                }
            }
        }
    }

    private fun loadMyPosts() {
        if (!state.value.canUseForum) return
        myPostsJob?.cancel()
        mutableState.update { state ->
            state.copy(myPosts = state.myPosts.copy(loading = true, problem = null))
        }
        myPostsJob = viewModelScope.launch {
            when (val outcome = attempt { repository.loadUserPosts(currentUserId()) }) {
                is Attempt.Success -> mutableState.update { state ->
                    state.copy(myPosts = ForumList(items = outcome.value, loaded = true))
                }
                is Attempt.Failure -> {
                    mutableState.update { state ->
                        state.copy(myPosts = state.myPosts.copy(loading = false, problem = outcome.problem))
                    }
                    if (state.value.myPosts.loaded) showProblem(outcome.problem)
                }
            }
        }
    }

    private fun loadNotifications(markRead: Boolean) {
        if (!state.value.canUseForum) return
        notificationsJob?.cancel()
        mutableState.update { state ->
            state.copy(notifications = state.notifications.copy(loading = true, problem = null))
        }
        notificationsJob = viewModelScope.launch {
            val outcome = attempt {
                val userId = currentUserId()
                val loaded = repository.loadNotifications(userId)
                if (markRead && loaded.any { notification -> !notification.isRead }) {
                    repository.markAllNotificationsRead(userId)
                } else {
                    loaded
                }
            }
            when (outcome) {
                is Attempt.Success -> mutableState.update { state ->
                    state.copy(notifications = ForumList(items = outcome.value, loaded = true))
                }
                is Attempt.Failure -> {
                    mutableState.update { state ->
                        state.copy(
                            notifications = state.notifications.copy(
                                loading = false,
                                problem = outcome.problem
                            )
                        )
                    }
                    if (state.value.notifications.loaded && state.value.section == ForumSection.NOTIFICATIONS) {
                        showProblem(outcome.problem)
                    }
                }
            }
        }
    }

    private fun report(send: suspend () -> Boolean) {
        viewModelScope.launch {
            when (val outcome = attempt { send() }) {
                is Attempt.Success -> if (outcome.value) {
                    mutableState.update { state ->
                        state.copy(message = ForumMessage.ReportSent(++messageCounter))
                    }
                }
                is Attempt.Failure -> showProblem(outcome.problem)
            }
        }
    }

    private fun replacePost(post: ForumPost) {
        mutableState.update { state ->
            state.copy(
                feed = state.feed.copy(items = state.feed.items.replacing(post)),
                myPosts = state.myPosts.copy(items = state.myPosts.items.replacing(post))
            )
        }
    }

    private fun removePost(postId: String) {
        mutableState.update { state ->
            state.copy(
                feed = state.feed.copy(items = state.feed.items.without(postId)),
                myPosts = state.myPosts.copy(items = state.myPosts.items.without(postId))
            )
        }
    }

    private fun adjustCommentsCount(postId: String, delta: Int) {
        fun List<ForumPost>.adjusted() = map { post ->
            if (post.id == postId) {
                post.copy(commentsCount = (post.commentsCount + delta).coerceAtLeast(0))
            } else {
                post
            }
        }
        mutableState.update { state ->
            state.copy(
                feed = state.feed.copy(items = state.feed.items.adjusted()),
                myPosts = state.myPosts.copy(items = state.myPosts.items.adjusted())
            )
        }
    }

    private fun showProblem(problem: ForumProblem) {
        // A lost session already sends the person back to the sign-in card.
        if (problem == ForumProblem.SESSION_EXPIRED) return
        mutableState.update { state ->
            state.copy(message = ForumMessage.Failure(++messageCounter, problem))
        }
    }

    private fun onSessionLost() {
        cancelLoads()
        mutableState.value = ForumUiState(
            access = ForumAccess.SIGNED_OUT,
            shared = repository.isShared,
            section = state.value.section,
            signInProblem = ForumProblem.SESSION_EXPIRED
        )
    }

    private fun cancelLoads() {
        feedJob?.cancel()
        myPostsJob?.cancel()
        notificationsJob?.cancel()
    }

    private fun currentUserId(): String = state.value.currentUserId

    private fun author(): ForumAuthor = ForumAuthor(
        userId = currentUserId(),
        displayName = profile.displayName,
        avatarId = profile.avatarId
    )

    private suspend fun <T> attempt(block: suspend () -> T): Attempt<T> = try {
        Attempt.Success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: ForumApiException) {
        if (error.code == ForumErrorCode.UNAUTHORIZED && accounts != null) onSessionLost()
        Attempt.Failure(problemFor(error.code))
    } catch (error: IOException) {
        Attempt.Failure(ForumProblem.NETWORK)
    } catch (error: Exception) {
        // org.json.JSONException is checked in Java and would otherwise escape to the UI.
        FocusGuardLogger.logError(TAG, "Forum operation failed", error)
        Attempt.Failure(ForumProblem.GENERIC)
    }

    private sealed interface Attempt<out T> {
        data class Success<T>(val value: T) : Attempt<T>

        data class Failure(val problem: ForumProblem) : Attempt<Nothing>
    }

    companion object {
        private const val TAG = "ForumViewModel"

        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    val repository = ForumRepositoryProvider.get(appContext)
                    ForumViewModel(repository, repository as? ForumAccountGateway)
                }
            }
        }

        internal fun problemFor(code: ForumErrorCode): ForumProblem = when (code) {
            ForumErrorCode.NETWORK -> ForumProblem.NETWORK
            ForumErrorCode.RATE_LIMITED -> ForumProblem.RATE_LIMITED
            ForumErrorCode.BANNED -> ForumProblem.BANNED
            ForumErrorCode.NOT_FOUND -> ForumProblem.NOT_FOUND
            ForumErrorCode.UNAUTHORIZED -> ForumProblem.SESSION_EXPIRED
            ForumErrorCode.INVALID_TOKEN -> ForumProblem.SIGN_IN_FAILED
            ForumErrorCode.FORBIDDEN,
            ForumErrorCode.BUSY,
            ForumErrorCode.BAD_REQUEST,
            ForumErrorCode.NOT_CONFIGURED,
            ForumErrorCode.SERVER -> ForumProblem.GENERIC
        }
    }
}

private fun List<ForumPost>.replacing(post: ForumPost): List<ForumPost> =
    map { candidate -> if (candidate.id == post.id) post else candidate }

private fun List<ForumPost>.without(postId: String): List<ForumPost> =
    filterNot { candidate -> candidate.id == postId }
