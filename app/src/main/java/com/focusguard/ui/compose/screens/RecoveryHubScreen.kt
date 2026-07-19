package com.focusguard.ui.compose.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R

@Composable
fun RecoveryHubScreen(onReadBook: (RecoveryBook) -> Unit) {
    var selectedBook by rememberSaveable { mutableStateOf<RecoveryBook?>(null) }

    BackHandler(enabled = selectedBook != null) {
        selectedBook = null
    }

    AnimatedContent(
        targetState = selectedBook,
        transitionSpec = {
            fadeIn(animationSpec = tween(180)) togetherWith
                fadeOut(animationSpec = tween(180))
        },
        label = "RecoveryContent"
    ) { book ->
        if (book != null) {
            RecoveryBookDetails(
                book = book,
                onBack = { selectedBook = null },
                onReadBook = {
                    onReadBook(book)
                }
            )
        } else {
            RecoveryLanding(onOpenBook = { selectedBook = it })
        }
    }
}

@Composable
private fun RecoveryLanding(onOpenBook: (RecoveryBook) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(76.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.VisibilityOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.recovery_title),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.recovery_subtitle),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(16.dp))
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.recovery_privacy_badge),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(Modifier.height(32.dp))
        Text(
            text = stringResource(R.string.recovery_resources_title),
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))

        RecoveryBookCard(
            book = RecoveryBook.CREATOR_INSTRUCTIONS,
            onClick = { onOpenBook(RecoveryBook.CREATOR_INSTRUCTIONS) }
        )
        Spacer(Modifier.height(12.dp))
        RecoveryBookCard(
            book = RecoveryBook.EASYPEASY,
            onClick = { onOpenBook(RecoveryBook.EASYPEASY) }
        )
    }
}

@Composable
private fun RecoveryBookCard(
    book: RecoveryBook,
    onClick: () -> Unit
) {
    val content = book.content

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        brush = Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)
                            )
                        ),
                        shape = RoundedCornerShape(17.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    content.cardIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(content.titleRes),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    stringResource(content.cardSubtitleRes),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = stringResource(R.string.recovery_open_details),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RecoveryBookDetails(
    book: RecoveryBook,
    onBack: () -> Unit,
    onReadBook: () -> Unit
) {
    val content = book.content
    val context = LocalContext.current
    val preferences = context.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE)
    var hasStartedReading by rememberSaveable {
        mutableStateOf(preferences.getBoolean(content.startedPreferenceKey, false))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = stringResource(R.string.recovery_book_back),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.recovery_title),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(18.dp))
        Surface(
            modifier = Modifier.size(72.dp),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    content.detailIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(38.dp)
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(content.titleRes),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(content.descriptionRes),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 15.sp,
            lineHeight = 23.sp
        )

        Spacer(Modifier.height(24.dp))
        content.features.forEach { (icon, textRes) ->
            FeatureRow(icon, textRes)
        }

        content.attributionRes?.let { attributionRes ->
            Spacer(Modifier.height(20.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = stringResource(attributionRes),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                preferences.edit().putBoolean(content.startedPreferenceKey, true).apply()
                hasStartedReading = true
                onReadBook()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Icon(Icons.Outlined.MenuBook, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(
                    if (hasStartedReading) R.string.recovery_continue_reading
                    else R.string.recovery_start_reading
                ),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.recovery_educational_notice),
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, textRes: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.size(38.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(textRes),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp
        )
    }
}

enum class RecoveryBook {
    CREATOR_INSTRUCTIONS,
    EASYPEASY
}

private data class RecoveryBookContent(
    val titleRes: Int,
    val cardSubtitleRes: Int,
    val descriptionRes: Int,
    val cardIcon: ImageVector,
    val detailIcon: ImageVector,
    val features: List<Pair<ImageVector, Int>>,
    val attributionRes: Int?,
    val startedPreferenceKey: String
)

private val RecoveryBook.content: RecoveryBookContent
    get() = when (this) {
        RecoveryBook.CREATOR_INSTRUCTIONS -> RecoveryBookContent(
            titleRes = R.string.recovery_creator_ebook_title,
            cardSubtitleRes = R.string.recovery_creator_ebook_card_subtitle,
            descriptionRes = R.string.recovery_creator_ebook_description,
            cardIcon = Icons.Outlined.Bookmark,
            detailIcon = Icons.Outlined.Bookmark,
            features = listOf(
                Icons.Outlined.MenuBook to R.string.recovery_creator_content_feature,
                Icons.Outlined.CloudOff to R.string.recovery_creator_offline_feature,
                Icons.Outlined.Bookmark to R.string.recovery_progress_feature
            ),
            attributionRes = R.string.recovery_creator_ebook_note,
            startedPreferenceKey = CREATOR_INSTRUCTIONS_STARTED
        )
        RecoveryBook.EASYPEASY -> RecoveryBookContent(
            titleRes = R.string.recovery_ebook_title,
            cardSubtitleRes = R.string.recovery_ebook_card_subtitle,
            descriptionRes = R.string.recovery_ebook_description,
            cardIcon = Icons.Outlined.MenuBook,
            detailIcon = Icons.Outlined.MenuBook,
            features = listOf(
                Icons.Outlined.MenuBook to R.string.recovery_offline_feature,
                Icons.Outlined.Search to R.string.recovery_search_feature,
                Icons.Outlined.Bookmark to R.string.recovery_progress_feature
            ),
            attributionRes = R.string.recovery_ebook_attribution,
            startedPreferenceKey = EASYPEASY_STARTED
        )
    }

private const val RECOVERY_PREFS = "recovery_preferences"
private const val CREATOR_INSTRUCTIONS_STARTED = "creator_instructions_started"
private const val EASYPEASY_STARTED = "easypeasy_started"
