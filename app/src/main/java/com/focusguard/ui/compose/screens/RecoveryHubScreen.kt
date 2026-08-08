package com.focusguard.ui.compose.screens

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.focusguard.R
import com.focusguard.data.RecoveryJourney
import com.focusguard.data.RecoveryJourney.Stage
import com.focusguard.data.RecoveryJourney.Status
import com.focusguard.security.AuthManager

/**
 * @param onOpenProtection leva à tela onde o filtro de pornografia é ligado. A
 *   etapa do escudo é a única que o app consegue conferir sozinho, então ela
 *   manda o usuário até lá em vez de pedir que ele se declare protegido.
 */
@Composable
fun RecoveryHubScreen(
    authManager: AuthManager,
    onReadBook: (RecoveryBook) -> Unit,
    onOpenProtection: () -> Unit
) {
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
                onReadBook = { onReadBook(book) }
            )
        } else {
            RecoveryLanding(
                authManager = authManager,
                onOpenBook = { selectedBook = it },
                onOpenProtection = onOpenProtection
            )
        }
    }
}

@Composable
private fun RecoveryLanding(
    authManager: AuthManager,
    onOpenBook: (RecoveryBook) -> Unit,
    onOpenProtection: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences = remember(context) {
        context.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE)
    }
    // A etapa do escudo se conclui sozinha quando o filtro é ligado, inclusive
    // se isso acontecer em outra tela — daí reler no ON_RESUME em vez de só na
    // primeira composição.
    var completed by remember { mutableStateOf(preferences.readCompletedStages(authManager)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                completed = preferences.readCompletedStages(authManager)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        JourneyHeader(completed = completed)

        Spacer(Modifier.height(26.dp))

        RecoveryJourney.stages.forEachIndexed { index, stage ->
            StageRow(
                stage = stage,
                index = index,
                status = RecoveryJourney.statusOf(stage, completed),
                isLast = index == RecoveryJourney.stages.lastIndex,
                daysFree = RecoveryJourney.daysFree(
                    armedAtMillis = preferences.getLong(SHIELD_ARMED_AT, 0L),
                    nowMillis = System.currentTimeMillis()
                ),
                onAction = {
                    when (stage) {
                        Stage.UNDERSTAND -> onOpenBook(RecoveryBook.CREATOR_INSTRUCTIONS)
                        Stage.SHIELD -> onOpenProtection()
                        Stage.REWIRE -> onOpenBook(RecoveryBook.EASYPEASY)
                        Stage.MAINTAIN -> onOpenBook(RecoveryBook.EASYPEASY)
                    }
                },
                onMarkDone = {
                    preferences.edit().putBoolean(stage.doneKey, true).apply()
                    completed = preferences.readCompletedStages(authManager)
                }
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.recovery_educational_notice),
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun JourneyHeader(completed: Set<Stage>) {
    val done = RecoveryJourney.completedCount(completed)
    val total = RecoveryJourney.completableStages.size
    val progress by animateFloatAsState(
        targetValue = RecoveryJourney.progress(completed),
        animationSpec = tween(600),
        label = "JourneyProgress"
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(70.dp),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.VisibilityOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.recovery_title),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 27.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.recovery_journey_subtitle),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(18.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (RecoveryJourney.isJourneyComplete(completed)) {
                            stringResource(R.string.recovery_journey_all_done)
                        } else {
                            stringResource(R.string.recovery_journey_progress, done + 1, total)
                        },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "$done/$total",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(50)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                    strokeCap = StrokeCap.Round
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.CloudOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        stringResource(R.string.recovery_privacy_badge),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

/**
 * Uma etapa da trilha: o trilho à esquerda (bolinha + linha) e o cartão.
 *
 * A linha vive dentro da mesma Row do cartão, e não entre as Rows, para ela
 * atravessar o espaçamento sem quebrar — é o que faz a coluna parecer um
 * caminho contínuo em vez de quatro cartões soltos.
 */
@Composable
private fun StageRow(
    stage: Stage,
    index: Int,
    status: Status,
    isLast: Boolean,
    daysFree: Int?,
    onAction: () -> Unit,
    onMarkDone: () -> Unit
) {
    val content = stage.content
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(
            modifier = Modifier.fillMaxHeight().width(34.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StageBullet(index = index, status = status)
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .width(2.dp)
                        .background(
                            if (status == Status.DONE) {
                                accent.copy(alpha = 0.55f)
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            }
                        )
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            StageCard(
                content = content,
                status = status,
                daysFree = daysFree,
                isFinal = RecoveryJourney.isFinal(stage),
                accent = accent,
                muted = muted,
                onAction = onAction,
                onMarkDone = onMarkDone
            )
            Spacer(Modifier.height(if (isLast) 0.dp else 14.dp))
        }
    }
}

@Composable
private fun StageBullet(index: Int, status: Status) {
    val accent = MaterialTheme.colorScheme.primary
    val background = when (status) {
        Status.DONE -> accent
        Status.CURRENT -> accent.copy(alpha = 0.16f)
        Status.LOCKED -> MaterialTheme.colorScheme.surfaceVariant
    }
    val border = when (status) {
        Status.DONE -> accent
        Status.CURRENT -> accent
        Status.LOCKED -> MaterialTheme.colorScheme.outline
    }

    Surface(
        modifier = Modifier.size(34.dp),
        shape = CircleShape,
        color = background,
        border = BorderStroke(if (status == Status.CURRENT) 2.dp else 1.dp, border)
    ) {
        Box(contentAlignment = Alignment.Center) {
            when (status) {
                Status.DONE -> Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp)
                )

                Status.CURRENT -> Text(
                    text = "${index + 1}",
                    color = accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                Status.LOCKED -> Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

@Composable
private fun StageCard(
    content: RecoveryStageContent,
    status: Status,
    daysFree: Int?,
    isFinal: Boolean,
    accent: Color,
    muted: Color,
    onAction: () -> Unit,
    onMarkDone: () -> Unit
) {
    val locked = status == Status.LOCKED
    val current = status == Status.CURRENT

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                // Concluída ou em andamento continua clicável para revisitar; a
                // trancada não responde ao toque, senão a tranca seria enfeite.
                if (locked) Modifier else Modifier.clickable(onClick = onAction)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (current) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            }
        ),
        border = BorderStroke(
            if (current) 1.5.dp else 1.dp,
            if (current) accent.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            brush = Brush.linearGradient(
                                if (locked) {
                                    listOf(Color.Transparent, Color.Transparent)
                                } else {
                                    listOf(
                                        accent.copy(alpha = 0.22f),
                                        accent.copy(alpha = 0.08f)
                                    )
                                }
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        content.icon,
                        contentDescription = null,
                        tint = if (locked) muted else accent,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(content.titleRes),
                        color = if (locked) muted else MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = stringResource(
                            when (status) {
                                Status.DONE -> R.string.recovery_stage_done
                                Status.CURRENT -> R.string.recovery_stage_current
                                Status.LOCKED -> R.string.recovery_stage_locked
                            }
                        ),
                        color = if (status == Status.DONE) accent else muted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (!locked) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(content.descriptionRes),
                    color = muted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }

            if (isFinal && current) {
                Spacer(Modifier.height(14.dp))
                FreeDaysBadge(daysFree = daysFree, accent = accent)
            }

            if (current && !isFinal) {
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onAction,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(
                        stringResource(content.actionRes),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // O escudo se confere sozinho: o app sabe se o filtro está
                // ligado, então oferecer "já fiz" ali seria deixar a pessoa
                // destrancar a etapa seguinte sem ter feito nada.
                if (content.confirmationRes != null) {
                    TextButton(
                        onClick = onMarkDone,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(content.confirmationRes),
                            color = muted,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Verified,
                            contentDescription = null,
                            tint = muted,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.recovery_stage_auto_checked),
                            color = muted,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FreeDaysBadge(daysFree: Int?, accent: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = accent.copy(alpha = 0.12f)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "${daysFree ?: 0}",
                color = accent,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = pluralStringResource(R.plurals.recovery_free_days, daysFree ?: 0),
                color = accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
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
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
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

/**
 * Lê o progresso guardado e junta com o que dá para conferir de verdade.
 *
 * Duas etapas são declaradas pelo usuário — o app não tem como saber se um
 * capítulo foi lido — e a do escudo é conferida no próprio filtro. Registrar
 * quando ele foi armado dá a data-base da contagem de dias livres.
 */
private fun SharedPreferences.readCompletedStages(authManager: AuthManager): Set<Stage> {
    val shieldArmed = runCatching { authManager.isAdultFilterEnabled() }.getOrDefault(false)
    if (shieldArmed && getLong(SHIELD_ARMED_AT, 0L) <= 0L) {
        edit().putLong(SHIELD_ARMED_AT, System.currentTimeMillis()).apply()
    }

    return buildSet {
        if (getBoolean(Stage.UNDERSTAND.doneKey, false)) add(Stage.UNDERSTAND)
        if (shieldArmed) add(Stage.SHIELD)
        if (getBoolean(Stage.REWIRE.doneKey, false)) add(Stage.REWIRE)
    }
}

private val Stage.doneKey: String
    get() = "stage_${name.lowercase()}_done"

private data class RecoveryStageContent(
    val titleRes: Int,
    val descriptionRes: Int,
    val actionRes: Int,
    val icon: ImageVector,
    /** Null quando o app confere a etapa sozinho, em vez de o usuário declarar. */
    val confirmationRes: Int?
)

private val Stage.content: RecoveryStageContent
    get() = when (this) {
        Stage.UNDERSTAND -> RecoveryStageContent(
            titleRes = R.string.recovery_stage_understand_title,
            descriptionRes = R.string.recovery_stage_understand_desc,
            actionRes = R.string.recovery_stage_understand_action,
            icon = Icons.Outlined.Bookmark,
            confirmationRes = R.string.recovery_stage_understand_confirm
        )
        Stage.SHIELD -> RecoveryStageContent(
            titleRes = R.string.recovery_stage_shield_title,
            descriptionRes = R.string.recovery_stage_shield_desc,
            actionRes = R.string.recovery_stage_shield_action,
            icon = Icons.Outlined.Shield,
            confirmationRes = null
        )
        Stage.REWIRE -> RecoveryStageContent(
            titleRes = R.string.recovery_stage_rewire_title,
            descriptionRes = R.string.recovery_stage_rewire_desc,
            actionRes = R.string.recovery_stage_rewire_action,
            icon = Icons.Outlined.MenuBook,
            confirmationRes = R.string.recovery_stage_rewire_confirm
        )
        Stage.MAINTAIN -> RecoveryStageContent(
            titleRes = R.string.recovery_stage_maintain_title,
            descriptionRes = R.string.recovery_stage_maintain_desc,
            actionRes = R.string.recovery_stage_rewire_action,
            icon = Icons.Outlined.Verified,
            confirmationRes = null
        )
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
private const val SHIELD_ARMED_AT = "shield_armed_at"
