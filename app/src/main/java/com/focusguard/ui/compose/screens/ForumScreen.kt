package com.focusguard.ui.compose.screens

import android.text.format.DateUtils
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Notifications
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.data.ForumComment
import com.focusguard.data.ForumNotification
import com.focusguard.data.ForumNotificationType
import com.focusguard.data.ForumPost
import com.focusguard.data.ForumPostPolicy
import com.focusguard.data.ForumPostStore
import com.focusguard.data.ForumRepository
import com.focusguard.data.UserProfile
import com.focusguard.data.toForumAuthor
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.CardBorder
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkCard
import com.focusguard.ui.compose.theme.FocusCard
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import kotlinx.coroutines.launch

private enum class ForumSection {
    FEED,
    NOTIFICATIONS,
    MY_POSTS
}

@Composable
fun ForumScreen(
    profile: UserProfile,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository: ForumRepository = remember(context) { ForumPostStore(context) }
    val scope = rememberCoroutineScope()
    var sectionName by rememberSaveable { mutableStateOf(ForumSection.FEED.name) }
    val section = ForumSection.valueOf(sectionName)

    var posts by remember { mutableStateOf(emptyList<ForumPost>()) }
    var postsLoaded by remember { mutableStateOf(false) }
    var myPosts by remember { mutableStateOf(emptyList<ForumPost>()) }
    var myPostsLoaded by remember { mutableStateOf(false) }
    var notifications by remember { mutableStateOf(emptyList<ForumNotification>()) }
    var notificationsLoaded by remember { mutableStateOf(false) }

    var composerExpanded by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var publishInFlight by remember { mutableStateOf(false) }

    val unreadNotificationCount = notifications.count { notification -> !notification.isRead }
    val visiblePosts = if (section == ForumSection.MY_POSTS) myPosts else posts
    val visiblePostsLoaded = if (section == ForumSection.MY_POSTS) {
        myPostsLoaded
    } else {
        postsLoaded
    }

    LaunchedEffect(repository, profile.userId) {
        postsLoaded = false
        notificationsLoaded = false

        runCatching {
            repository.loadPosts(profile.userId)
        }.onSuccess {
            posts = it
        }
        postsLoaded = true

        runCatching {
            repository.loadNotifications(profile.userId)
        }.onSuccess {
            notifications = it
        }
        notificationsLoaded = true

        if (section == ForumSection.MY_POSTS) {
            myPostsLoaded = false
            runCatching {
                repository.loadUserPosts(profile.userId)
            }.onSuccess {
                myPosts = it
            }
            myPostsLoaded = true
        }
    }

    fun openFeed() {
        sectionName = ForumSection.FEED.name
    }

    fun openNotifications() {
        sectionName = ForumSection.NOTIFICATIONS.name
        notificationsLoaded = false
        scope.launch {
            try {
                val loaded = repository.loadNotifications(profile.userId)
                notifications = if (loaded.any { notification -> !notification.isRead }) {
                    repository.markAllNotificationsRead(profile.userId)
                } else {
                    loaded
                }
            } finally {
                notificationsLoaded = true
            }
        }
    }

    fun openMyPosts() {
        sectionName = ForumSection.MY_POSTS.name
        myPostsLoaded = false
        scope.launch {
            try {
                myPosts = repository.loadUserPosts(profile.userId)
            } finally {
                myPostsLoaded = true
            }
        }
    }

    fun refreshPostViews() {
        scope.launch {
            posts = repository.loadPosts(profile.userId)
            if (myPostsLoaded || section == ForumSection.MY_POSTS) {
                myPosts = repository.loadUserPosts(profile.userId)
                myPostsLoaded = true
            }
        }
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
                unreadNotificationCount = unreadNotificationCount,
                onFeedClick = ::openFeed,
                onNotificationsClick = ::openNotifications,
                onMyPostsClick = ::openMyPosts
            )
        }

        if (section == ForumSection.FEED) {
            item(key = "forum_composer") {
                ForumComposer(
                    profile = profile,
                    expanded = composerExpanded,
                    draft = draft,
                    onExpand = { composerExpanded = true },
                    onDraftChange = { draft = ForumPostPolicy.limitBodyInput(it) },
                    onPublish = {
                        if (!publishInFlight) {
                            publishInFlight = true
                            scope.launch {
                                try {
                                    val published = repository.publish(
                                        author = profile.toForumAuthor(),
                                        body = draft
                                    )
                                    if (published != null) {
                                        posts = repository.loadPosts(profile.userId)
                                        if (myPostsLoaded) {
                                            myPosts = repository.loadUserPosts(profile.userId)
                                        }
                                        draft = ""
                                        composerExpanded = false
                                    }
                                } finally {
                                    publishInFlight = false
                                }
                            }
                        }
                    }
                )
            }

            item(key = "forum_storage_notice") {
                Text(
                    text = stringResource(R.string.forum_local_storage_notice),
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
                when {
                    !notificationsLoaded -> {
                        item(key = "forum_notifications_loading") {
                            ForumLoadingState()
                        }
                    }

                    notifications.isEmpty() -> {
                        item(key = "forum_notifications_empty") {
                            ForumNotificationsEmptyState()
                        }
                    }

                    else -> {
                        items(
                            items = notifications,
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
                    !visiblePostsLoaded -> {
                        item(key = "forum_posts_loading") {
                            ForumLoadingState()
                        }
                    }

                    visiblePosts.isEmpty() -> {
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
                            items = visiblePosts,
                            key = ForumPost::id
                        ) { post ->
                            ForumPostCard(
                                post = post,
                                profile = profile,
                                onLoadComments = {
                                    repository.loadComments(post.id)
                                },
                                onToggleLike = {
                                    scope.launch {
                                        if (
                                            repository.toggleLike(
                                                postId = post.id,
                                                actor = profile.toForumAuthor()
                                            ) != null
                                        ) {
                                            refreshPostViews()
                                        }
                                    }
                                },
                                onAddComment = { commentBody ->
                                    val added = repository.addComment(
                                        postId = post.id,
                                        author = profile.toForumAuthor(),
                                        body = commentBody
                                    )
                                    if (added != null) {
                                        posts = repository.loadPosts(profile.userId)
                                        if (myPostsLoaded || section == ForumSection.MY_POSTS) {
                                            myPosts = repository.loadUserPosts(profile.userId)
                                            myPostsLoaded = true
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                }
                            )
                        }
                    }
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
    onFeedClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onMyPostsClick: () -> Unit
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
                                    imageVector = Icons.Outlined.Article,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onMyPostsClick()
                            }
                        )
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
        icon = Icons.Outlined.Article
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
                    enabled = ForumPostPolicy.normalizeForPublish(draft).isNotBlank(),
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
    onLoadComments: suspend () -> List<ForumComment>,
    onToggleLike: () -> Unit,
    onAddComment: suspend (String) -> Boolean
) {
    val scope = rememberCoroutineScope()
    var commentsExpanded by rememberSaveable(post.id) { mutableStateOf(false) }
    var commentDraft by rememberSaveable(post.id) { mutableStateOf("") }
    var comments by remember(post.id) { mutableStateOf(emptyList<ForumComment>()) }
    var commentsLoaded by remember(post.id) { mutableStateOf(false) }
    var commentsLoading by remember(post.id) { mutableStateOf(false) }
    var commentPublishing by remember(post.id) { mutableStateOf(false) }

    val authorLabel = if (post.authorName.isBlank()) {
        stringResource(R.string.forum_member)
    } else {
        post.authorName
    }
    val relativeTime = forumRelativeTime(post.createdAtMillis)

    fun loadComments() {
        if (commentsLoading) return
        commentsLoading = true
        scope.launch {
            try {
                comments = onLoadComments()
                commentsLoaded = true
            } finally {
                commentsLoading = false
            }
        }
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

                    comments.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.forum_no_comments),
                            color = TextHint,
                            fontSize = 12.sp
                        )
                    }

                    else -> {
                        comments.forEachIndexed { index, comment ->
                            ForumCommentItem(comment = comment)
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
                    onDraftChange = {
                        commentDraft = ForumPostPolicy.limitCommentInput(it)
                    },
                    onPublish = {
                        if (!commentPublishing) {
                            commentPublishing = true
                            scope.launch {
                                try {
                                    if (onAddComment(commentDraft)) {
                                        commentDraft = ""
                                        comments = onLoadComments()
                                        commentsLoaded = true
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
private fun ForumCommentItem(comment: ForumComment) {
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
    }
}

@Composable
private fun ForumCommentComposer(
    profile: UserProfile,
    draft: String,
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
                    enabled = ForumPostPolicy.normalizeCommentForPublish(draft).isNotBlank(),
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
