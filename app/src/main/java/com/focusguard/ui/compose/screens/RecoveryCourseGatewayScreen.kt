package com.focusguard.ui.compose.screens

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.data.RecoveryJourney
import com.focusguard.data.RecoveryJourney.Stage

/**
 * Clean AntiPorn gateway inspired by the approved HTML mockup.
 *
 * The gateway deliberately keeps the complete value proposition, progress and CTA visible in
 * the available viewport without scrolling. The existing recovery/course flow remains untouched
 * and opens after the CTA.
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

    // Deliberately not rememberSaveable: leaving the AntiPorn tab and opening it again returns
    // to the presentation, matching the previous behavior.
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
                completedSteps = completedStages.size,
                totalSteps = RecoveryJourney.stages.size,
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
    completedSteps: Int,
    totalSteps: Int,
    onStart: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.background
    val onBackground = MaterialTheme.colorScheme.onBackground
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val lavender = Color(0xFFB9BEF8)
    val lavenderDim = Color(0xFF8E95D8)
    val cyanSoft = Color(0xFF93D6EA)
    val cyan = Color(0xFF4FC9DE)
    val coral = Color(0xFFF08A6E)
    val ink = Color(0xFF1B2140)

    var noteExpanded by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        cyanSoft.copy(alpha = 0.07f),
                        background,
                        lavender.copy(alpha = 0.06f)
                    )
                )
            )
    ) {
        val compact = maxHeight < 620.dp
        val veryCompact = maxHeight < 560.dp
        val horizontalPadding = if (compact) 16.dp else 20.dp
        val topPadding = if (compact) 8.dp else 10.dp
        val sectionGap = if (compact) 10.dp else 15.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    top = topPadding,
                    bottom = if (compact) 8.dp else 12.dp
                )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.padding(bottom = if (compact) 13.dp else 20.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF3FBF8F))
                )
                Text(
                    text = stringResource(R.string.recovery_course_badge),
                    color = muted,
                    fontSize = if (compact) 10.sp else 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    maxLines = 1
                )
            }

            Text(
                text = stringResource(R.string.recovery_course_title),
                color = onBackground,
                fontSize = if (compact) 23.sp else 27.sp,
                lineHeight = if (compact) 27.sp else 31.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.35).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(if (compact) 5.dp else 7.dp))

            Text(
                text = buildAnnotatedString {
                    append(stringResource(R.string.recovery_course_punch_prefix))
                    pushStyle(
                        SpanStyle(
                            color = coral,
                            textDecoration = TextDecoration.LineThrough
                        )
                    )
                    append(stringResource(R.string.recovery_course_punch_resist))
                    pop()
                    append(stringResource(R.string.recovery_course_punch_bridge))
                    pushStyle(
                        SpanStyle(
                            color = cyanSoft,
                            background = cyan.copy(alpha = 0.13f),
                            fontWeight = FontWeight.ExtraBold
                        )
                    )
                    append(stringResource(R.string.recovery_course_punch_goal))
                    pop()
                    append(stringResource(R.string.recovery_course_punch_end))
                },
                color = onBackground.copy(alpha = 0.94f),
                fontSize = if (compact) 16.sp else 19.sp,
                lineHeight = if (compact) 21.sp else 25.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.25).sp,
                maxLines = 2
            )

            Spacer(Modifier.height(if (compact) 7.dp else 11.dp))

            Text(
                text = stringResource(R.string.recovery_course_subtitle),
                color = muted,
                fontSize = if (compact) 11.5.sp else 14.sp,
                lineHeight = if (compact) 16.sp else 21.sp,
                maxLines = if (veryCompact) 3 else 4,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(sectionGap))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(2.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(lavender, cyanSoft, cyan)
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.recovery_course_not_willpower_title),
                        color = onBackground,
                        fontSize = if (compact) 12.5.sp else 15.sp,
                        lineHeight = if (compact) 16.sp else 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2
                    )
                    Spacer(Modifier.height(if (compact) 5.dp else 7.dp))
                    Text(
                        text = stringResource(R.string.recovery_course_not_willpower_body),
                        color = muted,
                        fontSize = if (compact) 10.5.sp else 13.5.sp,
                        lineHeight = if (compact) 14.5.sp else 19.sp,
                        maxLines = if (noteExpanded) Int.MAX_VALUE else if (compact) 2 else 3,
                        overflow = if (noteExpanded) TextOverflow.Clip else TextOverflow.Ellipsis
                    )
                    Surface(
                        onClick = { noteExpanded = !noteExpanded },
                        color = Color.Transparent,
                        shape = RoundedCornerShape(5.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Text(
                            text = stringResource(
                                if (noteExpanded) {
                                    R.string.recovery_course_read_less
                                } else {
                                    R.string.recovery_course_read_more
                                }
                            ),
                            color = lavender,
                            fontSize = if (compact) 11.sp else 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 5.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Surface(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 48.dp else 52.dp),
                shape = RoundedCornerShape(14.dp),
                color = Color.Transparent
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFFC9CBFC), lavender, Color(0xFFA2DAEC))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(
                            if (courseStarted) {
                                R.string.recovery_course_cta_continue
                            } else {
                                R.string.recovery_course_cta
                            }
                        ),
                        color = ink,
                        fontSize = if (compact) 14.sp else 15.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            Row(
                modifier = Modifier.padding(top = if (compact) 8.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text(
                    text = stringResource(
                        R.string.recovery_course_steps_progress,
                        completedSteps,
                        totalSteps
                    ),
                    color = muted.copy(alpha = 0.78f),
                    fontSize = if (compact) 10.5.sp else 12.sp
                )
                Box(
                    modifier = Modifier
                        .size(3.dp)
                        .clip(CircleShape)
                        .background(muted.copy(alpha = 0.35f))
                )
                Text(
                    text = stringResource(R.string.recovery_course_no_account),
                    color = muted.copy(alpha = 0.78f),
                    fontSize = if (compact) 10.5.sp else 12.sp
                )
            }

            Text(
                text = stringResource(R.string.recovery_course_footer),
                color = muted.copy(alpha = 0.62f),
                fontSize = if (compact) 9.sp else 10.5.sp,
                lineHeight = if (compact) 12.sp else 15.sp,
                maxLines = if (veryCompact) 2 else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = if (compact) 7.dp else 10.dp)
            )
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

private const val RECOVERY_COURSE_PREFS = "recovery_preferences"
private const val RECOVERY_COURSE_STARTED = "recovery_course_started"
private const val CREATOR_INSTRUCTIONS_STARTED = "creator_instructions_started"
private const val EASYPEASY_STARTED = "easypeasy_started"
