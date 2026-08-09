package com.focusguard.ui.compose.screens

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.Toast
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.focusguard.R
import com.focusguard.data.RecoveryJourney
import com.focusguard.data.RecoveryJourney.Stage
import com.focusguard.data.RecoveryJourney.Status
import com.focusguard.data.RecoveryProtectionPreset
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.manager.BlockingSessionManager.BlockingProtectionUnavailableException
import com.focusguard.ui.PermissionsActivity
import com.focusguard.utils.FocusGuardLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
                onReadBook = { onReadBook(book) }
            )
        } else {
            RecoveryLanding(onOpenBook = { selectedBook = it })
        }
    }
}

@Composable
private fun RecoveryLanding(onOpenBook: (RecoveryBook) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val preferences = remember(context) {
        context.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE)
    }
    val sessionManager = remember(context) {
        BlockingSessionManager.getInstance(context)
    }

    // Relido no ON_RESUME porque a leitura acontece fora daqui, noutra Activity:
    // esta composição sobrevive à ida e à volta e não recarregaria sozinha.
    var completed by remember { mutableStateOf(preferences.readCompletedStages()) }
    var showProtectionTerms by rememberSaveable { mutableStateOf(false) }
    var showAccessibilityRequired by rememberSaveable { mutableStateOf(false) }
    var isActivatingProtection by remember { mutableStateOf(false) }
    var activationErrorRes by remember { mutableStateOf<Int?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                completed = preferences.readCompletedStages()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showProtectionTerms) {
        RecoveryProtectionTermsDialog(
            isActivating = isActivatingProtection,
            activationErrorRes = activationErrorRes,
            onDismiss = {
                if (!isActivatingProtection) {
                    showProtectionTerms = false
                    activationErrorRes = null
                }
            },
            onActivate = { typedConsent ->
                isActivatingProtection = true
                activationErrorRes = null
                scope.launch {
                    try {
                        sessionManager.startRecoveryProtectionPreset(typedConsent)
                        preferences.edit()
                            .putBoolean(Stage.PROTECT.doneKey, true)
                            .apply()
                        completed = preferences.readCompletedStages()
                        showProtectionTerms = false
                        Toast.makeText(
                            context,
                            R.string.recovery_protection_success,
                            Toast.LENGTH_LONG
                        ).show()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: BlockingProtectionUnavailableException) {
                        if (
                            error.reason ==
                            BlockingProtectionUnavailableException.Reason.ACCESSIBILITY_REQUIRED
                        ) {
                            showProtectionTerms = false
                            showAccessibilityRequired = true
                        } else {
                            activationErrorRes = R.string.recovery_protection_failed
                        }
                    } catch (error: Exception) {
                        FocusGuardLogger.logError(
                            "RecoveryHub",
                            "Falha ao ativar o atalho de proteção",
                            error
                        )
                        activationErrorRes = R.string.recovery_protection_failed
                    } finally {
                        isActivatingProtection = false
                    }
                }
            }
        )
    }

    if (showAccessibilityRequired) {
        AlertDialog(
            onDismissRequest = { showAccessibilityRequired = false },
            title = {
                Text(stringResource(R.string.recovery_protection_permission_title))
            },
            text = {
                Text(stringResource(R.string.recovery_protection_permission_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAccessibilityRequired = false
                        context.startActivity(
                            Intent(context, PermissionsActivity::class.java)
                        )
                    }
                ) {
                    Text(stringResource(R.string.recovery_protection_open_permissions))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAccessibilityRequired = false }) {
                    Text(stringResource(R.string.status_close))
                }
            }
        )
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
            val status = RecoveryJourney.statusOf(stage, completed)
            StageRow(
                stage = stage,
                index = index,
                status = status,
                isLast = index == RecoveryJourney.stages.lastIndex,
                onAction = {
                    when (stage) {
                        Stage.UNDERSTAND,
                        Stage.REWIRE -> stage.bookOrNull?.let(onOpenBook)

                        Stage.PROTECT -> if (status == Status.CURRENT) {
                            activationErrorRes = null
                            showProtectionTerms = true
                        }
                    }
                },
                onMarkDone = {
                    if (stage != Stage.PROTECT) {
                        preferences.edit().putBoolean(stage.doneKey, true).apply()
                        completed = preferences.readCompletedStages()
                    }
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
    val total = RecoveryJourney.stages.size
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
 * caminho contínuo em vez de três cartões soltos.
 */
@Composable
private fun StageRow(
    stage: Stage,
    index: Int,
    status: Status,
    isLast: Boolean,
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
    accent: Color,
    muted: Color,
    onAction: () -> Unit,
    onMarkDone: () -> Unit
) {
    val locked = status == Status.LOCKED
    val current = status == Status.CURRENT
    // Leituras concluídas continuam abertas para consulta. A proteção, depois
    // de ativada, vira apenas um registro concluído e não pode ser duplicada.
    val canOpen = !locked && (current || content.confirmationRes != null)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (canOpen) Modifier.clickable(onClick = onAction) else Modifier
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

            if (current) {
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

                // Só as leituras dependem da declaração manual de conclusão.
                // A proteção conclui a etapa apenas após os dois blocos existirem.
                content.confirmationRes?.let { confirmationRes ->
                    TextButton(onClick = onMarkDone, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(confirmationRes),
                            color = muted,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecoveryProtectionTermsDialog(
    isActivating: Boolean,
    activationErrorRes: Int?,
    onDismiss: () -> Unit,
    onActivate: (String) -> Unit
) {
    var typedConsent by rememberSaveable { mutableStateOf("") }
    var pasteBlocked by rememberSaveable { mutableStateOf(false) }
    val consentAccepted = RecoveryProtectionPreset.isConsentAccepted(typedConsent)

    AlertDialog(
        onDismissRequest = {
            if (!isActivating) onDismiss()
        },
        title = {
            Text(stringResource(R.string.recovery_protection_terms_title))
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.recovery_protection_terms_intro),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(12.dp))
                ProtectionTermParagraph(R.string.recovery_protection_terms_porn)
                ProtectionTermParagraph(R.string.recovery_protection_terms_social)
                ProtectionTermParagraph(R.string.recovery_protection_terms_messengers)
                ProtectionTermParagraph(R.string.recovery_protection_terms_duration)

                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.recovery_protection_consent_instruction),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
                Spacer(Modifier.height(10.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    )
                ) {
                    Text(
                        text = RecoveryProtectionPreset.CONSENT_PHRASE,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = typedConsent,
                    onValueChange = { nextValue ->
                        if (
                            RecoveryProtectionPreset.acceptsTypedEdit(
                                previous = typedConsent,
                                next = nextValue
                            )
                        ) {
                            typedConsent = nextValue
                            pasteBlocked = false
                        } else {
                            pasteBlocked = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isActivating,
                    label = {
                        Text(stringResource(R.string.recovery_protection_consent_label))
                    },
                    singleLine = true,
                    isError = pasteBlocked,
                    supportingText = {
                        if (pasteBlocked) {
                            Text(stringResource(R.string.recovery_protection_paste_blocked))
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Done
                    )
                )
                activationErrorRes?.let { errorRes ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(errorRes),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onActivate(typedConsent) },
                enabled = consentAccepted && !isActivating
            ) {
                if (isActivating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.recovery_protection_activating))
                } else {
                    Text(stringResource(R.string.recovery_protection_activate))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isActivating
            ) {
                Text(stringResource(R.string.status_close))
            }
        }
    )
}

@Composable
private fun ProtectionTermParagraph(textRes: Int) {
    Text(
        text = "• " + stringResource(textRes),
        modifier = Modifier.padding(bottom = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        lineHeight = 19.sp
    )
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

/** Etapas que o usuário já declarou concluídas. */
private fun SharedPreferences.readCompletedStages(): Set<Stage> =
    Stage.entries.filterTo(linkedSetOf()) { getBoolean(it.doneKey, false) }

private val Stage.doneKey: String
    get() = "stage_${name.lowercase()}_done"

private data class RecoveryStageContent(
    val titleRes: Int,
    val descriptionRes: Int,
    val actionRes: Int,
    val confirmationRes: Int?,
    val icon: ImageVector
)

private val Stage.content: RecoveryStageContent
    get() = when (this) {
        Stage.UNDERSTAND -> RecoveryStageContent(
            titleRes = R.string.recovery_stage_understand_title,
            descriptionRes = R.string.recovery_stage_understand_desc,
            actionRes = R.string.recovery_stage_understand_action,
            confirmationRes = R.string.recovery_stage_understand_confirm,
            icon = Icons.Outlined.Bookmark
        )
        Stage.REWIRE -> RecoveryStageContent(
            titleRes = R.string.recovery_stage_rewire_title,
            descriptionRes = R.string.recovery_stage_rewire_desc,
            actionRes = R.string.recovery_stage_rewire_action,
            confirmationRes = R.string.recovery_stage_rewire_confirm,
            icon = Icons.Outlined.MenuBook
        )
        Stage.PROTECT -> RecoveryStageContent(
            titleRes = R.string.recovery_stage_protect_title,
            descriptionRes = R.string.recovery_stage_protect_desc,
            actionRes = R.string.recovery_stage_protect_action,
            confirmationRes = null,
            icon = Icons.Outlined.Shield
        )
    }

/** O livro que a etapa abre; a proteção abre o termo de compromisso. */
private val Stage.bookOrNull: RecoveryBook?
    get() = when (this) {
        Stage.UNDERSTAND -> RecoveryBook.CREATOR_INSTRUCTIONS
        Stage.REWIRE -> RecoveryBook.EASYPEASY
        Stage.PROTECT -> null
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
