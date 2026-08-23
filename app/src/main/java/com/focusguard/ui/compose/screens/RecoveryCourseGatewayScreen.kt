package com.focusguard.ui.compose.screens

import android.content.Context
import android.content.SharedPreferences
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.data.RecoveryJourney
import com.focusguard.data.RecoveryJourney.Stage
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * Marketing/education gateway shown before the existing AntiPorn recovery hub.
 *
 * The presentation is intentionally fixed to the available AntiPorn viewport: it does not
 * scroll, so the course message, progress and CTA remain visible together. The actual course
 * content still opens normally after the CTA.
 */
@Composable
fun RecoveryCourseGatewayScreen(
    recoveryContent: @Composable () -> Unit
) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences(RECOVERY_COURSE_PREFS, Context.MODE_PRIVATE)
    }
    val initialCompleted = remember(preferences) {
        preferences.readRecoveryCompletedStages()
    }

    // Deliberately not rememberSaveable: leaving the AntiPorn tab and opening it
    // again must always return to the presentation screen.
    var enteredCourse by remember { mutableStateOf(false) }
    var completedStages by remember { mutableStateOf(initialCompleted) }
    var courseStarted by remember {
        mutableStateOf(preferences.hasRecoveryCourseStarted(initialCompleted))
    }

    LaunchedEffect(enteredCourse) {
        if (!enteredCourse) {
            val refreshed = preferences.readRecoveryCompletedStages()
            completedStages = refreshed
            courseStarted = preferences.hasRecoveryCourseStarted(refreshed)
        }
    }

    BackHandler(enabled = enteredCourse) {
        enteredCourse = false
    }

    AnimatedContent(
        targetState = enteredCourse,
        transitionSpec = {
            fadeIn(animationSpec = tween(180)) togetherWith
                fadeOut(animationSpec = tween(180))
        },
        label = "AntiPornCourseGateway"
    ) { entered ->
        if (entered) {
            recoveryContent()
        } else {
            RecoveryCourseIntroScreen(
                courseStarted = courseStarted,
                progress = RecoveryJourney.progress(completedStages),
                onStart = {
                    if (!courseStarted) {
                        preferences.edit()
                            .putBoolean(RECOVERY_COURSE_STARTED, true)
                            .apply()
                        courseStarted = true
                    }
                    enteredCourse = true
                }
            )
        }
    }
}

