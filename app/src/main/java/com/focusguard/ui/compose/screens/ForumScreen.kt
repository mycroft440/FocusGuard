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
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.focusguard.data.ForumPost
import com.focusguard.data.ForumPostPolicy
import com.focusguard.data.ForumPostStore
import com.focusguard.data.UserProfile
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.CardBorder
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkCard
import com.focusguard.ui.compose.theme.FocusCard
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary

@Composable
fun ForumScreen(
    profile: UserProfile,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val store = remember(context) { ForumPostStore(context) }
    var posts by remember { mutableStateOf(store.load()) }
    var composerExpanded by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }

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
        item(key = "forum_composer") {
            ForumComposer(
                profile = profile,
                expanded = composerExpanded,
                draft = draft,
                onExpand = { composerExpanded = true },
                onDraftChange = { draft = ForumPostPolicy.limitBodyInput(it) },
                onPublish = {
                    val published = store.publish(
                        authorName = profile.displayName,
                        avatarId = profile.avatarId,
                        body = draft
                    )
                    if (published != null) {
                        posts = store.load()
                        draft = ""
                        composerExpanded = false
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

        if (posts.isEmpty()) {
            item(key = "forum_empty") {
                ForumEmptyState()
            }
        } else {
            items(
                items = posts,
                key = ForumPost::id
            ) { post ->
                ForumPostCard(
                    post = post,
                    profile = profile,
                    onToggleLike = {
                        if (store.toggleLike(post.id) != null) {
                            posts = store.load()
                        }
                    },
                    onAddComment = { commentBody ->
                        val added = store.addComment(
                            postId = post.id,
                            authorName = profile.displayName,
                            avatarId = profile.avatarId,
                            body = commentBody
                        )
                        if (added != null) {
                            posts = store.load()
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
    onToggleLike: () -> Unit,
    onAddComment: (String) -> Boolean
) {
    var commentsExpanded by rememberSaveable(post.id) { mutableStateOf(false) }
    var commentDraft by rememberSaveable(post.id) { mutableStateOf("") }
    val authorLabel = if (post.authorName.isBlank()) {
        stringResource(R.string.forum_member)
    } else {
        post.authorName
    }
    val relativeTime = forumRelativeTime(post.createdAtMillis)

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
                    onClick = { commentsExpanded = !commentsExpanded },
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
                    if (post.comments.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = post.comments.size.toString(),
                            color = if (commentsExpanded) AccentCyan else TextHint,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            if (commentsExpanded) {
                HorizontalDivider(color = CardBorder.copy(alpha = 0.70f))
                Spacer(modifier = Modifier.height(12.dp))

                if (post.comments.isEmpty()) {
                    Text(
                        text = stringResource(R.string.forum_no_comments),
                        color = TextHint,
                        fontSize = 12.sp
                    )
                } else {
                    post.comments.forEachIndexed { index, comment ->
                        ForumCommentItem(comment = comment)
                        if (index != post.comments.lastIndex) {
                            Spacer(modifier = Modifier.height(10.dp))
                            HorizontalDivider(color = CardBorder.copy(alpha = 0.45f))
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }
                }

                if (post.comments.isNotEmpty()) {
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
                        if (onAddComment(commentDraft)) {
                            commentDraft = ""
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
