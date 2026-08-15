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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WarningAmber
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.data.RecoveryJourney
import com.focusguard.data.RecoveryJourney.Stage
import kotlin.math.roundToInt

/**
 * Marketing/education gateway shown before the existing AntiPorn recovery hub.
 *
 * The gateway intentionally starts on the presentation every time it is composed.
 * Course-started state and journey progress are persistent, so returning to AntiPorn
 * shows the same presentation with a Continue CTA and the real progress bar.
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

    Box(
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = primary.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, primary.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.School,
                        contentDescription = null,
                        tint = primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.recovery_course_badge),
                        color = primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = surfaceVariant),
                border = BorderStroke(1.dp, primary.copy(alpha = 0.32f))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.recovery_course_progress_title),
                            modifier = Modifier.weight(1f),
                            color = onBackground,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(
                                R.string.recovery_course_progress_value,
                                progressPercent
                            ),
                            color = primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp),
                        color = primary,
                        trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                        strokeCap = StrokeCap.Round
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Text(
                text = stringResource(R.string.recovery_course_title),
                color = onBackground,
                fontSize = 27.sp,
                lineHeight = 31.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = stringResource(R.string.recovery_course_hero),
                color = primary,
                fontSize = 17.sp,
                lineHeight = 23.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.recovery_course_subtitle),
                color = onSurfaceVariant,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(20.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = surface),
                border = BorderStroke(1.dp, primary.copy(alpha = 0.38f))
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Psychology,
                            contentDescription = null,
                            tint = primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            text = stringResource(R.string.recovery_course_not_willpower_title),
                            color = onBackground,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = stringResource(R.string.recovery_course_not_willpower_body),
                        color = onSurfaceVariant,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )

                    CoursePoint(R.string.recovery_course_point_reward)
                    CoursePoint(R.string.recovery_course_point_triggers)
                    CoursePoint(R.string.recovery_course_point_before_after)
                    CoursePoint(R.string.recovery_course_point_rewire)
                    CoursePoint(R.string.recovery_course_point_relapse)
                    CoursePoint(R.string.recovery_course_point_protection)
                }
            }

            Spacer(Modifier.height(14.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(Modifier.padding(17.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.size(9.dp))
                        Text(
                            text = stringResource(R.string.recovery_course_no_cliches_title),
                            color = onBackground,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.recovery_course_no_cliches_body),
                        color = onSurfaceVariant,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = primary.copy(alpha = 0.10f)),
                border = BorderStroke(1.dp, primary.copy(alpha = 0.35f))
            ) {
                Column(Modifier.padding(17.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            tint = primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.size(9.dp))
                        Text(
                            text = stringResource(R.string.recovery_course_science_title),
                            color = onBackground,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.recovery_course_science_body),
                        color = onSurfaceVariant,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.recovery_course_bridge),
                modifier = Modifier.fillMaxWidth(),
                color = onBackground,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
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
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(Modifier.size(9.dp))
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.recovery_course_footer),
                color = onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun CoursePoint(textRes: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(18.dp)
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = stringResource(textRes),
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )
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