@Composable
private fun RecoveryCourseIntroScreen(
    courseStarted: Boolean,
    progress: Float,
    onStart: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onBackground = MaterialTheme.colorScheme.onBackground
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val progressPercent = (progress.coerceIn(0f, 1f) * 100f).roundToInt()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        primary.copy(alpha = 0.08f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
    ) {
        val compact = maxHeight < 620.dp
        val veryCompact = maxHeight < 560.dp
        val horizontalPadding = if (compact) 14.dp else 18.dp
        val verticalPadding = if (compact) 9.dp else 13.dp
        val sectionGap = if (compact) 7.dp else 10.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = primary.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, primary.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = if (compact) 11.dp else 14.dp,
                        vertical = if (compact) 5.dp else 7.dp
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Icon(
                        Icons.Default.School,
                        contentDescription = null,
                        tint = primary,
                        modifier = Modifier.size(if (compact) 15.dp else 18.dp)
                    )
                    Text(
                        text = stringResource(R.string.recovery_course_badge),
                        color = primary,
                        fontSize = if (compact) 10.sp else 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            Spacer(Modifier.height(sectionGap))
            RotatingCourseSlogan(compact = compact)
            Spacer(Modifier.height(sectionGap))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(if (compact) 14.dp else 16.dp),
                colors = CardDefaults.cardColors(containerColor = surfaceVariant),
                border = BorderStroke(1.dp, primary.copy(alpha = 0.32f))
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = if (compact) 12.dp else 15.dp,
                        vertical = if (compact) 9.dp else 12.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.recovery_course_progress_title),
                            modifier = Modifier.weight(1f),
                            color = onBackground,
                            fontSize = if (compact) 11.sp else 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(
                                R.string.recovery_course_progress_value,
                                progressPercent
                            ),
                            color = primary,
                            fontSize = if (compact) 10.sp else 12.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (compact) 6.dp else 7.dp),
                        color = primary,
                        trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                        strokeCap = StrokeCap.Round
                    )
                    if (!veryCompact) {
                        Text(
                            text = stringResource(R.string.recovery_course_progress_encouragement),
                            modifier = Modifier.fillMaxWidth(),
                            color = onSurfaceVariant,
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(Modifier.height(sectionGap))

            Text(
                text = stringResource(R.string.recovery_course_title),
                color = onBackground,
                fontSize = if (compact) 18.sp else 22.sp,
                lineHeight = if (compact) 21.sp else 25.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(if (compact) 4.dp else 6.dp))

            Text(
                text = stringResource(R.string.recovery_course_hero),
                color = primary,
                fontSize = if (compact) 12.sp else 14.sp,
                lineHeight = if (compact) 16.sp else 19.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                maxLines = if (compact) 2 else 3,
                overflow = TextOverflow.Ellipsis
            )

            if (!veryCompact) {
                Spacer(Modifier.height(5.dp))
                Text(
                    text = stringResource(R.string.recovery_course_subtitle),
                    color = onSurfaceVariant,
                    fontSize = if (compact) 10.sp else 12.sp,
                    lineHeight = if (compact) 14.sp else 17.sp,
                    textAlign = TextAlign.Center,
                    maxLines = if (compact) 2 else 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(sectionGap))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = true),
                shape = RoundedCornerShape(if (compact) 16.dp else 20.dp),
                colors = CardDefaults.cardColors(containerColor = surface),
                border = BorderStroke(1.dp, primary.copy(alpha = 0.38f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(if (compact) 12.dp else 16.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Psychology,
                            contentDescription = null,
                            tint = primary,
                            modifier = Modifier.size(if (compact) 19.dp else 23.dp)
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = stringResource(R.string.recovery_course_not_willpower_title),
                            color = onBackground,
                            fontSize = if (compact) 12.sp else 15.sp,
                            lineHeight = if (compact) 15.sp else 19.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(if (compact) 5.dp else 8.dp))
                    Text(
                        text = stringResource(R.string.recovery_course_not_willpower_body),
                        color = onSurfaceVariant,
                        fontSize = if (compact) 10.sp else 12.sp,
                        lineHeight = if (compact) 14.sp else 17.sp,
                        maxLines = if (compact) 4 else 5,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(sectionGap))

            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 48.dp else 52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = primary)
            ) {
                Text(
                    text = stringResource(
                        if (courseStarted) {
                            R.string.recovery_course_cta_continue
                        } else {
                            R.string.recovery_course_cta
                        }
                    ),
                    fontSize = if (compact) 12.sp else 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1
                )
                Spacer(Modifier.size(7.dp))
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(if (compact) 17.dp else 19.dp)
                )
            }

            if (!compact) {
                Spacer(Modifier.height(5.dp))
                Text(
                    text = stringResource(R.string.recovery_course_footer),
                    color = onSurfaceVariant,
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun RotatingCourseSlogan(compact: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    val slogans = listOf(
        R.string.recovery_course_slogan_instant_easy,
        R.string.recovery_course_slogan_understand_trap,
        R.string.recovery_course_slogan_without_only_willpower,
        R.string.recovery_course_slogan_private_offline
    )
    var activeIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(ROTATING_SLOGAN_VISIBLE_MILLIS)
            activeIndex = (activeIndex + 1) % slogans.size
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 50.dp else 58.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = primary.copy(alpha = 0.14f)),
        border = BorderStroke(1.dp, primary.copy(alpha = 0.48f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 13.dp, vertical = if (compact) 6.dp else 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AnimatedContent(
                targetState = activeIndex,
                transitionSpec = {
                    fadeIn(
                        animationSpec = tween(
                            durationMillis = ROTATING_SLOGAN_ENTER_MILLIS,
                            delayMillis = ROTATING_SLOGAN_ENTER_DELAY_MILLIS
                        )
                    ) togetherWith fadeOut(
                        animationSpec = tween(ROTATING_SLOGAN_EXIT_MILLIS)
                    )
                },
                label = "RecoveryCourseRotatingSlogan"
            ) { index ->
                Text(
                    text = stringResource(slogans[index]),
                    modifier = Modifier.fillMaxWidth(),
                    color = primary,
                    fontSize = if (compact) 11.sp else 13.sp,
                    lineHeight = if (compact) 14.sp else 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(3.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                slogans.indices.forEach { index ->
                    Box(
                        modifier = Modifier
                            .size(if (index == activeIndex) 6.dp else 5.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == activeIndex) {
                                    primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
                                }
                            )
                    )
                }
            }
        }
    }
}

private fun SharedPreferences.readRecoveryCompletedStages(): Set<Stage> =
    Stage.entries.filterTo(linkedSetOf()) { stage ->
        getBoolean("stage_${stage.name.lowercase()}_done", false)
    }

private fun SharedPreferences.hasRecoveryCourseStarted(completed: Set<Stage>): Boolean =
    getBoolean(RECOVERY_COURSE_STARTED, false) ||
        completed.isNotEmpty() ||
        getBoolean(CREATOR_INSTRUCTIONS_STARTED, false) ||
        getBoolean(EASYPEASY_STARTED, false)

private const val ROTATING_SLOGAN_VISIBLE_MILLIS = 4_200L
private const val ROTATING_SLOGAN_EXIT_MILLIS = 650
private const val ROTATING_SLOGAN_ENTER_MILLIS = 160
private const val ROTATING_SLOGAN_ENTER_DELAY_MILLIS = 260
private const val RECOVERY_COURSE_PREFS = "recovery_preferences"
private const val RECOVERY_COURSE_STARTED = "recovery_course_started"
private const val CREATOR_INSTRUCTIONS_STARTED = "creator_instructions_started"
private const val EASYPEASY_STARTED = "easypeasy_started"
