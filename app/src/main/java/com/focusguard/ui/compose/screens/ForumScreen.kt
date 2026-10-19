package com.focusguard.ui.compose.screens

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.focusguard.R
import com.focusguard.data.ForumBackendConfig
import com.focusguard.data.ForumComment
import com.focusguard.data.ForumGoogleSignIn
import com.focusguard.data.ForumGoogleSignInResult
import com.focusguard.data.ForumNotification
import com.focusguard.data.ForumNotificationType
import com.focusguard.data.ForumPost
import com.focusguard.data.ForumPostPolicy
import com.focusguard.data.UserProfile
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.CardBorder
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkCard
import com.focusguard.ui.compose.theme.DarkSurface
import com.focusguard.ui.compose.theme.FocusCard
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.utils.findActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ForumScreen(
    profile: UserProfile,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val factory = remember(context) { ForumViewModel.factory(context) }
    val viewModel: ForumViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()

    LaunchedEffect(viewModel, profile) {
        viewModel.bind(profile)
    }

    when (state.access) {
        ForumAccess.CHECKING -> Box(modifier = modifier.fillMaxSize()) {
            ForumLoadingState()
        }

        ForumAccess.SIGNED_OUT,
        ForumAccess.SIGNING_IN -> ForumSignIn(
            state = state,
            viewModel = viewModel,
            modifier = modifier
        )

        ForumAccess.LOCAL,
        ForumAccess.SIGNED_IN -> ForumContent(
            state = state,
            profile = profile,
            viewModel = viewModel,
            modifier = modifier
        )
    }
}

@Composable
private fun ForumContent(
    state: ForumUiState,
    profile: UserProfile,
    viewModel: ForumViewModel,
    modifier: Modifier
) {
    var composerExpanded by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var postPendingDelete by remember { mutableStateOf<ForumPost?>(null) }

    val section = state.section
    val visiblePosts = if (section == ForumSection.MY_POSTS) state.myPosts else state.feed
    val sectionLoading = when (section) {
        ForumSection.NOTIFICATIONS -> state.notifications.loading
        ForumSection.FEED,
        ForumSection.MY_POSTS -> visiblePosts.loading
    }

    postPendingDelete?.let { post ->
        ForumDeleteDialog(
            title = stringResource(R.string.forum_delete_post_title),
            onConfirm = {
                postPendingDelete = null
                viewModel.deletePost(post)
            },
            onDismiss = { postPendingDelete = null }
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 12.dp,
            bottom = 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "forum_social_header") {
            ForumSocialHeader(
                profile = profile,
                section = section,
                unreadNotificationCount = state.unreadNotificationCount,
                shared = state.shared,
                refreshing = sectionLoading,
                onFeedClick = { viewModel.selectSection(ForumSection.FEED) },
                onNotificationsClick = { viewModel.selectSection(ForumSection.NOTIFICATIONS) },
                onMyPostsClick = { viewModel.selectSection(ForumSection.MY_POSTS) },
                onRefresh = viewModel::refresh,
                onSignOut = viewModel::signOut
            )
        }

        state.message?.let { message ->
            item(key = "forum_message") {
                ForumMessageBanner(
                    message = message,
                    onDismiss = { viewModel.consumeMessage(message.id) }
                )
            }
        }

        if (section == ForumSection.FEED) {
            item(key = "forum_composer") {
                ForumComposer(
                    profile = profile,
                    expanded = composerExpanded,
                    draft = draft,
                    publishing = state.publishing,
                    onExpand = { composerExpanded = true },
                    onDraftChange = { draft = ForumPostPolicy.limitBodyInput(it) },
                    onPublish = {
                        viewModel.publish(draft) {
                            draft = ""
                            composerExpanded = false
                        }
                    }
                )
            }

            item(key = "forum_storage_notice") {
                Text(
                    text = stringResource(
                        if (state.shared) {
                            R.string.forum_remote_storage_notice
                        } else {
                            R.string.forum_local_storage_notice
                        }
                    ),
                    color = TextHint,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }

        if (section == ForumSection.MY_POSTS) {
            item(key = "forum_my_posts_title") {
                Text(
                    text = stringResource(R.string.forum_my_posts),
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }

        when (section) {
            ForumSection.NOTIFICATIONS -> {
                val notifications = state.notifications
                when {
                    !notifications.loaded && notifications.problem != null -> {
                        item(key = "forum_notifications_problem") {
                            ForumProblemState(
                                problem = notifications.problem,
                                onRetry = viewModel::refresh
                            )
                        }
                    }

                    !notifications.loaded -> {
                        item(key = "forum_notifications_loading") {
                            ForumLoadingState()
                        }
                    }

                    notifications.items.isEmpty() -> {
                        item(key = "forum_notifications_empty") {
                            ForumNotificationsEmptyState()
                        }
                    }

                    else -> {
                        items(
                            items = notifications.items,
                            key = ForumNotification::id
                        ) { notification ->
                            ForumNotificationItem(notification = notification)
                        }
                    }
                }
            }

            ForumSection.FEED,
            ForumSection.MY_POSTS -> {
                when {
                    !visiblePosts.loaded && visiblePosts.problem != null -> {
                        item(key = "forum_posts_problem") {
                            ForumProblemState(
                                problem = visiblePosts.problem,
                                onRetry = viewModel::refresh
                            )
                        }
                    }

                    !visiblePosts.loaded -> {
                        item(key = "forum_posts_loading") {
                            ForumLoadingState()
                        }
                    }

                    visiblePosts.items.isEmpty() -> {
                        item(key = "forum_empty") {
                            if (section == ForumSection.MY_POSTS) {
                                ForumMyPostsEmptyState()
                            } else {
                                ForumEmptyState()
                            }
                        }
                    }

                    else -> {
                        items(
                            items = visiblePosts.items,
                            key = ForumPost::id
                        ) { post ->
                            val currentUserId = state.currentUserId
                            ForumPostCard(
                                post = post,
                                profile = profile,
                                isOwnPost = post.authorId.isNotBlank() &&
                                    post.authorId == currentUserId,
                                canReport = state.shared,
                                isOwnComment = { comment ->
                                    // Comentários locais antigos não têm autor e são de quem usa o app.
                                    comment.authorId == currentUserId ||
                                        (!state.shared && comment.authorId.isBlank())
                                },
                                onLoadComments = { viewModel.loadComments(post.id) },
                                onToggleLike = { viewModel.toggleLike(post) },
                                onAddComment = { body -> viewModel.addComment(post.id, body) },
                                onDeleteComment = { comment ->
                                    viewModel.deleteComment(post.id, comment)
                                },
                                onReportComment = { comment ->
                                    viewModel.reportComment(post.id, comment)
                                },
                                onDeletePost = { postPendingDelete = post },
                                onReportPost = { viewModel.reportPost(post) }
                            )
                        }

                        if (section == ForumSection.FEED && state.feedCursor != null) {
                            item(key = "forum_load_more") {
                                ForumLoadMore(
                                    loading = state.loadingMore,
                                    onClick = viewModel::loadMore
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ForumSignIn(
    state: ForumUiState,
    viewModel: ForumViewModel,
    modifier: Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val webClientId = remember { ForumBackendConfig.fromBuildConfig().googleWebClientId }
    val signingIn = state.access == ForumAccess.SIGNING_IN

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        item(key = "forum_sign_in") {
            FocusCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Forum,
                        contentDescription = null,
                        tint = AccentCyan,
                        modifier = Modifier.size(34.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.forum_sign_in_title),
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.forum_sign_in_description),
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        textAlign = TextAlign.Center
                    )

                    state.signInProblem?.let { problem ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(forumProblemText(problem)),
                            color = DangerRed,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val activity = context.findActivity()
                            if (activity == null) {
                                viewModel.onSignInFinished(ForumGoogleSignInResult.Failed)
                            } else {
                                viewModel.onSignInStarted()
                                scope.launch {
                                    var result: ForumGoogleSignInResult =
                                        ForumGoogleSignInResult.Cancelled
                                    try {
                                        result = ForumGoogleSignIn.requestIdToken(
                                            activity,
                                            webClientId
                                        )
                                    } finally {
                                        // Também roda se a aba for trocada no meio do login,
                                        // para o botão não ficar preso em "Entrando…".
                                        viewModel.onSignInFinished(result)
                                    }
                                }
                            }
                        },
                        enabled = !signingIn,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (signingIn) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = TextSecondary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = stringResource(
                                if (signingIn) {
                                    R.string.forum_signing_in
                                } else {
                                    R.string.forum_sign_in_button
                                }
                            ),
                            color = if (signingIn) TextSecondary else DarkBg,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.forum_sign_in_terms),
                        color = TextHint,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun ForumSocialHeader(
    profile: UserProfile,
    section: ForumSection,
    unreadNotificationCount: Int,
    shared: Boolean,
    refreshing: Boolean,
    onFeedClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onMyPostsClick: () -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val profileName = if (profile.displayName.isBlank()) {
        stringResource(R.string.forum_member)
    } else {
        profile.displayName
    }

    FocusCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProfileAvatar(
                    avatarId = profile.avatarId,
                    modifier = Modifier.size(42.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profileName,
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.profile_title),
                        color = TextHint,
                        fontSize = 11.sp
                    )
                }
                IconButton(
                    onClick = onRefresh,
                    enabled = !refreshing
                ) {
                    if (refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = AccentCyan,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.forum_refresh),
                            tint = TextSecondary
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Menu,
                            contentDescription = stringResource(R.string.profile_title),
                            tint = TextSecondary
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(text = stringResource(R.string.forum_my_posts))
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.Article,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onMyPostsClick()
                            }
                        )
                        if (shared) {
                            DropdownMenuItem(
                                text = {
                                    Text(text = stringResource(R.string.forum_sign_out))
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.Logout,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onSignOut()
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TextButton(
                    onClick = onFeedClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = stringResource(R.string.nav_forum),
                        color = if (section == ForumSection.FEED) AccentCyan else TextSecondary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                TextButton(
                    onClick = onNotificationsClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Notifications,
                        contentDescription = null,
                        tint = if (section == ForumSection.NOTIFICATIONS) {
                            AccentCyan
                        } else {
                            TextSecondary
                        },
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.forum_notifications),
                        color = if (section == ForumSection.NOTIFICATIONS) {
                            AccentCyan
                        } else {
                            TextSecondary
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                    if (unreadNotificationCount > 0) {
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = unreadNotificationCount.toString(),
                            color = AccentCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ForumMessageBanner(
    message: ForumMessage,
    onDismiss: () -> Unit
) {
    LaunchedEffect(message.id) {
        delay(MESSAGE_VISIBLE_MILLIS)
        onDismiss()
    }
    val isProblem = message is ForumMessage.Failure
    val text = when (message) {
        is ForumMessage.Failure -> stringResource(forumProblemText(message.problem))
        is ForumMessage.ReportSent -> stringResource(R.string.forum_report_sent)
    }

    FocusCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            1.dp,
            if (isProblem) DangerRed.copy(alpha = 0.55f) else AccentCyan.copy(alpha = 0.45f)
        ),
        onClick = onDismiss
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isProblem) Icons.Outlined.CloudOff else Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = if (isProblem) DangerRed else AccentCyan,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = text,
                color = TextPrimary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun ForumProblemState(
    problem: ForumProblem,
    onRetry: () -> Unit
) {
    FocusCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = AccentCyan,
                modifier = Modifier.size(30.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(forumProblemText(problem)),
                color = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onRetry) {
                Text(
                    text = stringResource(R.string.forum_retry),
                    color = AccentCyan,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun ForumLoadMore(
    loading: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = AccentCyan,
                strokeWidth = 2.dp
            )
        } else {
            TextButton(onClick = onClick) {
                Text(
                    text = stringResource(R.string.forum_load_more),
                    color = AccentCyan,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun ForumItemMenu(
    canDelete: Boolean,
    canReport: Boolean,
    onDelete: () -> Unit,
    onReport: () -> Unit
) {
    if (!canDelete && !canReport) return
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.forum_more_options),
                tint = TextHint,
                modifier = Modifier.size(18.dp)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            if (canDelete) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.forum_delete)) },
                    leadingIcon = {
                        Icon(imageVector = Icons.Outlined.DeleteOutline, contentDescription = null)
                    },
                    onClick = {
                        expanded = false
                        onDelete()
                    }
                )
            }
            if (canReport) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.forum_report)) },
                    leadingIcon = {
                        Icon(imageVector = Icons.Outlined.Flag, contentDescription = null)
                    },
                    onClick = {
                        expanded = false
                        onReport()
                    }
                )
            }
        }
    }
}

@Composable
private fun ForumDeleteDialog(
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = TextPrimary) },
        text = {
            Text(
                text = stringResource(R.string.forum_delete_message),
                color = TextSecondary,
                fontSize = 14.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
            ) {
                Text(stringResource(R.string.forum_delete), color = TextPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = TextSecondary)
            }
        },
        containerColor = DarkSurface,
        shape = RoundedCornerShape(24.dp)
    )
}

@StringRes
private fun forumProblemText(problem: ForumProblem): Int = when (problem) {
    ForumProblem.NETWORK -> R.string.forum_error_network
    ForumProblem.RATE_LIMITED -> R.string.forum_error_rate_limited
    ForumProblem.BANNED -> R.string.forum_error_banned
    ForumProblem.NOT_FOUND -> R.string.forum_error_not_found
    ForumProblem.SESSION_EXPIRED -> R.string.forum_error_session_expired
    ForumProblem.SIGN_IN_FAILED -> R.string.forum_error_sign_in
    ForumProblem.NO_GOOGLE_ACCOUNT -> R.string.forum_error_no_google_account
    ForumProblem.GENERIC -> R.string.forum_error_generic
}

private const val MESSAGE_VISIBLE_MILLIS = 4_000L

@Composable
private fun ForumLoadingState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            color = AccentCyan,
            strokeWidth = 2.dp
        )
    }
}

@Composable
private fun ForumNotificationItem(notification: ForumNotification) {
    val actorName = if (notification.actorName.isBlank()) {
        stringResource(R.string.forum_member)
    } else {
        notification.actorName
    }
    val message = when (notification.type) {
        ForumNotificationType.LIKE ->
            stringResource(R.string.forum_notification_like, actorName)
        ForumNotificationType.COMMENT ->
            stringResource(R.string.forum_notification_comment, actorName)
    }

    FocusCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            1.dp,
            if (notification.isRead) CardBorder else AccentCyan.copy(alpha = 0.45f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProfileAvatar(
                avatarId = notification.actorAvatarId,
                modifier = Modifier.size(38.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = if (notification.isRead) {
                        FontWeight.Normal
                    } else {
                        FontWeight.SemiBold
                    }
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = forumRelativeTime(notification.createdAtMillis),
                    color = TextHint,
                    fontSize = 10.sp
                )
            }
            Icon(
                imageVector = if (notification.type == ForumNotificationType.LIKE) {
                    Icons.Filled.Favorite
                } else {
                    Icons.Outlined.ChatBubbleOutline
                },
                contentDescription = null,
                tint = AccentCyan,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ForumNotificationsEmptyState() {
    ForumSectionEmptyState(
        title = stringResource(R.string.forum_notifications_empty_title),
        description = stringResource(R.string.forum_notifications_empty_description),
        icon = Icons.Outlined.Notifications
    )
}

@Composable
private fun ForumMyPostsEmptyState() {
    ForumSectionEmptyState(
        title = stringResource(R.string.forum_empty_title),
        description = stringResource(R.string.forum_my_posts_empty_description),
        icon = Icons.AutoMirrored.Outlined.Article
    )
}

@Composable
private fun ForumSectionEmptyState(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    FocusCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AccentCyan,
                modifier = Modifier.size(30.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun ForumComposer(
    profile: UserProfile,
    expanded: Boolean,
    draft: String,
    publishing: Boolean,
    onExpand: () -> Unit,
    onDraftChange: (String) -> Unit,
    onPublish: () -> Unit
) {
    val authorLabel = if (profile.displayName.isBlank()) {
        stringResource(R.string.forum_member)
    } else {
        profile.displayName
    }

    if (!expanded) {
        FocusCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, CardBorder),
            onClick = onExpand
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProfileAvatar(
                    avatarId = profile.avatarId,
                    modifier = Modifier.size(38.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.forum_composer_hint),
                    color = TextHint,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Outlined.EditNote,
                    contentDescription = null,
                    tint = AccentCyan,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        return
    }

    FocusCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProfileAvatar(
                    avatarId = profile.avatarId,
                    modifier = Modifier.size(38.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = authorLabel,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        text = stringResource(R.string.forum_composer_expanded_hint),
                        color = TextHint
                    )
                },
                minLines = 4,
                maxLines = 8,
                colors = forumTextFieldColors(),
                shape = RoundedCornerShape(14.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.forum_character_count,
                        ForumPostPolicy.bodyCodePointCount(draft),
                        ForumPostPolicy.MAX_BODY_CODE_POINTS
                    ),
                    color = TextHint,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = onPublish,
                    enabled = !publishing &&
                        ForumPostPolicy.normalizeForPublish(draft).isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.forum_publish),
                        color = DarkBg,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun ForumPostCard(
    post: ForumPost,
    profile: UserProfile,
    isOwnPost: Boolean,
    canReport: Boolean,
    isOwnComment: (ForumComment) -> Boolean,
    onLoadComments: suspend () -> List<ForumComment>?,
    onToggleLike: () -> Unit,
    onAddComment: suspend (String) -> ForumComment?,
    onDeleteComment: suspend (ForumComment) -> Boolean,
    onReportComment: (ForumComment) -> Unit,
    onDeletePost: () -> Unit,
    onReportPost: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var commentsExpanded by rememberSaveable(post.id) { mutableStateOf(false) }
    var commentDraft by rememberSaveable(post.id) { mutableStateOf("") }
    var comments by remember(post.id) { mutableStateOf(emptyList<ForumComment>()) }
    var commentsLoaded by remember(post.id) { mutableStateOf(false) }
    var commentsLoading by remember(post.id) { mutableStateOf(false) }
    var commentsFailed by remember(post.id) { mutableStateOf(false) }
    var commentPublishing by remember(post.id) { mutableStateOf(false) }
    var commentPendingDelete by remember(post.id) { mutableStateOf<ForumComment?>(null) }

    val authorLabel = if (post.authorName.isBlank()) {
        stringResource(R.string.forum_member)
    } else {
        post.authorName
    }
    val relativeTime = forumRelativeTime(post.createdAtMillis)

    fun loadComments() {
        if (commentsLoading) return
        commentsLoading = true
        commentsFailed = false
        scope.launch {
            try {
                val loaded = onLoadComments()
                if (loaded != null) {
                    comments = loaded
                    commentsLoaded = true
                } else {
                    commentsFailed = true
                }
            } finally {
                commentsLoading = false
            }
        }
    }

    commentPendingDelete?.let { comment ->
        ForumDeleteDialog(
            title = stringResource(R.string.forum_delete_comment_title),
            onConfirm = {
                commentPendingDelete = null
                scope.launch {
                    if (onDeleteComment(comment)) {
                        comments = comments.filterNot { candidate -> candidate.id == comment.id }
                    }
                }
            },
            onDismiss = { commentPendingDelete = null }
        )
    }

    FocusCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(15.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProfileAvatar(
                    avatarId = post.avatarId,
                    modifier = Modifier.size(38.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = authorLabel,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = relativeTime,
                        color = TextHint,
                        fontSize = 11.sp
                    )
                }
                ForumItemMenu(
                    canDelete = isOwnPost,
                    canReport = canReport && !isOwnPost,
                    onDelete = onDeletePost,
                    onReport = onReportPost
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = post.body,
                color = TextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = CardBorder.copy(alpha = 0.70f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onToggleLike,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = if (post.likedByMe) {
                            Icons.Filled.Favorite
                        } else {
                            Icons.Outlined.FavoriteBorder
                        },
                        contentDescription = null,
                        tint = if (post.likedByMe) AccentCyan else TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(
                            if (post.likedByMe) R.string.forum_liked else R.string.forum_like
                        ),
                        color = if (post.likedByMe) AccentCyan else TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (post.likeCount > 0) {
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = post.likeCount.toString(),
                            color = if (post.likedByMe) AccentCyan else TextHint,
                            fontSize = 12.sp
                        )
                    }
                }

                TextButton(
                    onClick = {
                        val nextExpanded = !commentsExpanded
                        commentsExpanded = nextExpanded
                        if (nextExpanded && !commentsLoaded) {
                            loadComments()
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ChatBubbleOutline,
                        contentDescription = null,
                        tint = if (commentsExpanded) AccentCyan else TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.forum_comment),
                        color = if (commentsExpanded) AccentCyan else TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = post.commentsCount.toString(),
                        color = if (commentsExpanded) AccentCyan else TextHint,
                        fontSize = 12.sp
                    )
                }
            }

            if (commentsExpanded) {
                HorizontalDivider(color = CardBorder.copy(alpha = 0.70f))
                Spacer(modifier = Modifier.height(12.dp))

                when {
                    commentsLoading && !commentsLoaded -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = AccentCyan,
                                strokeWidth = 2.dp
                            )
                        }
                    }

                    commentsFailed && !commentsLoaded -> {
                        TextButton(onClick = { loadComments() }) {
                            Text(
                                text = stringResource(R.string.forum_retry),
                                color = AccentCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    comments.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.forum_no_comments),
                            color = TextHint,
                            fontSize = 12.sp
                        )
                    }

                    else -> {
                        comments.forEachIndexed { index, comment ->
                            val ownComment = isOwnComment(comment)
                            ForumCommentItem(
                                comment = comment,
                                canDelete = ownComment,
                                canReport = canReport && !ownComment,
                                onDelete = { commentPendingDelete = comment },
                                onReport = { onReportComment(comment) }
                            )
                            if (index != comments.lastIndex) {
                                Spacer(modifier = Modifier.height(10.dp))
                                HorizontalDivider(color = CardBorder.copy(alpha = 0.45f))
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                        }
                    }
                }

                if (comments.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(14.dp))
                } else {
                    Spacer(modifier = Modifier.height(10.dp))
                }

                ForumCommentComposer(
                    profile = profile,
                    draft = commentDraft,
                    publishing = commentPublishing,
                    onDraftChange = {
                        commentDraft = ForumPostPolicy.limitCommentInput(it)
                    },
                    onPublish = {
                        if (!commentPublishing) {
                            commentPublishing = true
                            scope.launch {
                                try {
                                    val added = onAddComment(commentDraft)
                                    if (added != null) {
                                        commentDraft = ""
                                        if (commentsLoaded) {
                                            comments = comments + added
                                        } else {
                                            loadComments()
                                        }
                                    }
                                } finally {
                                    commentPublishing = false
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ForumCommentItem(
    comment: ForumComment,
    canDelete: Boolean,
    canReport: Boolean,
    onDelete: () -> Unit,
    onReport: () -> Unit
) {
    val authorLabel = if (comment.authorName.isBlank()) {
        stringResource(R.string.forum_member)
    } else {
        comment.authorName
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        ProfileAvatar(
            avatarId = comment.avatarId,
            modifier = Modifier.size(30.dp)
        )
        Spacer(modifier = Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = authorLabel,
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = forumRelativeTime(comment.createdAtMillis),
                    color = TextHint,
                    fontSize = 10.sp
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = comment.body,
                color = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
        ForumItemMenu(
            canDelete = canDelete,
            canReport = canReport,
            onDelete = onDelete,
            onReport = onReport
        )
    }
}

@Composable
private fun ForumCommentComposer(
    profile: UserProfile,
    draft: String,
    publishing: Boolean,
    onDraftChange: (String) -> Unit,
    onPublish: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        ProfileAvatar(
            avatarId = profile.avatarId,
            modifier = Modifier
                .padding(top = 6.dp)
                .size(30.dp)
        )
        Spacer(modifier = Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        text = stringResource(R.string.forum_comment_hint),
                        color = TextHint,
                        fontSize = 12.sp
                    )
                },
                minLines = 2,
                maxLines = 5,
                colors = forumTextFieldColors(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(7.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.forum_character_count,
                        ForumPostPolicy.commentCodePointCount(draft),
                        ForumPostPolicy.MAX_COMMENT_CODE_POINTS
                    ),
                    color = TextHint,
                    fontSize = 10.sp
                )
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = onPublish,
                    enabled = !publishing &&
                        ForumPostPolicy.normalizeCommentForPublish(draft).isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.forum_comment_publish),
                        color = DarkBg,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun forumTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentCyan,
    unfocusedBorderColor = CardBorder,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = AccentCyan,
    focusedContainerColor = DarkCard,
    unfocusedContainerColor = DarkCard
)

private fun forumRelativeTime(createdAtMillis: Long): String =
    DateUtils.getRelativeTimeSpanString(
        createdAtMillis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()

@Composable
private fun ForumEmptyState() {
    FocusCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier.size(44.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.ChatBubbleOutline,
                    contentDescription = null,
                    tint = AccentCyan,
                    modifier = Modifier.size(30.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.forum_empty_title),
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.forum_empty_description),
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}
